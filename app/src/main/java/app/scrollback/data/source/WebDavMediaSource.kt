package app.scrollback.data.source

import android.net.Uri
import app.scrollback.accounts.ServerAccount
import app.scrollback.core.hash.DuplicateFinder
import app.scrollback.data.db.MediaKind
import app.scrollback.remote.nextcloud.NextcloudPaths
import app.scrollback.remote.webdav.WebDavClient
import java.io.InputStream

/**
 * A Nextcloud (WebDAV) folder. Items are indexed by their WebDAV URL and **streamed** by the player
 * and Coil through the shared OkHttp client, which adds the account's auth header — nothing is
 * downloaded first. Thumbnails come from Nextcloud's preview endpoint.
 */
class WebDavMediaSource(
    override val sourceId: Long,
    private val account: ServerAccount,
    /** WebDAV URL of the folder (no trailing slash). */
    private val rootUrl: String,
    private val dav: WebDavClient,
    private val includeAudio: Boolean,
) : MediaSource {
    override val type = SourceType.WEBDAV

    override fun scan(): Sequence<ScannedEntry> = sequence {
        val rootPath = NextcloudPaths.relativePath(account, rootUrl) ?: "/"
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(rootUrl.trimEnd('/') to 0) }
        val origin = account.baseUrl.substringBefore("://") + "://" + account.baseUrl.substringAfter("://").substringBefore('/')
        while (queue.isNotEmpty()) {
            val (url, depth) = queue.removeFirst()
            val children = runCatching { dav.list(url) }.getOrElse { if (depth == 0) throw it else emptyList() }
            for (e in children) {
                if (e.name.startsWith(".")) continue
                val abs = origin + e.href
                if (e.isDir) { if (depth < 16) queue.add(abs.trimEnd('/') to depth + 1); continue }
                val mime = MediaSource.resolveMime(e.name, e.mime) ?: continue
                val kind = MediaKind.fromMime(mime) ?: continue
                if (kind == MediaKind.AUDIO && !includeAudio) continue
                val rel = NextcloudPaths.relativePath(account, abs).orEmpty()
                yield(
                    ScannedEntry(
                        uri = abs,
                        name = e.name,
                        relativePath = rel.removePrefix(rootPath).substringBeforeLast('/', "").trim('/'),
                        mime = mime,
                        size = e.size,
                        dateModified = e.modified,
                        kind = kind,
                        thumbUri = if (e.fileId != null && kind != MediaKind.AUDIO && (e.hasPreview || kind == MediaKind.VIDEO)) NextcloudPaths.preview(account, e.fileId) else null,
                        remoteId = e.fileId,
                    )
                )
            }
        }
    }

    override fun playback(uri: String) = PlaybackRequest(Uri.parse(uri))

    override fun byteSource(uri: String, size: Long): DuplicateFinder.ByteSource = object : DuplicateFinder.ByteSource {
        override val size = size
        override fun readRange(offset: Long, length: Int): ByteArray =
            dav.get(uri, offset until offset + length)?.use { it.body.bytes() } ?: ByteArray(0)
        override fun openStream(): InputStream =
            dav.get(uri)?.body?.byteStream() ?: InputStream.nullInputStream()
    }

    override fun delete(uri: String): Boolean = runCatching { dav.delete(uri) }.getOrDefault(false)

    /** Remote files must be downloaded before other apps can open them. */
    override fun shareUri(uri: String): Uri? = null
}
