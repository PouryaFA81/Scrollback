package app.scrollback.remote.fs

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One entry on an SFTP/SMB server. [path] is absolute, '/'-separated (SMB: relative to the share). */
data class RemoteEntry(val path: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/** The small file-system surface Scrollback needs from a network server. Blocking; call off the main thread. */
interface RemoteFs : Closeable {
    val isConnected: Boolean
    fun list(path: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    fun openRead(path: String, offset: Long = 0): InputStream
    fun openWrite(path: String): OutputStream
    fun mkdir(path: String)
    fun rename(from: String, to: String)
    fun delete(path: String, isDir: Boolean)
}

/** First connection to an SFTP server whose host key isn't pinned yet (trust on first use). */
class UnknownHostKeyException(val fingerprint: String) : IOException("Unknown host key $fingerprint")

/** The server presented a different key than the pinned one: possible man-in-the-middle. */
class HostKeyMismatchException(val expected: String, val actual: String) : IOException("Host key changed")

/** '/'-separated path helpers shared by SFTP and SMB. Pure, unit-tested. */
object RemotePaths {
    fun normalize(p: String): String {
        val parts = ArrayDeque<String>()
        for (seg in p.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun join(dir: String, name: String): String = normalize("$dir/$name")
    fun parent(p: String): String = normalize(p).substringBeforeLast('/').ifEmpty { "/" }
    fun name(p: String): String = normalize(p).substringAfterLast('/')

    /** `rfs://<accountId>/<path>`: the stable URI stored in the DB; credentials are applied at read time. */
    const val SCHEME = "rfs"

    fun uri(accountId: String, path: String): String =
        "$SCHEME://$accountId" + normalize(path).split('/').joinToString("/") { encode(it) }

    /** Returns (accountId, path) for an `rfs://` URI. */
    fun parse(uri: String): Pair<String, String>? {
        if (!uri.startsWith("$SCHEME://")) return null
        val rest = uri.removePrefix("$SCHEME://")
        val acc = rest.substringBefore('/')
        val path = rest.substringAfter('/', "").split('/').joinToString("/") { decode(it) }
        return if (acc.isEmpty()) null else acc to normalize(path)
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    private fun encode(seg: String): String = buildString {
        for (b in seg.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in UNRESERVED) append(c) else append('%').append("%02X".format(b.toInt() and 0xff))
        }
    }

    private fun decode(seg: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < seg.length) {
            val c = seg[i]
            if (c == '%' && i + 2 < seg.length) {
                out.write(seg.substring(i + 1, i + 3).toInt(16)); i += 3
            } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
