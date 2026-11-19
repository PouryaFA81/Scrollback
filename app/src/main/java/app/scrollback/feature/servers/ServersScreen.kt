package app.scrollback.feature.servers

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ServerAccount
import app.scrollback.feature.library.ListRow
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.Fmt
import app.scrollback.ui.components.GlassPill
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.GlassSheet
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.RowDivider
import app.scrollback.ui.components.SbSwitch
import app.scrollback.ui.components.SettingsRow
import app.scrollback.ui.components.SheetTitle
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.components.rememberHaptics
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Servers: Nextcloud, Subsonic/OpenSubsonic and Jellyfin accounts (credentials encrypted). */
@Composable
fun ServersScreen(onBack: () -> Unit, onBrowseNextcloud: (String) -> Unit) {
    val container = LocalContainer.current
    // SFTP/SMB live in Files → Tools → Network storage, not here.
    val accounts = container.accounts.accounts.collectAsStateWithLifecycle().value.filterNot { it.type.isNetworkStorage }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    var adding by remember { mutableStateOf<AccountType?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }

    GlassScreen(
        title = "Servers",
        collapsed = collapsed,
        onBack = onBack,
        overlay = {
            GlassPill(
                "Add server", selected = true, icon = Icons.Outlined.Add, onClick = { choosing = true },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = SbSpace.xl),
            )
        },
    ) { mod ->
        LazyColumn(
            modifier = mod,
            state = list,
            contentPadding = PaddingValues(
                start = SbSpace.m, end = SbSpace.m,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(SbSpace.s),
        ) {
            item {
                LargeTitle(
                    "Servers",
                    "Optional. Stream from Nextcloud, Subsonic-compatible music servers and Jellyfin. Scrollback stays fully usable offline.",
                    horizontalPadding = SbSpace.s,
                )
            }
            if (accounts.isEmpty()) {
                item { EmptyState(Icons.Outlined.Dns, "No servers", "Add a Nextcloud to stream videos and pictures and sync likes and playlists, or a music server for the music player.") }
            }
            items(accounts, key = { it.id }) { a ->
                ListRow(
                    title = a.displayName,
                    subtitle = buildString {
                        append(a.type.label).append(" · ").append(a.baseUrl.substringAfter("://"))
                        if (a.type == AccountType.NEXTCLOUD && a.syncEnabled) {
                            append(" · ")
                            append(
                                when {
                                    a.lastSyncError != null -> "sync: ${a.lastSyncError}"
                                    a.lastSyncAt > 0 -> "synced ${Fmt.relative(a.lastSyncAt)}"
                                    else -> "sync on"
                                }
                            )
                        }
                    },
                    leading = { Icon(if (a.type == AccountType.NEXTCLOUD) Icons.Outlined.Cloud else Icons.Outlined.LibraryMusic, null, tint = SbColors.TextPrimary) },
                    onClick = { selected = a.id },
                )
            }
        }
    }

    if (choosing) {
        GlassSheet({ choosing = false }) { close ->
            SheetTitle("Add server")
            AccountType.entries.filter { !it.isNetworkStorage && (it == AccountType.NEXTCLOUD || container.musicServersReady) }.forEach { t ->
                SettingsRow(
                    t.label,
                    subtitle = when (t) {
                        AccountType.NEXTCLOUD -> "Videos, pictures and music over WebDAV + library sync"
                        AccountType.SUBSONIC -> "Navidrome, Nextcloud Music, Airsonic, Gonic…"
                        AccountType.JELLYFIN -> "Music libraries (video later)"
                        else -> "Files → Tools → Network storage"
                    },
                    icon = if (t == AccountType.NEXTCLOUD) Icons.Outlined.Cloud else Icons.Outlined.LibraryMusic,
                    onClick = { adding = t; close() },
                )
            }
        }
    }
    when (adding) {
        AccountType.NEXTCLOUD -> AddNextcloudSheet(onDismiss = { adding = null })
        null -> Unit
        else -> AddPasswordServerSheet(adding!!, onDismiss = { adding = null })
    }
    selected?.let { id -> accounts.firstOrNull { it.id == id }?.let { a -> AccountSheet(a, onBrowseNextcloud, onDismiss = { selected = null }) } }
}

@Composable
private fun AccountSheet(a: ServerAccount, onBrowse: (String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    var confirm by remember { mutableStateOf(false) }
    GlassSheet(onDismiss) { close ->
        SheetTitle(a.displayName)
        Text(a.baseUrl, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary, modifier = Modifier.padding(bottom = SbSpace.m))
        if (a.type == AccountType.NEXTCLOUD) {
            SettingsRow("Browse files", subtitle = "Pick folders to stream into your feeds, or download files", icon = Icons.Outlined.FolderOpen, onClick = { close(); onBrowse(a.id) })
            RowDivider()
            SettingsRow(
                "Sync library",
                subtitle = "Likes, notes, tags, history and playlists in /Scrollback/scrollback-sync.json",
                icon = Icons.Outlined.CloudSync,
                trailing = { SbSwitch(a.syncEnabled) { v -> haptics.tick(); container.accounts.update(a.id) { it.copy(syncEnabled = v) }; if (v) container.syncSoon(force = true) } },
            )
            RowDivider()
            SettingsRow(
                "Sync now",
                subtitle = a.lastSyncError?.let { "Last attempt failed: $it" } ?: if (a.lastSyncAt > 0) "Last synced ${Fmt.relative(a.lastSyncAt)}" else "Never synced",
                icon = Icons.Outlined.Refresh,
                enabled = a.syncEnabled,
                onClick = {
                    container.appScope.launch(Dispatchers.Main) {
                        val err = container.sync.sync(a.id)
                        Toast.makeText(ctx, err?.let { "Sync failed: $it" } ?: "Synced", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        } else {
            SettingsRow(
                "Add music library",
                subtitle = "Index this server's songs into Music",
                icon = Icons.Outlined.LibraryMusic,
                onClick = { container.appScope.launch { container.addMusicServerSource(a.id) }; Toast.makeText(ctx, "Indexing…", Toast.LENGTH_SHORT).show(); close() },
            )
        }
        RowDivider()
        SettingsRow("Sign out", subtitle = "Forgets the credentials and this server's items. Nothing is deleted on the server.", icon = Icons.AutoMirrored.Outlined.Logout, onClick = { confirm = true })
        if (confirm) {
            ConfirmDialog(
                "Sign out of ${a.displayName}?", "Streams, likes and playlist entries for this server's items are removed from this device.", "Sign out",
                onConfirm = {
                    container.appScope.launch {
                        container.library.removeAccountSources(a.id)
                        container.servers.remove(a.id)
                        container.library.invalidateSources()
                    }
                    close()
                },
                onDismiss = { confirm = false },
                destructive = true,
            )
        }
    }
}

@Composable
private fun AddNextcloudSheet(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    GlassSheet({ job?.cancel(); onDismiss() }) { close ->
        SheetTitle("Nextcloud")
        Text(
            "You sign in on your Nextcloud's own page (2FA and SSO work). Scrollback receives an app password, stored encrypted on this phone; you can revoke it any time in Nextcloud → Settings → Security.",
            style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary,
        )
        Spacer(Modifier.height(SbSpace.m))
        OutlinedTextField(
            url, { url = it; error = null },
            label = { Text("Server address") }, placeholder = { Text("cloud.example.com") },
            singleLine = true, isError = error != null, supportingText = { error?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(), enabled = !waiting,
        )
        Spacer(Modifier.height(SbSpace.m))
        if (waiting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = SbColors.TextPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.padding(SbSpace.s))
                Text("Waiting for you to approve access in the browser…", color = SbColors.TextSecondary)
            }
        } else {
            Button(
                onClick = {
                    val base = ServerAccount.normaliseUrl(url)
                    waiting = true
                    job = scope.launch {
                        try {
                            val flow = container.servers.loginFlow
                            val start = withContext(Dispatchers.IO) { flow.start(base) }
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(start.loginUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            val result = withContext(Dispatchers.IO) { flow.poll(start) }
                            val account = container.servers.addNextcloud(result)
                            Toast.makeText(ctx, "Signed in as ${account.displayName}", Toast.LENGTH_SHORT).show()
                            close()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = when (e) {
                                is java.net.UnknownHostException -> "Server not found"
                                is app.scrollback.remote.HttpException -> e.message
                                else -> "Couldn't connect (${e.javaClass.simpleName})"
                            }
                            waiting = false
                        }
                    }
                },
                enabled = url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SbColors.Accent, contentColor = SbColors.OnAccent),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Sign in in browser") }
        }
    }
}

/** Username/password sign-in for Subsonic and Jellyfin. */
@Composable
private fun AddPasswordServerSheet(type: AccountType, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GlassSheet(onDismiss) { close ->
        SheetTitle(type.label)
        Text(
            if (type == AccountType.SUBSONIC) "Uses token + salt authentication; the password is stored encrypted on this phone and never sent in clear text."
            else "Signs in once and keeps only Jellyfin's access token, stored encrypted on this phone.",
            style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary,
        )
        Spacer(Modifier.height(SbSpace.m))
        OutlinedTextField(url, { url = it; error = null }, label = { Text("Server address") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(user, { user = it; error = null }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(pass, { pass = it; error = null }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = error != null, supportingText = { error?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(SbSpace.m))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { container.signInMusicServer(type, url, user.trim(), pass) }
                    busy = false
                    r.onSuccess { Toast.makeText(ctx, "Connected — indexing music…", Toast.LENGTH_SHORT).show(); close() }
                    r.onFailure { e ->
                        error = when (e) {
                            is java.net.UnknownHostException -> "Server not found"
                            is app.scrollback.remote.HttpException -> e.message
                            else -> e.message ?: e.javaClass.simpleName
                        }
                    }
                }
            },
            enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = SbColors.Accent, contentColor = SbColors.OnAccent),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "Connecting…" else "Connect") }
    }
}
