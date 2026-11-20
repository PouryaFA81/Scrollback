// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote.fs

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import app.scrollback.core.hash.DuplicateFinder
import app.scrollback.data.db.MediaKind
import app.scrollback.data.source.MediaSource
import app.scrollback.data.source.PlaybackRequest
import app.scrollback.data.source.ScannedEntry
import app.scrollback.data.source.SourceType
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream

/** Media3 data source for `rfs://` URIs: seeks by re-opening the remote file at the requested offset. */
@OptIn(UnstableApi::class)
class RemoteFsDataSource(private val storage: RemoteStorage) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var input: InputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val (acc, path) = RemotePaths.parse(dataSpec.uri.toString()) ?: throw IOException("Bad remote URI")
        transferInitializing(dataSpec)
        val (stream, size) = storage.use(acc) { fs ->
            val size = fs.stat(path)?.size ?: C.LENGTH_UNSET.toLong()
            fs.openRead(path, dataSpec.position) to size
        }
        input = stream
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size >= 0 -> size - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val n = input?.read(buffer, offset, want) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { input?.close() }
        input = null
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** Coil fetcher for pictures on SFTP/SMB servers (`rfs://`). Video frames are not fetched remotely. */
class RemoteFsFetcher(private val storage: RemoteStorage) : Fetcher.Factory<coil3.Uri> {
    override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != RemotePaths.SCHEME) return null
        val (acc, path) = RemotePaths.parse(data.toString()) ?: return null
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in MediaSource.IMAGE_EXT) return null
        return Fetcher {
            val stream = storage.use(acc) { it.openRead(path) }
            SourceFetchResult(
                source = ImageSource(stream.source().buffer(), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.NETWORK,
            )
        }
    }
}

/** An SFTP/SMB folder as a feed source: scanned recursively, streamed through [RemoteFsDataSource]. */
class RemoteFsMediaSource(
    override val sourceId: Long,
    override val type: SourceType,
    private val accountId: String,
    private val root: String,
    private val storage: RemoteStorage,
    private val includeAudio: Boolean,
) : MediaSource {
    override fun scan(): Sequence<ScannedEntry> = sequence {
        val queue = ArrayDeque<Pair<String, Int>>().apply { add(RemotePaths.normalize(root) to 0) }
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            val children = runCatching { storage.use(accountId) { it.list(dir) } }.getOrElse { if (depth == 0) throw it else emptyList() }
            for (e in children) {
                if (e.name.startsWith(".")) continue
                if (e.isDir) { if (depth < 16) queue.add(e.path to depth + 1); continue }
                val mime = MediaSource.resolveMime(e.name, null) ?: continue
                val kind = MediaKind.fromMime(mime) ?: continue
                if (kind == MediaKind.AUDIO && !includeAudio) continue
                val uri = RemotePaths.uri(accountId, e.path)
                yield(
                    ScannedEntry(
                        uri = uri,
                        name = e.name,
                        relativePath = RemotePaths.parent(e.path).removePrefix(RemotePaths.normalize(root)).trim('/'),
                        mime = mime,
                        size = e.size,
                        dateModified = e.modified,
                        kind = kind,
                        thumbUri = if (kind == MediaKind.IMAGE) uri else null,
                    )
                )
            }
        }
    }

    override fun playback(uri: String) = PlaybackRequest(Uri.parse(uri))

    override fun byteSource(uri: String, size: Long): DuplicateFinder.ByteSource {
        val (acc, path) = RemotePaths.parse(uri) ?: throw IOException("Bad remote URI")
        return object : DuplicateFinder.ByteSource {
            override val size = size
            override fun readRange(offset: Long, length: Int): ByteArray = storage.use(acc) { fs ->
                fs.openRead(path, offset).use { s ->
                    val out = ByteArray(length)
                    var got = 0
                    while (got < length) { val n = s.read(out, got, length - got); if (n < 0) break; got += n }
                    if (got == length) out else out.copyOf(got)
                }
            }
            override fun openStream(): InputStream = storage.use(acc) { it.openRead(path) }
        }
    }

    override fun delete(uri: String): Boolean {
        val (acc, path) = RemotePaths.parse(uri) ?: return false
        return runCatching { storage.use(acc) { it.delete(path, false) }; true }.getOrDefault(false)
    }

    override fun shareUri(uri: String): Uri? = null
}
