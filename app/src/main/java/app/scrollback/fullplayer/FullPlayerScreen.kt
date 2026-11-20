// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.fullplayer

import android.content.ContentValues
import android.graphics.Bitmap
import android.media.AudioManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.SbSwitch
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.components.glass
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Sheet { AUDIO, SUBS, SPEED, MORE, PLAYLIST, ASPECT }

@Composable
fun UnavailableScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("The full player isn't available on this device", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("Its playback engine ships for ARM phones only.", color = SbColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Back", color = Color.White) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FullPlayerScreen(activity: FullPlayerActivity, engine: VlcEngine) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var touchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var aspect by remember { mutableStateOf(engine.prefs.aspect) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var indicator by remember { mutableStateOf<Pair<ImageVector?, String>?>(null) }
    var indicatorAt by remember { mutableLongStateOf(0L) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var layout by remember { mutableStateOf(engine.layout) }
    var screen by remember { mutableStateOf(0 to 0) }
    var abTick by remember { mutableIntStateOf(0) }

    fun poke() { touchedAt = System.currentTimeMillis() }
    fun flash(icon: ImageVector?, text: String) { indicator = icon to text; indicatorAt = System.currentTimeMillis() }

    activity.onLayoutChanged = { l -> layout = l; activity.updatePip(); activity.applyRotation(isLandscape(l)) }
    LaunchedEffect(engine.layout) { engine.layout?.let { layout = it; activity.applyRotation(isLandscape(it)) } }
    LaunchedEffect(engine.playing) { activity.updatePip() }
    LaunchedEffect(engine.index) { zoom = 1f; pan = Offset.Zero }
    LaunchedEffect(engine.resumedAt, engine.index) {
        if (engine.resumedAt > 0) flash(null, "Resumed at ${PlayerMath.formatTime(engine.resumedAt)}")
    }
    // Auto-hide controls while playing.
    LaunchedEffect(controls, touchedAt, engine.playing, sheet) {
        if (controls && engine.playing && sheet == null) { delay(3500); controls = false }
    }
    LaunchedEffect(indicatorAt) { if (indicator != null) { delay(900); indicator = null } }

    BackHandler(enabled = locked) { flash(Icons.Outlined.Lock, "Screen locked. Tap the lock to unlock") }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Video surface (sized by PlayerMath; pinch zoom/pan resize and move the frame) ----
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = constraints.maxWidth; val hPx = constraints.maxHeight
            LaunchedEffect(wPx, hPx) { screen = wPx to hPx; engine.setWindowSize(wPx, hPx) }
            val l = layout
            val disp = if (l != null) PlayerMath.displaySize(aspect, l.visW, l.visH, l.sarNum, l.sarDen, wPx, hPx) else null
            val surf = if (l != null && disp != null) PlayerMath.surfaceSize(disp, l.w, l.h, l.visW, l.visH) else null
            AndroidView(
                factory = { c ->
                    FrameLayout(c).apply {
                        clipChildren = true
                        val frame = FrameLayout(c).apply { clipChildren = true }
                        val video = SurfaceView(c)
                        // Subtitles get their own transparent surface above the video (libVLC can't blend into HW-decoded frames).
                        val subs = SurfaceView(c).apply { setZOrderMediaOverlay(true); holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT) }
                        frame.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        frame.addView(subs, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                        activity.videoView = video; activity.subsView = subs
                        activity.attachViews()
                    }
                },
                update = { root ->
                    val frame = root.getChildAt(0) as FrameLayout
                    val video = frame.getChildAt(0); val subs = frame.getChildAt(1)
                    if (disp != null && surf != null) {
                        frame.layoutParams = FrameLayout.LayoutParams((disp.w * zoom).roundToInt(), (disp.h * zoom).roundToInt(), Gravity.CENTER)
                        val sw = (surf.first * zoom).roundToInt(); val sh = (surf.second * zoom).roundToInt()
                        video.layoutParams = FrameLayout.LayoutParams(sw, sh, Gravity.TOP or Gravity.START)
                        subs.layoutParams = FrameLayout.LayoutParams(sw, sh, Gravity.TOP or Gravity.START)
                        frame.translationX = pan.x; frame.translationY = pan.y
                    } else {
                        frame.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---- Gestures ----
        if (!activity.inPip) Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    val edge = 40.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val h = size.height.toFloat(); val w = size.width.toFloat()
                        val fromEdge = down.position.y < edge || down.position.y > h - edge
                        var g = PlayerMath.Gesture.NONE
                        var pinching = false
                        var total = Offset.Zero
                        var startLevel = 0f
                        val startTime = engine.timeMs
                        do {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed >= 2) {
                                pinching = true
                                val z = ev.calculateZoom(); val p = ev.calculatePan()
                                zoom = PlayerMath.clampZoom(zoom * z)
                                pan = if (zoom <= 1.01f) Offset.Zero else Offset(
                                    (pan.x + p.x).coerceIn(-(zoom - 1) * w / 2, (zoom - 1) * w / 2),
                                    (pan.y + p.y).coerceIn(-(zoom - 1) * h / 2, (zoom - 1) * h / 2),
                                )
                                flash(null, if (zoom <= 1.01f) aspect.label else "Zoom %.1f×".format(zoom))
                                ev.changes.forEach { it.consume() }
                            } else if (!pinching && !fromEdge) {
                                val c = ev.changes.firstOrNull() ?: break
                                total += c.positionChange()
                                if (g == PlayerMath.Gesture.NONE) {
                                    g = PlayerMath.classify(total.x, total.y, down.position.x, w, slop)
                                    startLevel = when (g) {
                                        PlayerMath.Gesture.BRIGHTNESS -> currentBrightness(activity)
                                        PlayerMath.Gesture.VOLUME -> audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                                        else -> 0f
                                    }
                                }
                                when (g) {
                                    PlayerMath.Gesture.SEEK -> {
                                        val t = PlayerMath.seekTarget(startTime, total.x, w, engine.lengthMs)
                                        seekPreview = t
                                        flash(null, "${PlayerMath.formatTime(t - startTime, signed = true)}   ${PlayerMath.formatTime(t)}")
                                    }
                                    PlayerMath.Gesture.BRIGHTNESS -> {
                                        val lv = PlayerMath.levelAfterDrag(startLevel, total.y, h)
                                        setBrightness(activity, lv)
                                        flash(Icons.Outlined.BrightnessMedium, "${(lv * 100).roundToInt()}%")
                                    }
                                    PlayerMath.Gesture.VOLUME -> {
                                        val lv = PlayerMath.levelAfterDrag(startLevel, total.y, h)
                                        val step = PlayerMath.volumeStep(lv, maxVol)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
                                        flash(Icons.AutoMirrored.Outlined.VolumeUp, "$step / $maxVol")
                                    }
                                    PlayerMath.Gesture.NONE -> Unit
                                }
                                if (g != PlayerMath.Gesture.NONE) c.consume()
                            }
                        } while (ev.changes.any { it.pressed })
                        seekPreview?.let { engine.seekTo(it) }
                        seekPreview = null
                    }
                }
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { poke(); controls = !controls },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            val t = PlayerMath.doubleTapSeek(o.x, size.width.toFloat(), engine.timeMs, engine.lengthMs)
                            if (t == null) engine.toggle() else {
                                flash(null, if (t >= engine.timeMs) "+10 s" else "−10 s"); engine.seekTo(t)
                            }
                        },
                    )
                },
        )

        if (engine.buffering && engine.error == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        indicator?.let { (icon, text) ->
            Row(
                Modifier.align(Alignment.Center).glass(SbRadius.Pill).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
                Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }

        engine.error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(32.dp).glass(SbRadius.Card).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(top = 12.dp)) {
                    if (engine.hasNext) TextButton(onClick = { engine.next() }) { Text("Next video", color = Color.White) }
                    TextButton(onClick = { activity.finish() }) { Text("Close", color = SbColors.TextSecondary) }
                }
            }
        }

        if (activity.inPip) return@Box

        // ---- Lock mode: just the unlock button ----
        if (locked) {
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                GlassIconButton(Icons.Outlined.Lock, "Unlock", onClick = { locked = false; controls = true; poke() })
            }
            return@Box
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = { activity.finish() }, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(engine.current?.name ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                if (engine.items.size > 1) "${engine.index + 1} / ${engine.items.size}" else null,
                                engine.codecInfo.ifBlank { null },
                                if (engine.hwDecoding) "HW" else "SW",
                            ).joinToString(" · ")
                            Text(sub, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        GlassIconButton(Icons.Outlined.Subtitles, "Subtitles", onClick = { sheet = Sheet.SUBS }, size = 40.dp)
                        Spacer(Modifier.width(8.dp))
                        GlassIconButton(Icons.Outlined.Audiotrack, "Audio", onClick = { sheet = Sheet.AUDIO }, size = 40.dp)
                        Spacer(Modifier.width(8.dp))
                        if (engine.items.size > 1) {
                            GlassIconButton(Icons.AutoMirrored.Outlined.PlaylistPlay, "Playlist", onClick = { sheet = Sheet.PLAYLIST }, size = 40.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        GlassIconButton(Icons.Outlined.MoreVert, "More", onClick = { sheet = Sheet.MORE }, size = 40.dp)
                    }
                }

                // Center transport
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    GlassIconButton(Icons.Rounded.SkipPrevious, "Previous", onClick = { poke(); engine.prev() }, size = 52.dp, tint = if (engine.hasPrev || engine.timeMs > 0) Color.White else SbColors.TextTertiary)
                    GlassIconButton(
                        when { engine.ended -> Icons.Rounded.Replay; engine.playing -> Icons.Rounded.Pause; else -> Icons.Rounded.PlayArrow },
                        if (engine.playing) "Pause" else "Play", onClick = { poke(); engine.toggle() }, size = 72.dp,
                    )
                    GlassIconButton(Icons.Rounded.SkipNext, "Next", onClick = { poke(); engine.next() }, size = 52.dp, tint = if (engine.hasNext) Color.White else SbColors.TextTertiary)
                }

                // Bottom: seek bar + tools
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))) {
                    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val len = engine.lengthMs
                        val pos = scrubbing?.let { (it * len).toLong() } ?: seekPreview ?: engine.timeMs
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(PlayerMath.formatTime(pos), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f,
                                onValueChange = { poke(); scrubbing = it },
                                onValueChangeFinished = { scrubbing?.let { engine.seekTo((it * len).toLong()) }; scrubbing = null },
                                enabled = len > 0,
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            )
                            Text(if (len > 0) PlayerMath.formatTime(len) else "--:--", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolPill(Icons.Outlined.Lock, "Lock") { locked = true; poke(); flash(Icons.Outlined.Lock, "Locked") }
                            ToolPill(Icons.Outlined.AspectRatio, aspect.label) { sheet = Sheet.ASPECT }
                            ToolPill(Icons.Outlined.Speed, formatRate(engine.rate)) { sheet = Sheet.SPEED }
                            @Suppress("UNUSED_VARIABLE") val abv = engine.abVersion + abTick
                            val ab = engine.ab
                            ToolPill(null, when { ab.active -> "A-B on"; ab.a != null -> "Set B"; else -> "A-B" }, selected = ab.a != null) {
                                poke(); engine.tapAb(); abTick++
                                flash(null, when { engine.ab.active -> "Repeating ${PlayerMath.formatTime(engine.ab.a!!)}–${PlayerMath.formatTime(engine.ab.b!!)}"; engine.ab.a != null -> "A set at ${PlayerMath.formatTime(engine.ab.a!!)}"; else -> "A-B repeat off" })
                            }
                            ToolPill(Icons.Outlined.ScreenRotation, when (activity.rotation) { FullPlayerActivity.Rotation.AUTO -> "Auto"; FullPlayerActivity.Rotation.LANDSCAPE -> "Landscape"; FullPlayerActivity.Rotation.PORTRAIT -> "Portrait" }) {
                                poke(); activity.cycleRotation(layout?.let(::isLandscape) ?: true)
                            }
                            ToolPill(Icons.Outlined.PictureInPictureAlt, "PiP") { activity.enterPip() }
                            ToolPill(Icons.Outlined.CameraAlt, "Frame") {
                                poke()
                                scope.launch {
                                    val bmp = activity.videoView?.let { copyFrame(it) }
                                    val ok = bmp != null && saveFrame(ctx, bmp, engine.current?.name ?: "frame", engine.timeMs)
                                    Toast.makeText(ctx, if (ok) "Frame saved to Pictures/Scrollback" else "Couldn't capture this frame", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.AUDIO -> AudioSheet(engine) { sheet = null }
        Sheet.SUBS -> SubtitleSheet(engine) { sheet = null }
        Sheet.SPEED -> GlassSheet({ sheet = null }) { close ->
            SheetTitle("Speed")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerMath.SPEEDS.forEach { s -> ChoicePill(formatRate(s), engine.rate == s) { engine.setSpeed(s); close() } }
            }
            Spacer(Modifier.height(SbSpace.xl))
        }
        Sheet.ASPECT -> GlassSheet({ sheet = null }) { close ->
            SheetTitle("Aspect")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectMode.entries.forEach { m -> ChoicePill(m.label, aspect == m) { aspect = m; engine.prefs.aspect = m; zoom = 1f; pan = Offset.Zero; close() } }
            }
            Text("Pinch to zoom; double-tap the sides to skip 10 s.", color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = SbSpace.l, bottom = SbSpace.xl))
        }
        Sheet.PLAYLIST -> GlassSheet({ sheet = null }) { close ->
            SheetTitle("Up next")
            val state = rememberLazyListState(initialFirstVisibleItemIndex = engine.index)
            LazyColumn(state = state, modifier = Modifier.height(360.dp)) {
                itemsIndexed(engine.items) { i, it ->
                    Row(Modifier.fillMaxWidth().clickable { engine.playIndex(i); close() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = SbColors.TextTertiary, modifier = Modifier.width(36.dp))
                        Text(it.name, color = if (i == engine.index) Color.White else SbColors.TextSecondary, fontWeight = if (i == engine.index) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Sheet.MORE -> GlassSheet({ sheet = null }) { _ ->
            SheetTitle("Player")
            var hw by remember { mutableStateOf(engine.hwDecoding) }
            var bg by remember { mutableStateOf(engine.prefs.backgroundAudio) }
            var pip by remember { mutableStateOf(engine.prefs.autoPip) }
            var subs by remember { mutableStateOf(engine.prefs.autoSubtitles) }
            SettingsRow("Hardware decoding", subtitle = "Faster and saves battery. Turn off if a video shows artefacts or a green screen.", trailing = {
                SbSwitch(hw) { hw = it; engine.setHardwareDecoding(it) }
            })
            SettingsRow("Background audio", subtitle = "Keep playing the sound when you leave the player or lock the phone.", trailing = {
                SbSwitch(bg) { bg = it; engine.prefs.backgroundAudio = it }
            })
            SettingsRow("Show subtitles automatically", subtitle = "Turn on the first subtitle track when a video has one.", trailing = {
                SbSwitch(subs) { subs = it; engine.prefs.autoSubtitles = it }
            })
            SettingsRow("Picture-in-picture on Home", subtitle = "Shrink to a floating window when you go Home while a video plays.", trailing = {
                SbSwitch(pip) { pip = it; engine.prefs.autoPip = it; activity.updatePip() }
            })
            Spacer(Modifier.height(SbSpace.xl))
        }
        null -> Unit
    }
}

@Composable
private fun AudioSheet(engine: VlcEngine, onDismiss: () -> Unit) = GlassSheet(onDismiss) { _ ->
    SheetTitle("Audio")
    val v = engine.tracksVersion
    var selected by remember(v) { mutableIntStateOf(engine.audioTrack()) }
    val tracks = remember(v) { engine.audioTracks() }
    if (tracks.isEmpty()) Text("No audio tracks", color = SbColors.TextSecondary)
    tracks.forEach { (id, name) -> TrackRow(name, id == selected) { engine.selectAudio(id); selected = id } }
    DelayRow("Audio delay", engine.audioDelayMs, step = 50) { engine.setAudioDelay(it) }
    Spacer(Modifier.height(SbSpace.xl))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleSheet(engine: VlcEngine, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "subtitle.srt"
            val ok = engine.addSubtitle(uri, name)
            Toast.makeText(ctx, if (ok) "Subtitle added" else "Couldn't load that subtitle file", Toast.LENGTH_SHORT).show()
        }
    }
    GlassSheet(onDismiss) { close ->
        SheetTitle("Subtitles")
        val v = engine.tracksVersion
        var selected by remember(v) { mutableIntStateOf(engine.subTrack()) }
        val tracks = remember(v) { engine.subTracks() }
        if (tracks.none { it.first == -1 }) TrackRow("Off", selected == -1) { engine.selectSub(-1); selected = -1 }
        tracks.forEach { (id, name) -> TrackRow(if (id == -1) "Off" else name, id == selected) { engine.selectSub(id); selected = id } }
        SettingsRow("Add subtitle file…", subtitle = "SRT, ASS/SSA, VTT and more", onClick = { picker.launch(arrayOf("*/*")); close() })
        DelayRow("Subtitle delay", engine.subDelayMs, step = 100) { engine.setSubDelay(it) }
        Text("Style", color = SbColors.TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = SbSpace.l, bottom = SbSpace.s))
        var scale by remember { mutableIntStateOf(engine.prefs.subtitleScale) }
        var color by remember { mutableIntStateOf(engine.prefs.subtitleColor) }
        var bg by remember { mutableStateOf(engine.prefs.subtitleBackground) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(75 to "Small", 100 to "Normal", 130 to "Large", 170 to "Huge").forEach { (s, l) -> ChoicePill(l, scale == s) { scale = s } }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0xFFFFFF to "White", 0xFFE14D to "Yellow", 0x7FE7FF to "Cyan", 0x9CFF8A to "Green").forEach { (c, l) -> ChoicePill(l, color == c) { color = c } }
            ChoicePill("Background", bg) { bg = !bg }
        }
        val changed = scale != engine.prefs.subtitleScale || color != engine.prefs.subtitleColor || bg != engine.prefs.subtitleBackground
        TextButton(
            onClick = {
                engine.prefs.subtitleScale = scale; engine.prefs.subtitleColor = color; engine.prefs.subtitleBackground = bg
                engine.rebuildForStyle(); close()
            },
            enabled = changed,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Apply style", color = if (changed) Color.White else SbColors.TextTertiary) }
        Text("ASS/SSA subtitles keep their own styling.", color = SbColors.TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = SbSpace.xl))
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (selected) Color.White else Color.Transparent, SbRadius.Pill))
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (selected) Color.White else SbColors.TextSecondary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun DelayRow(label: String, valueMs: Long, step: Long, onChange: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = SbSpace.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White)
            Text("%+d ms".format(valueMs), color = SbColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        ChoicePill("−$step", false) { onChange(valueMs - step) }
        Spacer(Modifier.width(8.dp))
        ChoicePill("+$step", false) { onChange(valueMs + step) }
        Spacer(Modifier.width(8.dp))
        ChoicePill("Reset", false) { onChange(0) }
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) SbColors.OnAccent else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(if (selected) SbColors.Accent else SbColors.GlassFillStrong, SbRadius.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun ToolPill(icon: ImageVector?, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.glass(SbRadius.Pill, elevation = 0.dp, fill = SbColors.GlassFill)
            .background(if (selected) SbColors.GlassFillStrong else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatRate(r: Float): String = (if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()) + "×"

private fun isLandscape(l: VideoLayout): Boolean {
    val sar = if (l.sarNum > 0 && l.sarDen > 0) l.sarNum.toDouble() / l.sarDen else 1.0
    return l.visW * sar >= l.visH
}

private fun currentBrightness(a: FullPlayerActivity): Float {
    val b = a.window.attributes.screenBrightness
    if (b >= 0f) return b
    val sys = runCatching { android.provider.Settings.System.getInt(a.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
    return (sys / 255f).coerceIn(0f, 1f)
}

private fun setBrightness(a: FullPlayerActivity, level: Float) {
    a.window.attributes = a.window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
}

/** Grabs the current video frame from the SurfaceView (PixelCopy works with hardware-decoded video too). */
private suspend fun copyFrame(v: SurfaceView): Bitmap? {
    if (v.width <= 0 || v.height <= 0 || !v.holder.surface.isValid) return null
    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        android.view.PixelCopy.request(v, bmp, { r -> cont.resumeWith(Result.success(if (r == android.view.PixelCopy.SUCCESS) bmp else null)) }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
}

private suspend fun saveFrame(ctx: android.content.Context, bmp: Bitmap, name: String, atMs: Long): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(60)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${base}_${PlayerMath.formatTime(atMs).replace(':', '-')}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Scrollback")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return@runCatching false
        true
    }.getOrDefault(false)
}
