// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.core.FolderFilter
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.glass
import app.scrollback.ui.components.pressable
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * v0.15: Feed → Saved → folder chooser. A glass chip under the top bar ("All folders ▾") that
 * opens a menu with every source folder and its subfolders, so one folder can be watched in
 * saved order (newest or oldest first). The choice is remembered.
 */
@Composable
fun SavedFolderChooser(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val haptics = rememberHaptics()
    var open by remember { mutableStateOf(false) }
    val sources by container.library.observeSources().collectAsStateWithLifecycle(emptyList())
    val videoSources = sources.filter { it.videoCount > 0 }
    // Subfolders are only worked out while the menu is open.
    val folders by remember(open) {
        if (!open) flowOf(emptyMap())
        else container.library.videosByDate(newestFirst = true, showHidden = false).map { list ->
            list.groupBy({ it.sourceId }, { it.relativePath }).mapValues { (_, paths) -> FolderFilter.subfolders(paths.toSet()) }
        }
    }.collectAsStateWithLifecycle(emptyMap())

    val filter = FolderFilter.decode(selected)
    val label = when {
        filter == null -> "All folders"
        else -> {
            val src = sources.firstOrNull { it.id == filter.sourceId }?.displayName ?: "Folder"
            if (filter.path.isEmpty()) src else filter.path.substringAfterLast('/')
        }
    }

    Box(modifier) {
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .pressable(interaction, 0.96f)
                .glass(SbRadius.Pill, elevation = 0.dp)
                .clickable(interaction, indication = null) { haptics.tick(); open = true }
                .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (filter == null) Icons.Outlined.FolderCopy else Icons.Outlined.Folder, null, tint = SbColors.TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            Icon(Icons.Outlined.ExpandMore, "Choose folder", tint = SbColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            open, { open = false },
            containerColor = SbColors.SurfaceHigh, shape = SbRadius.Card,
            modifier = Modifier.heightIn(max = 460.dp).widthIn(min = 220.dp),
        ) {
            @Composable
            fun Item(text: String, value: String, depth: Int, icon: androidx.compose.ui.graphics.vector.ImageVector) {
                val on = value == selected
                DropdownMenuItem(
                    text = {
                        Text(text, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = { Icon(icon, null, modifier = Modifier.padding(start = (depth * 14).dp).size(18.dp)) },
                    trailingIcon = if (on) ({ Icon(Icons.Outlined.Check, null, tint = SbColors.Accent) }) else null,
                    onClick = { haptics.tick(); open = false; onSelect(value) },
                )
            }
            Item("All folders", "", 0, Icons.Outlined.FolderCopy)
            videoSources.forEach { s ->
                HorizontalDivider(color = SbColors.Divider, thickness = 0.5.dp)
                Item(s.displayName, FolderFilter(s.id).encode(), 0, Icons.Outlined.Folder)
                folders[s.id].orEmpty().forEach { path ->
                    Item(path.substringAfterLast('/'), FolderFilter(s.id, path).encode(), 1 + path.count { it == '/' }, Icons.Outlined.Folder)
                }
            }
        }
    }
}
