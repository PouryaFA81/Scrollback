package app.scrollback.fullplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny HTTP server on 127.0.0.1 that lets libVLC read anything Scrollback's Media3 data
 * sources can read: Nextcloud/WebDAV and music servers (auth headers), SFTP/SMB (`rfs://`).
 * Each stream gets an unguessable token; only this process knows it. Range requests are
 * supported so seeking works. It runs only while the full player is open.
 */
@OptIn(UnstableApi::class)
class LoopbackStream(private val factory: DataSource.Factory) : NanoHTTPD("127.0.0.1", 0) {
    private val routes = ConcurrentHashMap<String, Pair<String, String>>() // token -> (uri, mime)
    private val rnd = SecureRandom()

    fun register(uri: String, mime: String = "video/*"): String {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
        val token = ByteArray(16).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        routes[token] = uri to mime
        return "http://127.0.0.1:$listeningPort/s/$token"
    }

    fun clear() { routes.clear() }

    override fun serve(session: IHTTPSession): Response {
        val token = session.uri.removePrefix("/s/")
        val (uri, mime) = routes[token] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        val range = Range.parse(session.headers["range"])
        val ds = factory.createDataSource()
        return try {
            val spec = DataSpec.Builder().setUri(Uri.parse(uri)).setPosition(range?.first ?: 0)
                .setLength(range?.let { r -> r.second?.let { it - r.first + 1 } } ?: C.LENGTH_UNSET.toLong()).build()
            val opened = ds.open(spec)
            val input = DataSourceInput(ds)
            if (session.method == Method.HEAD) { input.close() }
            if (range == null) {
                val r = if (opened == C.LENGTH_UNSET.toLong()) newChunkedResponse(Response.Status.OK, mime, input)
                else newFixedLengthResponse(Response.Status.OK, mime, input, opened)
                r.addHeader("Accept-Ranges", "bytes"); r
            } else {
                // Total size is only known when the open reported a length for an open-ended range.
                val total = if (opened != C.LENGTH_UNSET.toLong() && range.second == null) (range.first + opened).toString() else "*"
                val end = if (opened != C.LENGTH_UNSET.toLong()) range.first + opened - 1 else range.second
                val r = if (opened == C.LENGTH_UNSET.toLong()) newChunkedResponse(Response.Status.PARTIAL_CONTENT, mime, input)
                else newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, input, opened)
                r.addHeader("Accept-Ranges", "bytes")
                if (end != null) r.addHeader("Content-Range", "bytes ${range.first}-$end/$total")
                r
            }
        } catch (e: Exception) {
            runCatching { ds.close() }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "")
        }
    }

    private class DataSourceInput(private val ds: DataSource) : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = ds.read(b, off, len)
            return if (n == C.RESULT_END_OF_INPUT) -1 else n
        }
        override fun close() { runCatching { ds.close() } }
    }

    /** "bytes=100-" / "bytes=100-199" → (first, last?). Suffix ranges are not used by libVLC. */
    object Range {
        fun parse(h: String?): Pair<Long, Long?>? {
            val v = h?.trim()?.removePrefix("bytes=") ?: return null
            if (v.contains(',')) return null
            val (a, b) = v.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val first = a.toLongOrNull() ?: return null
            return first to b.toLongOrNull()
        }
    }
}
