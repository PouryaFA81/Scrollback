// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scrollback.files.FileNode
import app.scrollback.files.Mime
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.InfoLine
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis

fun iconFor(node: FileNode): ImageVector = when (Mime.category(node.mime)) {
    "folder" -> Icons.Outlined.Folder
    "video" -> Icons.Outlined.Movie
    "image" -> Icons.Outlined.Image
    "audio" -> Icons.Outlined.MusicNote
    "apk" -> Icons.Outlined.Android
    "document" -> Icons.Outlined.Description
    "archive" -> Icons.Outlined.FolderZip
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** Thumbnail for images/videos, otherwise a monochrome type icon. */
@Composable
fun FileThumb(node: FileNode, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp = 24.dp) {
    val ctx = LocalContext.current
    val cat = Mime.category(node.mime)
    Box(modifier.background(SbColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        // Remote (SFTP/SMB) thumbnails: pictures only, under 25 MB, so browsing stays light on the network.
        val thumbable = if (node.isRemote) cat == "image" && node.size in 1..25_000_000 else cat == "image" || cat == "video"
        if (!node.isDir && thumbable) {
            val req = remember(node.key) {
                ImageRequest.Builder(ctx).data(node.uri).apply { if (cat == "video") videoFrameMillis(1000) }.build()
            }
            Icon(iconFor(node), null, tint = SbColors.TextTertiary, modifier = Modifier.size(iconSize))
            AsyncImage(req, node.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(iconFor(node), null, tint = if (node.isDir) SbColors.TextPrimary else SbColors.TextSecondary, modifier = Modifier.size(iconSize))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(node: FileNode, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(SbRadius.Card)
            .background(if (selected) SbColors.GlassFillStrong else SbColors.Background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = SbSpace.m, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileThumb(node, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = SbColors.Accent, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp))
        }
        Spacer(Modifier.width(SbSpace.l))
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    if (node.modified > 0) append(Fmt.date(ctx, node.modified))
                    if (!node.isDir) { if (isNotEmpty()) append(" · "); append(Fmt.size(ctx, node.size)) }
                },
                style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(node: FileNode, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .clip(SbRadius.Card)
            .background(if (selected) SbColors.GlassFillStrong else SbColors.Background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
    ) {
        Box {
            FileThumb(node, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)), iconSize = 36.dp)
            if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = SbColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
        }
        Text(
            node.name, style = MaterialTheme.typography.labelMedium, color = SbColors.TextPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

fun openWith(ctx: Context, node: FileNode) {
    val uri = runCatching { node.shareableUri(ctx) }.getOrNull() ?: return
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, node.mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        .onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

fun shareNodes(ctx: Context, nodes: List<FileNode>) {
    val files = nodes.filter { !it.isDir }
    if (files.isEmpty()) { Toast.makeText(ctx, "Folders can't be shared", Toast.LENGTH_SHORT).show(); return }
    val uris = files.mapNotNull { runCatching { it.shareableUri(ctx) }.getOrNull() }
    val mime = files.map { it.mime }.distinct().singleOrNull() ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
    }.apply {
        type = mime
        clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

@Composable
fun FileInfoSheet(node: FileNode, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val files = LocalContainer.current.files
    val count by produceState<Int?>(null, node.key) { if (node.isDir) value = files.list(node).size }
    GlassSheet(onDismiss) { _ ->
        SheetTitle("Details")
        InfoLine("Name", node.name)
        InfoLine("Location", node.path.ifBlank { node.id })
        InfoLine("Type", if (node.isDir) "Folder" else node.mime)
        if (node.isDir) InfoLine("Contains", count?.let { "$it items" } ?: "…") else InfoLine("Size", Fmt.size(ctx, node.size))
        if (node.modified > 0) InfoLine("Modified", Fmt.date(ctx, node.modified))
        InfoLine("Storage", when (node.kind) {
            app.scrollback.files.NodeKind.RAW -> "Direct (all-files access)"
            app.scrollback.files.NodeKind.SAF -> "Granted folder (SAF)"
            app.scrollback.files.NodeKind.MEDIA -> "Media library"
            app.scrollback.files.NodeKind.REMOTE -> "Network server (SFTP/SMB)"
        })
    }
}
