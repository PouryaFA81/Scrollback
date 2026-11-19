package app.scrollback.feature.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.scrollback.files.Category
import app.scrollback.files.FileNode
import app.scrollback.files.StorageSummary
import app.scrollback.feature.library.ListRow
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace

private val MEDIA_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_AUDIO,
)

fun categoryIcon(c: Category) = when (c) {
    Category.VIDEOS -> Icons.Outlined.Movie
    Category.IMAGES -> Icons.Outlined.Image
    Category.AUDIO -> Icons.Outlined.MusicNote
    Category.DOCUMENTS -> Icons.Outlined.Description
    Category.DOWNLOADS -> Icons.Outlined.Download
    Category.APKS -> Icons.Outlined.Android
}

/** Shade per category for the monochrome storage bar. */
private fun shade(c: Category) = when (c) {
    Category.VIDEOS -> Color(0xFFF2F2F2)
    Category.IMAGES -> Color(0xFFBDBDC2)
    Category.AUDIO -> Color(0xFF8E8E93)
    Category.DOCUMENTS -> Color(0xFF636366)
    Category.APKS -> Color(0xFF48484A)
    Category.DOWNLOADS -> Color(0xFF3A3A3C)
}

@Composable
fun FilesHomeScreen(
    onBrowse: (FileNode) -> Unit,
    onCategory: (Category) -> Unit,
    onOpenTool: (String) -> Unit = {},
) {
    val container = LocalContainer.current
    val files = container.files
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    val remotes = container.accounts.accounts.collectAsStateWithLifecycle().value.filter { it.type.isNetworkStorage }
    var resumeTick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val allFiles = remember(resumeTick) { files.hasAllFilesAccess() }
    val mediaGranted = remember(resumeTick) {
        MEDIA_PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }
    var permTick by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick++; permTick++ }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            resumeTick++
        }
    }
    val summary by produceState<StorageSummary?>(null, resumeTick) { value = files.storageSummary() }
    val recent by produceState(emptyList<FileNode>(), resumeTick, mediaGranted) { value = if (mediaGranted || allFiles) files.recent() else emptyList() }
    val trees = remember(resumeTick) { files.grantedTrees() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve

    GlassScreen(title = "Files", collapsed = collapsed) { mod ->
        LazyColumn(modifier = mod, state = list, contentPadding = PaddingValues(bottom = bottom)) {
            item { LargeTitle("Files") }
            item { summary?.let { StorageCard(it) } }
            item {
                SectionCard(title = "Categories") {
                    Category.entries.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = SbSpace.s, vertical = SbSpace.xs)) {
                            row.forEach { c ->
                                Column(
                                    Modifier.weight(1f).clip(SbRadius.Card).clickable { onCategory(c) }.padding(vertical = SbSpace.m),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(Modifier.size(44.dp).clip(SbRadius.Pill).background(SbColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                                        Icon(categoryIcon(c), null, tint = SbColors.TextPrimary)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(c.label, style = MaterialTheme.typography.labelMedium, color = SbColors.TextPrimary, maxLines = 1)
                                    summary?.byCategory?.get(c)?.let {
                                        Text(Fmt.size(ctx, it), style = MaterialTheme.typography.labelSmall, color = SbColors.TextTertiary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!mediaGranted && !allFiles) {
                item {
                    SectionCard {
                        ListRow(
                            title = "Allow media access",
                            subtitle = "Needed for categories and recent files. Videos, images and audio only.",
                            leading = { Icon(Icons.Outlined.Shield, null, tint = SbColors.TextPrimary) },
                            onClick = { permLauncher.launch(MEDIA_PERMISSIONS) },
                        )
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Text("RECENT", style = MaterialTheme.typography.labelSmall, color = SbColors.TextTertiary, modifier = Modifier.padding(start = 32.dp, top = SbSpace.m, bottom = SbSpace.s))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SbSpace.l),
                        horizontalArrangement = Arrangement.spacedBy(SbSpace.s),
                    ) {
                        recent.forEach { n ->
                            Column(Modifier.width(96.dp).clip(SbRadius.Card).clickable { openWith(ctx, n) }) {
                                FileThumb(n, Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)), iconSize = 32.dp)
                                Text(n.name, style = MaterialTheme.typography.labelSmall, color = SbColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = "Locations") {
                    if (allFiles) {
                        ListRow(
                            title = "Internal storage",
                            subtitle = summary?.let { "${Fmt.size(ctx, it.free)} free of ${Fmt.size(ctx, it.total)}" },
                            leading = { Icon(Icons.Outlined.PhoneAndroid, null, tint = SbColors.TextPrimary) },
                            onClick = { onBrowse(files.internalRoot()) },
                        )
                    }
                    trees.forEach { t ->
                        ListRow(
                            title = t.name,
                            subtitle = "Granted folder",
                            leading = { Icon(Icons.Outlined.Folder, null, tint = SbColors.TextPrimary) },
                            onClick = { onBrowse(t) },
                        )
                    }
                    remotes.forEach { a ->
                        ListRow(
                            title = a.displayName,
                            subtitle = "${a.type.id.uppercase()} · ${container.remoteStorage.endpoint(a)?.label ?: a.baseUrl}",
                            leading = { Icon(Icons.Outlined.Dns, null, tint = SbColors.TextPrimary) },
                            onClick = { remoteRoot(a)?.let(onBrowse) },
                        )
                    }
                    ListRow(
                        title = "Add folder access",
                        subtitle = "Pick a folder to browse and manage (works without extra permissions)",
                        leading = { Icon(Icons.Outlined.Add, null, tint = SbColors.TextPrimary) },
                        onClick = { treePicker.launch(null) },
                    )
                }
            }
            item { ToolsCard(onOpenTool) }
            if (!allFiles) {
                item {
                    SectionCard(title = "Optional") {
                        Column(Modifier.padding(SbSpace.l)) {
                            Text("All files access", style = MaterialTheme.typography.titleMedium, color = SbColors.TextPrimary)
                            Spacer(Modifier.height(SbSpace.xs))
                            Text(
                                "Lets Scrollback browse your whole internal storage like My Files, without picking folders one by one. " +
                                    "It's optional: everything else works with folder access. Scrollback has no ads or tracking, and file access stays on the device.",
                                style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary,
                            )
                            Spacer(Modifier.height(SbSpace.m))
                            GlassPill("Open permission settings", selected = true, onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                                }.onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageCard(s: StorageSummary) {
    val ctx = LocalContext.current
    SectionCard(title = "Storage") {
        Column(Modifier.padding(SbSpace.l)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.size(ctx, s.used), style = MaterialTheme.typography.headlineMedium, color = SbColors.TextPrimary)
                Spacer(Modifier.width(SbSpace.s))
                Text("used of ${Fmt.size(ctx, s.total)}", style = MaterialTheme.typography.bodyMedium, color = SbColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(SbSpace.m))
            Row(Modifier.fillMaxWidth().height(10.dp).clip(SbRadius.Pill).background(SbColors.SurfaceHigh)) {
                val total = s.total.coerceAtLeast(1).toFloat()
                var known = 0L
                s.byCategory.forEach { (c, bytes) ->
                    if (bytes > 0) {
                        known += bytes
                        Box(Modifier.weight((bytes / total).coerceAtLeast(0.004f)).fillMaxHeight().background(shade(c)))
                    }
                }
                val other = (s.used - known).coerceAtLeast(0)
                if (other > 0) Box(Modifier.weight((other / total).coerceAtLeast(0.004f)).fillMaxHeight().background(Color(0xFF2C2C2E)))
                val free = s.free.coerceAtLeast(0)
                if (free > 0) Spacer(Modifier.weight((free / total).coerceAtLeast(0.004f)))
            }
            Spacer(Modifier.height(SbSpace.m))
            Row(horizontalArrangement = Arrangement.spacedBy(SbSpace.m), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                s.byCategory.filter { it.value > 0 }.forEach { (c, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(SbRadius.Pill).background(shade(c)))
                        Spacer(Modifier.width(4.dp))
                        Text(c.label, style = MaterialTheme.typography.labelSmall, color = SbColors.TextSecondary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(SbRadius.Pill).background(Color(0xFF2C2C2E)))
                    Spacer(Modifier.width(4.dp))
                    Text("System & other", style = MaterialTheme.typography.labelSmall, color = SbColors.TextSecondary)
                }
            }
        }
    }
}

/** Root folder node for an SFTP/SMB account. */
fun remoteRoot(a: app.scrollback.accounts.ServerAccount): FileNode? {
    val ep = app.scrollback.remote.fs.RemoteEndpoint.parse(a.type, a.baseUrl) ?: return null
    return FileNode(
        kind = app.scrollback.files.NodeKind.REMOTE, id = ep.root, name = a.displayName, isDir = true,
        mime = android.provider.DocumentsContract.Document.MIME_TYPE_DIR, treeUri = a.id, path = ep.root,
    )
}

/**
 * Files → Tools: the file-manager add-ons live here, behind one card, so the tab bar stays at five
 * and the Files home stays a file manager first.
 */
@Composable
private fun ToolsCard(onOpenTool: (String) -> Unit) {
    SectionCard(title = "Tools") {
        ListRow(
            title = "Network storage",
            subtitle = "SFTP and SMB servers: browse, copy, stream into your feeds",
            leading = { Icon(Icons.Outlined.Dns, null, tint = SbColors.TextPrimary) },
            onClick = { onOpenTool("remotes") },
        )
        ListRow(
            title = "Notes",
            subtitle = "Markdown and text notes with preview; works with an Obsidian vault",
            leading = { Icon(Icons.AutoMirrored.Outlined.Notes, null, tint = SbColors.TextPrimary) },
            onClick = { onOpenTool("notes") },
        )
        ListRow(
            title = "Archives",
            subtitle = "Extract ZIP, TAR, GZ, XZ and 7z; compress files to ZIP",
            leading = { Icon(Icons.Outlined.FolderZip, null, tint = SbColors.TextPrimary) },
            onClick = { onOpenTool("archives") },
        )
    }
}
