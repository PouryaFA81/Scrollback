// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.watch

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.scrollback.remote.jellyfin.JellyfinVideoApi
import app.scrollback.remote.jellyfin.JfItem
import app.scrollback.remote.jellyfin.JfMediaSource
import app.scrollback.remote.jellyfin.JfStream
import app.scrollback.remote.jellyfin.PlayMethod
import app.scrollback.remote.jellyfin.StreamChoice
import app.scrollback.remote.jellyfin.StreamSelector
import app.scrollback.remote.jellyfin.TrackMapping
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.theme.SbColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays one Jellyfin movie or episode with Media3: the original file when the server says this
 * phone can play it, else Jellyfin's HLS transcode. Reports start/progress/stop so Jellyfin keeps
 * the resume point and played state.
 */
@OptIn(UnstableApi::class)
internal class JfPlayerController(
    private val api: JellyfinVideoApi,
    private val itemId: String,
    val player: ExoPlayer,
    private val background: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onEnded: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var item by mutableStateOf<JfItem?>(null); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var source by mutableStateOf<JfMediaSource?>(null); private set
    var choice by mutableStateOf<StreamChoice?>(null); private set
    var audioIndex by mutableStateOf<Int?>(null); private set
    /** -1 = off. */
    var subtitleIndex by mutableStateOf(-1); private set
    private var playSessionId: String? = null
    private var started = false
    private var fellBack = false
    private var applyDefaultsPending = false
    private var progressJob: Job? = null
    private var stopped = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                loading = false
                if (!started) { started = true; report { api.reportStart(it) } }
            }
            if (state == Player.STATE_BUFFERING) loading = true
            if (state == Player.STATE_ENDED) { finish(); onEnded() }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (started) report { api.reportProgress(it, if (isPlaying) "Unpause" else "Pause") }
        }
        override fun onTracksChanged(tracks: Tracks) {
            if (applyDefaultsPending && tracks.groups.isNotEmpty()) { applyDefaultsPending = false; applyClientTracks() }
        }
        override fun onPlayerError(e: PlaybackException) {
            val c = choice
            if (c != null && c.method != PlayMethod.Transcode && !fellBack) {
                // The phone couldn't decode the original: ask Jellyfin for a transcode instead.
                fellBack = true
                load(player.currentPosition.coerceAtLeast(0), allowDirect = false)
            } else {
                loading = false
                error = "Can't play this video (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})."
            }
        }
    }

    init { player.addListener(listener) }

    fun start(fromStart: Boolean) = scope.launch {
        try {
            val it = withContext(Dispatchers.IO) { api.item(itemId) }
            item = it
            load(if (fromStart || it.userData.played) 0 else it.positionMs, allowDirect = true)
        } catch (e: Exception) { loading = false; error = friendlyError(e) }
    }

    fun retry() { error = null; loading = true; if (item == null) start(false) else load(player.currentPosition.coerceAtLeast(0), allowDirect = !fellBack) }

    private fun load(startMs: Long, allowDirect: Boolean) {
        loading = true; error = null
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    api.playbackInfo(itemId, startMs, audioIndex, if (source == null) null else subtitleIndex, allowDirect = allowDirect)
                }
                val src = info.sources.firstOrNull() ?: error(info.errorCode?.let { "Jellyfin: $it" } ?: "No playable version of this item")
                val c = StreamSelector.select(api.base, itemId, src, info.playSessionId, api.deviceId) ?: error("Jellyfin can't stream this item to this phone")
                if (source == null) { // first load: take Jellyfin's defaults (user's language preferences)
                    audioIndex = src.defaultAudioIndex ?: src.audio.firstOrNull()?.index
                    subtitleIndex = src.defaultSubtitleIndex ?: -1
                }
                playSessionId = info.playSessionId
                source = src; choice = c
                val subs = StreamSelector.externalSubs(api.base, itemId, src).map { s ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                        .setMimeType(s.mimeType).setLanguage(s.language).setLabel(s.label).setId(TrackMapping.subId(s.streamIndex))
                        .build()
                }
                val mi = MediaItem.Builder().setUri(c.url).setMimeType(c.mimeType).setMediaId(itemId).setSubtitleConfigurations(subs).build()
                applyDefaultsPending = true
                player.setMediaItem(mi, startMs)
                player.prepare()
                player.playWhenReady = true
                startProgressLoop()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loading = false; error = friendlyError(e)
            }
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (started && player.isPlaying) report { api.reportProgress(it) }
            }
        }
    }

    private fun current(positionMs: Long = player.currentPosition): JellyfinVideoApi.Report? {
        val c = choice ?: return null
        return JellyfinVideoApi.Report(itemId, c.mediaSourceId, playSessionId, positionMs.coerceAtLeast(0), !player.isPlaying, c.method, audioIndex, subtitleIndex)
    }

    private fun report(block: (JellyfinVideoApi.Report) -> Unit) {
        val r = current() ?: return
        background.launch(Dispatchers.IO) { runCatching { block(r) } }
    }

    // ---- Tracks ----

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type }

    private fun override(group: Tracks.Group) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** Applies the chosen audio/subtitle on the client side (original file and side-loaded subs). */
    private fun applyClientTracks() {
        val src = source ?: return
        val c = choice ?: return
        if (c.method != PlayMethod.Transcode) audioIndex?.let { idx ->
            TrackMapping.audioOrdinal(src, idx)?.let { ord -> groups(C.TRACK_TYPE_AUDIO).getOrNull(ord)?.let(::override) }
        }
        val sub = subtitleIndex
        if (sub < 0) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return
        }
        val text = groups(C.TRACK_TYPE_TEXT)
        val id = TrackMapping.subId(sub)
        val sideLoaded = text.firstOrNull { g -> g.getTrackFormat(0).id?.contains(id) == true }
        val target = sideLoaded ?: if (c.method != PlayMethod.Transcode) {
            TrackMapping.embeddedSubOrdinal(src, sub)?.let { ord -> text.filterNot { it.getTrackFormat(0).id?.contains(TrackMapping.SUB_ID_PREFIX) == true }.getOrNull(ord) }
        } else {
            // HLS transcodes carry text subtitles as their own renditions; burned-in ones need nothing.
            val lang = src.subtitles.firstOrNull { it.index == sub }?.language
            text.firstOrNull { lang != null && it.getTrackFormat(0).language?.startsWith(lang.take(2)) == true }
        }
        if (target != null) override(target)
        else player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    }

    fun selectAudio(stream: JfStream) {
        val src = source ?: return
        val c = choice ?: return
        audioIndex = stream.index
        val ord = TrackMapping.audioOrdinal(src, stream.index)
        val group = ord?.let { groups(C.TRACK_TYPE_AUDIO).getOrNull(it) }
        if (c.method != PlayMethod.Transcode && group != null) override(group)
        else load(player.currentPosition, allowDirect = !fellBack) // the server switches the audio track
        report { api.reportProgress(it, "AudioTrackChange") }
    }

    fun selectSubtitle(stream: JfStream?) {
        val src = source ?: return
        val c = choice ?: return
        val before = subtitleIndex
        subtitleIndex = stream?.index ?: -1
        val serverNeeded = (stream != null && TrackMapping.needsServer(src, stream.index, c.method)) ||
            // leaving a burned-in subtitle also needs a new stream
            (before >= 0 && TrackMapping.needsServer(src, before, c.method) && src.subtitles.firstOrNull { it.index == before }?.deliveryMethod.equals("Encode", true))
        if (serverNeeded) load(player.currentPosition, allowDirect = !fellBack) else applyClientTracks()
        report { api.reportProgress(it, "SubtitleTrackChange") }
    }

    private fun finish() {
        if (stopped) return
        stopped = true
        val r = current(if (player.playbackState == Player.STATE_ENDED) player.duration.coerceAtLeast(player.currentPosition) else player.currentPosition)
        if (r != null && started) background.launch(Dispatchers.IO) { runCatching { api.reportStopped(r) }; withContext(Dispatchers.Main) { onChanged() } }
    }

    fun release() {
        finish()
        progressJob?.cancel()
        player.removeListener(listener)
        scope.cancel()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun WatchPlayerScreen(itemId: String, fromStart: Boolean, onBack: () -> Unit) {
    val container = LocalContainer.current
    val watch = container.watch
    val account by watch.active.collectAsStateWithLifecycle()
    val a = account
    if (a == null) { LaunchedEffect(Unit) { onBack() }; return }
    val api = remember(a) { watch.api(a) }
    val player = remember { container.playback.newPlayer() }
    val controller = remember { JfPlayerController(api, itemId, player, container.appScope, onChanged = { watch.changed() }, onEnded = onBack) }
    var controlsVisible by remember { mutableStateOf(true) }
    var pick by remember { mutableStateOf<String?>(null) } // "audio" | "subs"
    val activity = androidx.activity.compose.LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) { controller.start(fromStart) }
    DisposableEffect(Unit) {
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            controller.release()
            player.release()
            insets?.show(WindowInsetsCompat.Type.systemBars())
            if (prev != null) activity?.requestedOrientation = prev
        }
    }
    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == android.view.View.VISIBLE })
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (controller.loading && controller.error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }

        AnimatedVisibility(controlsVisible || controller.error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val it = controller.item
                    Text(it?.name ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(it?.seriesName, it?.episodeLabel, controller.choice?.method?.let(::methodLabel)).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                val src = controller.source
                if (src != null && src.audio.size > 1) GlassIconButton(Icons.Outlined.Audiotrack, "Audio track", onClick = { pick = "audio" }, size = 40.dp)
                if (src != null && src.subtitles.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(Icons.Outlined.Subtitles, "Subtitles", onClick = { pick = "subs" }, size = 40.dp)
                }
            }
        }

        controller.error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    TextButton(onClick = { controller.retry() }) { Text("Try again", color = Color.White) }
                    TextButton(onClick = onBack) { Text("Back", color = Color.White.copy(alpha = 0.7f)) }
                }
            }
        }
    }

    val src = controller.source
    if (pick != null && src != null) {
        val audio = pick == "audio"
        val options: List<Pair<JfStream?, String>> =
            if (audio) src.audio.map { it to it.label }
            else listOf<Pair<JfStream?, String>>(null to "Off") + src.subtitles.map { it to (it.label + if (it.isExternal) " (external)" else "") }
        val selectedIndex = if (audio) controller.audioIndex else controller.subtitleIndex
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(if (audio) "Audio" else "Subtitles") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (s, label) ->
                        val on = (s?.index ?: -1) == selectedIndex
                        Row(Modifier.fillMaxWidth().clickable {
                            pick = null
                            if (audio) s?.let(controller::selectAudio) else controller.selectSubtitle(s)
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = on, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = SbColors.Accent))
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = SbColors.TextPrimary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = null }) { Text("Close", color = SbColors.TextSecondary) } },
        )
    }
}

private fun methodLabel(m: PlayMethod) = when (m) {
    PlayMethod.DirectPlay -> "Direct play"
    PlayMethod.DirectStream -> "Direct stream"
    PlayMethod.Transcode -> "Transcoding"
}
