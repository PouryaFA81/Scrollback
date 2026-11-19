package app.scrollback.feature.servers

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AddToPhotos
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.scrollback.data.source.MediaSource
import app.scrollback.data.source.SourceType
import app.scrollback.feature.library.ListRow
import app.scrollback.remote.nextcloud.NextcloudPaths
import app.scrollback.remote.webdav.DavEntry
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.InfoLine
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Browse a Nextcloud account over WebDAV; add folders as streaming sources or download files. */
@Composable
fun NextcloudBrowseScreen(accountId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val account = container.accounts.get(accountId) ?: run { onBack(); return }
    val stack = remember { mutableStateListOf("/") }
    val path = stack.last()
    var entries by remember { mutableStateOf<List<DavEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<DavEntry?>(null) }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    val origin = account.baseUrl.substringBefore("://") + "://" + account.baseUrl.substringAfter("://").substringBefore('/')

    LaunchedEffect(path) {
        loading = true; error = null
        val r = withContext(Dispatchers.IO) { runCatching { container.dav.list(NextcloudPaths.davUrl(account, path)) } }
        entries = r.getOrDefault(emptyList()).sortedWith(compareBy<DavEntry>({ !it.isDir }, { it.name.lowercase() }))
        error = r.exceptionOrNull()?.let { e -> if (e is java.io.IOException && e !is app.scrollback.remote.HttpException) "You seem to be offline" else e.message ?: "Error" }
        loading = false
    }
    BackHandler(stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val folderName = if (path == "/") account.displayName else path.trimEnd('/').substringAfterLast('/')
    GlassScreen(
        title = folderName,
        collapsed = collapsed,
        onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() },
        overlay = {
            GlassPill(
                "Add as Scrollback source", selected = true, icon = Icons.Outlined.AddToPhotos,
                onClick = {
                    container.appScope.launch {
                        container.library.addRemoteSource(SourceType.WEBDAV, NextcloudPaths.davUrl(account, path), "☁ $folderName", account.id)
                    }
                    Toast.makeText(ctx, "Added — indexing “$folderName”. It streams into your feeds.", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = SbSpace.xl),
            )
        },
    ) { mod ->
        LazyColumn(
            modifier = mod,
            state = list,
            contentPadding = PaddingValues(
                start = SbSpace.m, end = SbSpace.m,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 112.dp, bottom = SbSpace.m).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stack.forEachIndexed { i, p ->
                        if (i > 0) Icon(Icons.Outlined.ChevronRight, null, tint = SbColors.TextTertiary, modifier = Modifier.size(16.dp))
                        Text(
                            if (p == "/") account.displayName else p.trimEnd('/').substringAfterLast('/'),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (i == stack.lastIndex) SbColors.TextPrimary else SbColors.TextSecondary,
                            modifier = Modifier.clip(SbRadius.Small).clickable { while (stack.size > i + 1) stack.removeAt(stack.lastIndex) }.padding(6.dp),
                        )
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(SbRadius.Pill), color = SbColors.TextPrimary, trackColor = SbColors.GlassFillStrong) }
            error?.let { item { EmptyState(Icons.Outlined.CloudOff, "Can't reach the server", it) } }
            if (!loading && error == null && entries.isEmpty()) item { EmptyState(Icons.Outlined.Folder, "Empty folder", "Nothing here yet.") }
            items(entries, key = { it.href }) { e ->
                val mime = MediaSource.resolveMime(e.name, e.mime)
                ListRow(
                    title = e.name,
                    subtitle = if (e.isDir) null else "${Fmt.size(ctx, e.size)} · ${Fmt.relative(e.modified)}",
                    leading = {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(SbColors.Surface), contentAlignment = Alignment.Center) {
                            val icon = when {
                                e.isDir -> Icons.Outlined.Folder
                                mime?.startsWith("video/") == true -> Icons.Outlined.Movie
                                mime?.startsWith("image/") == true -> Icons.Outlined.Image
                                mime?.startsWith("audio/") == true -> Icons.Outlined.MusicNote
                                else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                            }
                            Icon(icon, null, tint = SbColors.TextSecondary)
                            if (!e.isDir && e.fileId != null && e.hasPreview) {
                                AsyncImage(NextcloudPaths.preview(account, e.fileId, 128, 128), null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
                            }
                        }
                    },
                    onClick = {
                        if (e.isDir) stack.add(NextcloudPaths.relativePath(account, origin + e.href) ?: (path.trimEnd('/') + "/" + e.name))
                        else sheet = e
                    },
                )
            }
        }
    }

    sheet?.let { e ->
        GlassSheet({ sheet = null }) { close ->
            SheetTitle(e.name)
            InfoLine("Size", Fmt.size(ctx, e.size))
            InfoLine("Modified", Fmt.date(ctx, e.modified))
            e.mime?.let { InfoLine("Type", it) }
            SettingsRow(
                "Download",
                subtitle = "Save a copy to Download/Scrollback",
                icon = Icons.Outlined.Download,
                onClick = {
                    Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show()
                    container.appScope.launch(Dispatchers.Main) {
                        val r = runCatching { container.downloader.download(origin + e.href, e.name, e.mime ?: "application/octet-stream", e.size) }
                        Toast.makeText(ctx, if (r.isSuccess) "Saved to Download/Scrollback" else "Download failed", Toast.LENGTH_SHORT).show()
                    }
                    close()
                },
            )
        }
    }
}
