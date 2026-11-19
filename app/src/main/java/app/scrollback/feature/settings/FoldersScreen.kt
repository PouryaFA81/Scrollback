package app.scrollback.feature.settings

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.data.db.SourceSummary
import app.scrollback.feature.library.ListRow
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch

/** Human label for a SAF tree, e.g. "primary:Download/TikTok" -> "TikTok". */
fun folderLabel(tree: Uri): String {
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
    val path = id.substringAfter(':', id)
    return path.substringAfterLast('/').ifBlank { if (id.startsWith("primary")) "Internal storage" else id }
}

@Composable
fun FoldersScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val repo = container.library
    val haptics = rememberHaptics()
    val sources by repo.observeSources().collectAsStateWithLifecycle(emptyList())
    val scan by repo.scanState.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<SourceSummary?>(null) }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            haptics.confirm()
            container.appScope.launch { repo.addLocalFolder(uri, folderLabel(uri)) }
        }
    }

    GlassScreen(
        title = "Folders",
        collapsed = collapsed,
        onBack = onBack,
        overlay = {
            GlassPill(
                "Add folder",
                selected = true,
                icon = Icons.Outlined.CreateNewFolder,
                onClick = { picker.launch(null) },
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
            verticalArrangement = Arrangement.spacedBy(SbSpace.s),
        ) {
            item { LargeTitle("Folders", "Pick any folders with saved videos and pictures. Subfolders are included.", horizontalPadding = SbSpace.s) }
            if (scan.running) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.padding(horizontal = SbSpace.s, vertical = SbSpace.s).clip(SbRadius.Pill),
                        color = SbColors.TextPrimary,
                        trackColor = SbColors.GlassFillStrong,
                    )
                }
            }
            if (sources.isEmpty()) {
                item { EmptyState(Icons.Outlined.FolderOpen, "No folders yet", "Tap “Add folder” and choose where your TikTok downloads are saved, e.g. Movies/TikTok or Download.") }
            }
            items(sources, key = { it.id }) { s ->
                ListRow(
                    title = s.displayName,
                    subtitle = buildString {
                        append("${s.videoCount} videos · ${s.imageCount} pictures")
                        if (s.lastScanAt > 0) append(" · scanned ${Fmt.relative(s.lastScanAt)}")
                    },
                    leading = { Icon(Icons.Outlined.Folder, null, tint = SbColors.TextPrimary) },
                    trailing = {
                        IconButton(onClick = { removing = s }) { Icon(Icons.Outlined.Delete, "Remove folder", tint = SbColors.TextSecondary) }
                    },
                )
            }
        }
    }

    removing?.let { s ->
        ConfirmDialog(
            "Remove “${s.displayName}”?",
            "Scrollback forgets this folder and its likes, notes and playlist entries. Your files are not deleted.",
            "Remove",
            onConfirm = { container.appScope.launch { repo.removeSource(s.id) } },
            onDismiss = { removing = null },
            destructive = true,
        )
    }
}

