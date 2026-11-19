package app.scrollback.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.BuildConfigInfo
import app.scrollback.data.prefs.Settings
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.PinPromptDialog
import app.scrollback.ui.components.RowDivider
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.SetPinDialog
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenFolders: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val container = LocalContainer.current
    val accounts by container.accounts.accounts.collectAsStateWithLifecycle()
    val settingsStore = container.settings
    val haptics = rememberHaptics()
    val settings by settingsStore.settings.collectAsStateWithLifecycle(Settings())
    val session by container.session.collectAsStateWithLifecycle()
    val scan by container.library.scanState.collectAsStateWithLifecycle()
    val sources by container.library.observeSources().collectAsStateWithLifecycle(emptyList())
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    var settingPin by remember { mutableStateOf(false) }
    var confirmRemovePin by remember { mutableStateOf(false) }
    var askPinForHidden by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    fun launch(block: suspend () -> Unit) { haptics.tick(); container.appScope.launch { block() } }

    @Composable
    fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = SbColors.OnAccent,
            checkedTrackColor = SbColors.Accent,
            uncheckedThumbColor = SbColors.TextSecondary,
            uncheckedTrackColor = SbColors.SurfaceHigh,
            uncheckedBorderColor = SbColors.Divider,
        ),
    )

    GlassScreen(title = "Settings", collapsed = collapsed) { mod ->
        LazyColumn(
            modifier = mod,
            state = list,
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve),
        ) {
            item { LargeTitle("Settings") }
            item {
                SectionCard(title = "Library") {
                    SettingsRow(
                        "Folders",
                        subtitle = "${sources.size} folder${if (sources.size == 1) "" else "s"} · ${sources.sumOf { it.videoCount }} videos · ${sources.sumOf { it.imageCount }} pictures",
                        icon = Icons.Outlined.Folder,
                        onClick = onOpenFolders,
                    )
                    RowDivider()
                    SettingsRow(
                        "Rescan now",
                        subtitle = if (scan.running) "Scanning ${scan.currentSource ?: ""}… ${scan.found} found" else "Picks up new, changed and removed files",
                        icon = Icons.Outlined.Refresh,
                        enabled = !scan.running,
                        onClick = { launch { container.library.rescanAll() } },
                    )
                    RowDivider()
                    SettingsRow("Duplicate finder", subtitle = "Find identical copies and free space", icon = Icons.Outlined.ContentCopy, onClick = onOpenDuplicates)
                    RowDivider()
                    SettingsRow(
                        "Servers",
                        subtitle = if (accounts.isEmpty()) "Nextcloud streaming & sync — optional" else accounts.joinToString { it.displayName },
                        icon = Icons.Outlined.Cloud,
                        onClick = onOpenServers,
                    )
                }
            }
            item {
                SectionCard(title = "Playback") {
                    SettingsRow(
                        "Speed",
                        subtitle = "${settings.defaultSpeed}× · change it from the speed chip in any feed",
                        icon = Icons.Outlined.Speed,
                    )
                    RowDivider()
                    SettingsRow(
                        if (settings.loopOne) "Loop each video" else "Auto-advance to next",
                        subtitle = "When a video ends",
                        icon = if (settings.loopOne) Icons.Outlined.RepeatOne else Icons.Outlined.KeyboardDoubleArrowDown,
                        onClick = { launch { settingsStore.setLoopOne(!settings.loopOne) } },
                        trailing = { Toggle(settings.loopOne) { v -> launch { settingsStore.setLoopOne(v) } } },
                    )
                }
            }
            item {
                SectionCard(title = "Privacy") {
                    SettingsRow(
                        if (settings.hasPin) "Change PIN" else "Set a PIN",
                        subtitle = "Locks the app and hidden items",
                        icon = Icons.Outlined.Password,
                        onClick = { settingPin = true },
                    )
                    if (settings.hasPin) {
                        RowDivider()
                        SettingsRow("Remove PIN", subtitle = "Hidden items become visible again", icon = Icons.Outlined.Lock, onClick = { confirmRemovePin = true })
                        RowDivider()
                        SettingsRow(
                            "Require PIN to open",
                            subtitle = "Also re-locks after 30 s in the background",
                            icon = Icons.Outlined.Lock,
                            trailing = { Toggle(settings.requirePinOnLaunch) { v -> launch { settingsStore.setRequirePinOnLaunch(v) } } },
                        )
                        RowDivider()
                        SettingsRow(
                            "Show hidden items",
                            subtitle = "Until the app locks again",
                            icon = Icons.Outlined.Visibility,
                            trailing = {
                                Toggle(session.hiddenRevealed) { v ->
                                    if (v) askPinForHidden = true else container.revealHidden(false)
                                }
                            },
                        )
                    }
                    RowDivider()
                    SettingsRow(
                        "Block screenshots",
                        subtitle = "Hides content in recent apps and screen recordings",
                        icon = Icons.Outlined.Screenshot,
                        trailing = { Toggle(settings.secureScreen) { v -> launch { settingsStore.setSecureScreen(v) } } },
                    )
                }
            }
            item {
                SectionCard(title = "About") {
                    SettingsRow(
                        "Scrollback ${BuildConfigInfo.versionName(ctx)}",
                        subtitle = "Offline and private: nothing leaves this device.\nComing next: file manager · Nextcloud streaming & sync · music player",
                        icon = Icons.Outlined.Info,
                    )
                }
            }
        }
    }

    if (settingPin) {
        SetPinDialog(onSet = { pin -> container.appScope.launch { settingsStore.setPin(pin); container.unlockApp() } }, onDismiss = { settingPin = false })
    }
    if (confirmRemovePin) {
        ConfirmDialog("Remove PIN?", "The app will no longer lock, and hidden items become visible everywhere.", "Remove", onConfirm = {
            container.appScope.launch { settingsStore.clearPin() }
        }, onDismiss = { confirmRemovePin = false }, destructive = true)
    }
    if (askPinForHidden) {
        PinPromptDialog("Show hidden items", verify = { settingsStore.verifyPin(it) }, onSuccess = { container.revealHidden(true) }, onDismiss = { askPinForHidden = false })
    }
}
