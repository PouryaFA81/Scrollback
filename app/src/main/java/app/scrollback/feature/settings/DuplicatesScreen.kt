package app.scrollback.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.scrollback.data.db.MediaEntity
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.MediaThumb
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch

@Composable
fun DuplicatesScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var groups by remember { mutableStateOf<List<List<MediaEntity>>?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirm by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()

    fun scan() {
        running = true
        progress = 0f
        scope.launch {
            val found = runCatching {
                container.duplicates.findDuplicates { done, total -> progress = if (total > 0) done.toFloat() / total else 1f }
            }.getOrElse {
                Toast.makeText(ctx, "Scan failed: ${it.message}", Toast.LENGTH_LONG).show()
                emptyList()
            }
            groups = found
            // Keep the oldest copy of each group; pre-select the rest.
            selected = found.flatMap { g -> g.drop(1).map { it.id } }.toSet()
            running = false
        }
    }

    val all = groups.orEmpty().flatten()
    val selectedItems = all.filter { it.id in selected }
    val reclaim = selectedItems.sumOf { it.size }

    GlassScreen(
        title = "Duplicates",
        collapsed = collapsed,
        onBack = onBack,
        overlay = {
            if (selectedItems.isNotEmpty() && !running) {
                GlassPill(
                    "Delete ${selectedItems.size} · ${Fmt.size(ctx, reclaim)}",
                    selected = true,
                    icon = Icons.Outlined.Delete,
                    onClick = { confirm = true },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = SbSpace.xl),
                )
            }
        },
    ) { mod ->
        LazyColumn(
            modifier = mod,
            state = list,
            contentPadding = PaddingValues(
                start = SbSpace.m, end = SbSpace.m,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(SbSpace.m),
        ) {
            item { LargeTitle("Duplicates", "Byte-identical files across all your folders. Matching is by size, then a fingerprint, then a full hash.") }
            item {
                Row(Modifier.padding(horizontal = SbSpace.s)) {
                    GlassPill(if (groups == null) "Scan library" else "Scan again", selected = groups == null, icon = Icons.Outlined.Search, onClick = { if (!running) scan() })
                }
            }
            if (running) {
                item {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(SbSpace.s).clip(SbRadius.Pill),
                        color = SbColors.TextPrimary,
                        trackColor = SbColors.GlassFillStrong,
                        drawStopIndicator = {},
                    )
                }
            }
            if (groups?.isEmpty() == true && !running) {
                item { EmptyState(Icons.Outlined.ContentCopy, "No duplicates", "Every file in your library is unique.") }
            }
            items(groups.orEmpty(), key = { g -> g.first().id }) { g ->
                Column(
                    Modifier.fillMaxWidth().clip(SbRadius.Large).background(SbColors.Surface).padding(SbSpace.m),
                    verticalArrangement = Arrangement.spacedBy(SbSpace.s),
                ) {
                    Text("${g.size} copies · ${Fmt.size(ctx, g.first().size)} each", style = MaterialTheme.typography.labelLarge, color = SbColors.TextSecondary)
                    g.forEachIndexed { i, m ->
                        val sel = m.id in selected
                        Row(
                            Modifier.fillMaxWidth().clip(SbRadius.Card).clickable {
                                haptics.tick()
                                selected = if (sel) selected - m.id else selected + m.id
                            }.padding(SbSpace.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MediaThumb(m, Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(10.dp)))
                            Spacer(Modifier.width(SbSpace.m))
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${m.relativePath.ifBlank { "/" }} · ${Fmt.date(ctx, m.dateModified)}${if (i == 0) " · oldest" else ""}",
                                    style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                if (sel) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                if (sel) "Will be deleted" else "Keep",
                                tint = if (sel) SbColors.Danger else SbColors.TextTertiary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        val keepsOne = groups.orEmpty().all { g -> g.any { it.id !in selected } }
        ConfirmDialog(
            "Delete ${selectedItems.size} files?",
            (if (keepsOne) "" else "Warning: at least one group has every copy selected. ") +
                "These files are permanently deleted from their folders and free ${Fmt.size(ctx, reclaim)}.",
            "Delete",
            destructive = true,
            onConfirm = {
                val targets = selectedItems
                scope.launch {
                    var failed = 0
                    targets.forEach { if (!container.library.deleteFile(it)) failed++ }
                    haptics.confirm()
                    Toast.makeText(ctx, if (failed == 0) "Deleted ${targets.size} files" else "$failed files couldn't be deleted", Toast.LENGTH_LONG).show()
                    val gone = targets.map { it.id }.toSet()
                    groups = groups.orEmpty().map { g -> g.filter { it.id !in gone } }.filter { it.size > 1 }
                    selected = selected - gone
                }
            },
            onDismiss = { confirm = false },
        )
    }
}
