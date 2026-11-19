package app.scrollback.fullplayer

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.scrollback.ScrollbackApp
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.theme.ScrollbackTheme
import kotlinx.coroutines.launch

/**
 * "Scrollback Player": the long-form player (libVLC). Opened from Files, the feed's info sheet,
 * the feed's automatic fallback, and "Open with" for any video. Separate from the swipe feed.
 */
class FullPlayerActivity : ComponentActivity() {
    private val container by lazy { (application as ScrollbackApp).container }
    var engine: VlcEngine? = null; private set
    var inPip by mutableStateOf(false); private set
    /** Views the engine renders into; kept so they can be re-attached after onStop. */
    var videoView: TextureView? = null
    var subsView: TextureView? = null
    var onLayoutChanged: ((VideoLayout) -> Unit)? = null

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { engine?.toggle(); updatePip() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        hideSystemBars()
        lifecycleScope.launch {
            container.settings.settings.collect { s ->
                if (s.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val ok = VlcEngine.available()
        if (ok) {
            val e = VlcEngine.get(this, container)
            engine = e
            if (savedInstanceState == null) {
                val q = FullPlayer.parse(this, intent)
                if (q != null) e.setQueue(q.first, q.second)
            }
            FullPlayerService.stop(this)
        }
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                ScrollbackTheme {
                    val e = engine
                    if (e == null) UnavailableScreen(onBack = { finish() }) else FullPlayerScreen(this, e)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val q = FullPlayer.parse(this, intent) ?: return
        engine?.setQueue(q.first, q.second)
    }

    fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun attachViews() {
        val v = videoView ?: return; val s = subsView ?: return
        engine?.attachViews(v, s) { onLayoutChanged?.invoke(it) }
    }

    // ---- Orientation ----
    enum class Rotation { AUTO, LANDSCAPE, PORTRAIT }
    var rotation by mutableStateOf(Rotation.AUTO); private set

    fun cycleRotation(videoIsLandscape: Boolean) {
        rotation = when (rotation) { Rotation.AUTO -> Rotation.LANDSCAPE; Rotation.LANDSCAPE -> Rotation.PORTRAIT; Rotation.PORTRAIT -> Rotation.AUTO }
        applyRotation(videoIsLandscape)
    }

    fun applyRotation(videoIsLandscape: Boolean?) {
        requestedOrientation = when (rotation) {
            Rotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Rotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Rotation.AUTO -> when (videoIsLandscape) {
                true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                false -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                null -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // ---- Picture-in-picture ----

    private fun pipParams(): PictureInPictureParams {
        val e = engine
        val l = e?.layout
        val ratio = if (l != null && l.visW > 0 && l.visH > 0) {
            val sar = if (l.sarNum > 0 && l.sarDen > 0) l.sarNum.toDouble() / l.sarDen else 1.0
            val r = (l.visW * sar / l.visH).coerceIn(1 / 2.39, 2.39)
            Rational((r * 1000).toInt(), 1000)
        } else Rational(16, 9)
        val playing = e?.playing == true
        val action = RemoteAction(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", if (playing) "Pause" else "Play",
            PendingIntent.getBroadcast(this, 1, Intent(ACTION_PIP_TOGGLE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE),
        )
        return PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(listOf(action))
            .apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(playing && (e?.prefs?.autoPip ?: false)).setSeamlessResizeEnabled(true) }
            .build()
    }

    fun updatePip() { runCatching { setPictureInPictureParams(pipParams()) } }

    fun enterPip() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode && lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED) {
            // PiP window closed with the X: stop like VLC does (unless background audio is on).
            if (engine?.prefs?.backgroundAudio != true) engine?.pause()
        }
    }

    // ---- Lifecycle ----

    override fun onStart() {
        super.onStart()
        FullPlayerService.stop(this)
        engine?.setVideoEnabled(true)
        attachViews()
    }

    override fun onPause() {
        super.onPause()
        val e = engine ?: return
        if (!isInPictureInPictureMode && !isChangingConfigurations && e.prefs.backgroundAudio && e.playing) FullPlayerService.start(this)
    }

    override fun onStop() {
        super.onStop()
        val e = engine ?: return
        e.saveResume()
        if (isChangingConfigurations) return
        if (e.prefs.backgroundAudio && e.playing) {
            e.setVideoEnabled(false)
            e.detachViews()
        } else {
            e.pause()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        val e = engine
        if (e != null && !isChangingConfigurations) {
            e.detachViews()
            if (!(e.prefs.backgroundAudio && e.playing)) { e.release(); FullPlayerService.stop(this) }
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PIP_TOGGLE = "app.scrollback.fullplayer.PIP_TOGGLE"
    }
}
