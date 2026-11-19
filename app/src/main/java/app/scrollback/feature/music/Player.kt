package app.scrollback.feature.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.scrollback.data.db.MediaEntity
import app.scrollback.feature.feed.PlaylistSheet
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.MediaThumb
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.components.chromeGlass
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
private fun currentSong(): MediaEntity? {
    val c = LocalContainer.current
    val state by c.music.state.collectAsStateWithLifecycle()
    val id = state.currentId
    val item by remember(id) { if (id == null) flowOf(null) else c.library.item(id) }.collectAsStateWithLifecycle(null)
    return item
}

@Composable
private fun rememberPosition(): Long {
    val c = LocalContainer.current
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = c.music.position(); delay(500) } }
    return pos
}

/** Floating glass mini player (same fixed chrome token as the bottom nav). */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier, showWhenEmpty: Boolean = false) {
    val c = LocalContainer.current
    val state by c.music.state.collectAsStateWithLifecycle()
    val song = currentSong()
    AnimatedVisibility(song != null || showWhenEmpty, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val s = song ?: return@AnimatedVisibility
        val pos = rememberPosition()
        Column(
            Modifier
                .padding(horizontal = SbSpace.l)
                .fillMaxWidth()
                .chromeGlass(SbRadius.Card)
                .clickable(onClick = onOpen),
        ) {
            Row(Modifier.padding(SbSpace.s), verticalAlignment = Alignment.CenterVertically) {
                MediaThumb(s, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(horizontal = SbSpace.m)) {
                    Text(s.songTitle, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.error ?: s.songSubtitle, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { c.music.toggle() }) {
                    Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.playing) "Pause" else "Play", tint = SbColors.TextPrimary)
                }
                IconButton(onClick = { c.music.next() }) { Icon(Icons.Outlined.SkipNext, "Next", tint = SbColors.TextPrimary) }
            }
            val dur = state.durationMs.takeIf { it > 0 } ?: s.durationMs
            if (dur > 0) Box(Modifier.fillMaxWidth().padding(horizontal = SbSpace.m).padding(bottom = 6.dp).height(2.dp).clip(SbRadius.Pill).background(SbColors.GlassFillStrong)) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(2.dp).background(SbColors.TextPrimary))
            }
        }
    }
}

/** Full-screen player with artwork, seek bar, controls, like, queue and add-to-playlist. */
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val haptics = rememberHaptics()
    val state by c.music.state.collectAsStateWithLifecycle()
    val song = currentSong()
    val pos = rememberPosition()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var queue by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(SbColors.Background)) {
        // Soft blurred artwork wash behind everything (monochrome-friendly, low opacity).
        song?.let { MediaThumb(it, Modifier.fillMaxSize().background(SbColors.Background), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(SbColors.Background.copy(alpha = 0.86f)))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = SbSpace.xl)) {
            Row(Modifier.fillMaxWidth().padding(vertical = SbSpace.s), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.KeyboardArrowDown, "Close player", tint = SbColors.TextPrimary) }
                Text("Now playing", style = MaterialTheme.typography.labelLarge, color = SbColors.TextSecondary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { queue = true }) { Icon(Icons.AutoMirrored.Outlined.QueueMusic, "Queue", tint = SbColors.TextPrimary) }
            }
            Spacer(Modifier.height(SbSpace.xl))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).chromeGlass(SbRadius.Large), contentAlignment = Alignment.Center) {
                if (song != null) MediaThumb(song, Modifier.fillMaxSize().clip(SbRadius.Large))
            }
            Spacer(Modifier.height(SbSpace.xxl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song?.songTitle ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall, color = SbColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(state.error ?: song?.songSubtitle.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = SbColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (song != null) {
                    IconButton(onClick = { playlist = true }) { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, "Add to playlist", tint = SbColors.TextPrimary) }
                    IconButton(onClick = { haptics.confirm(); c.appScope.launch { c.library.setLiked(song.id, !song.liked) } }) {
                        Icon(if (song.liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, "Like", tint = if (song.liked) SbColors.Like else SbColors.TextPrimary)
                    }
                }
            }
            Spacer(Modifier.height(SbSpace.l))
            val dur = (state.durationMs.takeIf { it > 0 } ?: song?.durationMs ?: 0L).coerceAtLeast(1L)
            Slider(
                value = if (dragging) dragValue else (pos.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { c.music.seekTo((dragValue * dur).toLong()); dragging = false },
                colors = SliderDefaults.colors(thumbColor = SbColors.TextPrimary, activeTrackColor = SbColors.TextPrimary, inactiveTrackColor = SbColors.GlassFillStrong),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(Fmt.duration(if (dragging) (dragValue * dur).toLong() else pos), style = MaterialTheme.typography.labelSmall, color = SbColors.TextTertiary)
                Spacer(Modifier.weight(1f))
                Text(Fmt.duration(dur), style = MaterialTheme.typography.labelSmall, color = SbColors.TextTertiary)
            }
            Spacer(Modifier.height(SbSpace.l))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptics.tick(); c.music.setShuffle(!state.shuffle) }) {
                    Icon(Icons.Outlined.Shuffle, "Shuffle", tint = if (state.shuffle) SbColors.TextPrimary else SbColors.TextTertiary)
                }
                IconButton(onClick = { c.music.previous() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.SkipPrevious, "Previous", tint = SbColors.TextPrimary, modifier = Modifier.size(36.dp)) }
                Box(
                    Modifier.size(76.dp).clip(SbRadius.Pill).background(SbColors.Accent).clickable { haptics.tick(); c.music.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (state.playing) "Pause" else "Play", tint = SbColors.OnAccent, modifier = Modifier.size(40.dp)) }
                IconButton(onClick = { c.music.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.SkipNext, "Next", tint = SbColors.TextPrimary, modifier = Modifier.size(36.dp)) }
                IconButton(onClick = { haptics.tick(); c.music.cycleRepeat() }) {
                    Icon(
                        if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, "Repeat",
                        tint = if (state.repeat == Player.REPEAT_MODE_OFF) SbColors.TextTertiary else SbColors.TextPrimary,
                    )
                }
            }
        }
    }
    if (queue) QueueSheet(onDismiss = { queue = false })
    if (playlist && song != null) PlaylistSheet(song, onDismiss = { playlist = false })
}

@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val state by c.music.state.collectAsStateWithLifecycle()
    val songs by remember(state.queue) { kotlinx.coroutines.flow.flow { emit(state.queue.mapNotNull { c.library.itemNow(it) }) } }.collectAsStateWithLifecycle(emptyList())
    GlassSheet(onDismiss) { _ ->
        SheetTitle("Queue · ${state.queue.size}")
        LazyColumn(Modifier.fillMaxWidth().height(460.dp), state = rememberLazyListState(initialFirstVisibleItemIndex = state.index.coerceAtLeast(0))) {
            itemsIndexed(songs, key = { i, m -> "$i-${m.id}" }) { i, m ->
                Row(
                    Modifier.fillMaxWidth().clip(SbRadius.Small).clickable { c.music.skipTo(i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaThumb(m, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = SbSpace.m)) {
                        Text(m.songTitle, color = if (i == state.index) SbColors.TextPrimary else SbColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.songSubtitle, color = SbColors.TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { c.music.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up", tint = SbColors.TextSecondary) }
                    IconButton(onClick = { c.music.move(i, i + 1) }, enabled = i < songs.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down", tint = SbColors.TextSecondary) }
                    IconButton(onClick = { c.music.remove(i) }) { Icon(Icons.Outlined.Close, "Remove", tint = SbColors.TextSecondary) }
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
