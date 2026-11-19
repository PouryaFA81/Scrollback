package app.scrollback.sync

import androidx.room.withTransaction
import app.scrollback.accounts.AccountStore
import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ServerAccount
import app.scrollback.data.db.AppDatabase
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.db.MediaTagRef
import app.scrollback.data.db.PlaylistEntity
import app.scrollback.data.db.PlaylistEntry
import app.scrollback.data.db.SourceEntity
import app.scrollback.data.db.TagEntity
import app.scrollback.data.source.SourceType
import app.scrollback.remote.HttpException
import app.scrollback.remote.nextcloud.NextcloudPaths
import app.scrollback.remote.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Syncs likes, hidden flags, notes, tags, watch history and playlists through one JSON file on
 * Nextcloud (`/Scrollback/scrollback-sync.json`), merged last-write-wins per record ([SyncMerge]).
 * Failures (offline, server down) are recorded on the account and never block the app.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val accounts: AccountStore,
    private val dav: WebDavClient,
) {
    private val mutex = Mutex()

    suspend fun syncAll() {
        accounts.accounts.value.filter { it.type == AccountType.NEXTCLOUD && it.syncEnabled }.forEach { sync(it.id) }
    }

    /** Returns null on success, else a short error message. */
    suspend fun sync(accountId: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val account = accounts.get(accountId) ?: return@withLock "No such account"
            val error = runCatching { syncOnce(account, retry = true) }.exceptionOrNull()?.let { e ->
                when (e) {
                    is HttpException -> "Server answered ${e.code}"
                    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Offline"
                    else -> e.javaClass.simpleName
                }
            }
            accounts.update(accountId) { it.copy(lastSyncAt = if (error == null) System.currentTimeMillis() else it.lastSyncAt, lastSyncError = error) }
            error
        }
    }

    private suspend fun syncOnce(account: ServerAccount, retry: Boolean) {
        val folder = NextcloudPaths.davUrl(account, "/Scrollback")
        val fileUrl = "$folder/scrollback-sync.json"
        var etag: String? = null
        val remote = dav.get(fileUrl)?.use { r ->
            etag = r.header("ETag")?.trim('"')
            SyncJson.decode(r.body.string())
        } ?: SyncDoc()

        val snapshot = snapshot()
        val local = snapshot.doc
        val now = System.currentTimeMillis()
        val merged = SyncMerge.compact(SyncMerge.merge(local, remote, now), now)
        apply(SyncMerge.incoming(local, merged), snapshot)

        if (merged.items != remote.items || merged.playlists != remote.playlists) {
            dav.mkcol(folder)
            try {
                dav.put(fileUrl, SyncJson.encode(merged).toByteArray(), "application/json", ifMatch = etag, ifNoneMatch = etag == null)
            } catch (e: HttpException) {
                if (e.code == 412 && retry) syncOnce(account, retry = false) else throw e
            }
        }
    }

    private class Snapshot(val doc: SyncDoc, val idsByKey: Map<String, List<Long>>)

    /** Builds this device's view of the sync document from the database. */
    private suspend fun snapshot(): Snapshot {
        val sources: Map<Long, SourceEntity> = db.sources().all().associateBy { it.id }
        val tagNames = db.tags().allNames().groupBy({ it.mediaId }, { it.name })
        val keyById = HashMap<Long, String>()
        val idsByKey = HashMap<String, MutableList<Long>>()
        val items = HashMap<String, ItemState>()
        for (m in db.media().allPresent()) {
            val key = keyFor(m, sources[m.sourceId])
            keyById[m.id] = key
            idsByKey.getOrPut(key) { ArrayList() } += m.id
            if (m.metaUpdatedAt <= 0) continue
            val state = ItemState(
                liked = m.liked, likedAt = m.likedAt, hidden = m.hidden, note = m.note,
                tags = tagNames[m.id].orEmpty().sorted(), lastPositionMs = m.lastPositionMs,
                lastWatchedAt = m.lastWatchedAt, watchCount = m.watchCount, updatedAt = m.metaUpdatedAt,
            )
            val prev = items[key]
            if (prev == null || state.updatedAt > prev.updatedAt) items[key] = state
        }
        val playlists = HashMap<String, PlaylistState>()
        for (p in db.playlists().all()) {
            if (p.uuid.isEmpty()) continue
            val keys = db.playlists().orderedMediaIds(p.id).mapNotNull { keyById[it] }
            playlists[p.uuid] = PlaylistState(p.name, p.createdAt, keys, p.updatedAt.takeIf { it > 0 } ?: p.createdAt)
        }
        for (t in db.playlists().tombstones()) {
            val uuid = t.key.removePrefix("playlist:")
            if (uuid !in playlists) playlists[uuid] = PlaylistState("", 0, emptyList(), t.deletedAt, deleted = true)
        }
        return Snapshot(SyncDoc(items, playlists), idsByKey)
    }

    private fun keyFor(m: MediaEntity, source: SourceEntity?): String {
        val type = source?.let { SourceType.from(it.type) }
        val account = source?.accountId?.let(accounts::get)
        val ncPath = if (type == SourceType.WEBDAV && account != null) NextcloudPaths.relativePath(account, m.uri) else null
        val remoteType = if (type == SourceType.SUBSONIC || type == SourceType.JELLYFIN) "${type.id}@${account?.baseUrl}" else null
        return SyncKeys.forMedia(m.uri, m.name, m.size, ncPath, remoteType, m.remoteId)
    }

    private suspend fun apply(incoming: SyncDoc, snap: Snapshot) {
        if (incoming.items.isEmpty() && incoming.playlists.isEmpty()) return
        val media = db.media(); val tags = db.tags(); val pl = db.playlists()
        db.withTransaction {
            for ((key, s) in incoming.items) {
                for (id in snap.idsByKey[key].orEmpty()) {
                    tags.clearRefs(id)
                    val tagIds = s.tags.map { name -> tags.byName(name)?.id ?: tags.insert(TagEntity(name = name)).let { if (it > 0) it else tags.byName(name)!!.id } }
                    tags.insertRefs(tagIds.map { MediaTagRef(id, it) })
                    media.applyRemote(id, s.liked, s.likedAt, s.hidden, s.note, s.lastPositionMs, s.lastWatchedAt, s.watchCount, s.updatedAt)
                }
            }
            tags.deleteOrphans()
            for ((uuid, s) in incoming.playlists) {
                val existing = pl.byUuid(uuid)
                if (s.deleted) { existing?.let { pl.delete(it.id) }; continue }
                val id = existing?.id ?: pl.insert(PlaylistEntity(name = s.name, createdAt = s.createdAt, uuid = uuid, updatedAt = s.updatedAt))
                pl.applyRemote(id, s.name, s.updatedAt)
                pl.clearEntries(id)
                var pos = 0
                for (k in s.items) {
                    val mediaId = snap.idsByKey[k]?.firstOrNull() ?: continue
                    pl.insertEntry(PlaylistEntry(id, mediaId, pos++, s.updatedAt))
                }
            }
        }
    }
}
