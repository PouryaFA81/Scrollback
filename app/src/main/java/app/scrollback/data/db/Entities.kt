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

data class TagCount(val id: Long, val name: String, val count: Int)

data class SourceSummary(
    val id: Long,
    val type: String,
    val uri: String,
    val displayName: String,
    val lastScanAt: Long,
    val videoCount: Int,
    val imageCount: Int,
)

data class IndexRow(val id: Long, val uri: String, val size: Long, val dateModified: Long, val missing: Boolean)

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
