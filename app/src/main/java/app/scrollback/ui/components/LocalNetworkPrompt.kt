// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.remote.LocalNetwork
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius

private var checkedSavedServers = false

/**
 * v0.15: hosts the Android 17 local-network permission prompt for server connections
 * (Nextcloud, Navidrome/Subsonic, Jellyfin, SFTP, SMB, VERT). Placed once at the app root.
 * Also checks the saved servers once per launch, so existing LAN servers keep working after
 * an update or a phone reset.
 */
@Composable
fun LocalNetworkPrompt() {
    val container = LocalContainer.current
    val access = container.localNetwork
    val ctx = LocalContext.current
    val pending by access.pending.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf<String?>(null) }

    DisposableEffect(access) {
        access.uiAttached++
        onDispose { access.uiAttached-- }
    }
    LaunchedEffect(access) {
        if (checkedSavedServers) return@LaunchedEffect
        checkedSavedServers = true
        val urls = container.accounts.accounts.value.flatMap { listOfNotNull(it.baseUrl, it.altUrl) }
        if (urls.isNotEmpty()) access.ensure(*urls.toTypedArray(), userAction = false)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = access.pending.value ?: return@rememberLauncherForActivityResult
        if (!ok) denied = p.host
        p.result.complete(ok)
    }

    val p = pending
    if (p != null && !p.result.isCompleted) {
        var asked by remember(p) { mutableStateOf(false) }
        if (!asked) AlertDialog(
            onDismissRequest = { p.result.complete(false) },
            containerColor = SbColors.SurfaceHigh,
            shape = SbRadius.Large,
            title = { Text("Allow local network access") },
            text = {
                Text(
                    "${p.host} is on your local network. Android needs your permission before Scrollback can " +
                        "connect to servers there (Nextcloud, Navidrome, Jellyfin, SFTP, SMB or VERT). " +
                        "Scrollback only connects to the servers you add.",
                    color = SbColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { asked = true; launcher.launch(LocalNetwork.PERMISSION) }) { Text("Continue", color = SbColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { denied = p.host; p.result.complete(false) }) { Text("Not now", color = SbColors.TextSecondary) }
            },
        )
    }

    denied?.let { host ->
        AlertDialog(
            onDismissRequest = { denied = null },
            containerColor = SbColors.SurfaceHigh,
            shape = SbRadius.Large,
            title = { Text("Local network access is off") },
            text = {
                Text(
                    "Scrollback can't reach $host or other servers on your home network until you allow it. " +
                        "Open Settings → Apps → Scrollback → Permissions → Nearby devices and allow it. " +
                        "Servers on the internet still work.",
                    color = SbColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    denied = null
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Open settings", color = SbColors.Accent) }
            },
            dismissButton = { TextButton(onClick = { denied = null }) { Text("OK", color = SbColors.TextSecondary) } },
        )
    }
}
