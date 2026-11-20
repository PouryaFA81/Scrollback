// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.ProtocolException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Redirect rules (v0.14). OkHttp's built-in follower is switched off because it
 *  - removes the Authorization header whenever scheme, host or port change, so a plain
 *    `http://` server address that redirects to `https://` lost the sign-in (401 after the hop);
 *  - turns a 301/302 POST into a GET (Nextcloud Login Flow v2 start/poll then fail).
 *
 * Here:
 *  - 301, 302, 303, 307 and 308 are followed, at most [MAX_HOPS] times;
 *  - 307/308 always keep the method and body; WebDAV verbs (PROPFIND, PUT, MKCOL, MOVE…) keep
 *    them for every code; a 301/302 POST keeps them when only scheme/host/port change (a proxy
 *    canonicalisation), otherwise becomes a GET like browsers do; 303 becomes GET;
 *  - credentials (Authorization, Jellyfin tokens, cookies) set on the request survive a hop only
 *    to the same host (never on an https-to-http downgrade) or to an address the user configured.
 *    Configured-server credentials are re-applied per hop by [RemoteAuth.interceptor].
 */
object Redirects {
    const val MAX_HOPS = 10
    val CODES = setOf(301, 302, 303, 307, 308)
    private val KEEP_METHOD = setOf("PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "REPORT", "SEARCH", "PUT", "DELETE", "PATCH")
    val CREDENTIAL_HEADERS = listOf("Authorization", "X-Emby-Token", "X-Emby-Authorization", "X-MediaBrowser-Token", "Cookie")

    fun keepsMethod(code: Int, method: String, from: HttpUrl, to: HttpUrl): Boolean = when {
        method == "GET" || method == "HEAD" -> true
        code == 307 || code == 308 -> true
        code == 303 -> false
        method in KEEP_METHOD -> true
        else -> from.encodedPath.trimEnd('/') == to.encodedPath.trimEnd('/') && from.encodedQuery == to.encodedQuery
    }

    /** Same server for credential purposes: same host name, and not a downgrade to plain http. */
    fun sameServer(from: HttpUrl, to: HttpUrl): Boolean =
        from.host.equals(to.host, ignoreCase = true) && !(from.isHttps && !to.isHttps)

    /** The next request for a redirect [response] to [request], or null when it is not a followable redirect. */
    fun followUp(response: Response, request: Request, trusted: (HttpUrl) -> Boolean): Request? {
        if (response.code !in CODES) return null
        val location = response.header("Location") ?: return null
        val to = request.url.resolve(location) ?: return null
        val b = request.newBuilder().url(to)
        if (keepsMethod(response.code, request.method, request.url, to)) {
            if (request.body?.isOneShot() == true) return null
        } else {
            b.method(if (request.method == "HEAD") "HEAD" else "GET", null)
            b.removeHeader("Content-Type").removeHeader("Content-Length").removeHeader("Transfer-Encoding")
        }
        if (!sameServer(request.url, to) && !trusted(to)) CREDENTIAL_HEADERS.forEach { b.removeHeader(it) }
        return b.build()
    }

    /** "https://host:port/path" without query or credentials, safe to show and log. */
    fun safe(url: HttpUrl): String = buildString {
        append(url.scheme).append("://").append(url.host)
        if (url.port != HttpUrl.defaultPort(url.scheme)) append(':').append(url.port)
        append(url.encodedPath)
    }

    fun codeName(code: Int) = when (code) {
        301 -> "moved permanently"
        302 -> "found, temporary"
        303 -> "see other"
        307 -> "temporary redirect"
        308 -> "permanent redirect"
        else -> "HTTP $code"
    }
}

/** One followed redirect, without query strings (they can carry Subsonic tokens). */
data class Hop(val code: Int, val from: String, val to: String)

/** Request tag that collects the redirect chain of a call (used by "Test connection"). */
class RedirectTrace { val hops: MutableList<Hop> = CopyOnWriteArrayList() }

class RedirectInterceptor(
    private val trusted: (HttpUrl) -> Boolean,
    private val maxHops: Int = Redirects.MAX_HOPS,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val trace = request.tag(RedirectTrace::class.java)
        var response = chain.proceed(request)
        var hops = 0
        while (true) {
            val next = Redirects.followUp(response, request, trusted) ?: return response
            if (++hops > maxHops) {
                response.close()
                throw ProtocolException("Too many redirects ($maxHops), last to ${Redirects.safe(next.url)}")
            }
            trace?.hops?.add(Hop(response.code, Redirects.safe(request.url), Redirects.safe(next.url)))
            response.close()
            request = next
            response = chain.proceed(request)
        }
    }
}
