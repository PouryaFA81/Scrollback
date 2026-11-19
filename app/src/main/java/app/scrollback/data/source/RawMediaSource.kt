package app.scrollback.data.source

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.scrollback.core.hash.DuplicateFinder
import app.scrollback.data.db.MediaKind
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Local folder read with java.io.File; only usable while all-files access is granted. */
class RawMediaSource(
    override val sourceId: Long,
    private val root: File,
    private val context: Context,
) : MediaSource {
    override val type = SourceType.FILE

    override fun scan(): Sequence<ScannedEntry> = sequence {
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            val found = mutableListOf<ScannedEntry>()
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) { stack.addLast(f); continue }
                val mime = MediaSource.resolveMime(f.name, null) ?: continue
                val kind = MediaKind.fromMime(mime) ?: continue
                if (kind == MediaKind.AUDIO) continue // indexed once the music player lands
                found += ScannedEntry(
                    uri = Uri.fromFile(f).toString(),
                    name = f.name,
                    relativePath = f.parentFile?.relativeTo(root)?.path.orEmpty().let { if (it == ".") "" else it },
                    mime = mime,
                    size = f.length(),
                    dateModified = f.lastModified(),
                    kind = kind,
                )
            }
            yieldAll(found)
        }
    }

    override fun playback(uri: String) = PlaybackRequest(Uri.parse(uri))

    override fun byteSource(uri: String, size: Long): DuplicateFinder.ByteSource {
        val file = File(Uri.parse(uri).path!!)
        return object : DuplicateFinder.ByteSource {
            override val size: Long = size
            override fun readRange(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(length)
                raf.seek(offset)
                raf.readFully(buf)
                buf
            }
            override fun openStream(): InputStream = file.inputStream()
        }
    }

    override fun delete(uri: String): Boolean = File(Uri.parse(uri).path!!).delete()

    override fun shareUri(uri: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(Uri.parse(uri).path!!))
}
