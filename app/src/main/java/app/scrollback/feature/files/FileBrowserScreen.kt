package app.scrollback.feature.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.AddToPhotos
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.data.prefs.Settings
import app.scrollback.data.source.SourceType
import app.scrollback.files.Clipboard
import app.scrollback.files.FileNames
import app.scrollback.files.FileNode
import app.scrollback.files.FileSort
import app.scrollback.files.NodeKind
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.LocalHazeState
import app.scrollback.ui.components.TextInputDialog
import app.scrollback.ui.components.chromeGlass
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SORTS = listOf("name" to "Name", "date" to "Date modified", "size" to "Size", "type" to "Type")

/** One action in the floating selection bar. */
data class BarAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
fun SelectionBar(actions: List<BarAction>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = SbSpace.l, vertical = SbSpace.m)
            .fillMaxWidth()
            .chromeGlass(SbRadius.Large)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SbSpace.s, vertical = SbSpace.s),
        horizontalArrangement = Arrangement.spacedBy(SbSpace.xs),
    ) {
        actions.forEach { a ->
            Column(
                Modifier
                    .padding(horizontal = 6.dp)
                    .clip(SbRadius.Small)
                    .clickable(enabled = a.enabled, onClick = a.onClick)
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, a.label, tint = if (a.enabled) SbColors.TextPrimary else SbColors.TextTertiary, modifier = Modifier.size(22.dp))
                Text(a.label, style = MaterialTheme.typography.labelSmall, color = if (a.enabled) SbColors.TextSecondary else SbColors.TextTertiary)
            }
        }
    }
}

@Composable
fun FileBrowserScreen(root: FileNode, onBack: () -> Unit) {
    val container = LocalContainer.current
    val files = container.files
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val clipboard by files.clipboard.collectAsStateWithLifecycle()
    var stack by remember(root.key) { mutableStateOf(listOf(root)) }
    val dir = stack.last()
    var refresh by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileNode?>(null) }
    var deleting by remember { mutableStateOf<List<FileNode>?>(null) }
    var info by remember { mutableStateOf<FileNode?>(null) }

    val listing by produceState<List<FileNode>?>(null, dir.key, refresh) { value = null; value = files.list(dir) }
    val results by produceState<List<FileNode>?>(null, query, dir.key, searching) {
        value = null
        if (searching && query.isNotBlank()) { delay(300); value = files.search(dir, query) }
    }
    val shown = FileSort.sort((if (searching && query.isNotBlank()) results else listing).orEmpty(), settings.filesSort, settings.filesAscending)
    val selectedNodes = shown.filter { it.key in selection }
    val grid = rememberLazyGridState()
    val collapsed by grid.collapsedState()
    LaunchedEffect(dir.key) { selection = emptySet() }
    LaunchedEffect(Unit) { files.errors.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }

    /** Remote files are copied to the cache first so other apps can open or share them. */
    fun withLocal(nodes: List<FileNode>, then: (List<FileNode>) -> Unit) {
        if (nodes.none { it.isRemote }) { then(nodes); return }
        scope.launch {
            val out = mutableListOf<FileNode>()
            for (n in nodes) {
                if (!n.isRemote) { out += n; continue }
                busy = "Downloading ${n.name}…"
                val f = files.fetchToCache(n) { p -> busy = "Downloading ${n.name}… ${(p * 100).toInt()}%" }
                if (f != null) out += FileNode.raw(f).copy(mime = n.mime)
            }
            busy = null
            if (out.isNotEmpty()) then(out)
        }
    }

    BackHandler(enabled = selection.isNotEmpty() || searching || stack.size > 1) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            searching -> { searching = false; query = "" }
            else -> stack = stack.dropLast(1)
        }
    }

    fun open(n: FileNode) {
        if (selection.isNotEmpty()) {
            selection = if (n.key in selection) selection - n.key else selection + n.key
            return
        }
        if (n.isDir) { searching = false; query = ""; stack = stack + n } else withLocal(listOf(n)) { openWith(ctx, it.first()) }
    }
    fun longPress(n: FileNode) { haptics.tick(); selection = selection + n.key }
    fun done(msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); refresh++; selection = emptySet() }

    GlassScreen(
        title = dir.name,
        collapsed = collapsed,
        onBack = { if (stack.size > 1) stack = stack.dropLast(1) else onBack() },
        actions = {
            val haze = LocalHazeState.current
            GlassIconButton(Icons.Outlined.Search, "Search", { searching = !searching; query = "" }, size = 40.dp, hazeState = haze)
            if (dir.canWrite) GlassIconButton(Icons.Outlined.CreateNewFolder, "New folder", { creating = true }, size = 40.dp, hazeState = haze)
            Box {
                GlassIconButton(Icons.AutoMirrored.Outlined.Sort, "Sort", { sortMenu = true }, size = 40.dp, hazeState = haze)
                DropdownMenu(sortMenu, { sortMenu = false }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Card) {
                    SORTS.forEach { (k, label) ->
                        val sel = settings.filesSort == k
                        DropdownMenuItem(
                            text = { Text(label + if (sel) (if (settings.filesAscending) "  ↑" else "  ↓") else "") },
                            onClick = {
                                sortMenu = false
                                container.appScope.launch { container.settings.setFilesSort(k, if (sel) !settings.filesAscending else true) }
                            },
                        )
                    }
                }
            }
            GlassIconButton(
                if (settings.filesGrid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, "Layout",
                { container.appScope.launch { container.settings.setFilesGrid(!settings.filesGrid) } }, size = 40.dp, hazeState = haze,
            )
        },
        overlay = {
            Column(Modifier.align(Alignment.BottomCenter)) {
                AnimatedVisibility(busy != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    Row(
                        Modifier.padding(SbSpace.l).fillMaxWidth().chromeGlass(SbRadius.Large).padding(SbSpace.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = SbColors.TextPrimary, strokeWidth = 2.dp)
                        Text("  ${busy ?: ""}", color = SbColors.TextPrimary, maxLines = 1)
                    }
                }
                if (selection.isNotEmpty()) {
                    val single = selectedNodes.singleOrNull()
                    SelectionBar(
                        listOf(
                            BarAction("Copy", Icons.Outlined.ContentCopy) {
                                files.setClipboard(Clipboard(selectedNodes, dir, move = false)); selection = emptySet()
                                Toast.makeText(ctx, "Open the destination, then Paste", Toast.LENGTH_SHORT).show()
                            },
                            BarAction("Move", Icons.AutoMirrored.Outlined.DriveFileMove, enabled = dir.canWrite) {
                                files.setClipboard(Clipboard(selectedNodes, dir, move = true)); selection = emptySet()
                                Toast.makeText(ctx, "Open the destination, then Paste", Toast.LENGTH_SHORT).show()
                            },
                            BarAction("Rename", Icons.Outlined.DriveFileRenameOutline, enabled = single != null && dir.canWrite) { renaming = single },
                            BarAction("Delete", Icons.Outlined.Delete, enabled = dir.canWrite) { deleting = selectedNodes },
                            BarAction("Share", Icons.Outlined.Share) { val sel = selectedNodes; withLocal(sel.filter { !it.isDir }) { shareNodes(ctx, it) } },
                            BarAction("Info", Icons.Outlined.Info, enabled = single != null) { info = single },
                            BarAction("Add source", Icons.Outlined.AddToPhotos, enabled = single?.isDir == true) {
                                val n = single!!
                                container.appScope.launch {
                                    when (n.kind) {
                                        NodeKind.RAW -> container.library.addFolderSource(SourceType.FILE, n.id, n.name)
                                        NodeKind.REMOTE -> {
                                            val acc = container.accounts.get(n.treeUri)
                                            val t = if (acc?.type == app.scrollback.accounts.AccountType.SMB) SourceType.SMB else SourceType.SFTP
                                            container.library.addRemoteSource(t, n.id, n.name, n.treeUri.orEmpty())
                                        }
                                        else -> container.library.addFolderSource(SourceType.SAF_TREE, n.uri.toString(), n.name)
                                    }
                                }
                                done("Added “${n.name}” to Scrollback")
                            },
                            BarAction("All", Icons.Outlined.SelectAll) { selection = shown.map { it.key }.toSet() },
                            BarAction("Cancel", Icons.Outlined.Close) { selection = emptySet() },
                        ),
                    )
                } else clipboard?.let { clip ->
                    SelectionBar(
                        listOf(
                            BarAction("Paste ${clip.nodes.size} here", Icons.Outlined.ContentPaste, enabled = dir.canWrite && busy == null) {
                                scope.launch {
                                    busy = if (clip.move) "Moving…" else "Copying…"
                                    val r = files.paste(clip, dir) { busy = (if (clip.move) "Moving " else "Copying ") + it }
                                    busy = null
                                    done(if (r.failed == 0) "Done: ${r.ok} item(s)" else "${r.ok} done, ${r.failed} failed")
                                }
                            },
                            BarAction("Cancel", Icons.Outlined.Close) { files.setClipboard(null) },
                        ),
                    )
                }
            }
        },
    ) { mod ->
        val bottomPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 110.dp
        LazyVerticalGrid(
            columns = if (settings.filesGrid) GridCells.Adaptive(104.dp) else GridCells.Fixed(1),
            state = grid,
            modifier = mod,
            contentPadding = PaddingValues(start = SbSpace.s, end = SbSpace.s, bottom = bottomPad),
        ) {
            full { LargeTitle(dir.name, if (searching) "Searching in this folder and below" else null, horizontalPadding = SbSpace.m) }
            full {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SbSpace.s, vertical = SbSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stack.forEachIndexed { i, n ->
                        if (i > 0) Icon(Icons.Outlined.ChevronRight, null, tint = SbColors.TextTertiary, modifier = Modifier.size(18.dp))
                        GlassPill(n.name, selected = i == stack.lastIndex, onClick = { stack = stack.take(i + 1) })
                    }
                }
            }
            if (searching) {
                full {
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, shape = SbRadius.Pill,
                        placeholder = { Text("Search file names") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(SbSpace.s),
                    )
                }
            }
            val loading = if (searching && query.isNotBlank()) results == null else listing == null
            if (loading) {
                full { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SbColors.TextSecondary) } }
            } else if (shown.isEmpty()) {
                full { EmptyState(Icons.Outlined.FolderOpen, if (searching) "No matches" else "Empty folder", if (searching) "Try another name." else "Nothing here yet.") }
            }
            items(shown, key = { it.key }, span = { GridItemSpan(1) }) { n ->
                val sel = n.key in selection
                if (settings.filesGrid) FileTile(n, sel, { open(n) }, { longPress(n) })
                else FileRow(n, sel, { open(n) }, { longPress(n) })
            }
        }
    }

    if (creating) {
        TextInputDialog("New folder", "New folder", "Create", onConfirm = { name ->
            if (!FileNames.isValid(name)) { Toast.makeText(ctx, "Invalid name", Toast.LENGTH_SHORT).show() }
            else scope.launch { if (files.createDir(dir, name) != null) done("Folder created") else done("Couldn't create the folder") }
        }, onDismiss = { creating = false })
    }
    renaming?.let { n ->
        TextInputDialog("Rename", n.name, "Rename", onConfirm = { name ->
            if (!FileNames.isValid(name)) { Toast.makeText(ctx, "Invalid name", Toast.LENGTH_SHORT).show() }
            else scope.launch { if (files.rename(n, name) != null) done("Renamed") else done("Couldn't rename") }
        }, onDismiss = { renaming = null })
    }
    deleting?.let { nodes ->
        ConfirmDialog(
            "Delete ${nodes.size} item${if (nodes.size == 1) "" else "s"}?",
            "This permanently deletes " + (nodes.singleOrNull()?.let { "“${it.name}”" } ?: "the selected items") +
                (if (nodes.any { it.isDir }) " and everything inside." else "."),
            "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    busy = "Deleting…"
                    val r = files.delete(nodes)
                    busy = null
                    done(if (r.failed == 0) "Deleted ${r.ok}" else "${r.ok} deleted, ${r.failed} failed")
                }
            },
            onDismiss = { deleting = null },
        )
    }
    info?.let { FileInfoSheet(it) { info = null } }
}

fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }
