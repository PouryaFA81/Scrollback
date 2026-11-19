package app.scrollback.office

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.scrollback.files.FileNode
import app.scrollback.files.FileRepository
import app.scrollback.files.Mime
import app.scrollback.files.NodeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Reading/saving documents for the office viewers, plus the Office tool's recent list. */
class OfficeIo(private val context: Context, private val files: FileRepository) {
    private val prefs = context.getSharedPreferences("office", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(loadRecent())
    val recent: StateFlow<List<Recent>> = _recent

    data class Recent(val uri: String, val name: String, val opened: Long)

    /**
     * A seekable local copy (PdfRenderer and ZipFile need random access). Raw files are used in place;
     * everything else (SAF, MediaStore, other apps' content://, SFTP/SMB) is copied to the cache.
     */
    suspend fun localFile(node: FileNode, maxBytes: Long = MAX_BYTES): File = withContext(Dispatchers.IO) {
        if (node.kind == NodeKind.RAW) return@withContext File(node.id).also { if (!it.canRead()) throw IOException("Can't read ${node.name}") }
        val dir = File(context.cacheDir, "office").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val out = File(dir, "${node.key.hashCode().toUInt()}-${node.name.replace('/', '_')}")
        val input = files.backend(node).openInput(node) ?: throw IOException("Couldn't open ${node.name}")
        input.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(256 * 1024); var total = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("${node.name} is larger than ${maxBytes / 1_048_576} MB")
                    o.write(buf, 0, n)
                }
            }
        }
        out
    }

    suspend fun readText(node: FileNode, max: Int = 8_000_000): String = withContext(Dispatchers.IO) {
        val bytes = (files.backend(node).openInput(node) ?: throw IOException("Couldn't open ${node.name}")).use { it.readNBytes(max + 1) }
        if (bytes.size > max) throw IOException("${node.name} is too large to edit here (over ${max / 1_000_000} MB)")
        String(bytes, Charsets.UTF_8)
    }

    /** Overwrites [node]. False when the location is read-only (then the UI offers "Save as"). */
    suspend fun writeText(node: FileNode, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val out = files.backend(node).openOutput(node) ?: return@runCatching false
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    suspend fun writeTo(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }; true }.getOrDefault(false)
    }

    /** A FileNode for a content:// or file:// URI from the picker or another app. */
    fun nodeFor(uri: Uri, mimeHint: String? = null, writable: Boolean = true): FileNode {
        if (uri.scheme == "file") return FileNode.raw(File(uri.path.orEmpty()))
        var name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        val mime = mimeHint ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (OfficeFormat.detect(name) == null) OfficeFormat.fromMime(mime)?.let { name += "." + extensionFor(it) }
        return FileNode(NodeKind.MEDIA, uri.toString(), name, isDir = false, mime = mime ?: Mime.guess(name), canWrite = writable)
    }

    fun remember(uri: Uri, name: String) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val list = (listOf(Recent(uri.toString(), name, System.currentTimeMillis())) + _recent.value.filter { it.uri != uri.toString() }).take(12)
        _recent.value = list
        prefs.edit().putString("recent", JSONArray(list.map { JSONObject().put("u", it.uri).put("n", it.name).put("t", it.opened) }).toString()).apply()
    }

    fun forget(uri: String) {
        val list = _recent.value.filter { it.uri != uri }
        _recent.value = list
        prefs.edit().putString("recent", JSONArray(list.map { JSONObject().put("u", it.uri).put("n", it.name).put("t", it.opened) }).toString()).apply()
    }

    private fun loadRecent(): List<Recent> = runCatching {
        val a = JSONArray(prefs.getString("recent", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Recent(it.getString("u"), it.getString("n"), it.optLong("t")) } }
    }.getOrDefault(emptyList())

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024

        fun extensionFor(f: OfficeFormat) = when (f) {
            OfficeFormat.PDF -> "pdf"; OfficeFormat.DOCX -> "docx"; OfficeFormat.XLSX -> "xlsx"; OfficeFormat.PPTX -> "pptx"
            OfficeFormat.CSV -> "csv"; OfficeFormat.TSV -> "tsv"; OfficeFormat.TEXT -> "txt"
        }
    }
}
