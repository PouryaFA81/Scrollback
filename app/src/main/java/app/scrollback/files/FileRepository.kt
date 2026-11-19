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
class FileRepository(private val context: Context, private val storage: app.scrollback.remote.fs.RemoteStorage) {
    private val resolver = context.contentResolver
    val raw = RawFileBackend()
    val saf = SafFileBackend(resolver)
    val remote = RemoteFileBackend(storage)

    /** Last listing error (e.g. a server that can't be reached), shown by the browser as a message. */
    private val _errors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: kotlinx.coroutines.flow.SharedFlow<String> = _errors

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()
    fun setClipboard(c: Clipboard?) { _clipboard.value = c }

    fun backend(node: FileNode): FileBackend = when (node.kind) {
        NodeKind.RAW -> raw
        NodeKind.REMOTE -> remote
        else -> saf
    }

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

    suspend fun list(dir: FileNode): List<FileNode> = withContext(Dispatchers.IO) {
        try {
            backend(dir).list(dir)
        } catch (e: Exception) {
            _errors.tryEmit(describe(e))
            emptyList()
        }
    }

    /** Copies a (remote) file into the app cache so other apps can open or share it. */
    suspend fun fetchToCache(node: FileNode, onProgress: (Float) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        if (node.kind != NodeKind.REMOTE) return@withContext null
        val dir = File(context.cacheDir, "remote/${node.treeUri}/${node.id.hashCode().toUInt()}").apply { mkdirs() }
        val out = File(dir, node.name)
        if (out.isFile && out.length() == node.size && out.lastModified() >= node.modified) return@withContext out
        try {
            remote.openInput(node)?.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (node.size > 0) onProgress(done.toFloat() / node.size)
                    }
                }
            } ?: return@withContext null
            out
        } catch (e: Exception) {
            out.delete()
            _errors.tryEmit(describe(e))
            null
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is app.scrollback.remote.fs.HostKeyMismatchException -> "The server's host key changed. Remove and re-add the server only if you expected this."
        is app.scrollback.remote.fs.UnknownHostKeyException -> "The server's host key isn't trusted yet."
        is java.net.UnknownHostException -> "Server not found"
        is java.net.ConnectException, is java.net.SocketTimeoutException -> "Couldn't connect to the server"
        else -> e.message?.take(140) ?: e.javaClass.simpleName
    }

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
                // Bound arguments; also match .csv/.tsv by name (CSV files were missing from Documents in 0.10).
                "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ? OR ${MediaStore.Files.FileColumns.MIME_TYPE} IN (?,?,?,?,?,?,?,?,?,?,?,?)" +
                    " OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(
                    "text/%", "text/csv", "text/comma-separated-values", "text/tab-separated-values",
                    "application/pdf", "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.oasis.opendocument.text", "application/epub+zip",
                    "%.csv", "%.tsv",
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

    // ---- Text (notes editor) ----

    /** Reads a text file up to [max] bytes; null if larger or unreadable. */
    suspend fun readText(node: FileNode, max: Int = 2_000_000): String? = withContext(Dispatchers.IO) {
        try {
            backend(node).openInput(node)?.use { s ->
                val bytes = s.readNBytes(max + 1)
                if (bytes.size > max) null else String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            }
        } catch (e: Exception) { _errors.tryEmit(describe(e)); null }
    }

    suspend fun writeText(node: FileNode, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val out = backend(node).openOutput(node) ?: return@withContext false
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) { _errors.tryEmit(describe(e)); false }
    }

    suspend fun createTextFile(dir: FileNode, name: String, text: String): FileNode? = withContext(Dispatchers.IO) {
        try {
            val b = backend(dir)
            val existing = b.list(dir).map { it.name.lowercase() }.toSet()
            val unique = FileNames.unique(name, false) { it.lowercase() in existing }
            val (node, out) = b.createFile(dir, unique, Mime.guess(unique)) ?: return@withContext null
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            node
        } catch (e: Exception) { _errors.tryEmit(describe(e)); null }
    }

    /** Text notes (.md/.txt) under [root] with their folder relative to it, newest first. */
    suspend fun listNotes(root: FileNode, depth: Int = 4, limit: Int = 1000): List<Pair<FileNode, String>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Pair<FileNode, String>>()
        fun walk(dir: FileNode, rel: String, d: Int) {
            if (out.size >= limit) return
            val children = runCatching { backend(dir).list(dir) }.getOrDefault(emptyList())
            for (c in children) {
                if (c.isDir) { if (d < depth && !c.name.startsWith(".")) walk(c, if (rel.isEmpty()) c.name else "$rel/${c.name}", d + 1) }
                else if (app.scrollback.notes.NoteText.isNote(c.name)) out += c to rel
            }
        }
        walk(root, "", 0)
        out.sortedByDescending { it.first.modified }
    }

    // ---- Archives ----

    /** Extracts [archive] into [dest] (or a new sub-folder named after it). Returns the folder written to. */
    suspend fun extract(
        archive: FileNode, dest: FileNode, intoFolder: Boolean, onProgress: (String) -> Unit = {},
    ): Pair<FileNode, ExtractResult> = withContext(Dispatchers.IO) {
        val format = ArchiveFormat.detect(archive.name) ?: throw java.io.IOException("Not a supported archive")
        val db = backend(dest)
        val target = if (intoFolder && format.isArchive) {
            val existing = db.list(dest).map { it.name.lowercase() }.toSet()
            val name = FileNames.unique(ArchiveFormat.baseName(archive.name), true) { it.lowercase() in existing }
            db.createDir(dest, name) ?: throw java.io.IOException("Couldn't create the folder")
        } else dest
        var local: File? = null
        try {
            if (format == ArchiveFormat.SEVEN_Z) {
                local = if (archive.kind == NodeKind.RAW) File(archive.id) else {
                    onProgress("Preparing ${archive.name}…")
                    File(context.cacheDir, "extract-${System.nanoTime()}.7z").also { f ->
                        val input = backend(archive).openInput(archive) ?: throw java.io.IOException("Couldn't read the archive")
                        input.use { i -> f.outputStream().use { i.copyTo(it, 256 * 1024) } }
                    }
                }
            }
            val sink = BackendSink(db, target)
            var last = 0L
            val r = Archives.extract(format, archive.name, archive.size, {
                backend(archive).openInput(archive) ?: throw java.io.IOException("Couldn't read the archive")
            }, local, sink) { path ->
                val now = System.currentTimeMillis()
                if (now - last > 150) { last = now; onProgress("Extracting ${path.substringAfterLast('/')}") }
            }
            target to r
        } finally {
            if (local != null && archive.kind != NodeKind.RAW) local.delete()
        }
    }

    /** Zips [nodes] (folders recursively) into [dir]/[name]. */
    suspend fun compress(nodes: List<FileNode>, dir: FileNode, name: String, onProgress: (String) -> Unit = {}): FileNode? = withContext(Dispatchers.IO) {
        val items = mutableListOf<Archives.ZipItem>()
        fun add(n: FileNode, prefix: String) {
            val path = if (prefix.isEmpty()) n.name else "$prefix/${n.name}"
            if (n.isDir) {
                items += Archives.ZipItem(path, true) { null }
                backend(n).list(n).forEach { add(it, path) }
            } else items += Archives.ZipItem(path, false) { backend(n).openInput(n) }
        }
        nodes.forEach { add(it, "") }
        val db = backend(dir)
        val existing = db.list(dir).map { it.name.lowercase() }.toSet()
        val fileName = FileNames.unique(if (name.lowercase().endsWith(".zip")) name else "$name.zip", false) { it.lowercase() in existing }
        val (node, out) = db.createFile(dir, fileName, "application/zip") ?: return@withContext null
        try {
            var last = 0L
            Archives.createZip(out, items) { p ->
                val now = System.currentTimeMillis()
                if (now - last > 150) { last = now; onProgress("Adding ${p.substringAfterLast('/')}") }
            }
            node
        } catch (e: Exception) {
            runCatching { db.delete(node) }
            throw e
        }
    }

    /** Writes archive entries through any backend, creating folders on demand and never overwriting. */
    private class BackendSink(private val b: FileBackend, root: FileNode) : ArchiveSink {
        private val dirs = HashMap<String, FileNode>().apply { put("", root) }
        private val names = HashMap<String, MutableSet<String>>()

        private fun namesIn(path: String): MutableSet<String> = names.getOrPut(path) {
            b.list(dirs.getValue(path)).map { it.name.lowercase() }.toMutableSet()
        }

        private fun ensure(path: String): FileNode {
            dirs[path]?.let { return it }
            val parentPath = ArchivePaths.parent(path)
            val parent = ensure(parentPath)
            val name = ArchivePaths.name(path)
            val existing = if (name.lowercase() in namesIn(parentPath)) b.list(parent).firstOrNull { it.isDir && it.name.equals(name, true) } else null
            val node = existing ?: (b.createDir(parent, name) ?: throw java.io.IOException("Couldn't create folder $path")).also {
                namesIn(parentPath).add(name.lowercase()); names[path] = mutableSetOf()
            }
            dirs[path] = node
            return node
        }

        override fun dir(path: String) { ensure(path) }

        override fun file(path: String, input: java.io.InputStream) {
            val parentPath = ArchivePaths.parent(path)
            val parent = ensure(parentPath)
            val taken = namesIn(parentPath)
            val name = FileNames.unique(ArchivePaths.name(path), false) { it.lowercase() in taken }
            val (_, out) = b.createFile(parent, name, Mime.guess(name)) ?: throw java.io.IOException("Couldn't write $path")
            out.use { input.copyTo(it, 256 * 1024) }
            taken.add(name.lowercase())
        }
    }
}
