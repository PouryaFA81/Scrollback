// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.feature.feed.FeedSpec
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.MediaThumb
import app.scrollback.ui.components.TextInputDialog
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onOpen: (FeedSpec, Long) -> Unit,
) {
    val container = LocalContainer.current
    val repo = container.library
    val haptics = rememberHaptics()
    val session by container.session.collectAsStateWithLifecycle()
    val playlist by remember { repo.playlist(playlistId) }.collectAsStateWithLifecycle(null)
    val items by remember(session.hiddenRevealed) { repo.playlistItems(playlistId, session.hiddenRevealed) }
        .collectAsStateWithLifecycle(emptyList())
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    val name = playlist?.name ?: "Playlist"
    val spec = FeedSpec.Playlist(playlistId, name)

    GlassScreen(title = name, collapsed = collapsed, onBack = onBack) { mod ->
        LazyColumn(
            modifier = mod,
            state = list,
            contentPadding = PaddingValues(
                start = SbSpace.m, end = SbSpace.m,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SbSpace.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(SbSpace.s),
        ) {
            item { LargeTitle(name, "${items.size} items", horizontalPadding = SbSpace.s) }
            item {
                Row(Modifier.padding(bottom = SbSpace.m), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                    GlassPill("Play", selected = true, icon = Icons.Outlined.PlayArrow, onClick = {
                        items.firstOrNull()?.let { onOpen(spec, it.id) }
                    })
                    GlassPill("Rename", selected = false, icon = Icons.Outlined.DriveFileRenameOutline, onClick = { renaming = true })
                    GlassPill("Delete", selected = false, icon = Icons.Outlined.Delete, onClick = { deleting = true })
                }
            }
            if (items.isEmpty()) {
                item { EmptyState(Icons.AutoMirrored.Outlined.PlaylistPlay, "Empty playlist", "Tap the bookmark on any video to save it here.") }
            }
            itemsIndexed(items, key = { _, m -> m.id }) { index, m ->
                ListRow(
                    title = m.name,
                    subtitle = if (m.durationMs > 0) Fmt.duration(m.durationMs) else m.relativePath,
                    leading = { MediaThumb(m, Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(10.dp))) },
                    onClick = { onOpen(spec, m.id) },
                    trailing = {
                        Row {
                            IconButton(enabled = index > 0, onClick = {
                                haptics.tick(); container.appScope.launch { repo.moveInPlaylist(playlistId, m.id, -1) }
                            }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up", tint = SbColors.TextSecondary) }
                            IconButton(enabled = index < items.lastIndex, onClick = {
                                haptics.tick(); container.appScope.launch { repo.moveInPlaylist(playlistId, m.id, 1) }
                            }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down", tint = SbColors.TextSecondary) }
                            IconButton(onClick = {
                                haptics.tick(); container.appScope.launch { repo.removeFromPlaylist(playlistId, m.id) }
                            }) { Icon(Icons.Outlined.Close, "Remove", tint = SbColors.TextSecondary) }
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog("Rename playlist", name, "Save", onConfirm = { n ->
            container.appScope.launch { repo.renamePlaylist(playlistId, n) }
        }, onDismiss = { renaming = false })
    }
    if (deleting) {
        ConfirmDialog("Delete playlist?", "The videos themselves stay on your device.", "Delete", onConfirm = {
            container.appScope.launch { repo.deletePlaylist(playlistId) }
            onBack()
        }, onDismiss = { deleting = false }, destructive = true)
    }
}
