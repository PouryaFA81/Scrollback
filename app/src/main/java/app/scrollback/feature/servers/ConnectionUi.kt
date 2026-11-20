// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.servers

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ConnectionReport
import app.scrollback.accounts.ServerAccount
import app.scrollback.accounts.ServerManager
import app.scrollback.data.prefs.Settings
import app.scrollback.remote.ServerProbe
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.RowDivider
import app.scrollback.ui.components.SbSwitch
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Servers → (server) → Test connection: each address, redirects and sign-in, in plain words. */
@Composable
fun TestConnectionSheet(a: ServerAccount, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<ConnectionReport?>(null) }
    var run by remember { mutableStateOf(0) }
    LaunchedEffect(a.id, run) {
        report = null
        val allowed = container.localNetwork.ensure(a.baseUrl, a.altUrl)
        val r = runCatching { container.servers.testConnection(a) }.getOrElse {
            ConnectionReport(listOf(app.scrollback.accounts.ReportLine(false, "Test failed", it.javaClass.simpleName)), null, false)
        }
        // v0.15: on Android 17 a LAN address fails without the local-network permission; say why.
        report = if (allowed) r else r.copy(
            lines = listOf(app.scrollback.accounts.ReportLine(false, "Local network access is off", app.scrollback.remote.LocalNetwork.DENIED_MESSAGE)) + r.lines,
            allGood = false,
        )
    }
    GlassSheet(onDismiss) { close ->
        SheetTitle("Test connection")
        Text(a.displayName + " · " + a.type.label, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary)
        Spacer(Modifier.height(SbSpace.m))
        val r = report
        if (r == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = SbColors.TextPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.padding(SbSpace.s))
                Text("Checking each address…", color = SbColors.TextSecondary)
            }
        } else {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(SbSpace.m)) {
                r.lines.forEach { line ->
                    Row {
                        Icon(
                            when (line.ok) { true -> Icons.Outlined.CheckCircle; false -> Icons.Outlined.ErrorOutline; null -> Icons.Outlined.Info },
                            null,
                            tint = when (line.ok) { true -> SbColors.Accent; false -> SbColors.Like; null -> SbColors.TextTertiary },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.padding(SbSpace.xs))
                        Column {
                            Text(line.title, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary)
                            Text(line.detail, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(SbSpace.l))
            r.suggestedAddress?.let { s ->
                Button(
                    onClick = {
                        container.servers.setAlternate(a.id, s, prefer = true)
                        Toast.makeText(ctx, "Saved ${s.substringAfter("://")} as the alternate address and using it first", Toast.LENGTH_LONG).show()
                        close()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SbColors.Accent, contentColor = SbColors.OnAccent),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Use ${s.substringAfter("://")}") }
                Spacer(Modifier.height(SbSpace.s))
            }
            OutlinedButton(onClick = { run++ }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Test again", color = SbColors.TextPrimary) }
        }
    }
}

/** Optional LAN/VPN address; requests switch between the two automatically. */
@Composable
fun AlternateAddressSheet(a: ServerAccount, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(a.altUrl.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GlassSheet(onDismiss) { close ->
        SheetTitle("Alternate address")
        Text(
            "A second address for the same server, for example your LAN address at home or the VPN/Tailscale address away. " +
                "Scrollback tries the address that worked last and switches to the other one when it can't connect. Your sign-in is only sent to these two addresses.",
            style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary,
        )
        Spacer(Modifier.height(SbSpace.m))
        OutlinedTextField(
            url, { url = it; error = null }, label = { Text("Alternate address") }, placeholder = { Text("http://192.168.1.10:4533") },
            singleLine = true, isError = error != null, supportingText = { error?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), enabled = !busy,
        )
        Spacer(Modifier.height(SbSpace.m))
        Button(
            onClick = {
                val kind = ServerManager.probeKind(a.type) ?: return@Button
                busy = true
                scope.launch {
                    if (!container.localNetwork.ensure(url)) { busy = false; error = app.scrollback.remote.LocalNetwork.DENIED_MESSAGE; return@launch }
                    val p = withContext(Dispatchers.IO) { container.servers.resolveNewAddress(kind, url) }
                    busy = false
                    if (!p.recognised) {
                        error = p.problem ?: "Not reachable"
                        // Still allow saving: the address may only work on the other network (e.g. VPN off right now).
                        return@launch
                    }
                    container.servers.setAlternate(a.id, p.canonical ?: p.address)
                    Toast.makeText(ctx, "Alternate address saved", Toast.LENGTH_SHORT).show()
                    close()
                }
            },
            enabled = !busy && url.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = SbColors.Accent, contentColor = SbColors.OnAccent),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(if (busy) "Checking…" else "Check and save") }
        if (error != null) {
            Spacer(Modifier.height(SbSpace.s))
            OutlinedButton(onClick = { container.servers.setAlternate(a.id, url); close() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Save anyway (only reachable on another network)", color = SbColors.TextPrimary)
            }
        }
        if (a.altUrl != null) {
            Spacer(Modifier.height(SbSpace.s))
            OutlinedButton(onClick = { container.servers.setAlternate(a.id, null); close() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Remove alternate address", color = SbColors.TextPrimary)
            }
        }
    }
}

/**
 * Music → "Hide duplicates across servers" with the preferred source order. View-level only: it
 * never deletes or changes a file anywhere.
 */
@Composable
fun MusicDuplicatesCard(modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val dupes by container.library.musicDuplicates.collectAsStateWithLifecycle()
    val sources by container.library.observeSources().collectAsStateWithLifecycle(emptyList())
    val music = sources.filter { it.audioCount > 0 }
    // Saved order first, then any source not ordered yet (oldest first).
    val ordered = settings.musicSourceOrder.mapNotNull { id -> music.firstOrNull { it.id == id } } + music.filter { it.id !in settings.musicSourceOrder }
    fun save(list: List<Long>) { container.appScope.launch { container.settings.setMusicSourceOrder(list) } }
    SectionCard(modifier, title = "Music from several sources") {
        SettingsRow(
            "Hide duplicates across servers",
            subtitle = when {
                dupes.mergedSongs == 0 -> "Shows a song once when several servers or folders have it. Nothing is deleted."
                settings.hideMusicDuplicates -> "${dupes.mergedSongs} songs are on more than one source; ${dupes.hiddenCopies} extra copies hidden. Nothing is deleted."
                else -> "${dupes.mergedSongs} songs are on more than one source. Turn on to show each once; nothing is deleted."
            },
            icon = Icons.Outlined.LayersClear,
            onClick = { container.appScope.launch { container.settings.setHideMusicDuplicates(!settings.hideMusicDuplicates) } },
            trailing = { SbSwitch(settings.hideMusicDuplicates) { v -> container.appScope.launch { container.settings.setHideMusicDuplicates(v) } } },
        )
        if (settings.hideMusicDuplicates && ordered.size > 1) {
            RowDivider()
            Text(
                "Preferred source (plays first; long-press a song → Play from… to pick another copy)",
                style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary,
                modifier = Modifier.padding(start = 54.dp, end = SbSpace.l, top = SbSpace.s),
            )
            ordered.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().padding(start = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${s.displayName.removePrefix("♫ ")}", color = SbColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${s.audioCount}", color = SbColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { val l = ordered.map { it.id }.toMutableList(); java.util.Collections.swap(l, i, i - 1); save(l) }, enabled = i > 0) {
                        Icon(Icons.Outlined.ArrowUpward, "Prefer ${s.displayName}", tint = if (i > 0) SbColors.TextPrimary else SbColors.TextTertiary)
                    }
                    IconButton(onClick = { val l = ordered.map { it.id }.toMutableList(); java.util.Collections.swap(l, i, i + 1); save(l) }, enabled = i < ordered.lastIndex) {
                        Icon(Icons.Outlined.ArrowDownward, "Prefer less", tint = if (i < ordered.lastIndex) SbColors.TextPrimary else SbColors.TextTertiary)
                    }
                }
            }
        }
    }
}

/** Address help shown before a new server is added. */
internal fun probeError(p: ServerProbe.Result): String? = if (p.reachable) null else p.problem

internal val AccountType.hasConnectionTest: Boolean get() = this == AccountType.NEXTCLOUD || this == AccountType.SUBSONIC || this == AccountType.JELLYFIN
