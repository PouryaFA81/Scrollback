package app.scrollback.feature.files

import android.net.Uri
import android.provider.DocumentsContract
import app.scrollback.files.FileNode
import app.scrollback.files.NodeKind
import java.io.File

/** Encodes a browse root in a navigation route. */
object FileRoute {
    fun encode(node: FileNode): String = Uri.encode(
        when (node.kind) {
            NodeKind.RAW -> "raw|${node.id}"
            NodeKind.REMOTE -> "rem|${node.treeUri}|${node.id}|${node.name}"
            NodeKind.MEDIA -> "med|${node.id}|${node.name}"
            else -> "saf|${node.treeUri}|${node.id}|${node.name}"
        }
    )

    /** Same as [encode] but for a single file (the editor and other viewers). */
    fun encodeFile(node: FileNode): String = Uri.encode("F" + Uri.decode(encode(node)))

    fun decode(arg: String): FileNode? {
        if (arg.startsWith("F")) return decode(arg.drop(1))?.let { n ->
            n.copy(isDir = false, mime = app.scrollback.files.Mime.guess(n.name), canWrite = true)
        }
        val parts = arg.split('|')
        return when (parts.firstOrNull()) {
            "raw" -> FileNode.raw(File(parts.drop(1).joinToString("|"))).let {
                if (it.id == android.os.Environment.getExternalStorageDirectory().absolutePath) it.copy(name = "Internal storage") else it
            }
            "saf" -> if (parts.size >= 4) FileNode(
                kind = NodeKind.SAF, id = parts[2], name = parts.drop(3).joinToString("|"), isDir = true,
                mime = DocumentsContract.Document.MIME_TYPE_DIR, treeUri = parts[1],
            ) else null
            "rem" -> if (parts.size >= 4) FileNode(
                kind = NodeKind.REMOTE, id = parts[2], name = parts.drop(3).joinToString("|"), isDir = true,
                mime = DocumentsContract.Document.MIME_TYPE_DIR, treeUri = parts[1], path = parts[2],
            ) else null
            "med" -> if (parts.size >= 3) FileNode(
                kind = NodeKind.MEDIA, id = parts[1], name = parts.drop(2).joinToString("|"), isDir = false,
                mime = app.scrollback.files.Mime.guess(parts.drop(2).joinToString("|")),
            ) else null
            else -> null
        }
    }
}
