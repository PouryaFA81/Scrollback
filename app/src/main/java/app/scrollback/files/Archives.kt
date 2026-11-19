package app.scrollback.files

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ArchiveFormat(val label: String, val suffixes: List<String>, val isArchive: Boolean = true) {
    ZIP("ZIP", listOf(".zip", ".cbz")),
    TAR_GZ("TAR.GZ", listOf(".tar.gz", ".tgz")),
    TAR_BZ2("TAR.BZ2", listOf(".tar.bz2", ".tbz2", ".tbz")),
    TAR_XZ("TAR.XZ", listOf(".tar.xz", ".txz")),
    TAR("TAR", listOf(".tar")),
    SEVEN_Z("7z", listOf(".7z")),
    GZ("GZIP", listOf(".gz"), isArchive = false),
    BZ2("BZIP2", listOf(".bz2"), isArchive = false),
    XZ("XZ", listOf(".xz"), isArchive = false);

    companion object {
        /** Longest suffix wins, so "a.tar.gz" is TAR_GZ rather than GZ. */
        fun detect(name: String): ArchiveFormat? {
            val n = name.lowercase()
            return entries.flatMap { f -> f.suffixes.map { it to f } }
                .filter { n.endsWith(it.first) && n.length > it.first.length }
                .maxByOrNull { it.first.length }?.second
        }

        /** "photos.tar.gz" -> "photos". */
        fun baseName(name: String): String {
            val f = detect(name) ?: return name.substringBeforeLast('.', name)
            val suffix = f.suffixes.filter { name.lowercase().endsWith(it) }.maxBy { it.length }
            return name.dropLast(suffix.length).ifBlank { name }
        }
    }
}

/** Where extracted entries go. Paths are already sanitised, "/"-separated and relative. */
interface ArchiveSink {
    fun dir(path: String)
    fun file(path: String, input: InputStream)
}

data class ExtractResult(val files: Int, val dirs: Int, val skipped: Int, val bytes: Long)

class ArchiveLimitException(message: String) : IOException(message)

data class ExtractLimits(
    val maxBytes: Long = 32L shl 30,
    val maxEntries: Int = 200_000,
    /** Uncompressed/compressed ratio allowed once more than [ratioFloor] bytes are written. */
    val maxRatio: Long = 250,
    val ratioFloor: Long = 512L shl 20,
)

object ArchivePaths {
    /** Normalises an entry name; null if it is empty or escapes the destination (zip-slip). */
    fun safe(entryName: String): String? {
        val parts = ArrayDeque<String>()
        val cleaned = entryName.replace('\\', '/').replace(Regex("^[A-Za-z]:"), "")
        for (p in cleaned.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(p.replace(Regex("[\\u0000-\\u001f]"), "_"))
            }
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun name(path: String): String = path.substringAfterLast('/')
}

object Archives {
    /**
     * Extracts [format]. [open] opens the archive stream; 7z needs random access, so it uses [file].
     * Symlinks and special files are skipped. Throws [ArchiveLimitException] on suspected zip bombs.
     */
    fun extract(
        format: ArchiveFormat,
        archiveName: String,
        archiveSize: Long,
        open: () -> InputStream,
        file: File?,
        sink: ArchiveSink,
        limits: ExtractLimits = ExtractLimits(),
        onEntry: (String) -> Unit = {},
    ): ExtractResult {
        val counter = Counter(limits, archiveSize)
        when (format) {
            ArchiveFormat.GZ, ArchiveFormat.BZ2, ArchiveFormat.XZ -> {
                val out = ArchivePaths.safe(ArchiveFormat.baseName(archiveName)) ?: "extracted"
                open().use { raw ->
                    val inner = decompress(format, BufferedInputStream(raw))
                    onEntry(out)
                    sink.file(out, counter.wrap(inner))
                }
                counter.files++
            }
            ArchiveFormat.SEVEN_Z -> {
                val f = file ?: throw IOException("7z needs a local copy")
                SevenZFile.builder().setFile(f).get().use { sz ->
                    while (true) {
                        val e = sz.nextEntry ?: break
                        counter.entry()
                        val path = ArchivePaths.safe(e.name)
                        if (path == null || e.isAntiItem) { counter.skipped++; continue }
                        onEntry(path)
                        if (e.isDirectory) { sink.dir(path); counter.dirs++; continue }
                        val stream = object : InputStream() {
                            override fun read(): Int = sz.read()
                            override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
                        }
                        sink.file(path, counter.wrap(stream))
                        counter.files++
                    }
                }
            }
            else -> open().use { raw ->
                val buffered = BufferedInputStream(raw)
                val ais: ArchiveInputStream<out ArchiveEntry> = when (format) {
                    ArchiveFormat.ZIP -> ZipArchiveInputStream(buffered, "UTF-8", true, true)
                    ArchiveFormat.TAR -> TarArchiveInputStream(buffered)
                    else -> TarArchiveInputStream(BufferedInputStream(decompress(format, buffered)))
                }
                ais.use { a ->
                    while (true) {
                        val e = a.nextEntry ?: break
                        counter.entry()
                        val path = ArchivePaths.safe(e.name)
                        val special = (e is TarArchiveEntry && (e.isSymbolicLink || e.isLink || e.isCharacterDevice || e.isBlockDevice || e.isFIFO)) || (e is ZipArchiveEntry && e.isUnixSymlink)
                        if (path == null || special || !a.canReadEntryData(e)) { counter.skipped++; continue }
                        onEntry(path)
                        if (e.isDirectory) { sink.dir(path); counter.dirs++; continue }
                        sink.file(path, counter.wrap(NonClosing(a)))
                        counter.files++
                    }
                }
            }
        }
        return ExtractResult(counter.files, counter.dirs, counter.skipped, counter.bytes)
    }

    private fun decompress(format: ArchiveFormat, input: InputStream): InputStream = when (format) {
        ArchiveFormat.GZ, ArchiveFormat.TAR_GZ -> GzipCompressorInputStream(input, true)
        ArchiveFormat.BZ2, ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(input, true)
        ArchiveFormat.XZ, ArchiveFormat.TAR_XZ -> XZCompressorInputStream(input, true)
        else -> input
    }

    data class ZipItem(val path: String, val isDir: Boolean, val open: () -> InputStream?)

    /** Writes a zip (UTF-8 names, Zip64 when needed). Returns the number of files written. */
    fun createZip(out: OutputStream, items: List<ZipItem>, onEntry: (String) -> Unit = {}): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { zip ->
            for (item in items) {
                val path = ArchivePaths.safe(item.path) ?: continue
                onEntry(path)
                if (item.isDir) { zip.putNextEntry(ZipEntry("$path/")); zip.closeEntry(); continue }
                val input = item.open() ?: continue
                zip.putNextEntry(ZipEntry(path))
                input.use { it.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
                n++
            }
        }
        return n
    }

    private class NonClosing(input: InputStream) : FilterInputStream(input) { override fun close() {} }

    private class Counter(val limits: ExtractLimits, val archiveSize: Long) {
        var files = 0; var dirs = 0; var skipped = 0; var bytes = 0L; var entries = 0
        fun entry() { if (++entries > limits.maxEntries) throw ArchiveLimitException("Too many entries (over ${limits.maxEntries})") }
        fun add(n: Long) {
            bytes += n
            if (bytes > limits.maxBytes) throw ArchiveLimitException("Archive expands beyond ${limits.maxBytes shr 30} GB")
            if (archiveSize > 0 && bytes > limits.ratioFloor && bytes / archiveSize > limits.maxRatio)
                throw ArchiveLimitException("Suspicious compression ratio; stopped to protect your storage")
        }
        fun wrap(input: InputStream): InputStream = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) add(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }
        }
    }
}
