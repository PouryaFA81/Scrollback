// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.watch

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.accounts.ServerAccount
import app.scrollback.remote.jellyfin.JellyfinVideoApi
import app.scrollback.remote.jellyfin.JfItem
import app.scrollback.remote.jellyfin.JfType
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.components.swipeTabs
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of a background load. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

internal fun friendlyError(e: Throwable): String = app.scrollback.remote.jellyfin.JellyfinErrors.friendly(e)

/** Loads [block] on IO whenever [keys] (or the Watch change counter) change. */
@Composable
internal fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Pair<Load<T>, () -> Unit> {
    val watch = LocalContainer.current.watch
    val version by watch.version.collectAsStateWithLifecycle()
    var retry by remember { mutableIntStateOf(0) }
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, version, retry) {
        if (state !is Load.Ok) state = Load.Loading
        state = try { Load.Ok(withContext(Dispatchers.IO) { block() }) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Load.Failed(friendlyError(e))
        }
    }
    return state to { retry++ }
}

private val SEGMENTS = listOf("Home", "Movies", "Shows", "Collections")

/** Library → Watch. Movies and shows from Jellyfin, behind the account from Servers. */
@Composable
fun WatchHomeScreen(
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    onPlay: (String, Boolean) -> Unit,
    onSearch: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val container = LocalContainer.current
    val watch = container.watch
    val accounts by watch.jellyfin.collectAsStateWithLifecycle()
    val account by watch.active.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var pickAccount by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val collapsed by grid.collapsedState()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SbSpace.xl
    // v0.15: a Jellyfin on the home network needs Android 17's local-network permission.
    androidx.compose.runtime.LaunchedEffect(account?.id) {
        account?.let { container.localNetwork.ensure(it.baseUrl, it.altUrl, userAction = false) }
    }

    GlassScreen(
        title = "Watch",
        collapsed = collapsed,
        onBack = onBack,
        actions = {
            if (account != null) {
                if (accounts.size > 1) Box {
                    GlassIconButton(Icons.Outlined.SwapHoriz, "Switch server", onClick = { pickAccount = true }, size = 40.dp)
                    DropdownMenu(expanded = pickAccount, onDismissRequest = { pickAccount = false }) {
                        accounts.forEach { a ->
                            DropdownMenuItem(text = { Text(a.title) }, onClick = { pickAccount = false; watch.select(a.id) })
                        }
                    }
                }
                GlassIconButton(Icons.Outlined.Search, "Search", onClick = onSearch, size = 40.dp)
            }
        },
    ) { mod ->
        val a = account
        if (a == null) {
            LazyColumn(mod, contentPadding = PaddingValues(bottom = bottom)) {
                item { LargeTitle("Watch", "Movies and shows from your Jellyfin server") }
                item { ConnectState(onOpenServers) }
            }
            return@GlassScreen
        }
        val api = remember(a) { watch.api(a) }
        val types = when (segment) { 1 -> "Movie"; 2 -> "Series"; 3 -> "BoxSet"; else -> null }
        val scope = rememberCoroutineScope()
        val version by watch.version.collectAsStateWithLifecycle()
        val pager = remember(api, types) { types?.let { Pager(api, it, scope) } }
        LaunchedEffect(pager, version) { pager?.reset() }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            state = grid,
            modifier = mod.swipeTabs(SEGMENTS.size, segment) { haptics.tick(); segment = it },
            contentPadding = PaddingValues(start = SbSpace.m, end = SbSpace.m, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { LargeTitle("Watch", a.title, horizontalPadding = SbSpace.s) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = SbSpace.s), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                    SEGMENTS.forEachIndexed { i, s -> GlassPill(s, selected = segment == i, onClick = { haptics.tick(); segment = i }) }
                }
            }
            when (segment) {
                0 -> item(span = { GridItemSpan(maxLineSpan) }) { HomeRows(api, a, onOpenItem, onPlay) }
                else -> {
                    val p = pager ?: return@LazyVerticalGrid
                    when {
                        p.error != null && p.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(p.error!!) { p.reset() } }
                        p.total == 0 -> item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(Icons.Outlined.Movie, when (segment) { 1 -> "No movies"; 2 -> "No shows"; else -> "No collections" }, "Nothing of this kind in this Jellyfin library.")
                        }
                        p.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { Spinner() }
                        else -> {
                            itemsIndexed(p.items, key = { _, it -> it.id }) { i, item ->
                                if (i >= p.items.size - 12) LaunchedEffect(p.items.size) { p.loadMore() }
                                Poster(item, api) { onOpenItem(item.id) }
                            }
                            if (p.loading) item(span = { GridItemSpan(maxLineSpan) }) { Spinner() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectState(onOpenServers: () -> Unit) {
    EmptyState(
        Icons.Outlined.Movie,
        "Connect Jellyfin",
        "Browse and play your movies and shows from your own Jellyfin server. Sign in once under Servers. A Jellyfin account you already added for music works here too.",
    ) {
        GlassPill("Add a Jellyfin server", selected = true, onClick = onOpenServers)
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    EmptyState(Icons.Outlined.CloudOff, "Can't load from Jellyfin", message) {
        GlassPill("Try again", selected = true, onClick = retry, icon = Icons.Outlined.Replay)
    }
}

@Composable
private fun Spinner() = Box(Modifier.fillMaxWidth().padding(SbSpace.xxl), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = SbColors.TextSecondary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
}

private data class HomeData(val resume: List<JfItem>, val nextUp: List<JfItem>, val movies: List<JfItem>, val shows: List<JfItem>, val favorites: List<JfItem>)

@Composable
private fun HomeRows(api: JellyfinVideoApi, account: ServerAccount, onOpenItem: (String) -> Unit, onPlay: (String, Boolean) -> Unit) {
    val (state, retry) = rememberLoad(account.id) {
        HomeData(
            resume = api.resume(),
            nextUp = runCatching { api.nextUp() }.getOrDefault(emptyList()),
            movies = runCatching { api.latest("Movie") }.getOrDefault(emptyList()),
            shows = runCatching { api.latest("Series") }.getOrDefault(emptyList()),
            favorites = runCatching { api.items(JellyfinVideoApi.Query(types = "Movie,Series", filters = "IsFavorite", limit = 30)).items }.getOrDefault(emptyList()),
        )
    }
    when (val s = state) {
        Load.Loading -> Spinner()
        is Load.Failed -> ErrorState(s.message, retry)
        is Load.Ok -> Column(verticalArrangement = Arrangement.spacedBy(SbSpace.l)) {
            val d = s.value
            if (d.resume.isEmpty() && d.nextUp.isEmpty() && d.movies.isEmpty() && d.shows.isEmpty()) {
                EmptyState(Icons.Outlined.Movie, "Nothing here yet", "This Jellyfin library has no movies or shows for this user.")
            }
            if (d.resume.isNotEmpty()) WideRow("Continue watching", d.resume, api) { onPlay(it.id, false) }
            if (d.nextUp.isNotEmpty()) WideRow("Next up", d.nextUp, api) { onPlay(it.id, false) }
            if (d.movies.isNotEmpty()) PosterRow("Recently added movies", d.movies, api, onOpenItem)
            if (d.shows.isNotEmpty()) PosterRow("Recently added shows", d.shows, api, onOpenItem)
            if (d.favorites.isNotEmpty()) PosterRow("Favorites", d.favorites, api, onOpenItem)
        }
    }
}

@Composable
internal fun RowTitle(text: String) = Text(
    text, style = MaterialTheme.typography.titleMedium, color = SbColors.TextPrimary, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = SbSpace.s, bottom = SbSpace.s),
)

@Composable
private fun WideRow(title: String, items: List<JfItem>, api: JellyfinVideoApi, onClick: (JfItem) -> Unit) {
    Column {
        RowTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = SbSpace.xs)) {
            items(items, key = { it.id }) { item -> WideCard(item, api, Modifier.width(240.dp)) { onClick(item) } }
        }
    }
}

@Composable
internal fun WideCard(item: JfItem, api: JellyfinVideoApi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(SbRadius.Small).background(SbColors.SurfaceHigh)) {
            AsyncImage(api.wide(item), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.Center).size(36.dp))
            if (item.progress > 0f && !item.userData.played) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = SbColors.Accent, trackColor = Color.White.copy(alpha = 0.2f), drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(if (item.type == JfType.Episode) item.seriesName ?: item.name else item.name, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = if (item.type == JfType.Episode) listOfNotNull(item.episodeLabel, item.name).joinToString(" · ") else listOfNotNull(item.year?.toString(), item.runtimeMs?.let(::runtime)).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PosterRow(title: String, items: List<JfItem>, api: JellyfinVideoApi, onOpenItem: (String) -> Unit) {
    Column {
        RowTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = SbSpace.xs)) {
            items(items, key = { it.id }) { item -> Poster(item, api, Modifier.width(112.dp)) { onOpenItem(item.id) } }
        }
    }
}

@Composable
internal fun Poster(item: JfItem, api: JellyfinVideoApi, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(SbRadius.Small).background(SbColors.SurfaceHigh)) {
            Icon(Icons.Outlined.Movie, null, tint = SbColors.TextTertiary, modifier = Modifier.align(Alignment.Center))
            AsyncImage(api.poster(item), item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.userData.played) Badge(Icons.Filled.CheckCircle, Modifier.align(Alignment.TopEnd))
            else item.userData.unplayedCount?.takeIf { it > 0 && item.type == JfType.Series }?.let { n ->
                Text("$n", style = MaterialTheme.typography.labelSmall, color = SbColors.OnAccent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(50)).background(SbColors.Accent).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            if (item.progress > 0f && !item.userData.played && item.playable) {
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = SbColors.Accent, trackColor = Color.White.copy(alpha = 0.2f), drawStopIndicator = {})
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, style = MaterialTheme.typography.bodySmall, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.year?.let { Text("$it", style = MaterialTheme.typography.labelSmall, color = SbColors.TextSecondary) }
    }
}

@Composable
private fun Badge(icon: ImageVector, modifier: Modifier) = Icon(
    icon, null, tint = SbColors.TextPrimary,
    modifier = modifier.padding(6.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
)

internal fun runtime(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/** Posters of one item type, loaded 60 at a time as the grid scrolls. */
internal class Pager(private val api: JellyfinVideoApi, private val types: String, private val scope: kotlinx.coroutines.CoroutineScope) {
    val items = mutableStateListOf<JfItem>()
    var total by mutableIntStateOf(-1)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    private var generation = 0

    fun reset() { generation++; items.clear(); total = -1; error = null; loading = false; loadMore() }

    fun loadMore() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        val gen = generation
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { api.items(JellyfinVideoApi.Query(types = types, start = items.size, limit = 60, sortBy = "SortName")) }
                if (gen != generation) return@launch
                items.addAll(page.items.filter { n -> items.none { it.id == n.id } }); total = page.total; error = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (gen == generation) error = friendlyError(e)
            } finally { if (gen == generation) loading = false }
        }
    }
}

/** Search movies, shows, episodes and collections on the active server. */
@Composable
fun WatchSearchScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit) {
    val watch = LocalContainer.current.watch
    val account by watch.active.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<List<JfItem>>?>(null) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(account) {
        val api = account?.let { watch.api(it) } ?: return@LaunchedEffect
        snapshotFlow { query.trim() }.distinctUntilChanged().collect { q ->
            if (q.length < 2) { results = null; return@collect }
            kotlinx.coroutines.delay(350)
            results = Load.Loading
            results = try { Load.Ok(withContext(Dispatchers.IO) { api.search(q) }) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Load.Failed(friendlyError(e))
            }
        }
    }
    GlassScreen(title = "Search", collapsed = true, onBack = onBack) { mod ->
        LazyColumn(mod, state = list, contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SbSpace.xl)) {
            item {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Movies, shows, episodes") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(),
                    shape = SbRadius.Card,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SbColors.TextSecondary, unfocusedBorderColor = SbColors.Divider, cursorColor = SbColors.Accent),
                    modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = SbSpace.l, end = SbSpace.l, top = 72.dp, bottom = SbSpace.l).focusRequester(focus),
                )
            }
            val api = account?.let { watch.api(it) }
            when (val r = results) {
                null -> {}
                Load.Loading -> item { Spinner() }
                is Load.Failed -> item { EmptyState(Icons.Outlined.CloudOff, "Search failed", r.message) }
                is Load.Ok -> {
                    if (r.value.isEmpty()) item { EmptyState(Icons.Outlined.Search, "No results", "Nothing matches “${query.trim()}”.") }
                    if (api != null) items(r.value, key = { it.id }) { item -> SearchRow(item, api) { onOpenItem(item.id) } }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(item: JfItem, api: JellyfinVideoApi, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = SbSpace.l, vertical = SbSpace.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(SbColors.SurfaceHigh)) {
            AsyncImage(api.poster(item), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(SbSpace.m))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = when (item.type) { JfType.Movie -> "Movie"; JfType.Series -> "Show"; JfType.Episode -> listOfNotNull(item.seriesName, item.episodeLabel).joinToString(" · "); JfType.BoxSet -> "Collection"; else -> item.type.name }
            Text(listOfNotNull(kind, item.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, maxLines = 1)
        }
    }
}

/** Movie, show, season, episode or collection details. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchDetailScreen(itemId: String, onBack: () -> Unit, onOpenItem: (String) -> Unit, onPlay: (String, Boolean) -> Unit) {
    val container = LocalContainer.current
    val watch = container.watch
    val account by watch.active.collectAsStateWithLifecycle()
    val api = account?.let { watch.api(it) }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    val scope = rememberCoroutineScope()
    val (state, retry) = rememberLoad(itemId, account?.id) { (api ?: error("No Jellyfin account")).item(itemId) }
    var overrideFav by remember(itemId) { mutableStateOf<Boolean?>(null) }
    var overridePlayed by remember(itemId) { mutableStateOf<Boolean?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val title = (state as? Load.Ok)?.value?.name ?: ""

    GlassScreen(title = title, collapsed = collapsed, onBack = onBack) { mod ->
        LazyColumn(mod, state = list, contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + SbSpace.xl)) {
            when (val s = state) {
                Load.Loading -> item { Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 96.dp)) { Spinner() } }
                is Load.Failed -> item { Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 72.dp)) { ErrorState(s.message, retry) } }
                is Load.Ok -> {
                    val item = s.value
                    val a = api ?: return@LazyColumn
                    val fav = overrideFav ?: item.userData.favorite
                    val played = overridePlayed ?: item.userData.played
                    item { Header(item, a) }
                    item {
                        Column(Modifier.padding(horizontal = SbSpace.screenH)) {
                            if (item.type == JfType.Episode) item.seriesName?.let { Text(it, color = SbColors.TextSecondary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { item.seriesId?.let(onOpenItem) }) }
                            Text(item.name, style = MaterialTheme.typography.headlineSmall, color = SbColors.TextPrimary, fontWeight = FontWeight.Bold)
                            val meta = listOfNotNull(
                                item.episodeLabel,
                                item.year?.toString(),
                                item.runtimeMs?.takeIf { item.playable }?.let(::runtime),
                                item.communityRating?.let { "★ %.1f".format(it) },
                                item.officialRating,
                                item.childCount?.takeIf { item.type == JfType.Series }?.let { "$it seasons" },
                            ).joinToString("  ·  ")
                            if (meta.isNotEmpty()) Text(meta, color = SbColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                            if (item.genres.isNotEmpty()) FlowRow(Modifier.padding(top = SbSpace.m), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                item.genres.forEach { g ->
                                    Text(g, style = MaterialTheme.typography.labelMedium, color = SbColors.TextSecondary,
                                        modifier = Modifier.clip(SbRadius.Pill).background(SbColors.SurfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp))
                                }
                            }
                            Row(Modifier.padding(top = SbSpace.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                                if (item.playable) {
                                    val resume = item.positionMs > 0 && !played
                                    GlassPill(if (resume) "Resume ${clock(item.positionMs)}" else "Play", selected = true, onClick = { onPlay(item.id, false) }, icon = Icons.Filled.PlayArrow)
                                    if (resume) GlassPill("From start", selected = false, onClick = { onPlay(item.id, true) })
                                }
                                if (item.type == JfType.Series) NextUpButton(item, a, onPlay)
                                Spacer(Modifier.weight(1f))
                                if (item.type != JfType.BoxSet) GlassIconButton(
                                    if (played) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                    if (played) "Mark unplayed" else "Mark played",
                                    onClick = {
                                        val target = !played; overridePlayed = target
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { a.setPlayed(item.id, target) } }
                                                .onSuccess { watch.changed() }.onFailure { overridePlayed = !target; actionError = friendlyError(it) }
                                        }
                                    }, size = 44.dp, tint = if (played) SbColors.Accent else SbColors.TextSecondary,
                                )
                                GlassIconButton(
                                    if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    if (fav) "Remove from favorites" else "Add to favorites",
                                    onClick = {
                                        val target = !fav; overrideFav = target
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { a.setFavorite(item.id, target) } }
                                                .onSuccess { watch.changed() }.onFailure { overrideFav = !target; actionError = friendlyError(it) }
                                        }
                                    }, size = 44.dp, tint = if (fav) SbColors.Like else SbColors.TextSecondary,
                                )
                            }
                            actionError?.let { Text(it, color = SbColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                            item.overview?.let { Text(it, color = SbColors.TextPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = SbSpace.l)) }
                        }
                    }
                    when (item.type) {
                        JfType.Series -> item { SeasonsAndEpisodes(item.id, null, a, onPlay, onOpenItem) }
                        JfType.Season -> item { SeasonsAndEpisodes(item.seriesId ?: item.id, item.id, a, onPlay, onOpenItem) }
                        JfType.BoxSet -> item { CollectionItems(item.id, a, onOpenItem) }
                        else -> {}
                    }
                    val cast = item.people.filter { it.type == "Actor" || it.type == "GuestStar" }.take(30)
                    val crew = item.people.filter { it.type == "Director" || it.type == "Writer" }
                    if (crew.isNotEmpty()) item {
                        Text(crew.groupBy { it.type }.entries.joinToString("\n") { (t, ps) -> "$t: " + ps.joinToString(", ") { it.name } },
                            color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = SbSpace.screenH, vertical = SbSpace.m))
                    }
                    if (cast.isNotEmpty()) item {
                        Column(Modifier.padding(top = SbSpace.l)) {
                            Box(Modifier.padding(start = SbSpace.m)) { RowTitle("Cast") }
                            LazyRow(contentPadding = PaddingValues(horizontal = SbSpace.l), horizontalArrangement = Arrangement.spacedBy(SbSpace.m)) {
                                items(cast, key = { it.id + (it.role ?: "") }) { p ->
                                    Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.size(72.dp).clip(CircleShape).background(SbColors.SurfaceHigh), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Outlined.Person, null, tint = SbColors.TextTertiary)
                                            AsyncImage(a.personUrl(p), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        }
                                        Text(p.name, style = MaterialTheme.typography.labelMedium, color = SbColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                        p.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = SbColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Header(item: JfItem, api: JellyfinVideoApi) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(SbColors.Surface)) {
        val backdrop = when {
            item.backdropTags.isNotEmpty() -> api.backdropUrl(item.id, item.backdropTags.first())
            item.parentBackdropItemId != null && item.parentBackdropTags.isNotEmpty() -> api.backdropUrl(item.parentBackdropItemId, item.parentBackdropTags.first())
            else -> api.wide(item)
        }
        AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.45f to Color.Transparent, 1f to SbColors.Background)))
    }
    Spacer(Modifier.height(SbSpace.s))
}

@Composable
private fun NextUpButton(series: JfItem, api: JellyfinVideoApi, onPlay: (String, Boolean) -> Unit) {
    val (state, _) = rememberLoad(series.id, "next") { api.nextUp(1, series.id).firstOrNull() }
    val next = (state as? Load.Ok)?.value ?: return
    GlassPill("Play ${next.episodeLabel ?: next.name}", selected = true, onClick = { onPlay(next.id, false) }, icon = Icons.Filled.PlayArrow)
}

@Composable
private fun SeasonsAndEpisodes(seriesId: String, onlySeason: String?, api: JellyfinVideoApi, onPlay: (String, Boolean) -> Unit, onOpenItem: (String) -> Unit) {
    val (seasonsState, retry) = rememberLoad(seriesId, onlySeason) { if (onlySeason != null) emptyList() else api.seasons(seriesId) }
    val seasons = (seasonsState as? Load.Ok)?.value.orEmpty()
    var selected by rememberSaveable(seriesId) { mutableStateOf<String?>(null) }
    val season = onlySeason ?: selected ?: seasons.firstOrNull { !it.userData.played }?.id ?: seasons.firstOrNull()?.id
    Column(Modifier.padding(top = SbSpace.xl)) {
        if (seasonsState is Load.Failed) { ErrorState(seasonsState.message, retry); return }
        if (seasons.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SbSpace.l), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
            seasons.forEach { s -> GlassPill(s.name, selected = s.id == season, onClick = { selected = s.id }) }
        }
        if (season == null) { if (seasonsState is Load.Ok) EmptyState(Icons.Outlined.Movie, "No episodes", "This show has no episodes yet."); return }
        val (eps, retryEps) = rememberLoad(seriesId, season) { api.episodes(seriesId, season) }
        when (eps) {
            Load.Loading -> Spinner()
            is Load.Failed -> ErrorState(eps.message, retryEps)
            is Load.Ok -> Column(Modifier.padding(top = SbSpace.m), verticalArrangement = Arrangement.spacedBy(SbSpace.m)) {
                eps.value.forEach { e -> EpisodeRow(e, api, onClick = { onPlay(e.id, false) }, onDetails = { onOpenItem(e.id) }) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: JfItem, api: JellyfinVideoApi, onClick: () -> Unit, onDetails: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = SbSpace.l, vertical = 2.dp)) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(SbRadius.Small).background(SbColors.SurfaceHigh)) {
            AsyncImage(api.wide(e), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (e.userData.played) Badge(Icons.Filled.CheckCircle, Modifier.align(Alignment.TopEnd))
            if (e.progress > 0f && !e.userData.played) LinearProgressIndicator(progress = { e.progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = SbColors.Accent, trackColor = Color.White.copy(alpha = 0.2f), drawStopIndicator = {})
        }
        Spacer(Modifier.width(SbSpace.m))
        Column(Modifier.weight(1f).clickable(onClick = onDetails)) {
            Text(listOfNotNull(e.indexNumber?.let { "$it." }, e.name).joinToString(" "), style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            e.runtimeMs?.let { Text(runtime(it), style = MaterialTheme.typography.labelSmall, color = SbColors.TextSecondary) }
            e.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun CollectionItems(id: String, api: JellyfinVideoApi, onOpenItem: (String) -> Unit) {
    val (state, retry) = rememberLoad(id, "children") { api.items(JellyfinVideoApi.Query(types = "", parentId = id, recursive = false, sortBy = "ProductionYear,SortName", limit = 200)).items }
    Column(Modifier.padding(horizontal = SbSpace.m, vertical = SbSpace.l), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            Load.Loading -> Spinner()
            is Load.Failed -> ErrorState(state.message, retry)
            is Load.Ok -> state.value.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item -> Poster(item, api, Modifier.weight(1f)) { onOpenItem(item.id) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
