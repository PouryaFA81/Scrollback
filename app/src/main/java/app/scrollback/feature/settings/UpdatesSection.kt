// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.RowDivider
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.theme.SbColors
import app.scrollback.update.UpdateState
import kotlinx.coroutines.launch

/** Settings → Updates. Nothing here touches the network until the user taps a row. */
@Composable
fun UpdatesSection() {
    val container = LocalContainer.current
    val updater = container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    val hasToken by updater.hasToken.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var editToken by remember { mutableStateOf(false) }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing

    SectionCard(title = "Updates") {
        val subtitle = when (val s = state) {
            UpdateState.Idle -> "Only checks when you tap. Current: ${updater.currentVersion}"
            UpdateState.Checking -> "Checking GitHub Releases…"
            is UpdateState.UpToDate -> "You're up to date (${s.current}; latest ${s.latest})"
            is UpdateState.Available -> "${s.release.tag} is available · ${s.asset.size / 1_048_576} MB"
            is UpdateState.NoApk -> "${s.release.tag} has no APK for this build type yet"
            is UpdateState.Downloading -> "Downloading ${s.release.tag}… ${(s.progress * 100).toInt()}%"
            UpdateState.NeedsInstallPermission -> "Allow Scrollback to install apps, then tap again"
            UpdateState.Installing -> "Handing the update to Android…"
            is UpdateState.Error -> s.message
        }
        SettingsRow(
            "Check for update",
            subtitle = subtitle,
            icon = Icons.Outlined.SystemUpdate,
            enabled = !busy,
            onClick = {
                container.appScope.launch {
                    if (state is UpdateState.NeedsInstallPermission) ctx.startActivity(updater.installPermissionIntent())
                    else updater.check()
                }
            },
        )
        (state as? UpdateState.Downloading)?.let {
            LinearProgressIndicator(
                progress = { it.progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = SbColors.Accent,
                trackColor = SbColors.SurfaceHigh,
            )
        }
        (state as? UpdateState.Available)?.let { a ->
            RowDivider()
            SettingsRow(
                "Install ${a.release.tag}",
                subtitle = a.release.notes.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    .take(3).joinToString("\n").ifBlank { a.release.title },
                icon = Icons.Outlined.NewReleases,
                onClick = { container.appScope.launch { updater.downloadAndInstall(a) } },
            )
        }
        RowDivider()
        SettingsRow(
            "GitHub token",
            subtitle = if (hasToken) "Saved, encrypted on this device · tap to change or remove"
            else "Optional · only needed while the repository is private",
            icon = Icons.Outlined.Key,
            onClick = { editToken = true },
        )
    }

    if (editToken) {
        TokenDialog(
            hasToken = hasToken,
            onSave = { updater.setToken(it); updater.reset() },
            onRemove = { updater.setToken(null); updater.reset() },
            onDismiss = { editToken = false },
        )
    }
}

@Composable
private fun TokenDialog(hasToken: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SbColors.SurfaceHigh,
        shape = app.scrollback.ui.theme.SbRadius.Large,
        title = { Text("GitHub token") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "A fine-grained token with read-only Contents access to PouryaFA81/Scrollback. " +
                        "It is encrypted with the Android Keystore and only sent to api.github.com.",
                    color = SbColors.TextSecondary,
                )
                OutlinedTextField(
                    text, { text = it }, singleLine = true, placeholder = { Text("github_pat_…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            if (hasToken) TextButton(onClick = { onRemove(); onDismiss() }) { Text("Remove", color = SbColors.Danger) }
            else TextButton(onClick = onDismiss) { Text("Cancel", color = SbColors.TextSecondary) }
        },
    )
}
