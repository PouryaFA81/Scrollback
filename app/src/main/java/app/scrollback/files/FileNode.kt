package app.scrollback.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/** Where a node lives. RAW = java.io.File (all-files access), SAF = Storage Access Framework, MEDIA = MediaStore row. */
enum class NodeKind { RAW, SAF, MEDIA }

/**
 * One file or folder in the file manager.
 * - RAW: [id] is an absolute path.
 * - SAF: [id] is a document id inside [treeUri].
 * - MEDIA: [id] is a MediaStore content URI; [path] is its relative path for display.
 */
data class FileNode(
    val kind: NodeKind,
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val mime: String = "",
    val treeUri: String? = null,
    val path: String = "",
    val canWrite: Boolean = true,
) {
    val uri: Uri
        get() = when (kind) {
            NodeKind.RAW -> Uri.fromFile(File(id))
            NodeKind.SAF -> DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), id)
            NodeKind.MEDIA -> Uri.parse(id)
        }

    val key: String get() = "${kind.name}:${treeUri ?: ""}:$id"

    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** A content:// URI other apps can read (FileProvider for raw files). */
    fun shareableUri(ctx: Context): Uri = when (kind) {
        NodeKind.RAW -> FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(id))
        else -> uri
    }

    companion object {
        fun raw(f: File): FileNode = FileNode(
            kind = NodeKind.RAW,
            id = f.absolutePath,
            name = f.name.ifEmpty { f.absolutePath },
            isDir = f.isDirectory,
            size = if (f.isFile) f.length() else 0,
            modified = f.lastModified(),
            mime = if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else Mime.guess(f.name),
            path = f.absolutePath,
            canWrite = f.canWrite(),
        )
    }
}

object Mime {
    private val map = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "bmp" to "image/bmp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "opus" to "audio/opus", "wav" to "audio/wav",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "json" to "application/json",
        "zip" to "application/zip", "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "epub" to "application/epub+zip", "html" to "text/html",
    )

    fun guess(name: String): String = map[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun category(mime: String): String = when {
        mime == DocumentsContract.Document.MIME_TYPE_DIR -> "folder"
        mime.startsWith("video/") -> "video"
        mime.startsWith("image/") -> "image"
        mime.startsWith("audio/") -> "audio"
        mime == "application/vnd.android.package-archive" -> "apk"
        mime.startsWith("text/") || mime == "application/pdf" || mime.contains("document") || mime.contains("msword") ||
            mime.contains("sheet") || mime.contains("excel") || mime.contains("presentation") || mime.contains("powerpoint") ||
            mime == "application/epub+zip" -> "document"
        mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") -> "archive"
        else -> "other"
    }
}
