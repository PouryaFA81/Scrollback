// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Server kinds that can be probed without signing in. */
enum class ProbeKind(val path: String, val label: String) {
    /** status.php is public on every Nextcloud and says "installed": true. */
    NEXTCLOUD("/status.php", "Nextcloud"),
    /** Unauthenticated ping still answers with a `subsonic-response` (error 10/40), which proves the API. */
    SUBSONIC("/rest/ping.view?v=1.16.1&c=Scrollback&f=json", "Subsonic"),
    /** Public system info (server name and version). */
    JELLYFIN("/System/Info/Public", "Jellyfin"),
}

/**
 * Finds out what a server address really is: follows its redirects, checks that the expected API
 * answers at the end, and reports the canonical base URL to save. Never sends credentials and
 * never returns query strings or secrets.
 */
object ServerProbe {
    data class Result(
        val address: String,
        val reachable: Boolean,
        /** The expected server answered (Nextcloud status, Subsonic response, Jellyfin info). */
        val recognised: Boolean,
        /** Base URL after redirects, when it differs from [address] (null = same or unknown). */
        val canonical: String?,
        val hops: List<Hop>,
        val serverInfo: String?,
        val problem: String?,
    )

    /** A probe should answer in seconds: a VPN that drops packets would otherwise hit the 60 s read timeout. */
    private fun quick(http: OkHttpClient): OkHttpClient = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun probe(http: OkHttpClient, kind: ProbeKind, base: String): Result {
        val address = base.trimEnd('/')
        val trace = RedirectTrace()
        val url = (address + kind.path).toHttpUrlOrNull()
            ?: return Result(address, false, false, null, emptyList(), null, "That isn't a valid web address.")
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .tag(RedirectTrace::class.java, trace)
            .tag(DirectAddress::class.java, DirectAddress)
            .build()
        return try {
            quick(http).newCall(req).execute().use { r ->
                val body = runCatching { r.peekBody(256 * 1024).string() }.getOrDefault("")
                val info = recognise(kind, r.code, body)
                val finalBase = baseFrom(Redirects.safe(r.request.url), kind.path)
                val canonical = finalBase?.takeIf { it != address && trace.hops.isNotEmpty() }
                val problem = when {
                    info != null -> null
                    r.code == 401 || r.code == 403 -> "The address answers, but asks for a sign-in before showing the ${kind.label} API (HTTP ${r.code}). A proxy login page or an access rule may be in front of it."
                    r.code == 404 -> "Something answers here, but not ${kind.label} (HTTP 404). Check the path, e.g. a /nextcloud or /jellyfin sub-folder."
                    r.code >= 500 -> "The server answered with an error (HTTP ${r.code}). If a reverse proxy is in front, the app behind it may be down."
                    finalBase == null && trace.hops.isNotEmpty() -> "The redirects ended on a page that isn't the ${kind.label} API (${Redirects.safe(r.request.url)}), often a login portal or a captive network page."
                    else -> "Something answers here (HTTP ${r.code}), but it doesn't look like ${kind.label}."
                }
                Result(address, true, info != null, canonical, trace.hops.toList(), info, problem)
            }
        } catch (e: IOException) {
            Result(address, false, false, null, trace.hops.toList(), null, Explain.error(e, url.host, url.port))
        }
    }

    /** "https://x/nextcloud/status.php" with path "/status.php" -> "https://x/nextcloud". */
    fun baseFrom(finalUrl: String, probePath: String): String? {
        val p = probePath.substringBefore('?')
        val u = finalUrl.substringBefore('?')
        return if (u.endsWith(p)) u.removeSuffix(p).trimEnd('/') else null
    }

    /** A short description of the server when the body is what [kind] answers, else null. */
    fun recognise(kind: ProbeKind, code: Int, body: String): String? = runCatching {
        when (kind) {
            ProbeKind.NEXTCLOUD -> {
                if (code != 200) return null
                val o = JSONObject(body)
                if (!o.has("installed")) return null
                listOf(o.optString("productname").ifBlank { "Nextcloud" }, o.optString("versionstring")).filter { it.isNotBlank() }.joinToString(" ")
                    .let { if (o.optBoolean("maintenance")) "$it (maintenance mode)" else it }
            }
            ProbeKind.SUBSONIC -> {
                val r = JSONObject(body).optJSONObject("subsonic-response") ?: return null
                listOf(r.optString("type").ifBlank { "Subsonic" }, r.optString("serverVersion").ifBlank { r.optString("version") })
                    .filter { it.isNotBlank() }.joinToString(" ")
            }
            ProbeKind.JELLYFIN -> {
                if (code != 200) return null
                val o = JSONObject(body)
                if (!o.has("Version") || !o.has("Id")) return null
                listOf(o.optString("ProductName").ifBlank { "Jellyfin" }, o.optString("Version"), o.optString("ServerName").takeIf { it.isNotBlank() }?.let { "· $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            }
        }
    }.getOrNull()
}

/** Plain-words explanations for connection problems and redirect chains (no secrets). */
object Explain {
    fun error(e: Throwable, host: String, port: Int): String {
        val where = "$host:$port"
        return when (e) {
            is UnknownHostException -> "The name $host can't be found from this network. On a VPN, its DNS may not know this name; add the address that works there as the alternate address."
            is NoRouteToHostException -> "There is no route to $where from this network. It is probably only reachable at home or over your VPN."
            is ConnectException -> "Nothing answered at $where. The server may be down, or only reachable on another network (LAN or VPN)."
            is SocketTimeoutException -> "$where didn't answer in time. A VPN or firewall may be dropping the connection."
            is java.io.InterruptedIOException -> "$where didn't answer in time. A VPN or firewall may be dropping the connection."
            is SSLPeerUnverifiedException -> "The HTTPS certificate isn't valid for $host. Use the name the certificate was issued for."
            is SSLHandshakeException -> "The secure (HTTPS) connection to $host failed: the certificate isn't trusted by Android, or the server doesn't speak HTTPS on port $port."
            is SSLException -> "The secure (HTTPS) connection to $host broke off. Try http:// for a LAN-only address, or check the proxy."
            is UnknownServiceException -> "Android refused the connection to $host (${e.message})."
            is ProtocolException -> if (e.message?.startsWith("Too many redirects") == true)
                "The server keeps redirecting in a loop. This is usually a reverse-proxy rule or, on Nextcloud, the overwrite.cli.url / overwriteprotocol settings."
            else "The server answered with something that isn't valid HTTP."
            is HttpException -> when (e.code) {
                401, 403 -> "The server rejected the saved sign-in (HTTP ${e.code}). Sign out and in again, or check the account."
                in 1..99 -> "The server said: ${e.message?.substringAfter(": ")}" // Subsonic API error codes
                else -> "The server answered HTTP ${e.code}."
            }
            else -> "Couldn't connect to $where (${e.javaClass.simpleName})."
        }
    }

    fun hop(h: Hop): String = "${h.from} → ${h.to} (${h.code}, ${Redirects.codeName(h.code)})"

    /** One sentence about what the redirect chain means for the user. */
    fun chain(hops: List<Hop>): String? {
        if (hops.isEmpty()) return null
        val first = hops.first().from.toHttpUrlOrNull()
        val last = hops.last().to.toHttpUrlOrNull()
        if (first == null || last == null) return null
        return when {
            first.host.equals(last.host, true) && !first.isHttps && last.isHttps ->
                "The server upgrades http to https. That's fine: Scrollback keeps your sign-in across this hop, but saving the https address skips it."
            first.host.equals(last.host, true) && first.isHttps && !last.isHttps ->
                "The server sends you from https down to plain http. Scrollback won't send your sign-in over that hop; fix the proxy (on Nextcloud: overwriteprotocol = https)."
            first.host.equals(last.host, true) ->
                "The server moves you to another path on the same host. Saving the final address skips the redirect."
            else ->
                "The server redirects to a different host name, ${last.host}. Scrollback only sends your sign-in there if you use it as this server's address (on Nextcloud this usually comes from overwrite.cli.url or trusted_domains)."
        }
    }
}
