// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.files

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.scrollback.feature.library.ListRow
import app.scrollback.files.ArchiveFormat
import app.scrollback.files.ArchiveLimitException
import app.scrollback.files.FileNode
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch
import java.io.File

fun extractMessage(e: Throwable): String = when (e) {
    is ArchiveLimitException -> e.message ?: "Archive too large"
    is org.apache.commons.compress.PasswordRequiredException -> "This archive is password-protected, which isn't supported yet"
    is org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException -> "Unsupported zip feature: ${e.feature}"
    else -> "Couldn't extract: ${e.message?.take(120) ?: e.javaClass.simpleName}"
}

/** Shown when an archive is tapped in the browser. */
@Composable
fun ArchiveActionsDialog(
    node: FileNode, canExtractHere: Boolean,
    onExtract: (intoFolder: Boolean) -> Unit, onOpenWith: () -> Unit, onDismiss: () -> Unit,
) {
    val format = ArchiveFormat.detect(node.name)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SbColors.SurfaceHigh,
        shape = SbRadius.Large,
        title = { Text(node.name, maxLines = 2) },
        text = {
            Column {
                Text("${format?.label ?: "Archive"}${if (node.size > 0) " · " + android.text.format.Formatter.formatShortFileSize(LocalContext.current, node.size) else ""}",
                    style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, modifier = Modifier.padding(bottom = SbSpace.s))
                if (format?.isArchive == true) ListRow("Extract to “${ArchiveFormat.baseName(node.name)}”", subtitle = "Into a new folder next to it",
                    leading = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = { onExtract(true) }.takeIf { canExtractHere })
                ListRow(if (format?.isArchive == true) "Extract here" else "Decompress here", subtitle = if (canExtractHere) null else "This folder is read-only",
                    leading = { Icon(Icons.Outlined.Unarchive, null) }, onClick = { onExtract(false) }.takeIf { canExtractHere })
                ListRow("Open with another app", leading = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, onClick = onOpenWith)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Files → Tools → Archives: extract an archive from anywhere into a folder you pick. */
@Composable
fun ArchivesToolScreen(onBack: () -> Unit, onBrowse: (FileNode) -> Unit) {
    val container = LocalContainer.current
    val files = container.files
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    var source by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val src = source ?: return@rememberLauncherForActivityResult
        if (tree == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val dest = files.grantedTrees().firstOrNull { it.treeUri == tree.toString() } ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Copying ${src.second}…"
            // Copy the picked document into the cache so every format (incl. 7z) can be read.
            val local = File(ctx.cacheDir, "pick-${System.nanoTime()}-${src.second}")
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(src.first)!!.use { i -> local.outputStream().use { i.copyTo(it, 256 * 1024) } }
                }
                files.extract(FileNode.raw(local).copy(name = src.second), dest, intoFolder = true) { busy = it }
            }
            local.delete()
            busy = null
            result.onSuccess { (folder, r) ->
                Toast.makeText(ctx, "Extracted ${r.files} file${if (r.files == 1) "" else "s"}" + (if (r.skipped > 0) " (${r.skipped} skipped)" else ""), Toast.LENGTH_LONG).show()
                onBrowse(folder)
            }.onFailure { Toast.makeText(ctx, extractMessage(it), Toast.LENGTH_LONG).show() }
        }
    }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "archive"
        if (ArchiveFormat.detect(name) == null) { Toast.makeText(ctx, "“$name” isn't a supported archive", Toast.LENGTH_LONG).show(); return@rememberLauncherForActivityResult }
        source = uri to name
        Toast.makeText(ctx, "Now pick where to extract it", Toast.LENGTH_SHORT).show()
        pickDest.launch(null)
    }

    GlassScreen(title = "Archives", collapsed = collapsed, onBack = onBack) { mod ->
        LazyColumn(modifier = mod, state = list,
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve)) {
            item { LargeTitle("Archives", "ZIP, TAR, TAR.GZ/BZ2/XZ, 7z, GZIP") }
            item {
                SectionCard {
                    ListRow("Extract an archive…", subtitle = "Pick an archive, then the folder to extract into",
                        leading = { Icon(Icons.Outlined.Unarchive, null, tint = SbColors.TextPrimary) },
                        onClick = { if (busy == null) pickArchive.launch(arrayOf("*/*")) })
                }
            }
            busy?.let { b -> item { SectionCard { ListRow(b, leading = { CircularProgressIndicator(color = SbColors.TextSecondary) }) } } }
            item {
                SectionCard(title = "In Files") {
                    ListRow("Tap an archive", subtitle = "Extract it into a new folder or right where it is", leading = { Icon(Icons.Outlined.TouchApp, null, tint = SbColors.TextSecondary) })
                    ListRow("Select files → Compress", subtitle = "Makes a .zip with the selected files and folders", leading = { Icon(Icons.Outlined.FolderZip, null, tint = SbColors.TextSecondary) })
                    ListRow("Safe by default", subtitle = "Never overwrites files, refuses paths that escape the folder, skips links and stops on zip bombs. Password-protected archives aren't supported yet.",
                        leading = { Icon(Icons.Outlined.Inventory2, null, tint = SbColors.TextSecondary) })
                }
            }
        }
    }
}

