package app.scrollback.data.repo

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.withTransaction
import app.scrollback.core.text.Tags
import app.scrollback.data.db.AppDatabase
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.db.MediaTagRef
import app.scrollback.data.db.PlaylistEntity
import app.scrollback.data.db.PlaylistEntry
import app.scrollback.data.db.SourceEntity
import app.scrollback.data.db.TagEntity
import app.scrollback.data.source.MediaSource
import app.scrollback.data.source.MediaSourceRegistry
import app.scrollback.data.source.PlaybackRequest
import app.scrollback.data.source.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScanState(
    val running: Boolean = false,
    val currentSource: String? = null,
    val found: Int = 0,
)

/** Single entry point for library data: sources, scanning, feeds, likes, notes, tags, playlists, history. */
class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val sources: MediaSourceRegistry,
) {
    private val sourceDao = db.sources()
    private val mediaDao = db.media()
    private val tagDao = db.tags()
    private val playlistDao = db.playlists()
    private val scanMutex = Mutex()
    private val sourceCache = HashMap<Long, MediaSource>()

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    // ---- Sources -------------------------------------------------------------------------

    fun observeSources() = sourceDao.observeSummaries()

    /** Persists access to a folder picked with ACTION_OPEN_DOCUMENT_TREE and indexes it. */
    suspend fun addLocalFolder(treeUri: Uri, displayName: String): Long = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val existing = sourceDao.byUri(treeUri.toString())
        val id = existing?.id ?: sourceDao.insert(
            SourceEntity(
                type = SourceType.SAF_TREE.id,
                uri = treeUri.toString(),
                displayName = displayName,
                addedAt = System.currentTimeMillis(),
            )
        )
        scan(listOf(id))
        id
    }

    /** Adds a folder picked in the file manager (SAF subfolder of a granted tree, or a raw path). */
    suspend fun addFolderSource(type: SourceType, uri: String, displayName: String): Long = withContext(Dispatchers.IO) {
        val id = sourceDao.byUri(uri)?.id ?: sourceDao.insert(
            SourceEntity(type = type.id, uri = uri, displayName = displayName, addedAt = System.currentTimeMillis())
        )
        scan(listOf(id))
        id
    }

    /** Adds a folder/library on a server account (Nextcloud folder, Subsonic/Jellyfin library) and indexes it. */
    suspend fun addRemoteSource(type: SourceType, uri: String, displayName: String, accountId: String): Long = withContext(Dispatchers.IO) {
        val id = sourceDao.byUri(uri)?.id ?: sourceDao.insert(
            SourceEntity(type = type.id, uri = uri, displayName = displayName, addedAt = System.currentTimeMillis(), accountId = accountId)
        )
        scan(listOf(id))
        id
    }

    /** Removes every source that belongs to a server account (when the account is signed out). */
    suspend fun removeAccountSources(accountId: String) = withContext(Dispatchers.IO) {
        sourceDao.all().filter { it.accountId == accountId }.forEach { removeSource(it.id) }
    }

    /** Drops cached [MediaSource]s, e.g. after account credentials changed. */
    fun invalidateSources() = sourceCache.clear()

    suspend fun rescan(sourceId: Long) = scan(listOf(sourceId))

    suspend fun removeSource(sourceId: Long) = withContext(Dispatchers.IO) {
        val s = sourceDao.byId(sourceId) ?: return@withContext
        val othersShareTree = sourceDao.all().any { it.id != s.id && it.uri.startsWith(s.uri.substringBefore("/document/")) }
        if (s.type == SourceType.SAF_TREE.id && android.provider.DocumentsContract.isTreeUri(Uri.parse(s.uri)) &&
            !s.uri.contains("/document/") && !othersShareTree
        ) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(s.uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        sourceDao.delete(sourceId)
        sourceCache.remove(sourceId)
        tagDao.deleteOrphans()
    }

    private suspend fun sourceFor(sourceId: Long): MediaSource? {
        sourceCache[sourceId]?.let { return it }
        val entity = sourceDao.byId(sourceId) ?: return null
        return sources.forSource(entity)?.also { sourceCache[sourceId] = it }
    }

    // ---- Scanning ------------------------------------------------------------------------

    suspend fun rescanAll() {
        scan(sourceDao.all().map { it.id })
    }

    private suspend fun scan(sourceIds: List<Long>) = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            var found = 0
            for (id in sourceIds) {
                val entity = sourceDao.byId(id) ?: continue
                _scanState.value = ScanState(true, entity.displayName, found)
                val source = sourceFor(id) ?: continue
                try {
                    found = scanOne(id, entity.displayName, source, found)
                    sourceDao.setScanned(id, System.currentTimeMillis())
                } catch (e: Exception) {
                    // Offline or server error: keep the existing index untouched (nothing is marked missing).
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.w("Scrollback", "scan of source $id failed: ${e.javaClass.simpleName}")
                }
            }
            _scanState.value = ScanState(false, null, found)
        }
        enrichMetadata()
    }

    private suspend fun scanOne(id: Long, displayName: String, source: MediaSource, foundSoFar: Int): Int {
        var found = foundSoFar
        val existing = mediaDao.indexRows(id).associateBy { it.uri }
        val seen = HashSet<Long>()
        val batch = ArrayList<MediaEntity>()
        val now = System.currentTimeMillis()
        for (e in source.scan()) {
            found++
            if (found % 50 == 0) _scanState.value = ScanState(true, displayName, found)
            val row = existing[e.uri]
            if (row == null) {
                batch += MediaEntity(
                    sourceId = id, uri = e.uri, name = e.name, relativePath = e.relativePath,
                    mime = e.mime, kind = e.kind.id, size = e.size,
                    dateModified = e.dateModified.takeIf { it > 0 } ?: now, dateAdded = now,
                    thumbUri = e.thumbUri, remoteId = e.remoteId, artist = e.artist, album = e.album,
                    title = e.title, trackNo = e.trackNo, durationMs = e.durationMs,
                )
                if (batch.size >= 200) { mediaDao.insertAll(batch); batch.clear() }
            } else {
                seen += row.id
                if (row.size != e.size || row.dateModified != e.dateModified) {
                    mediaDao.updateChanged(row.id, e.name, e.relativePath, e.mime, e.size, e.dateModified)
                }
                if (e.thumbUri != null || e.remoteId != null || e.artist != null || e.title != null) {
                    mediaDao.setExtras(row.id, e.thumbUri, e.artist, e.album, e.title, e.trackNo, e.remoteId, e.durationMs)
                }
            }
        }
        if (batch.isNotEmpty()) mediaDao.insertAll(batch)
        val gone = existing.values.filter { it.id !in seen && !it.missing }.map { it.id }
        val back = existing.values.filter { it.id in seen && it.missing }.map { it.id }
        gone.chunked(500).forEach { mediaDao.setMissing(it, true) }
        back.chunked(500).forEach { mediaDao.setMissing(it, false) }
        return found
    }

    /** Fills in duration and resolution for videos, in small batches, after a scan. */
    private suspend fun enrichMetadata() = withContext(Dispatchers.IO) {
        while (true) {
            val batch = mediaDao.missingMetadata(40)
            if (batch.isEmpty()) break
            for (m in batch) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, Uri.parse(m.uri))
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    mediaDao.setMetadata(m.id, if (d > 0) d else -1L, w, h)
                } catch (_: Exception) {
                    mediaDao.setMetadata(m.id, -1L, 0, 0) // unreadable: don't retry forever
                } finally {
                    runCatching { r.release() }
                }
            }
        }
    }

    // ---- Feeds ---------------------------------------------------------------------------

    fun videos(showHidden: Boolean) = mediaDao.observeVideos(showHidden)
    fun videosByDate(newestFirst: Boolean, showHidden: Boolean) = mediaDao.observeVideosByDate(newestFirst, showHidden)
    fun images(newestFirst: Boolean, showHidden: Boolean) = mediaDao.observeImages(newestFirst, showHidden)
    fun liked(showHidden: Boolean) = mediaDao.observeLiked(showHidden)
    fun history(showHidden: Boolean) = mediaDao.observeHistory(showHidden)
    fun hidden() = mediaDao.observeHidden()
    fun item(id: Long) = mediaDao.observeById(id)
    suspend fun itemNow(id: Long) = mediaDao.byId(id)

    fun search(query: String, tagIds: List<Long>, showHidden: Boolean): Flow<List<MediaEntity>> {
        val q = query.trim()
        return when {
            tagIds.isNotEmpty() -> mediaDao.observeWithAllTags(tagIds, tagIds.size, showHidden)
            q.isNotEmpty() -> mediaDao.observeSearch("%${q.replace("%", "").replace("_", "")}%", showHidden)
            else -> flowOf(emptyList())
        }
    }

    // ---- Item actions --------------------------------------------------------------------

    suspend fun setLiked(id: Long, liked: Boolean) =
        mediaDao.setLiked(id, liked, if (liked) System.currentTimeMillis() else 0)

    suspend fun setHidden(id: Long, hidden: Boolean) = mediaDao.setHidden(id, hidden)

    suspend fun savePosition(id: Long, positionMs: Long) = mediaDao.savePosition(id, positionMs)

    suspend fun markWatched(id: Long) = mediaDao.markWatched(id, System.currentTimeMillis())

    suspend fun clearHistory() = mediaDao.clearHistory()

    fun tagsFor(mediaId: Long) = tagDao.observeTagsFor(mediaId)

    fun tagCounts(showHidden: Boolean) = tagDao.observeTagCounts(showHidden)

    suspend fun saveNoteAndTags(mediaId: Long, note: String, tagsInput: String) {
        val tags = Tags.parse(tagsInput)
        db.withTransaction {
            mediaDao.setNote(mediaId, note.trim())
            tagDao.clearRefs(mediaId)
            val ids = tags.map { name ->
                tagDao.byName(name)?.id ?: tagDao.insert(TagEntity(name = name)).let { newId ->
                    if (newId > 0) newId else tagDao.byName(name)!!.id
                }
            }
            tagDao.insertRefs(ids.map { MediaTagRef(mediaId, it) })
            tagDao.deleteOrphans()
        }
    }

    /** Offline copies win over streaming. */
    suspend fun playback(item: MediaEntity): PlaybackRequest {
        item.localUri?.let { local -> if (exists(local)) return PlaybackRequest(Uri.parse(local)) }
        return sourceFor(item.sourceId)?.playback(item.uri) ?: PlaybackRequest(Uri.parse(item.uri))
    }

    suspend fun shareUri(item: MediaEntity): Uri? {
        item.localUri?.let { local -> if (exists(local)) return Uri.parse(local) }
        return sourceFor(item.sourceId)?.shareUri(item.uri)
    }

    private fun exists(uri: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    suspend fun setLocalCopy(mediaId: Long, localUri: String?) = mediaDao.setLocalUri(mediaId, localUri)

    suspend fun mediaSourceFor(item: MediaEntity): MediaSource? = sourceFor(item.sourceId)

    /** Deletes the file from its source, then from the index. */
    suspend fun deleteFile(item: MediaEntity): Boolean = withContext(Dispatchers.IO) {
        val ok = sourceFor(item.sourceId)?.delete(item.uri) ?: false
        if (ok) mediaDao.delete(item.id)
        ok
    }

    // ---- Playlists -----------------------------------------------------------------------

    fun playlists(showHidden: Boolean) = playlistDao.observeSummaries(showHidden)
    fun playlist(id: Long) = playlistDao.observe(id)
    fun playlistItems(id: Long, showHidden: Boolean) = playlistDao.observeItems(id, showHidden)
    fun membership(mediaId: Long) = playlistDao.observeMembership(mediaId)

    suspend fun createPlaylist(name: String): Long =
        System.currentTimeMillis().let { now ->
            playlistDao.insert(
                PlaylistEntity(name = name.trim().ifEmpty { "Playlist" }, createdAt = now, uuid = java.util.UUID.randomUUID().toString(), updatedAt = now)
            )
        }

    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name.trim())
    suspend fun deletePlaylist(id: Long) = db.withTransaction {
        playlistDao.byId(id)?.uuid?.takeIf { it.isNotEmpty() }?.let {
            playlistDao.insertTombstone(app.scrollback.data.db.SyncTombstone("playlist:$it", System.currentTimeMillis()))
        }
        playlistDao.delete(id)
    }

    /** Gives playlists created before v0.3 a stable sync id. */
    suspend fun ensurePlaylistUuids() = playlistDao.all().filter { it.uuid.isEmpty() }.forEach {
        playlistDao.setUuid(it.id, java.util.UUID.randomUUID().toString())
    }

    suspend fun addToPlaylist(playlistId: Long, mediaId: Long) = db.withTransaction {
        playlistDao.insertEntry(
            PlaylistEntry(playlistId, mediaId, playlistDao.nextPosition(playlistId), System.currentTimeMillis())
        )
        playlistDao.touch(playlistId)
    }

    suspend fun removeFromPlaylist(playlistId: Long, mediaId: Long) = db.withTransaction {
        playlistDao.removeEntry(playlistId, mediaId)
        playlistDao.touch(playlistId)
    }

    /** Moves an entry one step up (-1) or down (+1) and renumbers positions densely. */
    suspend fun moveInPlaylist(playlistId: Long, mediaId: Long, delta: Int) = db.withTransaction {
        val ids = playlistDao.orderedMediaIds(playlistId).toMutableList()
        val from = ids.indexOf(mediaId)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return@withTransaction
        ids.add(to, ids.removeAt(from))
        ids.forEachIndexed { i, id -> playlistDao.setPosition(playlistId, id, i) }
        playlistDao.touch(playlistId)
    }
}
