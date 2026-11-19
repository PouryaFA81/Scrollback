package app.scrollback.data.source

import android.net.Uri
import app.scrollback.core.hash.DuplicateFinder

/** Kinds of media sources. Stored as [id] in `sources.type`. */
enum class SourceType(val id: String) {
    /** A local folder picked with the Storage Access Framework (persistable tree URI). */
    SAF_TREE("saf"),

    /** A local folder read directly with java.io.File (needs the optional all-files access). */
    FILE("file"),

    /** A Nextcloud/WebDAV folder, streamed over HTTPS with auth headers. */
    WEBDAV("webdav"),

    /** A Subsonic/OpenSubsonic music server (Navidrome, Nextcloud Music, Airsonic, Gonic). */
    SUBSONIC("subsonic"),

    /** A Jellyfin server's music library. */
    JELLYFIN("jellyfin");

    companion object {
        fun from(id: String): SourceType = entries.firstOrNull { it.id == id } ?: SAF_TREE
    }
}

/** One file found while scanning a source. */
data class ScannedEntry(
    val uri: String,
    val name: String,
    val relativePath: String,
    val mime: String,
    val size: Long,
    val dateModified: Long,
    val kind: app.scrollback.data.db.MediaKind,
)

/**
 * How the player should open an item. Local items are plain content:// URIs; a remote WebDAV
 * source (phase 3) returns an https:// URL plus auth headers, which Media3's
 * DefaultHttpDataSource can stream directly without downloading first.
 */
data class PlaybackRequest(
    val uri: Uri,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * The media-source abstraction every feed, the player, sharing and the duplicate finder go through.
 * Feeds never care where bytes live, so a remote source can later appear in the same feeds.
 */
interface MediaSource {
    val sourceId: Long
    val type: SourceType

    /** Walks the source recursively and yields every video/image it finds. Blocking; call off the main thread. */
    fun scan(): Sequence<ScannedEntry>

    fun playback(uri: String): PlaybackRequest

    /** Random-access bytes for hashing (duplicate finder). */
    fun byteSource(uri: String, size: Long): DuplicateFinder.ByteSource

    /** Deletes the underlying file. Returns true on success. */
    fun delete(uri: String): Boolean

    /** A URI other apps can read via the share sheet, or null if the item must be fetched first. */
    fun shareUri(uri: String): Uri?

    companion object {
        val VIDEO_EXT = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "ts")
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp")
        /** Recognised but not indexed until the phase 4 music player. */
        val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")

        /** Resolves a usable mime type, falling back to the file extension when providers report octet-stream. */
        fun resolveMime(name: String, reported: String?): String? {
            val r = reported?.lowercase()
            if (r != null && (r.startsWith("video/") || r.startsWith("image/") || r.startsWith("audio/"))) return r
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in VIDEO_EXT -> if (ext == "mov") "video/quicktime" else "video/$ext"
                ext in IMAGE_EXT -> if (ext == "jpg") "image/jpeg" else "image/$ext"
                ext in AUDIO_EXT -> if (ext == "mp3") "audio/mpeg" else "audio/$ext"
                else -> null
            }
        }
    }
}
