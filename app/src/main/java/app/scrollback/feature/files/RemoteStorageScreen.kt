package app.scrollback.feature.files

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddToPhotos
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ServerAccount
import app.scrollback.data.source.SourceType
import app.scrollback.feature.library.ListRow
import app.scrollback.files.FileNode
import app.scrollback.remote.fs.HostKeyMismatchException
import app.scrollback.remote.fs.RemoteEndpoint
import app.scrollback.remote.fs.RemoteSecret
import app.scrollback.remote.fs.UnknownHostKeyException
import app.scrollback.ui.LocalContainer
import app.scrollback.ui.components.ConfirmDialog
import app.scrollback.ui.components.EmptyState
import app.scrollback.ui.components.FloatingNavReserve
import app.scrollback.ui.components.GlassScreen
import app.scrollback.ui.components.LargeTitle
import app.scrollback.ui.components.SectionCard
import app.scrollback.ui.components.SegmentedToggle
import app.scrollback.ui.components.collapsedState
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files → Tools → Network storage: SFTP and SMB servers. Credentials are Keystore-encrypted. */
@Composable
fun RemoteStorageScreen(onBack: () -> Unit, onBrowse: (FileNode) -> Unit) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val remotes = container.accounts.accounts.collectAsStateWithLifecycle().value.filter { it.type.isNetworkStorage }
    val list = rememberLazyListState()
    val collapsed by list.collapsedState()
    var editing by remember { mutableStateOf<ServerAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ServerAccount?>(null) }
    var menuFor by remember { mutableStateOf<ServerAccount?>(null) }

    GlassScreen(title = "Network storage", collapsed = collapsed, onBack = onBack) { mod ->
        LazyColumn(
            modifier = mod,
            state = list,
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + FloatingNavReserve),
        ) {
            item { LargeTitle("Network storage", "SFTP and SMB (Windows, Samba, NAS) servers") }
            if (remotes.isEmpty()) {
                item { EmptyState(Icons.Outlined.Dns, "No servers yet", "Add an SFTP or SMB server to browse it like a folder and stream its videos and pictures in your feeds.") }
            }
            if (remotes.isNotEmpty()) item {
                SectionCard(title = "Servers") {
                    remotes.forEach { a ->
                        ListRow(
                            title = a.displayName,
                            subtitle = "${a.type.label} · ${container.remoteStorage.endpoint(a)?.label ?: a.baseUrl}" +
                                (a.hostKey?.let { "\n$it" } ?: ""),
                            leading = { Icon(Icons.Outlined.Dns, null, tint = SbColors.TextPrimary) },
                            onClick = { menuFor = a },
                        )
                    }
                }
            }
            item {
                SectionCard {
                    ListRow(
                        title = "Add server",
                        subtitle = "SFTP (password or private key) or SMB2/3 share",
                        leading = { Icon(Icons.Outlined.Add, null, tint = SbColors.TextPrimary) },
                        onClick = { adding = true },
                    )
                }
            }
        }
    }

    menuFor?.let { a ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = SbColors.SurfaceHigh,
            shape = app.scrollback.ui.theme.SbRadius.Large,
            title = { Text(a.displayName) },
            text = {
                Column {
                    ListRow("Browse", leading = { Icon(Icons.Outlined.FolderOpen, null) }, onClick = { menuFor = null; remoteRoot(a)?.let(onBrowse) })
                    ListRow("Add to feeds", subtitle = "Index the server's root folder as a source", leading = { Icon(Icons.Outlined.AddToPhotos, null) }, onClick = {
                        menuFor = null
                        val ep = container.remoteStorage.endpoint(a) ?: return@ListRow
                        container.appScope.launch {
                            container.library.addRemoteSource(if (a.type == AccountType.SMB) SourceType.SMB else SourceType.SFTP, ep.root, a.displayName, a.id)
                        }
                        Toast.makeText(ctx, "Added; scanning in the background", Toast.LENGTH_SHORT).show()
                    })
                    ListRow("Edit", leading = { Icon(Icons.Outlined.Edit, null) }, onClick = { menuFor = null; editing = a })
                    ListRow("Remove", leading = { Icon(Icons.Outlined.Delete, null, tint = SbColors.Danger) }, onClick = { menuFor = null; removing = a })
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } },
        )
    }
    if (adding || editing != null) {
        RemoteServerDialog(editing, onDismiss = { adding = false; editing = null })
    }
    removing?.let { a ->
        ConfirmDialog("Remove ${a.displayName}?", "Its saved credentials are deleted from this device. Feed sources from this server stop updating.", "Remove", destructive = true,
            onConfirm = { container.remoteStorage.disconnect(a.id); container.accounts.remove(a.id) }, onDismiss = { removing = null })
    }
}

/** Add or edit an SFTP/SMB server. Tests the connection and asks to trust an unknown SFTP host key. */
@Composable
private fun RemoteServerDialog(existing: ServerAccount?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val ep0 = existing?.let { container.remoteStorage.endpoint(it) }
    val secret0 = existing?.let { RemoteSecret.parse(container.accounts.secret(it.id)) }
    var type by remember { mutableStateOf(existing?.type ?: AccountType.SFTP) }
    var name by remember { mutableStateOf(existing?.displayName ?: "") }
    var host by remember { mutableStateOf(ep0?.host ?: "") }
    var port by remember { mutableStateOf(ep0?.port?.toString() ?: "") }
    var share by remember { mutableStateOf(ep0?.share ?: "") }
    var root by remember { mutableStateOf(ep0?.root?.takeIf { it != "/" } ?: "") }
    var user by remember { mutableStateOf(existing?.loginName ?: "") }
    var password by remember { mutableStateOf(secret0?.password ?: "") }
    var useKey by remember { mutableStateOf(secret0?.privateKey != null) }
    var key by remember { mutableStateOf(secret0?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(secret0?.passphrase ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var trustPrompt by remember { mutableStateOf<String?>(null) }
    var pinned by remember { mutableStateOf(existing?.hostKey) }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s -> String(s.readBytes(), Charsets.UTF_8) }
        }.getOrNull()?.takeIf { it.length < 64_000 }?.let { key = it.trim() } ?: run { if (uri != null) error = "Couldn't read that key file" }
    }

    fun build(): Pair<ServerAccount, RemoteSecret>? {
        val h = host.trim().removePrefix("sftp://").removePrefix("smb://").trimEnd('/')
        if (h.isBlank()) { error = "Enter the server address"; return null }
        if (type == AccountType.SMB && share.isBlank()) { error = "Enter the share name"; return null }
        val p = port.trim().toIntOrNull() ?: if (type == AccountType.SMB) 445 else 22
        val ep = RemoteEndpoint(type, h, p, share.trim().trim('/', '\\'), "/" + root.trim().trim('/'))
        val account = (existing ?: ServerAccount(type = type, baseUrl = "", loginName = "")).copy(
            type = type,
            baseUrl = ep.toUrl(),
            loginName = user.trim(),
            userId = user.trim(),
            displayName = name.trim().ifBlank { ep.label },
            hostKey = if (type == AccountType.SFTP) pinned else null,
        )
        val secret = if (type == AccountType.SFTP && useKey) RemoteSecret(privateKey = app.scrollback.remote.fs.KeyText.normalize(key), passphrase = passphrase.ifBlank { null })
        else RemoteSecret(password = password)
        return account to secret
    }

    fun testAndSave() {
        val (account, secret) = build() ?: return
        busy = true; error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.remoteStorage.connect(account, secret).use { it.list(container.remoteStorage.endpoint(account)!!.root) } }
            }
            busy = false
            result.onSuccess {
                container.remoteStorage.disconnect(account.id)
                container.accounts.upsert(account, secret.toJson())
                Toast.makeText(ctx, "Connected and saved", Toast.LENGTH_SHORT).show()
                onDismiss()
            }.onFailure { e ->
                when (e) {
                    is UnknownHostKeyException -> trustPrompt = e.fingerprint
                    is HostKeyMismatchException -> error = "The server's host key changed (${e.actual}). This can mean someone is intercepting the connection. Only continue if you reinstalled the server: remove this server and add it again."
                    is java.net.UnknownHostException -> error = "Server not found"
                    is java.net.ConnectException, is java.net.SocketTimeoutException -> error = "Couldn't connect. Check the address, port and that you're on the right network."
                    else -> error = when {
                        e.javaClass.simpleName.contains("UserAuth") || e.message?.contains("auth", true) == true -> "Sign-in failed. Check the user name and password or key."
                        e.message?.contains("No provider available") == true -> "Couldn't read that private key. Use Load key file… with an OpenSSH, PEM or PuTTY key (pasted keys can get mangled by the keyboard)."
                        e.javaClass.simpleName.contains("SMBApiException") -> "The server refused: ${e.message?.substringBefore("\n")?.take(120)}"
                        else -> e.message?.take(160) ?: e.javaClass.simpleName
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = SbColors.SurfaceHigh,
        shape = app.scrollback.ui.theme.SbRadius.Large,
        title = { Text(if (existing == null) "Add server" else "Edit server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (existing == null) {
                    SegmentedToggle(listOf("SFTP", "SMB"), if (type == AccountType.SFTP) 0 else 1, { type = if (it == 0) AccountType.SFTP else AccountType.SMB })
                    Spacer(Modifier.height(SbSpace.s))
                }
                Field("Name (optional)", name) { name = it }
                Row {
                    Field("Host or IP", host, Modifier.weight(1f), KeyboardType.Uri) { host = it }
                    Spacer(Modifier.width(8.dp))
                    Field(if (type == AccountType.SMB) "445" else "22", port, Modifier.width(84.dp), KeyboardType.Number, label = "Port") { port = it }
                }
                if (type == AccountType.SMB) Field("Share (e.g. media)", share) { share = it }
                Field(if (type == AccountType.SMB) "Folder in the share (optional)" else "Start folder (optional, e.g. /home/me)", root) { root = it }
                Field(if (type == AccountType.SMB) "User (DOMAIN\\user; empty = guest)" else "User", user) { user = it }
                if (type == AccountType.SFTP) {
                    SegmentedToggle(listOf("Password", "Private key"), if (useKey) 1 else 0, { useKey = it == 1 }, Modifier.padding(vertical = SbSpace.xs))
                }
                if (type == AccountType.SFTP && useKey) {
                    OutlinedTextField(
                        key, { key = it }, label = { Text("Private key (OpenSSH / PEM)") }, minLines = 2, maxLines = 4,
                        visualTransformation = if (key.isBlank()) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) { Text("Load key file…") }
                    Field("Key passphrase (if any)", passphrase, secret = true) { passphrase = it }
                } else {
                    Field("Password", password, secret = true) { password = it }
                }
                if (type == AccountType.SFTP && pinned != null) {
                    Text("Trusted host key: $pinned", style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary)
                }
                error?.let { Text(it, color = SbColors.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = SbSpace.s)) }
            }
        },
        confirmButton = {
            if (busy) CircularProgressIndicator(Modifier.padding(8.dp), color = SbColors.TextSecondary)
            else TextButton(onClick = { testAndSave() }) { Text("Connect & save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel", color = SbColors.TextSecondary) } },
    )

    trustPrompt?.let { fp ->
        ConfirmDialog(
            "Trust this server?",
            "First connection to $host. Its host key fingerprint is:\n\n$fp\n\nIf you can, compare it with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the server. Scrollback will refuse to connect if it ever changes.",
            "Trust",
            onConfirm = { pinned = fp; testAndSave() },
            onDismiss = { trustPrompt = null },
        )
    }
}

@Composable
private fun Field(
    placeholder: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    label: String? = null,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value, onChange, singleLine = true,
    label = { Text(label ?: placeholder) },
    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    modifier = modifier.padding(vertical = 2.dp),
)
