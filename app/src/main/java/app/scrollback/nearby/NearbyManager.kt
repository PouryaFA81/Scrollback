// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.nearby

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.scrollback.accounts.CredentialStore
import app.scrollback.files.FileNode
import app.scrollback.files.FileRepository
import app.scrollback.files.Mime
import app.scrollback.files.NodeKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class NearbySettings(val alias: String, val quickSave: Boolean, val saveTree: String?, val saveTreeName: String?, val hasPin: Boolean)

/** Something queued to send (from Files, the feed or a share intent). */
data class Outgoing(val name: String, val size: Long, val mime: String, val uri: Uri? = null, val node: FileNode? = null)

enum class TransferState { WAITING, RUNNING, DONE, FAILED, CANCELLED }

data class TransferFile(val name: String, val size: Long, val done: Long = 0, val failed: String? = null, val location: String? = null, val mime: String = "")

data class Transfer(
    val incoming: Boolean, val peer: String, val files: List<TransferFile>,
    val state: TransferState = TransferState.WAITING, val message: String? = null, val sessionId: String? = null,
) {
    val total: Long get() = files.sumOf { it.size }
    val done: Long get() = files.sumOf { it.done }
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val active: Boolean get() = state == TransferState.WAITING || state == TransferState.RUNNING
}

/** An offer waiting for Accept/Decline in the UI. */
data class IncomingOffer(val sessionId: String, val sender: Peer, val files: List<FileOffer>, internal val answer: CompletableFuture<Set<String>?>)

data class TextMessage(val from: String, val text: String)
data class Received(val name: String, val uri: String, val mime: String, val at: Long)

data class NearbyState(
    val needsPermission: Boolean = false,
    val running: Boolean = false, val error: String? = null, val scanning: Boolean = false,
    val peers: List<Peer> = emptyList(), val self: DeviceInfo? = null, val addresses: List<String> = emptyList(),
)

/**
 * Nearby sharing (LocalSend v2). The server and discovery only run while a screen holds the
 * manager ([acquire]/[release]) or a transfer is active; transfers run under [NearbyService].
 */
class NearbyManager(
    private val context: Context,
    http: OkHttpClient,
    private val credentials: CredentialStore,
    private val files: FileRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("nearby", Context.MODE_PRIVATE)
    private val identity by lazy { Identity.loadOrCreate(File(context.filesDir, "nearby")) }
    private val client by lazy { NearbyClient(http, identity) }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<NearbySettings> = _settings.asStateFlow()
    private val _state = MutableStateFlow(NearbyState())
    val state: StateFlow<NearbyState> = _state.asStateFlow()
    private val _outgoing = MutableStateFlow<Transfer?>(null)
    val outgoing: StateFlow<Transfer?> = _outgoing.asStateFlow()
    private val _incoming = MutableStateFlow<Transfer?>(null)
    val incoming: StateFlow<Transfer?> = _incoming.asStateFlow()
    private val _offer = MutableStateFlow<IncomingOffer?>(null)
    val offer: StateFlow<IncomingOffer?> = _offer.asStateFlow()
    private val _text = MutableStateFlow<TextMessage?>(null)
    val text: StateFlow<TextMessage?> = _text.asStateFlow()
    private val _received = MutableStateFlow<List<Received>>(emptyList())
    val received: StateFlow<List<Received>> = _received.asStateFlow()
    private val _pending = MutableStateFlow<List<Outgoing>>(emptyList())
    val pending: StateFlow<List<Outgoing>> = _pending.asStateFlow()

    private var server: NearbyServer? = null
    private var discovery: Discovery? = null
    private var lock: WifiManager.MulticastLock? = null
    private var holders = 0
    private var sendJob: Job? = null
    @Volatile private var sendCancelled = false

    val active: Boolean get() = _outgoing.value?.active == true || _incoming.value?.active == true

    // ---- settings

    private fun loadSettings() = NearbySettings(
        alias = prefs.getString("alias", null)?.takeIf { it.isNotBlank() } ?: defaultAlias(),
        quickSave = prefs.getBoolean("quickSave", false),
        saveTree = prefs.getString("saveTree", null),
        saveTreeName = prefs.getString("saveTreeName", null),
        hasPin = credentials.get(PIN_ID) != null,
    )

    private fun defaultAlias() = (Build.MODEL ?: "Scrollback").replaceFirstChar { it.uppercase() }

    fun setAlias(a: String) { prefs.edit().putString("alias", a.trim().take(40)).apply(); _settings.value = loadSettings(); refreshSelf(); discovery?.announce() }
    fun setQuickSave(v: Boolean) { prefs.edit().putBoolean("quickSave", v).apply(); _settings.value = loadSettings() }
    fun setPin(pin: String?) { if (pin.isNullOrBlank()) credentials.remove(PIN_ID) else credentials.put(PIN_ID, pin.trim()); _settings.value = loadSettings() }
    fun setSaveTree(uri: Uri?, name: String?) {
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.edit().putString("saveTree", uri?.toString()).putString("saveTreeName", name).apply(); _settings.value = loadSettings()
    }

    val fingerprint: String get() = identity.fingerprint

    private fun selfInfo() = DeviceInfo(
        alias = _settings.value.alias, fingerprint = identity.fingerprint, deviceModel = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() },
        deviceType = DeviceType.MOBILE, port = LocalSend.PORT, protocol = "https", download = false,
    )

    private fun refreshSelf() = _state.update { it.copy(self = if (it.running) selfInfo() else it.self) }

    // ---- lifecycle

    @Synchronized fun acquire() { holders++; startIfNeeded() }
    @Synchronized fun release() { holders = (holders - 1).coerceAtLeast(0); stopIfIdle() }

    @Synchronized private fun startIfNeeded() {
        if (server != null) return
        scope.launch(Dispatchers.IO) { startNow() }
    }

    /** Android 17+: LAN sockets need ACCESS_LOCAL_NETWORK (runtime, NEARBY_DEVICES group). */
    fun hasLocalNetworkAccess(): Boolean = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(LOCAL_NETWORK) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** After the permission is granted: rebind sockets and search again. */
    fun restart() = scope.launch(Dispatchers.IO) {
        synchronized(this@NearbyManager) {
            if (active) return@launch
            discovery?.shutdown(); discovery = null
            server?.stop(); server = null
            runCatching { lock?.release() }; lock = null
        }
        startNow()
    }

    @Synchronized private fun startNow() {
        if (server != null || holders == 0 && !active) return
        _state.update { it.copy(needsPermission = !hasLocalNetworkAccess()) }
        try {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            lock = wifi?.createMulticastLock("scrollback-nearby")?.apply { setReferenceCounted(false); acquire() }
            val s = NearbyServer(::selfInfo, identity, LocalSend.PORT, callbacks)
            s.startServer()
            server = s
            discovery = Discovery(::selfInfo, client) { p -> addPeer(p) }.also { it.start() }
            _state.update { it.copy(running = true, error = null, self = selfInfo(), addresses = Discovery.localAddresses()) }
            if (hasLocalNetworkAccess()) refresh(scan = true)
        } catch (e: Exception) {
            lock?.release(); lock = null
            _state.update { it.copy(running = false, error = if (e is java.net.BindException) "Port ${LocalSend.PORT} is in use (is another LocalSend app open?)" else e.message ?: "Can't start") }
        }
    }

    @Synchronized private fun stopIfIdle() {
        if (holders > 0 || active) return
        discovery?.shutdown(); discovery = null
        server?.stop(); server = null
        runCatching { lock?.release() }; lock = null
        _state.update { it.copy(running = false, scanning = false) }
    }

    private fun addPeer(p: Peer) = _state.update { st ->
        val rest = st.peers.filterNot { it.info.fingerprint == p.info.fingerprint }
        // Prefer an https route if we already know one.
        st.copy(peers = (rest + p).sortedBy { it.info.alias.lowercase() })
    }

    /** Re-announce and (optionally) scan the subnet over HTTP. */
    fun refresh(scan: Boolean = true) {
        val d = discovery ?: return startIfNeeded()
        _state.update { it.copy(peers = emptyList(), addresses = Discovery.localAddresses()) }
        d.announce()
        if (scan && !_state.value.scanning) scope.launch(Dispatchers.IO) {
            _state.update { it.copy(scanning = true) }
            runCatching { d.scan() }
            _state.update { it.copy(scanning = false) }
        }
    }

    suspend fun addByAddress(host: String): Peer? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val (h, port) = host.trim().let { s -> if (s.count { it == ':' } == 1) s.substringBefore(':') to (s.substringAfter(':').toIntOrNull() ?: LocalSend.PORT) else s to LocalSend.PORT }
        val d = discovery ?: Discovery(::selfInfo, client) { addPeer(it) }
        d.probe(h, port)
    }

    // ---- outgoing

    fun queue(items: List<Outgoing>) = _pending.update { cur -> (cur + items).distinctBy { it.uri?.toString() ?: it.node?.key ?: it.name } }
    fun unqueue(item: Outgoing) = _pending.update { it - item }
    fun clearQueue() { _pending.value = emptyList() }

    fun queueUris(uris: List<Uri>) = queue(uris.mapNotNull { describe(it) })
    fun queueNodes(nodes: List<FileNode>) = queue(nodes.filter { !it.isDir }.map { Outgoing(it.name, it.size, it.mime.ifBlank { Mime.guess(it.name) }, node = it) })

    private fun describe(uri: Uri): Outgoing? = runCatching {
        if (uri.scheme == "file") { val f = File(uri.path!!); return@runCatching Outgoing(f.name, f.length(), Mime.guess(f.name), uri) }
        var name = uri.lastPathSegment ?: "file"; var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
        }
        if (size < 0) size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        Outgoing(name, size, context.contentResolver.getType(uri) ?: Mime.guess(name), uri)
    }.getOrNull()

    private suspend fun toSendItem(o: Outgoing): SendItem {
        val node = o.node
        if (node != null) {
            if (node.kind == NodeKind.REMOTE) {
                val f = files.fetchToCache(node) ?: throw IOException("Couldn't download ${node.name}")
                return SendItem(o.name, f.length(), o.mime) { FileInputStream(f) }
            }
            if (node.kind == NodeKind.RAW) { val f = File(node.id); return SendItem(o.name, f.length(), o.mime) { FileInputStream(f) } }
            return SendItem(o.name, node.size, o.mime) { context.contentResolver.openInputStream(node.uri) ?: throw IOException("Can't read ${o.name}") }
        }
        val uri = o.uri ?: throw IOException("Nothing to send")
        if (uri.scheme == "file") { val f = File(uri.path!!); return SendItem(o.name, f.length(), o.mime) { FileInputStream(f) } }
        if (o.size >= 0) return SendItem(o.name, o.size, o.mime) { context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read ${o.name}") }
        // Unknown size (some providers): copy first, LocalSend needs the size up front.
        val tmp = File(context.cacheDir, "nearby-out/${System.nanoTime()}/${SafeName.clean(o.name)}").apply { parentFile?.mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: throw IOException("Can't read ${o.name}")
        return SendItem(o.name, tmp.length(), o.mime) { FileInputStream(tmp) }
    }

    /** Sends the queue to [peer]. [pin] after a 401. Returns immediately; observe [outgoing]. */
    fun send(peer: Peer, pin: String? = null) {
        val items = _pending.value
        if (items.isEmpty() || _outgoing.value?.active == true) return
        sendCancelled = false
        _outgoing.value = Transfer(false, peer.info.alias, items.map { TransferFile(it.name, it.size.coerceAtLeast(0), mime = it.mime) }, TransferState.WAITING, "Waiting for ${peer.info.alias} to accept…")
        NearbyService.start(context)
        sendJob = scope.launch(Dispatchers.IO) {
            try {
                val prepared = items.map { toSendItem(it) }
                _outgoing.update { t -> t?.copy(files = prepared.map { TransferFile(it.name, it.size, mime = it.mime) }) }
                var started = false
                val sent = client.send(selfInfo(), peer, prepared, pin,
                    onSession = { sid -> _outgoing.update { it?.copy(sessionId = sid, state = TransferState.RUNNING, message = null) } },
                    onProgress = { i, n ->
                        if (!started) started = true
                        _outgoing.update { t -> t?.copy(files = t.files.mapIndexed { j, f -> if (j == i) f.copy(done = n) else f }) }
                    },
                    isCancelled = { sendCancelled })
                _outgoing.update { it?.copy(state = TransferState.DONE, message = if (sent) "Sent to ${peer.info.alias}" else "${peer.info.alias} didn't need any files") }
                _pending.value = emptyList()
            } catch (e: SendException) {
                _outgoing.update { it?.copy(state = TransferState.FAILED, message = Status.message(e.code), sessionId = if (e.code == 401) "pin" else it.sessionId) }
            } catch (e: Exception) {
                _outgoing.update { it?.copy(state = if (sendCancelled) TransferState.CANCELLED else TransferState.FAILED, message = if (sendCancelled) "Cancelled" else friendly(e)) }
            } finally {
                transferEnded()
            }
        }
    }

    fun cancelSend() { sendCancelled = true; sendJob?.cancel(); _outgoing.update { if (it?.active == true) it.copy(state = TransferState.CANCELLED, message = "Cancelled") else it }; transferEnded() }
    fun dismissOutgoing() { if (_outgoing.value?.active != true) _outgoing.value = null }

    private fun friendly(e: Exception): String = when {
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "Can't reach the device. Is it on the same Wi-Fi?"
        e is java.net.SocketTimeoutException -> "No answer (timed out)"
        e is javax.net.ssl.SSLException && (e.cause is java.security.cert.CertificateException || e.message?.contains("fingerprint") == true) -> "Security check failed: the device's certificate doesn't match"
        else -> e.message ?: "Failed"
    }

    // ---- incoming

    fun answerOffer(accept: Boolean) {
        val o = _offer.value ?: return
        _offer.value = null
        o.answer.complete(if (accept) o.files.map { it.id }.toSet() else null)
    }

    fun cancelReceive() { server?.cancelSession(); _incoming.update { if (it?.active == true) it.copy(state = TransferState.CANCELLED, message = "Stopped") else it }; transferEnded() }
    fun dismissIncoming() { if (_incoming.value?.active != true) _incoming.value = null }
    fun dismissText() { _text.value = null }

    private fun transferEnded() = scope.launch { stopIfIdleLater() }
    private fun stopIfIdleLater() { synchronized(this) { stopIfIdle() } }

    private val callbacks = object : ReceiveCallbacks {
        override fun pin(): String? = credentials.get(PIN_ID)
        override fun onRegister(peer: Peer) = addPeer(peer)

        override fun onOffer(sessionId: String, sender: Peer, files: List<FileOffer>): Set<String>? {
            addPeer(sender)
            val ids: Set<String>? = if (_settings.value.quickSave) files.map { it.id }.toSet() else {
                if (_offer.value != null) return null
                val f = CompletableFuture<Set<String>?>()
                _offer.value = IncomingOffer(sessionId, sender, files, f)
                val r = runCatching { f.get(120, TimeUnit.SECONDS) }.getOrNull()
                if (_offer.value?.sessionId == sessionId) _offer.value = null
                r
            }
            if (ids.isNullOrEmpty()) return null
            _incoming.value = Transfer(true, sender.info.alias, files.filter { it.id in ids }.map { TransferFile(SafeName.clean(it.fileName), it.size, mime = it.fileType) }, TransferState.RUNNING, sessionId = sessionId)
            NearbyService.start(context)
            return ids
        }

        override fun onText(sender: Peer, text: String) { _text.value = TextMessage(sender.info.alias, text) }

        override fun openTarget(file: FileOffer): ReceiveTarget = target(SafeName.clean(file.fileName), file.fileType)

        private fun update(sid: String, file: FileOffer, f: (TransferFile) -> TransferFile) = _incoming.update { t ->
            if (t?.sessionId != sid) t else t.copy(files = t.files.map { if (it.name == SafeName.clean(file.fileName) && it.size == file.size) f(it) else it })
        }
        override fun onProgress(sessionId: String, file: FileOffer, received: Long) = update(sessionId, file) { it.copy(done = received) }
        override fun onSaved(sessionId: String, file: FileOffer, location: String) {
            update(sessionId, file) { it.copy(done = file.size, location = location) }
            _received.update { listOf(Received(SafeName.clean(file.fileName), location, file.fileType, System.currentTimeMillis())) + it.take(49) }
        }
        override fun onFailed(sessionId: String, file: FileOffer, message: String) = update(sessionId, file) { it.copy(failed = message) }
        override fun onSessionEnd(sessionId: String, complete: Boolean) {
            _incoming.update { t ->
                if (t?.sessionId != sessionId) t else {
                    val failed = t.files.count { it.failed != null }
                    when {
                        complete && failed == 0 -> t.copy(state = TransferState.DONE, message = "Saved ${t.files.size} file${if (t.files.size == 1) "" else "s"} to ${saveLabel()}")
                        complete -> t.copy(state = TransferState.FAILED, message = "$failed file(s) failed")
                        else -> t.copy(state = TransferState.CANCELLED, message = "${t.peer} cancelled the transfer")
                    }
                }
            }
            transferEnded()
        }
    }

    fun saveLabel(): String = _settings.value.saveTreeName ?: "Download/Scrollback"

    /** Default: MediaStore Download/Scrollback (pending until complete); or the chosen folder. */
    private fun target(name: String, mime: String): ReceiveTarget {
        val resolver = context.contentResolver
        val tree = _settings.value.saveTree?.let(Uri::parse)
        val type = mime.ifBlank { Mime.guess(name) }.ifBlank { "application/octet-stream" }
        if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, type, name) ?: throw IOException("Can't create $name")
            val out = resolver.openOutputStream(doc, "w") ?: throw IOException("Can't write $name")
            return object : ReceiveTarget {
                override val out: OutputStream = out
                override fun commit() = doc.toString()
                override fun abort() { runCatching { out.close() }; runCatching { DocumentsContract.deleteDocument(resolver, doc) } }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Scrollback")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw IOException("Can't create $name")
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Can't write $name")
        return object : ReceiveTarget {
            override val out: OutputStream = out
            override fun commit(): String { resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null); return uri.toString() }
            override fun abort() { runCatching { out.close() }; runCatching { resolver.delete(uri, null, null) } }
        }
    }

    companion object {
        private const val PIN_ID = "nearby:pin"
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
