// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.data.source

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.scrollback.core.hash.DuplicateFinder
import app.scrollback.data.db.MediaKind
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Local folder source backed by a persistable SAF tree URI. [treeUri] may also be a document URI
 * inside a granted tree (a subfolder added from the file manager); it is then the scan root.
 */
class SafMediaSource(
    override val sourceId: Long,
    private val treeUri: Uri,
    private val resolver: ContentResolver,
) : MediaSource {
    override val type = SourceType.SAF_TREE

    override fun scan(): Sequence<ScannedEntry> = sequence {
        val rootId = runCatching { DocumentsContract.getDocumentId(treeUri) }
            .getOrElse { DocumentsContract.getTreeDocumentId(treeUri) }
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to "")
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        while (stack.isNotEmpty()) {
            val (docId, path) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val found = mutableListOf<ScannedEntry>()
            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (name.startsWith(".")) continue
                        if (mime == Document.MIME_TYPE_DIR) {
                            stack.addLast(id to (if (path.isEmpty()) name else "$path/$name"))
                            continue
                        }
                        val resolved = MediaSource.resolveMime(name, mime) ?: continue
                        val kind = MediaKind.fromMime(resolved) ?: continue
                        found += ScannedEntry(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                            name = name,
                            relativePath = path,
                            mime = resolved,
                            size = if (c.isNull(3)) 0 else c.getLong(3),
                            dateModified = if (c.isNull(4)) 0 else c.getLong(4),
                            kind = kind,
                        )
                    }
                }
            }
            yieldAll(found)
        }
    }

    override fun playback(uri: String) = PlaybackRequest(Uri.parse(uri))

    override fun byteSource(uri: String, size: Long): DuplicateFinder.ByteSource {
        val parsed = Uri.parse(uri)
        return object : DuplicateFinder.ByteSource {
            override val size: Long = size
            override fun readRange(offset: Long, length: Int): ByteArray {
                val pfd = resolver.openFileDescriptor(parsed, "r") ?: error("Cannot open $uri")
                pfd.use {
                    FileInputStream(it.fileDescriptor).channel.use { ch ->
                        val buf = ByteBuffer.allocate(length)
                        var pos = offset
                        while (buf.hasRemaining()) {
                            val n = ch.read(buf, pos)
                            if (n < 0) break
                            pos += n
                        }
                        return buf.array().copyOf(buf.position())
                    }
                }
            }

            override fun openStream(): InputStream =
                resolver.openInputStream(parsed) ?: error("Cannot open $uri")
        }
    }

    override fun delete(uri: String): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) }.getOrDefault(false)

    override fun shareUri(uri: String): Uri = Uri.parse(uri)
}
