package app.scrollback

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.ScrollbackRoot
import app.scrollback.ui.theme.ScrollbackTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }

    /** Set when launched from the music notification; the UI opens the full player. */
    val openPlayerRequests = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** ACTION_VIEW for a document (v0.10 office); the UI opens it in the viewer. */
    val openDocRequests = kotlinx.coroutines.flow.MutableStateFlow<android.content.Intent?>(null)

    private fun handleView(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW && intent.data != null) openDocRequests.value = intent
    }

    private val container by lazy { (application as ScrollbackApp).container }
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayerRequests.value = true
        if (savedInstanceState == null) handleView(intent)
        container.music.attach()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settings.settings.collect { s ->
                    if (s.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                ScrollbackTheme { ScrollbackRoot() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayerRequests.value = true
        handleView(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            stoppedAt = SystemClock.elapsedRealtime()
            container.syncSoon(force = true)
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-lock after 30 s in the background (short trips to the share sheet don't re-lock).
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 30_000) container.lockAll()
        stoppedAt = 0
        container.syncSoon()
    }
}
