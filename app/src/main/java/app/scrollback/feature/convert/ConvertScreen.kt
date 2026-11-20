// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.convert

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.convert.VertConfig
import app.scrollback.convert.VertFormats
import app.scrollback.convert.VertService
import app.scrollback.feature.library.ListRow
import app.scrollback.files.FileNode
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.SegmentedToggle
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStream

private data class Source(val name: String, val size: Long, val open: () -> InputStream)

/** Files → Tools → Convert (VERT). Video converts through vertd; other types open in the VERT web app. */
@Composable
fun ConvertScreen(initial: FileNode?, onBack: () -> Unit) {
    val container = LocalContainer.current
    val vert = container.vert
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by vert.config.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()

    var source by remember {
        mutableStateOf(initial?.let { n -> Source(n.name, n.size) { container.files.backend(n).openInput(n) ?: error("Couldn't read ${n.name}") } })
    }
    val kind = source?.let { VertFormats.kind(it.name) }
    val fromExt = source?.name?.substringAfterLast('.', "")?.lowercase()
    var target by remember { mutableStateOf("mp4") }
    var speed by remember { mutableStateOf(1) } // 0 fast, 1 balanced, 2 quality
    var stage by remember { mutableStateOf<VertService.Stage?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editingServer by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name = "file"; var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; size = if (c.isNull(1)) -1 else c.getLong(1) }
        }
        source = Source(name, size) { ctx.contentResolver.openInputStream(uri) ?: error("Couldn't read $name") }
        result = null; error = null
    }

    fun openVert() = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(config.instance))) }
        .onFailure { Toast.makeText(ctx, "No browser found", Toast.LENGTH_SHORT).show() }

    fun start() {
        val s = source ?: return
        error = null; result = null
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val online = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (!online) { error = "You're offline. Video conversion needs a connection to the vertd server."; return }
        job = scope.launch {
            runCatching {
                vert.convertVideo(s.name, s.size, s.open, target, when (speed) { 0 -> 4; 2 -> 1; else -> 3 }) { st -> stage = st }
            }.onSuccess { result = it; Toast.makeText(ctx, "Saved to Download/Scrollback", Toast.LENGTH_SHORT).show() }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = app.scrollback.convert.vertErrorMessage(it) }
            stage = null; job = null
        }
    }

    GlassScreen(title = "Convert", collapsed = collapsed, onBack = onBack) { mod ->
        LazyColumn(modifier = mod, state = list,
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve)) {
            item { LargeTitle("Convert", "Powered by VERT · ${config.instance.removePrefix("https://")}") }
            item {
                SectionCard(title = "File") {
                    ListRow(
                        title = source?.name ?: "Pick a file…",
                        subtitle = source?.let { "${kind?.label}${if (it.size > 0) " · " + android.text.format.Formatter.formatShortFileSize(ctx, it.size) else ""}" }
                            ?: "Video converts here; images, audio and documents open in VERT",
                        leading = { Icon(Icons.Outlined.InsertDriveFile, null, tint = SbColors.TextPrimary) },
                        onClick = { if (job == null) pick.launch(arrayOf("*/*")) },
                    )
                }
            }
            if (kind == VertFormats.Kind.VIDEO) {
                item {
                    SectionCard(title = "Convert to") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(SbSpace.m), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                            VertFormats.VIDEO_TARGETS.filter { it != fromExt }.forEach { f -> GlassPill(f.uppercase(), selected = target == f, onClick = { target = f }) }
                        }
                        Row(Modifier.padding(start = SbSpace.m, end = SbSpace.m, bottom = SbSpace.m)) {
                            SegmentedToggle(listOf("Fast", "Balanced", "Quality"), speed, { speed = it })
                        }
                    }
                }
                item {
                    SectionCard {
                        val st = stage
                        if (st == null) {
                            ListRow("Convert to ${target.uppercase()}", subtitle = if (config.vertd.isBlank()) "Set a video server (vertd) below first"
                                else "Uploads to ${config.vertd.removePrefix("https://")}, deleted from the server after download",
                                leading = { Icon(Icons.Outlined.Transform, null, tint = SbColors.TextPrimary) },
                                onClick = { if (config.vertd.isNotBlank()) start() else editingServer = true })
                        } else {
                            val (label, fraction) = when (st) {
                                is VertService.Stage.Uploading -> "Uploading… ${(st.fraction * 100).toInt()}%" to st.fraction
                                is VertService.Stage.Converting -> (st.fraction?.let { "Converting… ${(it * 100).toInt()}%" }
                                    ?: ("Converting…" + (st.frame?.let { " frame $it" } ?: ""))) + (st.fps?.let { " · %.0f fps".format(it) } ?: "") to st.fraction
                                is VertService.Stage.Downloading -> "Downloading… ${(st.fraction * 100).toInt()}%" to st.fraction
                            }
                            Column(Modifier.padding(SbSpace.l)) {
                                Text(label, color = SbColors.TextPrimary)
                                Spacer(Modifier.height(SbSpace.s))
                                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = SbColors.Accent)
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = SbColors.Accent)
                                TextButton(onClick = { job?.cancel() }) { Text("Cancel", color = SbColors.TextSecondary) }
                            }
                        }
                        error?.let { Text(it, color = SbColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(SbSpace.l)) }
                        result?.let { uri ->
                            ListRow("Open result", subtitle = "Download/Scrollback/${VertFormats.outputName(source!!.name, target)}",
                                leading = { Icon(Icons.Outlined.PlayArrow, null, tint = SbColors.TextPrimary) },
                                onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } })
                            ListRow("Share", leading = { Icon(Icons.Outlined.Share, null, tint = SbColors.TextPrimary) },
                                onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(uri)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) })
                        }
                    }
                }
            } else if (source != null) {
                item {
                    SectionCard(title = "${kind?.label} conversion") {
                        ListRow("Open VERT", subtitle = "VERT converts ${kind?.label?.lowercase()} files inside your browser, so nothing is uploaded. Pick “${source!!.name}” there.",
                            leading = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = SbColors.TextPrimary) }, onClick = { openVert() })
                    }
                }
            }
            item {
                SectionCard(title = "Server") {
                    ListRow("VERT: ${config.instance.removePrefix("https://")}", subtitle = "Web app for images, audio and documents",
                        leading = { Icon(Icons.Outlined.Cloud, null, tint = SbColors.TextPrimary) }, onClick = { editingServer = true })
                    ListRow("Video server: ${config.vertd.removePrefix("https://").ifBlank { "not set" }}", subtitle = "vertd (FFmpeg) does video conversion",
                        leading = { Icon(Icons.Outlined.Dns, null, tint = SbColors.TextPrimary) }, onClick = { editingServer = true })
                    ListRow("Open VERT in the browser", leading = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = SbColors.TextPrimary) }, onClick = { openVert() })
                }
            }
        }
    }

    if (editingServer) VertServerDialog(vert, config) { editingServer = false }
}

@Composable
private fun VertServerDialog(vert: VertService, config: VertConfig, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var instance by remember { mutableStateOf(config.instance) }
    var vertd by remember { mutableStateOf(config.vertd) }
    var header by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SbColors.SurfaceHigh,
        shape = SbRadius.Large,
        title = { Text("VERT server") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(SbSpace.s)) {
                    GlassPill("vert.sh", selected = instance == VertConfig.DEFAULT_INSTANCE, onClick = { instance = VertConfig.DEFAULT_INSTANCE; vertd = VertConfig.DEFAULT_VERTD })
                    GlassPill("Custom", selected = instance != VertConfig.DEFAULT_INSTANCE, onClick = { if (instance == VertConfig.DEFAULT_INSTANCE) { instance = ""; vertd = "" } })
                }
                Spacer(Modifier.height(SbSpace.s))
                OutlinedTextField(instance, { instance = it }, label = { Text("VERT URL") }, placeholder = { Text("https://vert.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Your own VERT instance, or vert.sh. Saved only on this phone.") })
                OutlinedTextField(vertd, { vertd = it }, label = { Text("Video server (vertd) URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Empty = no video conversion. Self-hosted VERT shows its vertd URL in its settings.") })
                OutlinedTextField(header, { header = it }, label = { Text(if (vert.hasHeader()) "Extra header (saved; type to replace)" else "Extra header (optional)") },
                    placeholder = { Text("Name: value") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Key, null) },
                    supportingText = { Text("For a vertd behind an auth proxy. Stored encrypted; only sent to vertd.") })
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vert.save(instance, vertd)
                if (header.isNotBlank()) vert.setHeader(header)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (vert.hasHeader()) TextButton(onClick = { vert.setHeader(null); Toast.makeText(ctx, "Header removed", Toast.LENGTH_SHORT).show() }) { Text("Remove header", color = SbColors.TextSecondary) }
                TextButton(onClick = {
                    vert.save(instance, vertd); if (header.isNotBlank()) vert.setHeader(header)
                    status = "Testing…"
                    scope.launch { status = runCatching { "Connected (vertd ${vert.test()})" }.getOrElse { "Failed: ${app.scrollback.convert.vertErrorMessage(it)}" } }
                }) { Text("Test", color = SbColors.TextSecondary) }
            }
        },
    )
}
