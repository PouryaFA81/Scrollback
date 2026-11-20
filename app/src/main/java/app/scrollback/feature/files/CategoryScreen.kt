// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.files

import android.app.Activity
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.data.prefs.Settings
import app.scrollback.files.Category
import app.scrollback.files.Clipboard
import app.scrollback.files.FileNode
import app.scrollback.files.FileSort
import app.scrollback.files.NodeKind
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.LocalHazeState
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch

@Composable
fun CategoryScreen(category: Category, onBack: () -> Unit, onOpenDoc: ((FileNode) -> Unit)? = null, onSendNearby: ((List<FileNode>) -> Unit)? = null) {
    val container = LocalContainer.current
    val files = container.files
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    var refresh by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var deleting by remember { mutableStateOf<List<FileNode>?>(null) }
    var info by remember { mutableStateOf<FileNode?>(null) }
    val items by produceState<List<FileNode>?>(null, refresh) { value = files.category(category) }
    val shown = FileSort.sort(items.orEmpty(), settings.filesSort.let { if (it == "name" && category != Category.DOCUMENTS) "date" else it }, if (settings.filesSort == "name") false else settings.filesAscending)
    val selected = shown.filter { it.key in selection }
    val grid = rememberLazyGridState()
    val collapsed by grid.collapsedState()
    val deleteRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
        selection = emptySet(); refresh++
    }
    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }

    GlassScreen(
        title = category.label,
        collapsed = collapsed,
        onBack = onBack,
        actions = {
            GlassIconButton(
                if (settings.filesGrid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, "Layout",
                { container.appScope.launch { container.settings.setFilesGrid(!settings.filesGrid) } }, size = 40.dp, hazeState = LocalHazeState.current,
            )
        },
        overlay = {
            if (selection.isNotEmpty()) {
                val single = selected.singleOrNull()
                SelectionBar(
                    listOf(
                        BarAction("Copy", Icons.Outlined.ContentCopy) {
                            val parent = FileNode(NodeKind.MEDIA, "", category.label, true)
                            files.setClipboard(Clipboard(selected, parent, move = false)); selection = emptySet()
                            Toast.makeText(ctx, "Open a folder in Files, then Paste", Toast.LENGTH_SHORT).show()
                        },
                        BarAction("Share", Icons.Outlined.Share) { shareNodes(ctx, selected) },
                        *(if (onSendNearby != null) arrayOf(BarAction("Send nearby", Icons.Outlined.WifiTethering) { val sel = selected; selection = emptySet(); onSendNearby(sel) }) else emptyArray()),
                        BarAction("Delete", Icons.Outlined.Delete) {
                            if (files.hasAllFilesAccess()) deleting = selected
                            else {
                                val pi = MediaStore.createDeleteRequest(ctx.contentResolver, selected.map { it.uri })
                                deleteRequest.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                            }
                        },
                        BarAction("Info", Icons.Outlined.Info, enabled = single != null) { info = single },
                        BarAction("All", Icons.Outlined.SelectAll) { selection = shown.map { it.key }.toSet() },
                        BarAction("Cancel", Icons.Outlined.Close) { selection = emptySet() },
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
    ) { mod ->
        LazyVerticalGrid(
            columns = if (settings.filesGrid) GridCells.Adaptive(104.dp) else GridCells.Fixed(1),
            state = grid,
            modifier = mod,
            contentPadding = PaddingValues(start = SbSpace.s, end = SbSpace.s, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp),
        ) {
            full { LargeTitle(category.label, items?.let { "${it.size} files" }, horizontalPadding = SbSpace.m) }
            if (items == null) {
                full { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SbColors.TextSecondary) } }
            } else if (shown.isEmpty()) {
                full { EmptyState(Icons.Outlined.FolderOpen, "Nothing here", "Allow media access or all-files access on the Files tab to see more.") }
            }
            items(shown, key = { it.key }, span = { GridItemSpan(1) }) { n ->
                val sel = n.key in selection
                val click: () -> Unit = { if (selection.isNotEmpty()) { selection = if (sel) selection - n.key else selection + n.key } else if (onOpenDoc != null && app.scrollback.office.OfficeFormat.detect(n.name) != null) onOpenDoc(n) else if (isPlayable(n)) playNodes(ctx, shown, n) else { openWith(ctx, n) } }
                val long: () -> Unit = { haptics.tick(); selection = selection + n.key }
                if (settings.filesGrid) FileTile(n, sel, click, long) else FileRow(n, sel, click, long)
            }
        }
    }

    deleting?.let { nodes ->
        ConfirmDialog("Delete ${nodes.size} files?", "They are permanently deleted from your device.", "Delete", destructive = true, onConfirm = {
            scope.launch {
                val r = files.delete(nodes)
                Toast.makeText(ctx, if (r.failed == 0) "Deleted ${r.ok}" else "${r.ok} deleted, ${r.failed} failed", Toast.LENGTH_SHORT).show()
                selection = emptySet(); refresh++
            }
        }, onDismiss = { deleting = null })
    }
    info?.let { FileInfoSheet(it) { info = null } }
}
