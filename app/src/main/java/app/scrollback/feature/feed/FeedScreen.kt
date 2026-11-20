// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.feed

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.scrollback.core.FeedOrder
import app.scrollback.data.db.MediaEntity
import app.scrollback.data.db.isVideo
import app.scrollback.data.prefs.Settings
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.MediaThumb
import app.scrollback.ui.components.glass
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.components.shareMedia
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbMotion
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private sealed interface FeedSheet {
    data class Playlists(val id: Long) : FeedSheet
    data class Notes(val id: Long) : FeedSheet
    data class Info(val id: Long) : FeedSheet
}

/**
 * TikTok-style vertical feed used everywhere (tabs, playlists, liked, history, search, pictures).
 * [header] lets a tab add its own controls (reshuffle, newest/oldest) to the floating top bar.
 */
@Composable
fun FeedScreen(
    spec: FeedSpec,
    startId: Long,
    isTab: Boolean,
    onBack: (() -> Unit)? = null,
    onOpenFolders: () -> Unit = {},
    header: @Composable RowScope.() -> Unit = {},
    /** Main Feed tab: the segmented Random | Saved toggle replaces the title pill. */
    showTitle: Boolean = true,
) {
    val container = LocalContainer.current
    val repo = container.library
    val session by container.session.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val showHidden = session.hiddenRevealed || spec == FeedSpec.Hidden
    val rawList by remember(spec, showHidden) { spec.flow(repo, showHidden) }.collectAsStateWithLifecycle(null)
    // Songs live in Music; playlists may mix both, the feed only shows videos and pictures.
    val list = remember(rawList) { rawList?.filterNot { it.kind == app.scrollback.data.db.MediaKind.AUDIO.id } }

    // Order is snapshotted on first load so likes, history updates or rescans never make the feed jump.
    var order by remember(spec, showHidden) { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(list) {
        val l = list ?: return@LaunchedEffect
        val ids = l.map { it.id }
        val latest = if (spec is FeedSpec.Random) FeedOrder.shuffle(ids, spec.seed) else ids
        order = order?.let { FeedOrder.merge(it, latest) } ?: latest
    }
    val byId = remember(list) { list.orEmpty().associateBy { it.id } }
    val items = remember(order, byId) { order.orEmpty().mapNotNull { byId[it] } }

    val bottomReserve = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        if (isTab) FloatingNavReserve else 24.dp

    Box(Modifier.fillMaxSize().background(SbColors.Background)) {
        when {
            order == null -> Unit
            items.isEmpty() -> EmptyState(
                icon = if (spec is FeedSpec.Pictures) Icons.Outlined.FolderOpen else Icons.Outlined.VideoLibrary,
                title = "Nothing here yet",
                body = if (spec is FeedSpec.Random || spec is FeedSpec.Saved)
                    "Add the folders where your saved videos live. Scrollback indexes them on this device only."
                else "Items you add will show up here.",
                modifier = Modifier.align(Alignment.Center),
                action = if (spec is FeedSpec.Random || spec is FeedSpec.Saved) {
                    { GlassPill("Add folders", selected = true, onClick = onOpenFolders, icon = Icons.Outlined.FolderOpen) }
                } else null,
            )
            else -> key(spec, showHidden) {
                FeedPager(items, byId, startId, settings, bottomReserve)
            }
        }
        FeedTopBar(if (showTitle) spec.title else "", onBack, settings, header)
    }
}

@Composable
private fun FeedTopBar(
    title: String,
    onBack: (() -> Unit)?,
    settings: Settings,
    header: @Composable RowScope.() -> Unit,
) {
    val container = LocalContainer.current
    val haptics = rememberHaptics()
    var speedMenu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = SbSpace.m, vertical = SbSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SbSpace.s),
    ) {
        if (onBack != null) GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, size = 40.dp)
        if (title.isNotEmpty()) Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = SbColors.TextPrimary,
            modifier = Modifier.glass(SbRadius.Pill, elevation = 0.dp).padding(horizontal = 14.dp, vertical = 9.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        header()
        Spacer(Modifier.weight(1f))
        val openSettings = app.scrollback.ui.LocalOpenSettings.current
        if (onBack == null && openSettings != null) app.scrollback.ui.components.SettingsButton(openSettings)
        Box {
            Text(
                "${formatSpeed(settings.defaultSpeed)}×",
                style = MaterialTheme.typography.labelLarge,
                color = SbColors.TextPrimary,
                modifier = Modifier
                    .glass(SbRadius.Pill, elevation = 0.dp)
                    .pointerInput(Unit) { detectTapGestures { speedMenu = true } }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            DropdownMenu(speedMenu, { speedMenu = false }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Card) {
                SPEEDS.forEach { s ->
                    DropdownMenuItem(
                        text = { Text("${formatSpeed(s)}×", fontWeight = if (s == settings.defaultSpeed) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = {
                            haptics.tick()
                            speedMenu = false
                            container.appScope.launch { container.settings.setDefaultSpeed(s) }
                        },
                    )
                }
            }
        }
        GlassIconButton(
            if (settings.loopOne) Icons.Outlined.RepeatOne else Icons.Outlined.KeyboardDoubleArrowDown,
            if (settings.loopOne) "Looping this video" else "Auto-advance",
            onClick = {
                haptics.tick()
                container.appScope.launch { container.settings.setLoopOne(!settings.loopOne) }
            },
            size = 40.dp,
        )
    }
}

private fun formatSpeed(s: Float): String = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FeedPager(
    items: List<MediaEntity>,
    byId: Map<Long, MediaEntity>,
    startId: Long,
    settings: Settings,
    bottomReserve: androidx.compose.ui.unit.Dp,
) {
    val container = LocalContainer.current
    val repo = container.library
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)) { items.size }
    val player = remember { container.playback.newPlayer() }
    var currentId by remember { mutableStateOf<Long?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var sheet by remember { mutableStateOf<FeedSheet?>(null) }

    fun saveCurrentPosition() {
        val id = currentId ?: return
        val pos = player.currentPosition
        val dur = player.duration
        val save = if (dur > 0 && pos > dur - 2500) 0L else pos
        container.appScope.launch { repo.savePosition(id, save) }
    }

    LaunchedEffect(settings.loopOne) {
        player.repeatMode = if (settings.loopOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(settings.defaultSpeed) { player.setPlaybackSpeed(settings.defaultSpeed) }

    val settledItem = items.getOrNull(pagerState.settledPage)
    LaunchedEffect(settledItem?.id) {
        saveCurrentPosition()
        val item = settledItem ?: return@LaunchedEffect
        if (item.isVideo) {
            currentId = item.id
            val req = repo.playback(item)
            player.setMediaItem(MediaItem.fromUri(req.uri))
            val resume = item.lastPositionMs
            if (resume > 1500 && (item.durationMs <= 0 || resume < item.durationMs - 2500)) player.seekTo(resume)
            player.prepare()
            player.playWhenReady = true
            repo.markWatched(item.id)
        } else {
            currentId = null
            player.stop()
            player.clearMediaItems()
        }
    }

    // v0.13: files Media3 can't decode (MPEG-2, VC-1, DTS, AVI/WMV…) open in the full player instead.
    val fellBack = remember { mutableSetOf<Long>() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val id = currentId ?: return
                if (!app.scrollback.fullplayer.FullPlayer.isDecoderError(error.errorCode) || !fellBack.add(id)) return
                val item = byId[id] ?: return
                android.widget.Toast.makeText(context, "Opening in the full player…", android.widget.Toast.LENGTH_SHORT).show()
                container.appScope.launch { app.scrollback.fullplayer.FullPlayer.playMedia(context, container, item) }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF) {
                    currentId?.let { id -> container.appScope.launch { repo.savePosition(id, 0) } }
                    scope.launch {
                        val next = pagerState.currentPage + 1
                        if (next < pagerState.pageCount) pagerState.animateScrollToPage(next, animationSpec = SbMotion.gentle())
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            saveCurrentPosition()
            player.release()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { saveCurrentPosition(); player.pause() }
                Lifecycle.Event.ON_RESUME -> if (currentId != null) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            progress = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(200)
        }
    }

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            key = { items[it].id },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            FeedPage(
                item = item,
                isCurrent = page == pagerState.settledPage,
                player = player,
                bottomReserve = bottomReserve,
                onSheet = { sheet = it },
            )
        }
        if (settledItem?.isVideo == true) {
            SeekBar(
                progress = { progress },
                onSeek = { f -> if (player.duration > 0) player.seekTo((player.duration * f).toLong()) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomReserve - 18.dp)
                    .padding(horizontal = SbSpace.l),
            )
        }
    }

    when (val s = sheet) {
        is FeedSheet.Playlists -> byId[s.id]?.let { PlaylistSheet(it) { sheet = null } }
        is FeedSheet.Notes -> byId[s.id]?.let { NotesSheet(it) { sheet = null } }
        is FeedSheet.Info -> byId[s.id]?.let { InfoSheet(it) { sheet = null } }
        null -> Unit
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedPage(
    item: MediaEntity,
    isCurrent: Boolean,
    player: ExoPlayer,
    bottomReserve: androidx.compose.ui.unit.Dp,
    onSheet: (FeedSheet) -> Unit,
) {
    val container = LocalContainer.current
    val repo = container.library
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var heartAt by remember { mutableStateOf<Offset?>(null) }
    val heartScale = remember { Animatable(0f) }
    var pausedFlash by remember { mutableStateOf(false) }
    val tags by repo.tagsFor(item.id).collectAsStateWithLifecycle(emptyList())
    val membership by repo.membership(item.id).collectAsStateWithLifecycle(emptyList())

    fun like(value: Boolean) {
        haptics.confirm()
        container.appScope.launch { repo.setLiked(item.id, value) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SbColors.Background)
            .pointerInput(item.id, isCurrent) {
                detectTapGestures(
                    onTap = {
                        if (item.isVideo && isCurrent) {
                            if (player.isPlaying) player.pause() else player.play()
                            pausedFlash = !player.playWhenReady
                        }
                    },
                    onDoubleTap = { pos ->
                        heartAt = pos
                        if (!item.liked) like(true) else haptics.tick()
                        scope.launch {
                            heartScale.snapTo(0.4f)
                            heartScale.animateTo(1.15f, SbMotion.spring())
                            delay(350)
                            heartScale.animateTo(0f, SbMotion.gentle())
                        }
                    },
                )
            },
    ) {
        MediaThumb(item, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, full = true)
        if (item.isVideo && isCurrent) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Double-tap heart burst
        heartAt?.let { p ->
            val density = LocalDensity.current
            val half = with(density) { 48.dp.toPx() }
            Icon(
                Icons.Filled.Favorite, null, tint = SbColors.Like,
                modifier = Modifier
                    .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
                    .size(96.dp)
                    .graphicsLayer { scaleX = heartScale.value; scaleY = heartScale.value; alpha = heartScale.value.coerceIn(0f, 1f) },
            )
        }

        AnimatedVisibility(
            visible = isCurrent && pausedFlash,
            enter = scaleIn(SbMotion.spring()) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(Modifier.size(72.dp).glass(SbRadius.Pill), contentAlignment = Alignment.Center) {
                Icon(if (pausedFlash) Icons.Filled.PlayArrow else Icons.Filled.Pause, "Paused", tint = SbColors.TextPrimary, modifier = Modifier.size(36.dp))
            }
        }

        // Right action rail
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = SbSpace.m, bottom = bottomReserve + SbSpace.l),
            verticalArrangement = Arrangement.spacedBy(SbSpace.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassIconButton(
                if (item.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (item.liked) "Unlike" else "Like",
                onClick = { like(!item.liked) },
                tint = if (item.liked) SbColors.Like else SbColors.TextPrimary,
                label = "Like",
            )
            GlassIconButton(
                if (membership.isNotEmpty()) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                "Save to playlist",
                onClick = { haptics.tick(); onSheet(FeedSheet.Playlists(item.id)) },
                label = "Save",
            )
            GlassIconButton(Icons.Outlined.EditNote, "Notes and tags", { haptics.tick(); onSheet(FeedSheet.Notes(item.id)) }, label = "Notes")
            GlassIconButton(Icons.Outlined.Share, "Share", {
                haptics.tick()
                scope.launch {
                    val uri = repo.shareUri(item)
                    if (uri != null) shareMedia(context, uri, item.mime, item.name)
                    else android.widget.Toast.makeText(context, "Download it first (Info → Download) to share the file", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, label = "Share")
            GlassIconButton(Icons.Outlined.Info, "Info", { haptics.tick(); onSheet(FeedSheet.Info(item.id)) }, label = "Info")
        }

        // Caption block
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.78f)
                .padding(start = SbSpace.l, bottom = bottomReserve + SbSpace.l),
        ) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(item.relativePath.ifBlank { null }, Fmt.relative(item.dateModified)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = SbColors.TextSecondary,
                maxLines = 1,
            )
            if (item.note.isNotBlank()) {
                Spacer(Modifier.height(SbSpace.xs))
                Text(item.note, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(SbSpace.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.take(6).forEach { t ->
                        Text(
                            "#${t.name}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SbColors.TextPrimary,
                            modifier = Modifier.clip(SbRadius.Pill).glass(SbRadius.Pill, elevation = 0.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Thin, draggable progress line (TikTok-like). Reads progress lazily to avoid recomposing the pager. */
@Composable
private fun SeekBar(progress: () -> Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / widthPx).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / widthPx).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / widthPx).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (dragging != null) 4.dp else 2.dp
        Box(Modifier.fillMaxWidth().height(h).clip(SbRadius.Pill).background(SbColors.GlassFillStrong))
        Box(
            Modifier
                .fillMaxWidth()
                .height(h)
                .graphicsLayer {
                    val f = dragging ?: progress()
                    scaleX = f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }
                .clip(SbRadius.Pill)
                .background(SbColors.TextPrimary),
        )
    }
}
