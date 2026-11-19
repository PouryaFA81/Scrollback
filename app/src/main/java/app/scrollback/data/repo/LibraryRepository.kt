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

    suspend fun removeSource(sourceId: Long) = withContext(Dispatchers.IO) {
        val s = sourceDao.byId(sourceId) ?: return@withContext
        if (s.type == SourceType.SAF_TREE.id) {
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
        return sources.forSource(entity).also { sourceCache[sourceId] = it }
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
                val existing = mediaDao.indexRows(id).associateBy { it.uri }
                val seen = HashSet<Long>()
                val batch = ArrayList<MediaEntity>()
                val now = System.currentTimeMillis()
                for (e in source.scan()) {
                    found++
                    if (found % 50 == 0) _scanState.value = ScanState(true, entity.displayName, found)
                    val row = existing[e.uri]
                    if (row == null) {
                        batch += MediaEntity(
                            sourceId = id, uri = e.uri, name = e.name, relativePath = e.relativePath,
                            mime = e.mime, kind = e.kind.id, size = e.size,
                            dateModified = e.dateModified.takeIf { it > 0 } ?: now, dateAdded = now,
                        )
                        if (batch.size >= 200) { mediaDao.insertAll(batch); batch.clear() }
                    } else {
                        seen += row.id
                        if (row.size != e.size || row.dateModified != e.dateModified) {
                            mediaDao.updateChanged(row.id, e.name, e.relativePath, e.mime, e.size, e.dateModified)
                        }
                    }
                }
                if (batch.isNotEmpty()) mediaDao.insertAll(batch)
                val gone = existing.values.filter { it.id !in seen && !it.missing }.map { it.id }
                val back = existing.values.filter { it.id in seen && it.missing }.map { it.id }
                gone.chunked(500).forEach { mediaDao.setMissing(it, true) }
                back.chunked(500).forEach { mediaDao.setMissing(it, false) }
                sourceDao.setScanned(id, System.currentTimeMillis())
            }
            _scanState.value = ScanState(false, null, found)
        }
        enrichMetadata()
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

    suspend fun playback(item: MediaEntity): PlaybackRequest =
        sourceFor(item.sourceId)?.playback(item.uri) ?: PlaybackRequest(Uri.parse(item.uri))

    suspend fun shareUri(item: MediaEntity): Uri? = sourceFor(item.sourceId)?.shareUri(item.uri)

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
        playlistDao.insert(PlaylistEntity(name = name.trim().ifEmpty { "Playlist" }, createdAt = System.currentTimeMillis()))

    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name.trim())
    suspend fun deletePlaylist(id: Long) = playlistDao.delete(id)

    suspend fun addToPlaylist(playlistId: Long, mediaId: Long) = db.withTransaction {
        playlistDao.insertEntry(
            PlaylistEntry(playlistId, mediaId, playlistDao.nextPosition(playlistId), System.currentTimeMillis())
        )
    }

    suspend fun removeFromPlaylist(playlistId: Long, mediaId: Long) = playlistDao.removeEntry(playlistId, mediaId)

    /** Moves an entry one step up (-1) or down (+1) and renumbers positions densely. */
    suspend fun moveInPlaylist(playlistId: Long, mediaId: Long, delta: Int) = db.withTransaction {
        val ids = playlistDao.orderedMediaIds(playlistId).toMutableList()
        val from = ids.indexOf(mediaId)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return@withTransaction
        ids.add(to, ids.removeAt(from))
        ids.forEachIndexed { i, id -> playlistDao.setPosition(playlistId, id, i) }
    }
}
