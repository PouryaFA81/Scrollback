package app.scrollback.fullplayer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.scrollback.AppContainer
import app.scrollback.remote.fs.RemotePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File

/** Reported by libVLC for the current video: buffer size, visible part and sample aspect ratio. */
data class VideoLayout(val w: Int, val h: Int, val visW: Int, val visH: Int, val sarNum: Int, val sarDen: Int)

/**
 * The full player's playback engine (libVLC). One per process so playback can continue in the
 * background service after the activity goes away. All calls on the main thread.
 */
class VlcEngine private constructor(private val app: Context, private val container: AppContainer) {
    companion object {
        @Volatile private var instance: VlcEngine? = null
        fun get(ctx: Context, container: AppContainer): VlcEngine =
            instance ?: synchronized(this) { instance ?: VlcEngine(ctx.applicationContext, container).also { instance = it } }
        fun peek(): VlcEngine? = instance

        /** Loading the native libraries fails on CPUs we don't ship (x86). */
        fun available(): Boolean = runCatching { System.loadLibrary("c++_shared"); System.loadLibrary("vlc"); System.loadLibrary("vlcjni"); true }.getOrDefault(false)
    }

    val prefs = PlayerPrefs(app)
    private val main = Handler(Looper.getMainLooper())
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var afd: AssetFileDescriptor? = null
    private var loopback: LoopbackStream? = null
    private var views: Pair<SurfaceView, SurfaceView>? = null
    private var layoutListener: IVLCVout.OnNewVideoLayoutListener? = null

    var items by mutableStateOf<List<PlayItem>>(emptyList()); private set
    var index by mutableIntStateOf(0); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var timeMs by mutableLongStateOf(0L); private set
    var lengthMs by mutableLongStateOf(0L); private set
    var ended by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var layout by mutableStateOf<VideoLayout?>(null); private set
    var rate by mutableStateOf(1f); private set
    var audioDelayMs by mutableLongStateOf(0L); private set
    var subDelayMs by mutableLongStateOf(0L); private set
    var hwDecoding by mutableStateOf(prefs.hardwareDecoding); private set
    /** Bumps whenever track lists change, so the UI re-reads them. */
    var tracksVersion by mutableIntStateOf(0); private set
    var codecInfo by mutableStateOf(""); private set
    val ab = AbRepeat()
    var abVersion by mutableIntStateOf(0); private set

    val current: PlayItem? get() = items.getOrNull(index)
    val hasNext get() = index < items.size - 1
    val hasPrev get() = index > 0

    var onStateChanged: (() -> Unit)? = null

    private fun options(): ArrayList<String> = arrayListOf(
        "--audio-time-stretch",
        "--sub-text-scale=${prefs.subtitleScale}",
        "--freetype-color=${prefs.subtitleColor}",
        "--freetype-outline-thickness=${if (prefs.subtitleBackground) 0 else 4}",
        "--freetype-background-opacity=${if (prefs.subtitleBackground) 160 else 0}",
        "--freetype-background-color=0",
        "--sub-autodetect-fuzzy=3",
    ).apply {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) add("-vv")
    }

    private fun ensure(): MediaPlayer {
        player?.let { return it }
        android.util.Log.d("SbPlayer", "new libVLC player")
        val lib = LibVLC(app, options()).also { libVlc = it }
        return MediaPlayer(lib).also { mp ->
            mp.setEventListener(::onEvent)
            player = mp
            views?.let { (v, s) -> attachTo(mp, v, s) }
        }
    }

    private fun onEvent(e: MediaPlayer.Event) {
        if (e.type != MediaPlayer.Event.TimeChanged && e.type != MediaPlayer.Event.PositionChanged) android.util.Log.d("SbPlayer", "raw event 0x${Integer.toHexString(e.type)} player=${player != null}")
        val mp = player ?: return
        if (e.type != MediaPlayer.Event.TimeChanged && e.type != MediaPlayer.Event.PositionChanged && e.type != MediaPlayer.Event.Buffering)
            android.util.Log.d("SbPlayer", "event 0x${Integer.toHexString(e.type)}")
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                playing = true; buffering = false; ended = false; error = null; onStateChanged?.invoke()
                if (autoSubPending) {
                    autoSubPending = false
                    if (mp.spuTrack == -1) mp.spuTracks?.firstOrNull { it.id != -1 }?.let { mp.spuTrack = it.id; tracksVersion++ }
                }
            }
            MediaPlayer.Event.Paused -> { playing = false; onStateChanged?.invoke() }
            MediaPlayer.Event.Stopped -> { playing = false; onStateChanged?.invoke() }
            MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
            MediaPlayer.Event.TimeChanged -> {
                timeMs = e.timeChanged
                ab.check(timeMs)?.let { mp.setTime(it) }
            }
            MediaPlayer.Event.LengthChanged -> lengthMs = e.lengthChanged
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> { tracksVersion++; readCodec() }
            MediaPlayer.Event.EndReached -> {
                playing = false
                saveResume(0)
                if (hasNext) playIndex(index + 1) else { ended = true; onStateChanged?.invoke() }
            }
            MediaPlayer.Event.EncounteredError -> {
                playing = false; buffering = false
                error = "Scrollback can't play this file. It may be damaged or use an unsupported format."
                onStateChanged?.invoke()
            }
        }
    }

    private fun readCodec() {
        val v = runCatching { player?.currentVideoTrack }.getOrNull()
        codecInfo = v?.let { "${it.codec ?: "video"} ${it.width}×${it.height}" } ?: ""
    }

    // ---- Views ----

    fun attachViews(video: SurfaceView, subs: SurfaceView, onLayout: (VideoLayout) -> Unit) {
        layoutListener = IVLCVout.OnNewVideoLayoutListener { _, w, h, vw, vh, sn, sd ->
            val l = VideoLayout(w, h, vw, vh, sn, sd); layout = l; onLayout(l)
            android.util.Log.d("SbPlayer", "layout $l")
        }
        views = video to subs
        attachTo(ensure(), video, subs)
        deferredOpen?.invoke()
    }

    /** (player, view) currently attached: libVLC's AWindow throws if views are re-attached needlessly. */
    private var attached: Pair<MediaPlayer, SurfaceView>? = null

    private fun attachTo(mp: MediaPlayer, video: SurfaceView, subs: SurfaceView) {
        val vout = mp.vlcVout
        if (attached?.first === mp && attached?.second === video && vout.areViewsAttached()) {
            setWindowSize(video.width.takeIf { it > 0 } ?: video.resources.displayMetrics.widthPixels,
                video.height.takeIf { it > 0 } ?: video.resources.displayMetrics.heightPixels)
            return
        }
        android.util.Log.d("SbPlayer", "attach views (wasAttached=${vout.areViewsAttached()})")
        if (vout.areViewsAttached()) vout.detachViews()
        attached = mp to video
        vout.setVideoView(video)
        vout.setSubtitlesView(subs)
        vout.attachViews(layoutListener)
        setWindowSize(video.width.takeIf { it > 0 } ?: video.resources.displayMetrics.widthPixels,
            video.height.takeIf { it > 0 } ?: video.resources.displayMetrics.heightPixels)
    }

    fun setWindowSize(w: Int, h: Int) { player?.vlcVout?.setWindowSize(w, h) }

    /** Audio-only while in the background (no surface to draw on). */
    private var videoOff = false
    fun setVideoEnabled(on: Boolean) {
        if (on == !videoOff) return
        videoOff = !on
        runCatching { player?.setVideoTrackEnabled(on) }
    }

    fun detachViews() {
        android.util.Log.d("SbPlayer", "detach views")
        views = null
        attached = null
        player?.vlcVout?.let { if (it.areViewsAttached()) it.detachViews() }
    }

    // ---- Queue ----

    fun setQueue(list: List<PlayItem>, start: Int) {
        saveResume()
        index = -1
        items = list
        playIndex(start)
    }

    fun playIndex(i: Int, startOverride: Long? = null) {
        if (i !in items.indices) return
        if (i != index) saveResume()
        index = i
        ab.clear(); abVersion++
        val item = items[i]
        ended = false; error = null; timeMs = 0; lengthMs = 0; layout = null; buffering = true
        container.appScope.launch {
            val resume = startOverride ?: resumeFor(item)
            val target = resolve(item)
            withContext(Dispatchers.Main) {
                if (items.getOrNull(index) != item) return@withContext
                open(target, resume, exact = startOverride != null)
                if (item.mediaId >= 0) container.appScope.launch { container.library.markWatched(item.mediaId) }
            }
        }
    }

    fun next() { if (hasNext) playIndex(index + 1) }
    fun prev() { if (timeMs > 5_000 || !hasPrev) seekTo(0) else playIndex(index - 1) }

    private suspend fun resumeFor(item: PlayItem): Long {
        val pos = if (item.mediaId >= 0) container.db.media().byId(item.mediaId)?.lastPositionMs ?: 0 else prefs.resumeFor(item.uri)
        return pos
    }

    /** The URI libVLC should open: library items go through their source (offline copy first); remote bytes via the loopback. */
    private suspend fun resolve(item: PlayItem): String {
        var uri = item.uri
        if (item.mediaId >= 0) container.db.media().byId(item.mediaId)?.let { m -> uri = container.library.playback(m).uri.toString() }
        val needsLoopback = uri.startsWith(RemotePaths.SCHEME + "://") || container.auth.isRemote(uri)
        if (!needsLoopback) return uri
        val lb = loopback ?: LoopbackStream(container.playback.dataSourceFactory).also { loopback = it }
        return withContext(Dispatchers.IO) { lb.register(uri) }
    }

    private var pendingResume = 0L
    private var autoSubPending = false

    /** Opening before the surfaces exist makes libVLC skip MediaCodec, so wait for the views. */
    private var deferredOpen: (() -> Unit)? = null

    private fun open(uri: String, resumeMs: Long, exact: Boolean = false) {
        if (views == null && !videoOff) { deferredOpen = { open(uri, resumeMs, exact) }; return }
        deferredOpen = null
        val mp = ensure()
        android.util.Log.d("SbPlayer", "open $uri resume=$resumeMs")
        val lib = libVlc ?: return
        afd?.let { runCatching { it.close() } }; afd = null
        val u = Uri.parse(uri)
        val media = try {
            when (u.scheme) {
                "content" -> {
                    val fd = app.contentResolver.openAssetFileDescriptor(u, "r") ?: error("no fd")
                    afd = fd
                    Media(lib, fd)
                }
                "file" -> Media(lib, u.path ?: uri)
                else -> Media(lib, u)
            }
        } catch (e: Exception) {
            buffering = false
            error = "Couldn't open this file. It may have been moved or deleted."
            return
        }
        media.setHWDecoderEnabled(hwDecoding, false)
        pendingResume = if (exact && resumeMs > 0 || PlayerMath.shouldResume(resumeMs, 0)) resumeMs else 0
        if (pendingResume > 0) media.addOption(":start-time=${pendingResume / 1000.0}")
        mp.media = media
        media.release()
        autoSubPending = prefs.autoSubtitles
        mp.rate = rate
        mp.play()
        // Delays are per media in libVLC; re-apply the user's choice.
        mp.audioDelay = audioDelayMs * 1000
        mp.spuDelay = subDelayMs * 1000
    }

    /** Last resume point that was applied when the file opened (for the "Resumed at" toast). */
    val resumedAt: Long get() = pendingResume

    // ---- Controls ----

    fun toggle() { val mp = player ?: return; if (ended) { playIndex(index, 0) } else if (mp.isPlaying) mp.pause() else mp.play() }
    fun pause() { player?.takeIf { it.isPlaying }?.pause() }
    fun play() { player?.play() }
    fun seekTo(ms: Long) {
        val mp = player ?: return
        val t = if (lengthMs > 0) ms.coerceIn(0, lengthMs) else ms.coerceAtLeast(0)
        mp.setTime(t); timeMs = t
    }
    fun setSpeed(s: Float) { rate = PlayerMath.clampSpeed(s); player?.rate = rate }
    fun setAudioDelay(ms: Long) { audioDelayMs = ms; player?.audioDelay = ms * 1000 }
    fun setSubDelay(ms: Long) { subDelayMs = ms; player?.spuDelay = ms * 1000 }
    fun tapAb() { ab.tap(timeMs); abVersion++ }

    fun audioTracks(): List<Pair<Int, String>> = player?.audioTracks?.map { it.id to it.name }.orEmpty()
    fun audioTrack(): Int = player?.audioTrack ?: -1
    fun selectAudio(id: Int) { player?.setAudioTrack(id) }
    fun subTracks(): List<Pair<Int, String>> = player?.spuTracks?.map { it.id to it.name }.orEmpty()
    fun subTrack(): Int = player?.spuTrack ?: -1
    fun selectSub(id: Int) { player?.setSpuTrack(id) }

    /** Adds an external subtitle file (copied to the cache first, since libVLC reads paths). */
    suspend fun addSubtitle(uri: Uri, name: String): Boolean {
        val f = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(app.cacheDir, "subs").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "subtitle.srt" }
                val out = File(dir, safe)
                app.contentResolver.openInputStream(uri)!!.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                out
            }.getOrNull()
        } ?: return false
        return player?.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true) ?: false
    }

    fun setHardwareDecoding(on: Boolean) {
        hwDecoding = on; prefs.hardwareDecoding = on
        playIndex(index, timeMs)
    }

    /** Subtitle styling lives in libVLC's options, so apply it by rebuilding the engine at the same spot. */
    fun rebuildForStyle() {
        val at = timeMs
        releasePlayer()
        playIndex(index, at)
    }

    // ---- Resume ----

    fun saveResume(force: Long? = null) {
        val item = current ?: return
        val pos = force ?: PlayerMath.positionToSave(timeMs, lengthMs)
        if (force == null && timeMs <= 0) return
        if (item.mediaId >= 0) container.appScope.launch { container.library.savePosition(item.mediaId, pos) }
        else prefs.saveResume(item.uri, pos)
    }

    private fun releasePlayer() {
        player?.let { mp ->
            runCatching { mp.setEventListener(null); mp.vlcVout.detachViews(); mp.stop(); mp.release() }
        }
        player = null
        attached = null
        libVlc?.let { runCatching { it.release() } }
        libVlc = null
    }

    /** Stops everything (activity closed without background audio, or Stop in the notification). */
    fun release() {
        saveResume()
        releasePlayer()
        afd?.let { runCatching { it.close() } }; afd = null
        loopback?.let { runCatching { it.stop() } }; loopback = null
        items = emptyList(); playing = false
        onStateChanged?.invoke()
    }
}
