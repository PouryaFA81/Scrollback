package app.scrollback.fullplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the full player's audio going with the screen off or another app open, when
 * "Background audio" is on. Shows Play/Pause and Stop; tapping it reopens the player.
 */
class FullPlayerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = VlcEngine.peek()
        when (intent?.action) {
            ACTION_TOGGLE -> engine?.toggle()
            ACTION_STOP -> { engine?.release(); stop(); return START_NOT_STICKY }
        }
        if (engine == null || engine.current == null) { stop(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video player", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, build(engine), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        engine.onStateChanged = {
            if (engine.current == null) stop() else nm.notify(ID, build(engine))
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        VlcEngine.peek()?.onStateChanged = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun build(e: VlcEngine): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, FullPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, FullPlayerService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(e.current?.name ?: "Scrollback Player")
            .setContentText(if (e.playing) "Playing in the background" else "Paused")
            .setOngoing(e.playing).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(if (e.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (e.playing) "Pause" else "Play", action(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP))
            .build()
    }

    override fun onDestroy() { VlcEngine.peek()?.onStateChanged = null; super.onDestroy() }

    companion object {
        private const val CHANNEL = "full_player"
        private const val ID = 5332
        private const val ACTION_TOGGLE = "app.scrollback.fullplayer.TOGGLE"
        private const val ACTION_STOP = "app.scrollback.fullplayer.STOP"
        fun start(c: Context) = runCatching { ContextCompat.startForegroundService(c, Intent(c, FullPlayerService::class.java)) }
        fun stop(c: Context) = runCatching { c.stopService(Intent(c, FullPlayerService::class.java)) }
    }
}
