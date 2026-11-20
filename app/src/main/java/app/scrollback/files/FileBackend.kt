// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Operations one storage kind supports. Cross-backend copy/move is done by [FileOps]. */
interface FileBackend {
    fun list(dir: FileNode): List<FileNode>
    fun createDir(parent: FileNode, name: String): FileNode?
    fun createFile(parent: FileNode, name: String, mime: String): Pair<FileNode, OutputStream>?
    fun rename(node: FileNode, newName: String): FileNode?
    fun delete(node: FileNode): Boolean
    fun openInput(node: FileNode): InputStream?
    /** Overwrites an existing file. */
    fun openOutput(node: FileNode): OutputStream? = null
    /** Native move within this backend, or null when unsupported (caller falls back to copy + delete). */
    fun move(node: FileNode, fromParent: FileNode, toDir: FileNode): FileNode?
    fun exists(parent: FileNode, name: String): Boolean = list(parent).any { it.name.equals(name, ignoreCase = true) }
}

class RawFileBackend : FileBackend {
    override fun list(dir: FileNode): List<FileNode> =
        File(dir.id).listFiles()?.filter { !it.name.startsWith(".") }?.map { FileNode.raw(it) }.orEmpty()

    override fun createDir(parent: FileNode, name: String): FileNode? {
        val f = File(parent.id, name)
        return if (f.mkdirs() || f.isDirectory) FileNode.raw(f) else null
    }

    override fun createFile(parent: FileNode, name: String, mime: String): Pair<FileNode, OutputStream>? {
        val f = File(parent.id, name)
        return runCatching { val out = f.outputStream(); FileNode.raw(f) to out }.getOrNull()
    }

    override fun rename(node: FileNode, newName: String): FileNode? {
        val src = File(node.id)
        val dst = File(src.parentFile, newName)
        return if (!dst.exists() && src.renameTo(dst)) FileNode.raw(dst) else null
    }

    override fun delete(node: FileNode): Boolean = File(node.id).deleteRecursively()

    override fun openInput(node: FileNode): InputStream? = runCatching { File(node.id).inputStream() }.getOrNull()
    override fun openOutput(node: FileNode): OutputStream? = runCatching { File(node.id).outputStream() }.getOrNull()

    override fun move(node: FileNode, fromParent: FileNode, toDir: FileNode): FileNode? {
        val dst = File(toDir.id, node.name)
        if (dst.exists()) return null
        return if (File(node.id).renameTo(dst)) FileNode.raw(dst) else null
    }
}

class SafFileBackend(private val resolver: ContentResolver) : FileBackend {
    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    )

    override fun list(dir: FileNode): List<FileNode> {
        val tree = Uri.parse(dir.treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id)
        val out = mutableListOf<FileNode>()
        runCatching {
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2) ?: ""
                    val flags = if (c.isNull(5)) 0 else c.getInt(5)
                    out += FileNode(
                        kind = NodeKind.SAF,
                        id = c.getString(0),
                        name = name,
                        isDir = mime == Document.MIME_TYPE_DIR,
                        size = if (c.isNull(3)) 0 else c.getLong(3),
                        modified = if (c.isNull(4)) 0 else c.getLong(4),
                        mime = if (mime == Document.MIME_TYPE_DIR) mime else mime.ifBlank { Mime.guess(name) },
                        treeUri = dir.treeUri,
                        path = if (dir.path.isEmpty()) name else "${dir.path}/$name",
                        canWrite = flags and (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE) != 0,
                    )
                }
            }
        }
        return out
    }

    private fun nodeFor(parent: FileNode, docUri: Uri, name: String, isDir: Boolean, mime: String) = FileNode(
        kind = NodeKind.SAF,
        id = DocumentsContract.getDocumentId(docUri),
        name = name,
        isDir = isDir,
        mime = mime,
        modified = System.currentTimeMillis(),
        treeUri = parent.treeUri,
        path = if (parent.path.isEmpty()) name else "${parent.path}/$name",
    )

    override fun createDir(parent: FileNode, name: String): FileNode? = runCatching {
        val uri = DocumentsContract.createDocument(resolver, parent.uri, Document.MIME_TYPE_DIR, name) ?: return null
        nodeFor(parent, uri, name, true, Document.MIME_TYPE_DIR)
    }.getOrNull()

    override fun createFile(parent: FileNode, name: String, mime: String): Pair<FileNode, OutputStream>? = runCatching {
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name) ?: return null
        val out = resolver.openOutputStream(uri) ?: return null
        nodeFor(parent, uri, name, false, mime) to out
    }.getOrNull()

    override fun rename(node: FileNode, newName: String): FileNode? = runCatching {
        val uri = DocumentsContract.renameDocument(resolver, node.uri, newName) ?: return null
        node.copy(id = DocumentsContract.getDocumentId(uri), name = newName, path = node.path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" })
    }.getOrNull()

    override fun delete(node: FileNode): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, node.uri) }.getOrDefault(false)

    override fun openInput(node: FileNode): InputStream? = runCatching { resolver.openInputStream(node.uri) }.getOrNull()
    override fun openOutput(node: FileNode): OutputStream? = runCatching { resolver.openOutputStream(node.uri, "wt") }.getOrNull()

    override fun move(node: FileNode, fromParent: FileNode, toDir: FileNode): FileNode? {
        if (node.treeUri != toDir.treeUri) return null
        return runCatching {
            val uri = DocumentsContract.moveDocument(resolver, node.uri, fromParent.uri, toDir.uri) ?: return null
            node.copy(id = DocumentsContract.getDocumentId(uri), path = "${toDir.path}/${node.name}".trimStart('/'))
        }.getOrNull()
    }
}

/** SFTP/SMB through [app.scrollback.remote.fs.RemoteStorage]. Errors surface as exceptions to the repository. */
class RemoteFileBackend(private val storage: app.scrollback.remote.fs.RemoteStorage) : FileBackend {
    private fun acc(n: FileNode) = n.treeUri ?: throw java.io.IOException("No account")
    private fun child(parent: FileNode, name: String) = app.scrollback.remote.fs.RemotePaths.join(parent.id, name)

    override fun list(dir: FileNode): List<FileNode> = storage.use(acc(dir)) { fs ->
        fs.list(dir.id).filter { !it.name.startsWith(".") }.map { FileNode.remote(acc(dir), it) }
    }

    override fun createDir(parent: FileNode, name: String): FileNode? = storage.use(acc(parent)) { fs ->
        val p = child(parent, name)
        fs.mkdir(p)
        FileNode.remote(acc(parent), app.scrollback.remote.fs.RemoteEntry(p, name, true, 0, System.currentTimeMillis()))
    }

    override fun createFile(parent: FileNode, name: String, mime: String): Pair<FileNode, OutputStream>? = storage.use(acc(parent)) { fs ->
        val p = child(parent, name)
        FileNode.remote(acc(parent), app.scrollback.remote.fs.RemoteEntry(p, name, false, 0, System.currentTimeMillis())) to fs.openWrite(p)
    }

    override fun rename(node: FileNode, newName: String): FileNode? = storage.use(acc(node)) { fs ->
        val to = app.scrollback.remote.fs.RemotePaths.join(app.scrollback.remote.fs.RemotePaths.parent(node.id), newName)
        fs.rename(node.id, to)
        node.copy(id = to, name = newName, path = to)
    }

    override fun delete(node: FileNode): Boolean = runCatching { storage.use(acc(node)) { it.delete(node.id, node.isDir) }; true }.getOrDefault(false)

    override fun openInput(node: FileNode): InputStream? = storage.use(acc(node)) { it.openRead(node.id) }
    override fun openOutput(node: FileNode): OutputStream? = storage.use(acc(node)) { it.openWrite(node.id) }

    override fun move(node: FileNode, fromParent: FileNode, toDir: FileNode): FileNode? {
        if (node.treeUri != toDir.treeUri) return null
        return runCatching {
            storage.use(acc(node)) { fs ->
                val to = child(toDir, node.name)
                fs.rename(node.id, to)
                node.copy(id = to, path = to)
            }
        }.getOrNull()
    }
}
