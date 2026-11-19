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
            else -> "saf|${node.treeUri}|${node.id}|${node.name}"
        }
    )

    fun decode(arg: String): FileNode? {
        val parts = arg.split('|')
        return when (parts.firstOrNull()) {
            "raw" -> FileNode.raw(File(parts.drop(1).joinToString("|"))).let {
                if (it.id == android.os.Environment.getExternalStorageDirectory().absolutePath) it.copy(name = "Internal storage") else it
            }
            "saf" -> if (parts.size >= 4) FileNode(
                kind = NodeKind.SAF, id = parts[2], name = parts.drop(3).joinToString("|"), isDir = true,
                mime = DocumentsContract.Document.MIME_TYPE_DIR, treeUri = parts[1],
            ) else null
            else -> null
        }
    }
}
