package app.scrollback.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class Category(val label: String) {
    VIDEOS("Videos"), IMAGES("Images"), AUDIO("Audio"), DOCUMENTS("Documents"), DOWNLOADS("Downloads"), APKS("Installation files")
}

data class StorageSummary(
    val total: Long,
    val free: Long,
    val byCategory: Map<Category, Long>,
) {
    val used: Long get() = total - free
}

data class Clipboard(val nodes: List<FileNode>, val fromParent: FileNode, val move: Boolean)

data class OpResult(val ok: Int, val failed: Int)

/** File manager data: roots, browsing, MediaStore categories, storage summary and file operations. */
class FileRepository(private val context: Context) {
    private val resolver = context.contentResolver
    val raw = RawFileBackend()
    val saf = SafFileBackend(resolver)

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()
    fun setClipboard(c: Clipboard?) { _clipboard.value = c }

    fun backend(node: FileNode): FileBackend = if (node.kind == NodeKind.RAW) raw else saf

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun internalRoot(): FileNode = FileNode.raw(Environment.getExternalStorageDirectory()).copy(name = "Internal storage")

    /** SAF folders the user granted (persisted tree permissions). */
    fun grantedTrees(): List<FileNode> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { p ->
            val docId = DocumentsContract.getTreeDocumentId(p.uri)
            FileNode(
                kind = NodeKind.SAF,
                id = docId,
                name = docId.substringAfter(':').substringAfterLast('/').ifBlank { if (docId.startsWith("primary")) "Internal storage" else docId },
                isDir = true,
                mime = DocumentsContract.Document.MIME_TYPE_DIR,
                treeUri = p.uri.toString(),
                canWrite = p.isWritePermission,
            )
        }

    suspend fun list(dir: FileNode): List<FileNode> = withContext(Dispatchers.IO) { backend(dir).list(dir) }

    suspend fun createDir(parent: FileNode, name: String): FileNode? = withContext(Dispatchers.IO) {
        val b = backend(parent)
        val existing = b.list(parent).map { it.name.lowercase() }.toSet()
        b.createDir(parent, FileNames.unique(name, true) { it.lowercase() in existing })
    }

    suspend fun rename(node: FileNode, newName: String): FileNode? = withContext(Dispatchers.IO) { backend(node).rename(node, newName) }

    suspend fun delete(nodes: List<FileNode>): OpResult = withContext(Dispatchers.IO) {
        var ok = 0
        var failed = 0
        nodes.forEach { n ->
            val done = when (n.kind) {
                NodeKind.MEDIA -> runCatching { resolver.delete(n.uri, null, null) > 0 }.getOrDefault(false)
                else -> backend(n).delete(n)
            }
            if (done) ok++ else failed++
        }
        OpResult(ok, failed)
    }

    /** Pastes the clipboard into [dest]; moves use a native move when possible, else copy + delete. */
    suspend fun paste(clip: Clipboard, dest: FileNode, onProgress: (String) -> Unit = {}): OpResult = withContext(Dispatchers.IO) {
        var ok = 0
        var failed = 0
        for (n in clip.nodes) {
            onProgress(n.name)
            if (n.kind == dest.kind && n.id == dest.id) { failed++; continue } // into itself
            val success = runCatching {
                if (clip.move) {
                    val native = if (n.kind == dest.kind) backend(n).move(n, clip.fromParent, dest) else null
                    native != null || (copyRecursive(n, dest) && backend(n).delete(n))
                } else {
                    copyRecursive(n, dest)
                }
            }.getOrDefault(false)
            if (success) ok++ else failed++
        }
        if (clip.move) _clipboard.value = null
        OpResult(ok, failed)
    }

    private fun copyRecursive(src: FileNode, destDir: FileNode): Boolean {
        val db = backend(destDir)
        val existing = db.list(destDir).map { it.name.lowercase() }.toSet()
        val name = FileNames.unique(src.name, src.isDir) { it.lowercase() in existing }
        return if (src.isDir) {
            val newDir = db.createDir(destDir, name) ?: return false
            backend(src).list(src).all { copyRecursive(it, newDir) }
        } else {
            val input = when (src.kind) {
                NodeKind.MEDIA -> resolver.openInputStream(src.uri)
                else -> backend(src).openInput(src)
            } ?: return false
            val (_, out) = db.createFile(destDir, name, src.mime.ifBlank { Mime.guess(name) }) ?: run { input.close(); return false }
            input.use { i -> out.use { o -> i.copyTo(o, 256 * 1024) } }
            true
        }
    }

    /** Recursive name search below [root] (bounded so huge trees stay responsive). */
    suspend fun search(root: FileNode, query: String, limit: Int = 300): List<FileNode> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()
        val out = mutableListOf<FileNode>()
        val stack = ArrayDeque<FileNode>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && out.size < limit && visited < 20_000) {
            val dir = stack.removeLast()
            for (c in backend(dir).list(dir)) {
                visited++
                if (c.name.lowercase().contains(q)) out += c
                if (c.isDir) stack.addLast(c)
            }
        }
        out
    }

    // ---- MediaStore -----------------------------------------------------------------------

    private fun categoryQuery(cat: Category): Triple<Uri, String?, Array<String>?> {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return when (cat) {
            Category.VIDEOS -> Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null)
            Category.IMAGES -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null)
            Category.AUDIO -> Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null)
            Category.DOWNLOADS -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, null, null)
            Category.APKS -> Triple(files, "${MediaStore.Files.FileColumns.MIME_TYPE} = ?", arrayOf("application/vnd.android.package-archive"))
            Category.DOCUMENTS -> Triple(
                files,
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} IN (?,?,?,?,?,?,?,?,?)",
                arrayOf(
                    "application/pdf", "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.oasis.opendocument.text", "application/epub+zip",
                ),
            )
        }
    }

    suspend fun category(cat: Category, limit: Int = 5000): List<FileNode> = withContext(Dispatchers.IO) {
        val (uri, sel, args) = categoryQuery(cat)
        queryMedia(uri, sel, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC", limit)
    }

    suspend fun recent(limit: Int = 40): List<FileNode> = withContext(Dispatchers.IO) {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        queryMedia(
            files,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,2,3) OR ${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf'",
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            limit,
        )
    }

    private fun queryMedia(uri: Uri, sel: String?, args: Array<String>?, order: String, limit: Int): List<FileNode> {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val out = mutableListOf<FileNode>()
        runCatching {
            resolver.query(uri, cols, sel, args, order)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    out += FileNode(
                        kind = NodeKind.MEDIA,
                        id = ContentUris.withAppendedId(uri, id).toString(),
                        name = name,
                        isDir = false,
                        size = c.getLong(2),
                        modified = c.getLong(3) * 1000,
                        mime = c.getString(4) ?: Mime.guess(name),
                        path = (c.getString(5) ?: "") + name,
                    )
                }
            }
        }
        return out
    }

    suspend fun storageSummary(): StorageSummary = withContext(Dispatchers.IO) {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val sizes = Category.entries.filter { it != Category.DOWNLOADS }.associateWith { cat ->
            val (uri, sel, args) = categoryQuery(cat)
            var sum = 0L
            runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), sel, args, null)?.use { c ->
                    while (c.moveToNext()) sum += c.getLong(0)
                }
            }
            sum
        }
        StorageSummary(stat.totalBytes, stat.availableBytes, sizes)
    }

    /** Resolves a raw path or SAF node to a root node + breadcrumb stack for deep links. */
    fun rawNode(path: String): FileNode = FileNode.raw(File(path))
}
