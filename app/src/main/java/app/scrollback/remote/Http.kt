// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote

import android.content.Context
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Maps custom media URIs (subsonic:…, jellyfin:…) to real HTTP URLs at request time, and adds
 * auth headers for known servers. Secrets therefore never end up in the database, in Coil's or
 * Media3's cache keys, or in logs: the DB stores stable URIs, credentials are applied on the wire.
 *
 * v0.14: every server can also have an alternate address (LAN, VPN, Tailscale…). Requests always
 * use the primary address in their URLs; [fallback] rewrites them to whichever address currently
 * answers. Credentials are attached per hop (after redirects too), but only to addresses that
 * belong to a configured server: its primary, its alternate, or the https upgrade of an http one.
 */
class RemoteAuth {
    /** Primary base URL -> headers to add (e.g. Nextcloud Basic auth, Jellyfin token). */
    private val headers = ConcurrentHashMap<String, Map<String, String>>()

    /** Alias base URL (alternate address) -> primary base URL. */
    private val aliases = ConcurrentHashMap<String, String>()

    /** Primary base URL -> its alternate-address route. */
    private val routes = ConcurrentHashMap<String, Route>()

    /** URI scheme -> resolver (scheme-specific part -> https URL). */
    private val resolvers = ConcurrentHashMap<String, (String) -> String?>()

    class Route(val primary: String, val alternate: String) {
        /** Set when the last request only worked on the alternate address. */
        @Volatile var preferAlternate: Boolean = false
    }

    fun setHeaders(prefix: String, h: Map<String, String>) { headers[prefix.trimEnd('/')] = h }

    fun clearHeaders(prefix: String) {
        val p = prefix.trimEnd('/')
        headers.remove(p)
        routes.remove(p)
        aliases.entries.removeIf { it.value == p }
    }

    /** Registers (or with null removes) the alternate address of a server. */
    fun setAlternate(primary: String, alternate: String?, preferAlternate: Boolean = false) {
        val p = primary.trimEnd('/')
        val a = alternate?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() && it != p }
        val existing = routes[p]
        if (a != null && existing != null && existing.alternate == a) return // keep the learned preference
        aliases.entries.removeIf { it.value == p }
        if (a == null) { routes.remove(p); return }
        aliases[a] = p
        routes[p] = Route(p, a).also { it.preferAlternate = preferAlternate }
    }

    fun route(primary: String): Route? = routes[primary.trimEnd('/')]

    fun setResolver(scheme: String, r: (String) -> String?) { resolvers[scheme] = r }

    /** The configured primary base URL that [url] belongs to, if any. */
    fun primaryFor(url: String): String? {
        headers.keys.firstOrNull { under(url, it) }?.let { return it }
        aliases.entries.firstOrNull { under(url, it.key) }?.let { return it.value }
        // The https upgrade of a server configured with http:// is the same server.
        if (url.startsWith("https://")) {
            val u = url.toHttpUrlOrNull() ?: return null
            for (p in headers.keys + aliases.keys) {
                if (!p.startsWith("http://")) continue
                val pu = p.toHttpUrlOrNull() ?: continue
                if (pu.host.equals(u.host, ignoreCase = true) && u.encodedPath.startsWith(pu.encodedPath.trimEnd('/'))) {
                    return aliases[p] ?: p
                }
            }
        }
        return null
    }

    fun headersFor(url: String): Map<String, String> = primaryFor(url)?.let { headers[it] }.orEmpty()

    /** True when [url] is an address of a configured server (so credentials may follow a redirect there). */
    fun isTrusted(url: HttpUrl): Boolean = primaryFor(url.toString()) != null

    /** Returns a fetchable URL for [uri], or the input unchanged when it is already http(s)/content. */
    fun resolve(uri: String): String {
        val scheme = uri.substringBefore(':', "")
        if (scheme == "http" || scheme == "https" || scheme == "content" || scheme == "file") return uri
        return resolvers[scheme]?.invoke(uri) ?: uri
    }

    fun isRemote(uri: String): Boolean {
        val scheme = uri.substringBefore(':', "")
        return scheme == "http" || scheme == "https" || resolvers.containsKey(scheme)
    }

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val extra = headersFor(req.url.toString())
        if (extra.isEmpty()) return@Interceptor chain.proceed(req)
        val b = req.newBuilder()
        extra.forEach { (k, v) -> if (req.header(k) == null) b.header(k, v) }
        chain.proceed(b.build())
    }

    /**
     * Primary <-> alternate address fallback. Tries the address that worked last first; on a
     * connection failure (or a 502/503/504 from a proxy for read-only requests) tries the other.
     */
    val fallback = Interceptor { chain ->
        val req = chain.request()
        if (req.tag(DirectAddress::class.java) != null) return@Interceptor chain.proceed(req)
        val url = req.url.toString()
        val route = routeFor(url) ?: return@Interceptor chain.proceed(req)
        val from = if (under(url, route.primary)) route.primary else route.alternate
        val order = if (route.preferAlternate) listOf(route.alternate, route.primary) else listOf(route.primary, route.alternate)
        var lastError: IOException? = null
        for ((i, base) in order.withIndex()) {
            val attempt = req.newBuilder().url(base + url.removePrefix(from)).build()
            try {
                val r = chain.proceed(attempt)
                val retryable = i == 0 && r.code in RETRY_CODES && req.method in IDEMPOTENT
                if (retryable) { r.close(); continue }
                route.preferAlternate = base == route.alternate
                return@Interceptor r
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                lastError = e
            }
        }
        throw lastError ?: IOException("Neither address answered")
    }

    private fun routeFor(url: String): Route? =
        routes.values.firstOrNull { under(url, it.primary) || under(url, it.alternate) }

    private fun under(url: String, prefix: String) = url == prefix || url.startsWith("$prefix/") || url.startsWith("$prefix?")

    private companion object {
        val RETRY_CODES = setOf(502, 503, 504)
        val IDEMPOTENT = setOf("GET", "HEAD", "PROPFIND")
    }
}

/** Request tag: send exactly to this URL (no alternate-address fallback). Used by "Test connection". */
object DirectAddress

object Http {
    fun client(context: Context, auth: RemoteAuth): OkHttpClient = builder(auth)
        .cache(Cache(File(context.cacheDir, "http"), 64L * 1024 * 1024))
        .build()

    /**
     * OkHttp's own redirect handling is off: it drops credentials on any host or scheme change,
     * so an http-to-https upgrade lost the sign-in, and it turns a 301/302 POST into a GET.
     * [RedirectInterceptor] replaces it (see [Redirects] for the rules).
     */
    fun builder(auth: RemoteAuth): OkHttpClient.Builder = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(auth.fallback)
        .addInterceptor(RedirectInterceptor(auth::isTrusted))
        .addInterceptor(auth.interceptor)
        .addInterceptor(UserAgent)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)

    private object UserAgent : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Scrollback (Android)").build())
    }
}

/** Thrown for non-2xx responses; message never contains credentials. */
class HttpException(val code: Int, message: String) : java.io.IOException("HTTP $code: $message")
