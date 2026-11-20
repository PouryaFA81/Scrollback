// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.nearby

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
import app.scrollback.MainActivity
import app.scrollback.ScrollbackApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Foreground service only while a nearby transfer is running (keeps it alive with the screen off). */
class NearbyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nearby share", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, build(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val m = (application as ScrollbackApp).container.nearby
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            combine(m.outgoing, m.incoming) { o, i -> listOfNotNull(o?.takeIf { it.active }, i?.takeIf { it.active }).firstOrNull() }
                .sample(500)
                .collect { t ->
                    if (t == null) { ServiceCompat.stopForeground(this@NearbyService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
                    else nm.notify(ID, build(t))
                }
        }
        return START_NOT_STICKY
    }

    private fun build(t: Transfer?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NEARBY).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val title = when { t == null -> "Nearby share"; t.incoming -> "Receiving from ${t.peer}"; else -> "Sending to ${t.peer}" }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(t?.let { "${(it.fraction * 100).toInt()}% · ${it.files.size} file${if (it.files.size == 1) "" else "s"}" } ?: "Preparing…")
            .setProgress(100, ((t?.fraction ?: 0f) * 100).toInt(), t == null || t.state == TransferState.WAITING)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "nearby"
        private const val ID = 5331
        fun start(c: Context) = runCatching { ContextCompat.startForegroundService(c, Intent(c, NearbyService::class.java)) }
    }
}
