// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.fullplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.db.isVideo
import app.scrollback.data.source.MediaSource
import app.scrollback.files.FileNode
import app.scrollback.files.NodeKind
import java.io.File

/**
 * One entry of the full player's playlist.
 * [uri] is what to open (file://, content://, http(s):// or a Scrollback-internal URI such as
 * `rfs://` that is streamed through [LoopbackStream]); [mediaId] links it to the library so
 * resume/history reuse the feed's data (-1 = not indexed).
 */
data class PlayItem(val uri: String, val name: String, val mediaId: Long = -1, val viaLoopback: Boolean = false)

object FullPlayer {
    private const val EXTRA_URIS = "fp_uris"
    private const val EXTRA_NAMES = "fp_names"
    private const val EXTRA_IDS = "fp_ids"
    private const val EXTRA_LOOP = "fp_loop"
    private const val EXTRA_INDEX = "fp_index"
    /** Keep intents small: at most this many items around the one being opened. */
    private const val MAX_ITEMS = 400

    /** Media3 errors that mean "this phone's decoders / Media3's extractors can't handle the file". */
    fun isDecoderError(code: Int): Boolean = code in setOf(
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** Containers the full player opens from the file manager (wider than the feed's list). */
    val VIDEO_EXT = MediaSource.VIDEO_EXT + setOf("mpg", "mpeg", "m2ts", "mts", "vob", "wmv", "asf", "flv", "ogv", "rm", "rmvb", "divx", "f4v", "mxf")

    fun intent(ctx: Context, items: List<PlayItem>, index: Int): Intent {
        val start = (index - MAX_ITEMS / 2).coerceAtLeast(0)
        val window = items.subList(start, minOf(items.size, start + MAX_ITEMS))
        return Intent(ctx, FullPlayerActivity::class.java)
            .putExtra(EXTRA_URIS, window.map { it.uri }.toTypedArray())
            .putExtra(EXTRA_NAMES, window.map { it.name }.toTypedArray())
            .putExtra(EXTRA_IDS, window.map { it.mediaId }.toLongArray())
            .putExtra(EXTRA_LOOP, window.map { it.viaLoopback }.toBooleanArray())
            .putExtra(EXTRA_INDEX, index - start)
    }

    fun launch(ctx: Context, items: List<PlayItem>, index: Int) {
        if (items.isEmpty()) return
        ctx.startActivity(intent(ctx, items, index.coerceIn(0, items.size - 1)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { ctx !is android.app.Activity } ?: 0))
    }

    /** Reads the queue back; for ACTION_VIEW intents from other apps it builds a one-item queue. */
    fun parse(ctx: Context, intent: Intent): Pair<List<PlayItem>, Int>? {
        val uris = intent.getStringArrayExtra(EXTRA_URIS)
        if (uris != null && uris.isNotEmpty()) {
            val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
            val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0)
            val loop = intent.getBooleanArrayExtra(EXTRA_LOOP) ?: BooleanArray(0)
            val items = uris.indices.map { i ->
                PlayItem(uris[i], names.getOrNull(i) ?: Uri.parse(uris[i]).lastPathSegment.orEmpty(), ids.getOrElse(i) { -1 }, loop.getOrElse(i) { false })
            }
            return items to intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
        }
        val data = intent.data ?: return null
        val name = displayName(ctx, data)
        // A file:// path from another app: offer the rest of its folder as the playlist.
        if (data.scheme == "file") data.path?.let { p -> folderOf(File(p))?.let { return it } }
        return listOf(PlayItem(data.toString(), name)) to 0
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "content") runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        }
        return Uri.decode(uri.lastPathSegment ?: uri.toString()).substringAfterLast('/')
    }

    /** All videos next to [file], sorted by name, with [file] selected. */
    fun folderOf(file: File): Pair<List<PlayItem>, Int>? {
        val siblings = file.parentFile?.listFiles()?.filter { it.isFile && isVideoName(it.name) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: return null
        val idx = siblings.indexOfFirst { it.absolutePath == file.absolutePath }
        if (idx < 0) return null
        return siblings.map { PlayItem(Uri.fromFile(it).toString(), it.name) } to idx
    }

    /** Playlist from a file-manager listing: every video in [listing], starting at [node]. */
    fun fromNodes(listing: List<FileNode>, node: FileNode): Pair<List<PlayItem>, Int> {
        val vids = listing.filter { !it.isDir && (it.mime.startsWith("video/") || isVideoName(it.name)) }.ifEmpty { listOf(node) }
        val items = vids.map { n -> PlayItem(n.uri.toString(), n.name, viaLoopback = n.kind == NodeKind.REMOTE) }
        return items to vids.indexOfFirst { it.key == node.key }.coerceAtLeast(0)
    }

    fun fromMedia(list: List<MediaEntity>, current: MediaEntity, remote: (MediaEntity) -> Boolean): Pair<List<PlayItem>, Int> {
        val vids = list.filter { it.isVideo }.ifEmpty { listOf(current) }
        val items = vids.map { m -> PlayItem(m.uri, m.name, m.id, viaLoopback = remote(m)) }
        return items to vids.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    }
    /** Opens a library video in the full player with the rest of its folder as the playlist. */
    suspend fun playMedia(ctx: Context, container: app.scrollback.AppContainer, item: MediaEntity) {
        val showHidden = container.session.value.hiddenRevealed || !container.hasPin.value
        val list = container.db.media().folderVideos(item.sourceId, item.relativePath, showHidden, item.id)
        val (items, idx) = fromMedia(list, item) { false }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { launch(ctx, items, idx) }
    }
}
