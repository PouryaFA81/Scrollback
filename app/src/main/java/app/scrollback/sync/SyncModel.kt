// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.sync

import org.json.JSONArray
import org.json.JSONObject

/** User metadata for one media item, keyed by a device-independent [SyncKeys] key. */
data class ItemState(
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val hidden: Boolean = false,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val lastPositionMs: Long = 0,
    val lastWatchedAt: Long = 0,
    val watchCount: Int = 0,
    val updatedAt: Long = 0,
)

data class PlaylistState(
    val name: String,
    val createdAt: Long,
    val items: List<String>,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** The document stored at /Scrollback/scrollback-sync.json on Nextcloud. */
data class SyncDoc(
    val items: Map<String, ItemState> = emptyMap(),
    val playlists: Map<String, PlaylistState> = emptyMap(),
    val updatedAt: Long = 0,
    val version: Int = 1,
)

/** Last-write-wins merge by per-record timestamp. Ties go to the remote copy (same change seen twice). */
object SyncMerge {
    fun merge(local: SyncDoc, remote: SyncDoc, now: Long = System.currentTimeMillis()): SyncDoc {
        val items = HashMap<String, ItemState>(remote.items)
        for ((k, l) in local.items) {
            val r = remote.items[k]
            if (r == null || l.updatedAt > r.updatedAt) items[k] = l
        }
        val playlists = HashMap<String, PlaylistState>(remote.playlists)
        for ((k, l) in local.playlists) {
            val r = remote.playlists[k]
            if (r == null || l.updatedAt > r.updatedAt) playlists[k] = l
        }
        return SyncDoc(items, playlists, now)
    }

    /** Records in [merged] that differ from what this device has, i.e. what to write into the local DB. */
    fun incoming(local: SyncDoc, merged: SyncDoc): SyncDoc = SyncDoc(
        items = merged.items.filter { (k, v) -> local.items[k] != v },
        playlists = merged.playlists.filter { (k, v) -> local.playlists[k] != v },
        updatedAt = merged.updatedAt,
    )

    /** Drops long-deleted playlist tombstones and blank item records to keep the file small. */
    fun compact(doc: SyncDoc, now: Long, keepTombstonesMs: Long = 180L * 24 * 3600 * 1000): SyncDoc = doc.copy(
        playlists = doc.playlists.filterValues { !it.deleted || now - it.updatedAt < keepTombstonesMs },
    )
}

/** Device-independent keys for media items. */
object SyncKeys {
    /** Nextcloud items by their path in the user's files; local items by name + size. */
    fun forMedia(uri: String, name: String, size: Long, remoteRelativePath: String?, remoteType: String?, remoteId: String?): String = when {
        remoteRelativePath != null -> "nc:$remoteRelativePath"
        remoteType != null && remoteId != null -> "$remoteType:$remoteId"
        else -> "f:${name.lowercase()}|$size"
    }
}

object SyncJson {
    fun encode(doc: SyncDoc): String {
        val items = JSONObject()
        for ((k, v) in doc.items.toSortedMap()) {
            items.put(k, JSONObject().apply {
                if (v.liked) put("liked", true).put("likedAt", v.likedAt)
                if (v.hidden) put("hidden", true)
                if (v.note.isNotEmpty()) put("note", v.note)
                if (v.tags.isNotEmpty()) put("tags", JSONArray(v.tags))
                if (v.lastPositionMs > 0) put("pos", v.lastPositionMs)
                if (v.lastWatchedAt > 0) put("watchedAt", v.lastWatchedAt)
                if (v.watchCount > 0) put("watchCount", v.watchCount)
                put("t", v.updatedAt)
            })
        }
        val playlists = JSONObject()
        for ((k, v) in doc.playlists.toSortedMap()) {
            playlists.put(k, JSONObject().put("name", v.name).put("createdAt", v.createdAt)
                .put("items", JSONArray(v.items)).put("t", v.updatedAt).apply { if (v.deleted) put("deleted", true) })
        }
        return JSONObject().put("app", "scrollback").put("version", doc.version).put("updatedAt", doc.updatedAt)
            .put("items", items).put("playlists", playlists).toString(1)
    }

    fun decode(text: String): SyncDoc {
        val o = JSONObject(text)
        val items = HashMap<String, ItemState>()
        o.optJSONObject("items")?.let { io ->
            for (k in io.keys()) {
                val v = io.getJSONObject(k)
                items[k] = ItemState(
                    liked = v.optBoolean("liked"), likedAt = v.optLong("likedAt"), hidden = v.optBoolean("hidden"),
                    note = v.optString("note", ""), tags = v.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    lastPositionMs = v.optLong("pos"), lastWatchedAt = v.optLong("watchedAt"), watchCount = v.optInt("watchCount"),
                    updatedAt = v.optLong("t"),
                )
            }
        }
        val playlists = HashMap<String, PlaylistState>()
        o.optJSONObject("playlists")?.let { po ->
            for (k in po.keys()) {
                val v = po.getJSONObject(k)
                playlists[k] = PlaylistState(
                    name = v.optString("name", "Playlist"), createdAt = v.optLong("createdAt"),
                    items = v.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    updatedAt = v.optLong("t"), deleted = v.optBoolean("deleted"),
                )
            }
        }
        return SyncDoc(items, playlists, o.optLong("updatedAt"), o.optInt("version", 1))
    }
}
