// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-chosen media source. [type] maps to a [app.scrollback.data.source.MediaSource]
 * implementation: "saf" (local Storage Access Framework tree) today, "webdav" (Nextcloud) in phase 3.
 */
@Entity(tableName = "sources", indices = [Index(value = ["uri"], unique = true)])
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val uri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long = 0,
    /** Server account (see `accounts/`) for remote sources; null for local folders. */
    val accountId: String? = null,
)

@Entity(
    tableName = "media",
    foreignKeys = [ForeignKey(
        entity = SourceEntity::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["uri"], unique = true),
        Index("sourceId"),
        Index("dateModified"),
        Index("size"),
        Index("kind"),
        Index("artist"),
        Index("album"),
    ],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val uri: String,
    val name: String,
    val relativePath: String,
    val mime: String,
    /** [MediaKind.id]: video, image (v1) or audio (phase 4 music player). */
    val kind: String,
    val size: Long,
    val dateModified: Long,
    /** When Scrollback first indexed the file. */
    val dateAdded: Long,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val partialHash: String? = null,
    val fullHash: String? = null,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val hidden: Boolean = false,
    val note: String = "",
    val lastPositionMs: Long = 0,
    val lastWatchedAt: Long = 0,
    val watchCount: Int = 0,
    val missing: Boolean = false,
    /** Remote preview/cover URL (Nextcloud preview, Subsonic/Jellyfin cover art); null = decode locally. */
    val thumbUri: String? = null,
    /** content:// URI of an offline copy downloaded from a remote source. */
    val localUri: String? = null,
    /** Last change to user metadata (like, note, tags, hidden, history): last-write-wins sync clock. */
    @ColumnInfo(defaultValue = "0") val metaUpdatedAt: Long = 0,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    @ColumnInfo(defaultValue = "0") val trackNo: Int = 0,
    /** v0.14: disc number (0 = unknown). */
    @ColumnInfo(defaultValue = "0") val discNo: Int = 0,
    /** Server-side id (Nextcloud fileid, Subsonic/Jellyfin item id). */
    val remoteId: String? = null,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
)

@Entity(
    tableName = "media_tags",
    primaryKeys = ["mediaId", "tagId"],
    foreignKeys = [
        ForeignKey(MediaEntity::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class MediaTagRef(val mediaId: Long, val tagId: Long)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Stable id used by Nextcloud sync. */
    @ColumnInfo(defaultValue = "''") val uuid: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

/** Remembers deleted playlists so sync does not resurrect them. */
@Entity(tableName = "sync_tombstones")
data class SyncTombstone(
    @PrimaryKey val key: String,
    val deletedAt: Long,
)

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "mediaId"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MediaEntity::class, ["id"], ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("mediaId")],
)
data class PlaylistEntry(
    val playlistId: Long,
    val mediaId: Long,
    val position: Int,
    val addedAt: Long,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val count: Int,
    val coverUri: String?,
)

data class MediaTagName(val mediaId: Long, val name: String)

data class TagCount(val id: Long, val name: String, val count: Int)

data class SourceSummary(
    val id: Long,
    val type: String,
    val uri: String,
    val displayName: String,
    val lastScanAt: Long,
    val videoCount: Int,
    val imageCount: Int,
    val audioCount: Int = 0,
)

/** Music browse rows. */
data class ArtistRow(val artist: String, val songs: Int, val albums: Int, val cover: String?)
data class AlbumRow(val album: String, val artist: String?, val songs: Int, val cover: String?, val year: Long)
data class FolderRow(val folder: String, val songs: Int)

data class IndexRow(val id: Long, val uri: String, val size: Long, val dateModified: Long, val missing: Boolean, val liked: Boolean, val metaUpdatedAt: Long, val remoteId: String?)

/** Generic media kinds so the index can also hold audio for the phase 4 music player. */
enum class MediaKind(val id: String) {
    VIDEO("video"), IMAGE("image"), AUDIO("audio");

    companion object {
        fun fromMime(mime: String): MediaKind? = when {
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("audio/") -> AUDIO
            else -> null
        }
    }
}

val MediaEntity.isVideo: Boolean get() = kind == MediaKind.VIDEO.id
val MediaEntity.isImage: Boolean get() = kind == MediaKind.IMAGE.id
val MediaEntity.isAudio: Boolean get() = kind == MediaKind.AUDIO.id
val MediaEntity.isRemote: Boolean get() = uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("subsonic:") || uri.startsWith("jellyfin:")
/** What thumbnails should load: a server preview when there is one, else the item itself. */
val MediaEntity.thumbModel: String get() = thumbUri ?: localUri ?: uri
