// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.URI

/**
 * v0.15: which addresses are on the local network. Android 17 (API 37) blocks connections to
 * devices on the local network (home servers, NAS, a LAN Jellyfin…) unless the app holds the
 * ACCESS_LOCAL_NETWORK runtime permission. Pure logic, unit-tested.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    const val DENIED_MESSAGE = "Local network access is off, so Scrollback can't reach servers on your home network. " +
        "Allow it in Settings → Apps → Scrollback → Permissions → Nearby devices, then try again."

    /** Host of a URL ("https://h:8096/x", "sftp://u@h:22", "smb://h/share") or a bare host / host:port. */
    fun hostOf(urlOrHost: String?): String? {
        val s = urlOrHost?.trim().orEmpty()
        if (s.isEmpty()) return null
        val withScheme = if ("://" in s) s else "x://$s"
        val host = runCatching { URI(withScheme).host }.getOrNull()
            ?: withScheme.substringAfter("://").substringAfter('@').substringBefore('/').let { h ->
                if (h.startsWith("[")) h.substringAfter('[').substringBefore(']') else h.substringBefore(':')
            }
        return host.removePrefix("[").removeSuffix("]").lowercase().ifBlank { null }
    }

    /** Private, link-local and unique-local addresses (what Android treats as the local network). */
    fun isLocalAddress(addr: ByteArray): Boolean {
        fun u(i: Int) = addr[i].toInt() and 0xff
        return when (addr.size) {
            4 -> u(0) == 10 ||
                (u(0) == 172 && u(1) in 16..31) ||
                (u(0) == 192 && u(1) == 168) ||
                (u(0) == 169 && u(1) == 254)
            16 -> {
                // IPv4-mapped (::ffff:a.b.c.d)
                if ((0 until 10).all { addr[it].toInt() == 0 } && u(10) == 0xff && u(11) == 0xff) {
                    isLocalAddress(addr.copyOfRange(12, 16))
                } else (u(0) and 0xfe) == 0xfc || (u(0) == 0xfe && (u(1) and 0xc0) == 0x80)
            }
            else -> false
        }
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")

    /** Parses a literal IPv4/IPv6 address without any DNS lookup; null for host names. */
    fun literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            return ByteArray(4) { parts[it].toInt().toByte() }
        }
        if (':' in host && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) {
            return runCatching { InetAddress.getByName(host.substringBefore('%')).address }.getOrNull()
        }
        return null
    }

    /**
     * Whether connecting to [host] needs local network access. Literal IPs and typical LAN names
     * (`nas.local`, single-label names) are decided offline; other names are resolved with
     * [resolve] (so a split-DNS name that points at a LAN address counts too).
     */
    fun isLocalHost(host: String, resolve: (String) -> List<ByteArray>): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost")) return false
        literal(h)?.let { return isLocalAddress(it) }
        if ('.' !in h || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        return runCatching { resolve(h) }.getOrDefault(emptyList()).any { isLocalAddress(it) }
    }

    fun dnsResolve(host: String): List<ByteArray> = InetAddress.getAllByName(host).map { it.address }
}

/**
 * Asks for Android 17's local-network permission when a server connection needs it, from
 * anywhere in the app (adding or testing a server, the first connection to a LAN address).
 * The UI ([app.scrollback.ui.components.LocalNetworkPrompt]) shows a short explanation, then the
 * system dialog, and completes the pending request.
 */
class LocalNetworkAccess(private val context: Context) {
    class Request(val host: String, val result: CompletableDeferred<Boolean>)

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()
    private val mutex = Mutex()

    /** Number of visible screens that can show the prompt. */
    @Volatile var uiAttached = 0
    /** Set after a "Not now"/denial so background connections don't keep asking this session. */
    @Volatile private var declinedThisSession = false

    fun granted(): Boolean = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(LocalNetwork.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when [urlOrHost] is on the local network and the permission is missing. */
    suspend fun missingFor(urlOrHost: String?): Boolean {
        if (granted()) return false
        val host = LocalNetwork.hostOf(urlOrHost) ?: return false
        return withContext(Dispatchers.IO) { LocalNetwork.isLocalHost(host, LocalNetwork::dnsResolve) }
    }

    /**
     * Makes sure [urls] can be reached. Returns false only when one of them is on the local
     * network and the user declined. [userAction] = the user just tapped Add/Test/Connect, so
     * ask again even after an earlier "Not now".
     */
    suspend fun ensure(vararg urls: String?, userAction: Boolean = true): Boolean {
        if (granted()) return true
        val host = urls.firstOrNull { missingFor(it) }?.let { LocalNetwork.hostOf(it) } ?: return true
        if (uiAttached <= 0 || (!userAction && declinedThisSession)) return false
        return mutex.withLock {
            if (granted()) return@withLock true
            val r = Request(host, CompletableDeferred())
            _pending.value = r
            val ok = try { withTimeoutOrNull(10 * 60_000L) { r.result.await() } ?: false } finally { _pending.value = null }
            if (!ok) declinedThisSession = true else declinedThisSession = false
            ok
        }
    }

    /** [ensure] that throws a readable error instead of returning false. */
    suspend fun require(vararg urls: String?, userAction: Boolean = true) {
        if (!ensure(*urls, userAction = userAction)) throw java.io.IOException(LocalNetwork.DENIED_MESSAGE)
    }
}
