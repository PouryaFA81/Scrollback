package app.scrollback.feature.nearby

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.feature.library.ListRow
import app.scrollback.nearby.DeviceType
import app.scrollback.nearby.Fingerprint
import app.scrollback.nearby.Peer
import app.scrollback.nearby.Transfer
import app.scrollback.nearby.TransferState
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassIconButton
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.launch

private val bottomPad @Composable get() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve

/**
 * Files → Tools → Nearby share: LocalSend-compatible send/receive on the local network
 * (works with LocalSend and RSend). The server only runs while this screen is open or a transfer is active.
 */
@Composable
fun NearbyScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val m = container.nearby
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    val state by m.state.collectAsStateWithLifecycle()
    val settings by m.settings.collectAsStateWithLifecycle()
    val pending by m.pending.collectAsStateWithLifecycle()
    val outgoing by m.outgoing.collectAsStateWithLifecycle()
    val incoming by m.incoming.collectAsStateWithLifecycle()
    val offer by m.offer.collectAsStateWithLifecycle()
    val text by m.text.collectAsStateWithLifecycle()
    val received by m.received.collectAsStateWithLifecycle()

    DisposableEffect(Unit) { m.acquire(); onDispose { m.release() } }

    var editAlias by remember { mutableStateOf(false) }
    var editPin by remember { mutableStateOf(false) }
    var addAddress by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Peer?>(null) }
    var lastTarget by remember { mutableStateOf<Peer?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) m.queueUris(uris) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) m.setSaveTree(uri, Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast(':').ifBlank { "Chosen folder" })
    }

    fun sendTo(p: Peer, pin: String? = null) {
        if (pending.isEmpty()) { Toast.makeText(ctx, "Add files to send first", Toast.LENGTH_SHORT).show(); return }
        if (outgoing?.active == true) return
        lastTarget = p
        m.send(p, pin)
    }
    // A 401 from the receiver: ask for its PIN, then retry.
    val needsPin = outgoing?.state == TransferState.FAILED && outgoing?.sessionId == "pin"

    GlassScreen(
        title = "Nearby share", collapsed = collapsed, onBack = onBack,
        actions = { GlassIconButton(Icons.Outlined.Refresh, "Refresh", onClick = { m.refresh() }) },
    ) { mod ->
        LazyColumn(modifier = mod, state = list, contentPadding = PaddingValues(bottom = bottomPad)) {
            item { LargeTitle("Nearby share", "Send and receive files on the same Wi-Fi. Works with LocalSend and RSend.") }

            item {
                SectionCard {
                    val sub = when {
                        state.error != null -> state.error!!
                        !state.running -> "Starting…"
                        else -> buildString {
                            append("Visible as “${settings.alias}”")
                            state.addresses.firstOrNull()?.let { append(" · $it") }
                            if (settings.hasPin) append(" · PIN on")
                            if (settings.quickSave) append(" · Quick save")
                        }
                    }
                    ListRow(if (state.running) "Ready to receive" else if (state.error != null) "Not available" else "Starting", subtitle = sub,
                        leading = { Icon(Icons.Outlined.WifiTethering, null, tint = if (state.running) SbColors.Accent else SbColors.TextSecondary) })
                }
            }

            item {
                SectionCard(title = "To send") {
                    pending.forEach { o ->
                        ListRow(o.name, subtitle = if (o.size >= 0) android.text.format.Formatter.formatShortFileSize(ctx, o.size) else null,
                            leading = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = SbColors.TextPrimary) },
                            trailing = { if (outgoing?.active != true) GlassIconButton(Icons.Outlined.Close, "Remove", onClick = { m.unqueue(o) }, size = 36.dp) })
                    }
                    ListRow(if (pending.isEmpty()) "Choose files…" else "Add more…",
                        subtitle = if (pending.isEmpty()) "Or use “Send nearby” in Files or the share sheet" else "${pending.size} file${if (pending.size == 1) "" else "s"} · ${android.text.format.Formatter.formatShortFileSize(ctx, pending.sumOf { it.size.coerceAtLeast(0) })} · tap a device below",
                        leading = { Icon(Icons.Outlined.Add, null, tint = SbColors.TextPrimary) },
                        trailing = if (pending.isNotEmpty() && outgoing?.active != true) ({ TextButton(onClick = { m.clearQueue() }) { Text("Clear", color = SbColors.TextSecondary) } }) else null,
                        onClick = { pick.launch(arrayOf("*/*")) })
                }
            }

            outgoing?.let { t -> item { TransferCard(t, onCancel = { m.cancelSend() }, onDismiss = { m.dismissOutgoing() }) } }
            incoming?.let { t -> item { TransferCard(t, onCancel = { m.cancelReceive() }, onDismiss = { m.dismissIncoming() }) } }

            item {
                SectionCard(title = "Devices") {
                    if (state.peers.isEmpty()) {
                        ListRow(if (state.scanning) "Looking for devices…" else "No devices found yet",
                            subtitle = "Open LocalSend or RSend on the other device, on the same network. Tap refresh to search again.",
                            leading = { if (state.scanning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = SbColors.TextPrimary) else Icon(Icons.Outlined.Lan, null, tint = SbColors.TextSecondary) })
                    }
                    state.peers.forEach { p ->
                        ListRow(p.info.alias,
                            subtitle = listOfNotNull(p.info.deviceModel, p.address, if (p.info.protocol == "http") "unencrypted" else null).joinToString(" · "),
                            leading = { Icon(iconFor(p.info.deviceType), null, tint = SbColors.TextPrimary) },
                            trailing = if (pending.isNotEmpty()) ({ Text("Send", color = SbColors.Accent, style = MaterialTheme.typography.labelLarge) }) else null,
                            onClick = { sendTo(p) })
                    }
                    if (state.scanning && state.peers.isNotEmpty()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = SbSpace.s, vertical = SbSpace.s).height(2.dp), color = SbColors.TextPrimary, trackColor = SbColors.GlassFillStrong)
                    }
                    ListRow("Add by address…", subtitle = "If a device doesn't show up (e.g. 192.168.1.20)",
                        leading = { Icon(Icons.Outlined.Add, null, tint = SbColors.TextSecondary) }, onClick = { addAddress = true })
                }
            }

            if (received.isNotEmpty()) item {
                SectionCard(title = "Received") {
                    received.take(12).forEach { r ->
                        ListRow(r.name, subtitle = android.text.format.DateUtils.getRelativeTimeSpanString(r.at).toString(),
                            leading = { Icon(Icons.Outlined.Download, null, tint = SbColors.TextPrimary) },
                            onClick = { openReceived(ctx, Uri.parse(r.uri), r.mime) })
                    }
                }
            }

            item {
                SectionCard(title = "Settings") {
                    ListRow("Device name", subtitle = settings.alias, leading = { Icon(Icons.Outlined.Badge, null, tint = SbColors.TextPrimary) }, onClick = { editAlias = true })
                    ListRow("Receive PIN", subtitle = if (settings.hasPin) "On: senders must enter it" else "Off", leading = { Icon(Icons.Outlined.Lock, null, tint = SbColors.TextPrimary) }, onClick = { editPin = true })
                    ListRow("Quick save", subtitle = "Accept incoming files without asking", leading = { Icon(Icons.Outlined.Bolt, null, tint = SbColors.TextPrimary) },
                        trailing = { Toggle(settings.quickSave) { m.setQuickSave(it) } }, onClick = { m.setQuickSave(!settings.quickSave) })
                    ListRow("Save to", subtitle = m.saveLabel(), leading = { Icon(Icons.Outlined.Folder, null, tint = SbColors.TextPrimary) },
                        trailing = if (settings.saveTree != null) ({ TextButton(onClick = { m.setSaveTree(null, null) }) { Text("Default", color = SbColors.TextSecondary) } }) else null,
                        onClick = { tree.launch(null) })
                    ListRow("Security fingerprint", subtitle = Fingerprint.short(m.fingerprint) + " · LocalSend v2, HTTPS",
                        leading = { Icon(Icons.Outlined.Fingerprint, null, tint = SbColors.TextSecondary) },
                        onClick = { copy(ctx, m.fingerprint); Toast.makeText(ctx, "Fingerprint copied", Toast.LENGTH_SHORT).show() })
                }
            }
            item {
                Text("Compatible with LocalSend (protocol v2) and RSend. Discovery uses multicast plus a scan of your Wi-Fi subnet.",
                    style = MaterialTheme.typography.bodySmall, color = SbColors.TextTertiary,
                    modifier = Modifier.padding(horizontal = SbSpace.screenH, vertical = SbSpace.m))
            }
        }
    }

    // ---- dialogs

    offer?.let { o ->
        AlertDialog(
            onDismissRequest = {}, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("${o.sender.info.alias} wants to send") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("${o.files.size} file${if (o.files.size == 1) "" else "s"} · ${android.text.format.Formatter.formatShortFileSize(ctx, o.files.sumOf { it.size })} → ${m.saveLabel()}",
                        color = SbColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(SbSpace.s))
                    o.files.take(30).forEach { f ->
                        Text(f.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextPrimary)
                    }
                    if (o.files.size > 30) Text("and ${o.files.size - 30} more", color = SbColors.TextSecondary)
                    Spacer(Modifier.height(SbSpace.s))
                    Text("${o.sender.address} · ${Fingerprint.short(o.sender.info.fingerprint)}", style = MaterialTheme.typography.bodySmall, color = SbColors.TextTertiary)
                }
            },
            confirmButton = { TextButton(onClick = { m.answerOffer(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { m.answerOffer(false) }) { Text("Decline", color = SbColors.TextSecondary) } },
        )
    }

    text?.let { t ->
        AlertDialog(
            onDismissRequest = { m.dismissText() }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("Message from ${t.from}") },
            text = { Text(t.text, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { copy(ctx, t.text); m.dismissText() }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { m.dismissText() }) { Text("Close", color = SbColors.TextSecondary) } },
        )
    }

    if (needsPin && lastTarget != null && pinFor == null) pinFor = lastTarget
    pinFor?.let { p ->
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinFor = null; m.dismissOutgoing() }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("PIN for ${p.info.alias}") },
            text = { OutlinedTextField(pin, { pin = it }, singleLine = true, label = { Text("PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)) },
            confirmButton = { TextButton(onClick = { pinFor = null; m.dismissOutgoing(); sendTo(p, pin) }, enabled = pin.isNotBlank()) { Text("Send") } },
            dismissButton = { TextButton(onClick = { pinFor = null; m.dismissOutgoing() }) { Text("Cancel", color = SbColors.TextSecondary) } },
        )
    }

    if (editAlias) {
        var v by remember { mutableStateOf(settings.alias) }
        AlertDialog(
            onDismissRequest = { editAlias = false }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("Device name") },
            text = { OutlinedTextField(v, { v = it.take(40) }, singleLine = true, supportingText = { Text("Shown to other devices") }) },
            confirmButton = { TextButton(onClick = { editAlias = false; m.setAlias(v) }, enabled = v.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editAlias = false }) { Text("Cancel", color = SbColors.TextSecondary) } },
        )
    }

    if (editPin) {
        var v by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editPin = false }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("Receive PIN") },
            text = { OutlinedTextField(v, { v = it.take(12) }, singleLine = true, label = { Text("New PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = { Text("Senders must enter this PIN. Leave empty to turn it off.") }) },
            confirmButton = { TextButton(onClick = { editPin = false; m.setPin(v.ifBlank { null }) }) { Text(if (v.isBlank()) "Turn off" else "Save") } },
            dismissButton = { TextButton(onClick = { editPin = false }) { Text("Cancel", color = SbColors.TextSecondary) } },
        )
    }

    if (addAddress) {
        var v by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addAddress = false }, containerColor = SbColors.SurfaceHigh, shape = SbRadius.Large,
            title = { Text("Add by address") },
            text = { OutlinedTextField(v, { v = it.trim(); err = null }, singleLine = true, label = { Text("IP address (or ip:port)") }, isError = err != null,
                supportingText = { Text(err ?: "The other device must have LocalSend or RSend open") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) },
            confirmButton = {
                TextButton(enabled = v.isNotBlank() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        val p = m.addByAddress(v)
                        busy = false
                        if (p == null) err = "No LocalSend device answered at $v" else addAddress = false
                    }
                }) { Text(if (busy) "Connecting…" else "Add") }
            },
            dismissButton = { TextButton(onClick = { addAddress = false }) { Text("Cancel", color = SbColors.TextSecondary) } },
        )
    }
}

@Composable
private fun TransferCard(t: Transfer, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard(title = if (t.incoming) "Receiving from ${t.peer}" else "Sending to ${t.peer}") {
        val size = android.text.format.Formatter.formatShortFileSize(ctx, t.total)
        val status = t.message ?: when (t.state) {
            TransferState.WAITING -> "Waiting…"
            TransferState.RUNNING -> "${(t.fraction * 100).toInt()}% · ${android.text.format.Formatter.formatShortFileSize(ctx, t.done)} of $size"
            TransferState.DONE -> "Done"
            TransferState.FAILED -> "Failed"
            TransferState.CANCELLED -> "Cancelled"
        }
        ListRow("${t.files.size} file${if (t.files.size == 1) "" else "s"} · $size", subtitle = status,
            leading = {
                if (t.active) CircularProgressIndicator(progress = { t.fraction }, modifier = Modifier.size(26.dp), strokeWidth = 3.dp, color = SbColors.Accent, trackColor = SbColors.GlassFillStrong)
                else Icon(if (t.incoming) Icons.Outlined.Download else Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = if (t.state == TransferState.DONE) SbColors.Accent else SbColors.TextSecondary)
            },
            trailing = { TextButton(onClick = if (t.active) onCancel else onDismiss) { Text(if (t.active) "Stop" else "OK", color = SbColors.TextSecondary) } },
            below = {
                if (t.active) Column {
                    LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp), color = SbColors.Accent, trackColor = SbColors.GlassFillStrong, drawStopIndicator = {})
                    t.files.firstOrNull { it.done < it.size && it.failed == null }?.let {
                        Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = SbColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                t.files.filter { it.failed != null }.take(3).forEach {
                    Text("${it.name}: ${it.failed}", style = MaterialTheme.typography.bodySmall, color = SbColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            })
    }
}

@Composable
private fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(
        checkedThumbColor = SbColors.OnAccent, checkedTrackColor = SbColors.Accent,
        uncheckedThumbColor = SbColors.TextSecondary, uncheckedTrackColor = SbColors.SurfaceHigh, uncheckedBorderColor = SbColors.Divider,
    ),
)

private fun iconFor(t: DeviceType?) = when (t) {
    DeviceType.MOBILE -> Icons.Outlined.PhoneAndroid
    DeviceType.WEB -> Icons.Outlined.Language
    DeviceType.SERVER, DeviceType.HEADLESS -> Icons.Outlined.Dns
    else -> Icons.Outlined.Computer
}

private fun copy(ctx: Context, s: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Nearby", s))
}

private fun openReceived(ctx: Context, uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}
