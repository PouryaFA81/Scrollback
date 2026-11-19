package app.scrollback.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.prefs.Settings
import app.scrollback.feature.feed.FeedSpec
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.swipeTabs
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.LocalHazeState
import app.scrollback.ui.components.MediaThumb
import app.scrollback.ui.components.PinPromptDialog
import app.scrollback.ui.components.TextInputDialog
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch

private val SEGMENTS = listOf("Liked", "Playlists", "History", "Search")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    onOpen: (FeedSpec, Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenDuplicates: () -> Unit = {},
    onOpenWatch: () -> Unit = {},
) {
    val container = LocalContainer.current
    val repo = container.library
    val haptics = rememberHaptics()
    val session by container.session.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val showHidden = session.hiddenRevealed
    var segment by rememberSaveable { mutableStateOf(0) }
    var askPin by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val collapsed by grid.collapsedState()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve

    GlassScreen(
        title = "Library",
        collapsed = collapsed,
        actions = {
            if (settings.hasPin) {
                GlassIconButton(
                    if (showHidden) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    if (showHidden) "Hide hidden items" else "Show hidden items",
                    onClick = { if (showHidden) { haptics.tick(); container.revealHidden(false) } else askPin = true },
                    size = 40.dp,
                    hazeState = LocalHazeState.current,
                )
            }
        },
    ) { mod ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = grid,
            modifier = mod.swipeTabs(SEGMENTS.size, segment) { haptics.tick(); segment = it },
            contentPadding = PaddingValues(start = SbSpace.m, end = SbSpace.m, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            full { LargeTitle("Library", horizontalPadding = SbSpace.s) }
            full {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = SbSpace.l),
                    horizontalArrangement = Arrangement.spacedBy(SbSpace.s),
                ) {
                    SEGMENTS.forEachIndexed { i, s ->
                        GlassPill(s, selected = segment == i, onClick = { haptics.tick(); segment = i })
                    }
                }
            }
            if (settings.hasPin) {
                full {
                    ListRow(
                        title = "Hidden items",
                        subtitle = if (showHidden) "Only visible while unlocked" else "Locked — enter your PIN to view",
                        leading = { Icon(Icons.Outlined.VisibilityOff, null, tint = SbColors.TextPrimary) },
                        onClick = { if (showHidden) onOpen(FeedSpec.Hidden, -1) else askPin = true },
                    )
                }
            }
            full {
                ListRow(
                    title = "Watch",
                    subtitle = "Movies and shows from Jellyfin",
                    leading = { Icon(Icons.Outlined.Movie, null, tint = SbColors.TextPrimary) },
                    onClick = onOpenWatch,
                    modifier = Modifier.padding(bottom = SbSpace.xs),
                )
            }
            full {
                ListRow(
                    title = "Duplicates",
                    subtitle = "Find identical copies and free space",
                    leading = { Icon(Icons.Outlined.ContentCopy, null, tint = SbColors.TextPrimary) },
                    onClick = onOpenDuplicates,
                )
            }
            when (segment) {
                0 -> likedSection(onOpen)
                1 -> playlistsSection(onOpenPlaylist)
                2 -> historySection(onOpen)
                else -> searchSection(onOpen)
            }
        }
    }

    if (askPin) {
        PinPromptDialog(
            title = "Show hidden items",
            verify = { container.settings.verifyPin(it) },
            onSuccess = { container.revealHidden(true) },
            onDismiss = { askPin = false },
        )
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.likedSection(onOpen: (FeedSpec, Long) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { LikedGrid(onOpen) }
}

@Composable
private fun LikedGrid(onOpen: (FeedSpec, Long) -> Unit) {
    val container = LocalContainer.current
    val session by container.session.collectAsStateWithLifecycle()
    val liked by remember(session.hiddenRevealed) { container.library.liked(session.hiddenRevealed) }
        .collectAsStateWithLifecycle(null)
    val list = liked ?: return
    if (list.isEmpty()) {
        EmptyState(Icons.Outlined.FavoriteBorder, "No likes yet", "Double-tap a video to like it.")
        return
    }
    ThumbGrid(list) { onOpen(FeedSpec.Liked, it.id) }
}

/** Non-lazy 3-column grid used inside a single lazy item (lists here are user-curated and small). */
@Composable
private fun ThumbGrid(list: List<MediaEntity>, onClick: (MediaEntity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { m ->
                    MediaThumb(
                        m,
                        Modifier.weight(1f).aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).clickable { onClick(m) },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun LazyGridScope.playlistsSection(onOpenPlaylist: (Long) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { PlaylistsList(onOpenPlaylist) }
}

@Composable
private fun PlaylistsList(onOpenPlaylist: (Long) -> Unit) {
    val container = LocalContainer.current
    val session by container.session.collectAsStateWithLifecycle()
    val playlists by remember(session.hiddenRevealed) { container.library.playlists(session.hiddenRevealed) }
        .collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(SbSpace.s)) {
        ListRow(
            title = "New playlist",
            leading = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, tint = SbColors.TextPrimary) },
            onClick = { creating = true },
        )
        playlists.forEach { p ->
            ListRow(
                title = p.name,
                subtitle = "${p.count} items",
                leading = {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(SbColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, tint = SbColors.TextSecondary)
                    }
                },
                onClick = { onOpenPlaylist(p.id) },
            )
        }
    }
    if (creating) {
        TextInputDialog("New playlist", "", "Create", onConfirm = { name ->
            container.appScope.launch { container.library.createPlaylist(name) }
        }, onDismiss = { creating = false })
    }
}

private fun LazyGridScope.historySection(onOpen: (FeedSpec, Long) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { HistoryList(onOpen) }
}

@Composable
private fun HistoryList(onOpen: (FeedSpec, Long) -> Unit) {
    val container = LocalContainer.current
    val session by container.session.collectAsStateWithLifecycle()
    val history by remember(session.hiddenRevealed) { container.library.history(session.hiddenRevealed) }
        .collectAsStateWithLifecycle(null)
    var confirmClear by remember { mutableStateOf(false) }
    val list = history ?: return
    if (list.isEmpty()) {
        EmptyState(Icons.Outlined.History, "No history yet", "Videos you watch appear here, and resume where you left off.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(SbSpace.s)) {
        ListRow(
            title = "Clear history",
            leading = { Icon(Icons.Outlined.DeleteSweep, null, tint = SbColors.TextPrimary) },
            onClick = { confirmClear = true },
        )
        list.forEach { m ->
            ListRow(
                title = m.name,
                subtitle = Fmt.relative(m.lastWatchedAt),
                leading = { MediaThumb(m, Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(10.dp))) },
                onClick = { onOpen(FeedSpec.History, m.id) },
                below = {
                    if (m.durationMs > 0 && m.lastPositionMs > 0) {
                        LinearProgressIndicator(
                            progress = { (m.lastPositionMs.toFloat() / m.durationMs).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp).clip(SbRadius.Pill),
                            color = SbColors.TextPrimary,
                            trackColor = SbColors.GlassFillStrong,
                            drawStopIndicator = {},
                        )
                    }
                },
            )
        }
    }
    if (confirmClear) {
        ConfirmDialog("Clear watch history?", "Resume positions are reset too.", "Clear", onConfirm = {
            container.appScope.launch { container.library.clearHistory() }
        }, onDismiss = { confirmClear = false }, destructive = true)
    }
}

private fun LazyGridScope.searchSection(onOpen: (FeedSpec, Long) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { SearchPanel(onOpen) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPanel(onOpen: (FeedSpec, Long) -> Unit) {
    val container = LocalContainer.current
    val haptics = rememberHaptics()
    val session by container.session.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTags by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val tags by remember(session.hiddenRevealed) { container.library.tagCounts(session.hiddenRevealed) }
        .collectAsStateWithLifecycle(emptyList())
    val results by remember(query, selectedTags, session.hiddenRevealed) {
        container.library.search(query, selectedTags, session.hiddenRevealed)
    }.collectAsStateWithLifecycle(emptyList())
    // With tags selected, the text query narrows the tag results further.
    val filtered = if (selectedTags.isNotEmpty() && query.isNotBlank()) {
        val q = query.trim().lowercase()
        results.filter { it.name.lowercase().contains(q) || it.note.lowercase().contains(q) }
    } else results

    Column {
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Search names, notes, tags") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = SbRadius.Pill,
            modifier = Modifier.fillMaxWidth(),
        )
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(SbSpace.m))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    val sel = t.id in selectedTags
                    GlassPill("#${t.name} · ${t.count}", selected = sel, onClick = {
                        haptics.tick()
                        selectedTags = if (sel) selectedTags - t.id else selectedTags + t.id
                    })
                }
            }
        }
        Spacer(Modifier.height(SbSpace.l))
        if (filtered.isEmpty() && (query.isNotBlank() || selectedTags.isNotEmpty())) {
            Text("No matches", color = SbColors.TextSecondary, modifier = Modifier.padding(SbSpace.l))
        } else {
            ThumbGrid(filtered) { onOpen(FeedSpec.Search(query, selectedTags), it.id) }
        }
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    below: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(SbRadius.Card)
            .background(SbColors.Surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = SbSpace.l, vertical = SbSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(SbSpace.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, maxLines = 1)
            below?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(SbSpace.s))
            trailing()
        }
    }
}
