package app.scrollback.feature.feed

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.core.text.Tags
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.db.isVideo
import app.scrollback.data.prefs.Settings
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.InfoLine
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun PlaylistSheet(item: MediaEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.library
    val haptics = rememberHaptics()
    val playlists by repo.playlists(true).collectAsStateWithLifecycle(emptyList())
    val member by repo.membership(item.id).collectAsStateWithLifecycle(emptyList())
    var newName by remember { mutableStateOf("") }
    GlassSheet(onDismiss) { _ ->
        SheetTitle("Save to playlist")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                newName, { newName = it },
                placeholder = { Text("New playlist") },
                singleLine = true,
                shape = SbRadius.Card,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(SbSpace.m))
            GlassIconButton(Icons.Outlined.Add, "Create playlist", onClick = {
                if (newName.isNotBlank()) {
                    val name = newName
                    newName = ""
                    haptics.confirm()
                    container.appScope.launch { repo.addToPlaylist(repo.createPlaylist(name), item.id) }
                }
            }, hazeState = null)
        }
        Spacer(Modifier.height(SbSpace.m))
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(playlists, key = { it.id }) { p ->
                val inIt = p.id in member
                SettingsRow(
                    title = p.name,
                    subtitle = "${p.count} items",
                    icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    onClick = {
                        haptics.tick()
                        container.appScope.launch {
                            if (inIt) repo.removeFromPlaylist(p.id, item.id) else repo.addToPlaylist(p.id, item.id)
                        }
                    },
                    trailing = {
                        Icon(
                            if (inIt) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                            tint = if (inIt) SbColors.Accent else SbColors.TextTertiary,
                        )
                    },
                )
            }
        }
        if (playlists.isEmpty()) {
            Text("No playlists yet. Name one above to create it and save this item.", color = SbColors.TextSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesSheet(item: MediaEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.library
    val haptics = rememberHaptics()
    val current by repo.tagsFor(item.id).collectAsStateWithLifecycle(null)
    val all by repo.tagCounts(true).collectAsStateWithLifecycle(emptyList())
    var note by remember(item.id) { mutableStateOf(item.note) }
    var tagText by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        if (tagText == null && current != null) tagText = Tags.format(current!!.map { it.name })
    }
    GlassSheet(onDismiss) { close ->
        SheetTitle("Notes & tags")
        OutlinedTextField(
            note, { note = it },
            label = { Text("Description") },
            minLines = 3,
            shape = SbRadius.Card,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(SbSpace.m))
        OutlinedTextField(
            tagText.orEmpty(), { tagText = it },
            label = { Text("Tags") },
            placeholder = { Text("#recipe #funny") },
            shape = SbRadius.Card,
            modifier = Modifier.fillMaxWidth(),
        )
        val parsed = Tags.parse(tagText.orEmpty()).toSet()
        val suggestions = all.filter { it.name !in parsed }.take(12)
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(SbSpace.m))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { t ->
                    GlassPill("#${t.name}", selected = false, onClick = {
                        haptics.tick()
                        tagText = (tagText.orEmpty().trimEnd() + " #${t.name}").trim()
                    })
                }
            }
        }
        Spacer(Modifier.height(SbSpace.xl))
        Button(
            onClick = {
                haptics.confirm()
                val n = note
                val t = tagText.orEmpty()
                container.appScope.launch { repo.saveNoteAndTags(item.id, n, t) }
                close()
            },
            colors = ButtonDefaults.buttonColors(containerColor = SbColors.Accent, contentColor = SbColors.OnAccent),
            shape = SbRadius.Pill,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Save") }
    }
}

@Composable
fun InfoSheet(item: MediaEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.library
    val ctx = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    var confirmDelete by remember { mutableStateOf(false) }
    GlassSheet(onDismiss) { close ->
        SheetTitle("Details")
        InfoLine("Name", item.name)
        InfoLine("Folder", item.relativePath.ifBlank { "/" })
        InfoLine("Type", item.mime)
        InfoLine("Size", Fmt.size(ctx, item.size))
        if (item.isVideo) InfoLine("Duration", Fmt.duration(item.durationMs))
        if (item.width > 0) InfoLine("Resolution", "${item.width} × ${item.height}")
        InfoLine("Modified", Fmt.date(ctx, item.dateModified))
        InfoLine("Indexed", Fmt.date(ctx, item.dateAdded))
        if (item.watchCount > 0) InfoLine("Watched", "${item.watchCount}× · last ${Fmt.relative(item.lastWatchedAt)}")
        Spacer(Modifier.height(SbSpace.l))
        SettingsRow(
            title = if (item.hidden) "Unhide" else "Hide",
            subtitle = if (settings.hasPin) "Hidden items only show after unlocking with your PIN" else "Set a PIN in Settings to use hidden items",
            icon = if (item.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            enabled = settings.hasPin || item.hidden,
            onClick = {
                container.appScope.launch { repo.setHidden(item.id, !item.hidden) }
                close()
            },
        )
        if (container.auth.isRemote(item.uri)) {
            SettingsRow(
                title = if (item.localUri != null) "Downloaded" else "Download",
                subtitle = if (item.localUri != null) "Plays offline from Download/Scrollback" else "Save an offline copy to Download/Scrollback",
                icon = if (item.localUri != null) Icons.Outlined.DownloadDone else Icons.Outlined.Download,
                enabled = item.localUri == null,
                onClick = {
                    Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show()
                    container.appScope.launch(Dispatchers.Main) {
                        val r = runCatching { container.downloader.download(item) }
                        r.getOrNull()?.let { repo.setLocalCopy(item.id, it) }
                        Toast.makeText(ctx, if (r.isSuccess) "Saved to Download/Scrollback" else "Download failed", Toast.LENGTH_SHORT).show()
                    }
                    close()
                },
            )
        }
        SettingsRow(
            title = "Delete file",
            subtitle = if (container.auth.isRemote(item.uri)) "Deletes it on the server" else "Removes it from the device folder",
            icon = Icons.Outlined.Delete,
            onClick = { confirmDelete = true },
        )
        if (confirmDelete) {
            ConfirmDialog(
                title = "Delete this file?",
                body = "\"${item.name}\" will be permanently deleted from its folder.",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = {
                    container.appScope.launch(Dispatchers.Main) {
                        val ok = repo.deleteFile(item)
                        Toast.makeText(ctx, if (ok) "Deleted" else "Couldn't delete this file", Toast.LENGTH_SHORT).show()
                    }
                    close()
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}
