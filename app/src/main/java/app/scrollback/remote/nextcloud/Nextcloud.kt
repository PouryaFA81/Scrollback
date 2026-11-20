// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.remote.nextcloud

import app.scrollback.accounts.ServerAccount
import app.scrollback.remote.HttpException
import app.scrollback.remote.webdav.DavParser
import kotlinx.coroutines.delay
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Nextcloud URL helpers. */
object NextcloudPaths {
    fun davRoot(a: ServerAccount) = "${a.baseUrl}/remote.php/dav/files/${DavParser.encodePath(a.userId)}"

    /** Full WebDAV URL for a user-relative path like "/Videos/TikTok". */
    fun davUrl(a: ServerAccount, path: String) = davRoot(a) + DavParser.encodePath("/" + path.trim('/')).let { if (it == "/") "" else it }

    /** User-relative path ("/Videos/a.mp4") for a WebDAV URL or href, or null if it is not under this account. */
    fun relativePath(a: ServerAccount, urlOrHref: String): String? {
        val marker = "/remote.php/dav/files/"
        val i = urlOrHref.indexOf(marker)
        if (i < 0) return null
        val rest = urlOrHref.substring(i + marker.length) // user/Videos/a.mp4
        return "/" + DavParser.decode(rest.substringAfter('/', "")).trimEnd('/')
    }

    fun preview(a: ServerAccount, fileId: String, w: Int = 480, h: Int = 854) =
        "${a.baseUrl}/index.php/core/preview?fileId=$fileId&x=$w&y=$h&a=1&mode=cover"

    fun authHeader(loginName: String, appPassword: String): Map<String, String> =
        mapOf("Authorization" to Credentials.basic(loginName, appPassword, Charsets.UTF_8), "OCS-APIRequest" to "true")
}

/**
 * Nextcloud Login Flow v2: the user signs in in their browser (2FA/SSO work), we poll and receive an
 * app password — the account password never passes through Scrollback.
 */
class LoginFlowV2(private val http: OkHttpClient) {
    data class Start(val loginUrl: String, val pollEndpoint: String, val token: String, val base: String)
    data class Result(val server: String, val loginName: String, val appPassword: String)

    fun start(baseUrl: String): Start {
        val req = Request.Builder().url("$baseUrl/index.php/login/v2").post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "This does not look like a Nextcloud server")
            val o = JSONObject(r.body.string())
            val poll = o.getJSONObject("poll")
            // Nextcloud builds these from overwrite.cli.url / its own idea of its address, which can be
            // http:// behind a proxy or a LAN/VPN-only host name. Keep them on the address that just worked.
            return Start(LoginUrls.align(o.getString("login"), baseUrl), LoginUrls.align(poll.getString("endpoint"), baseUrl), poll.getString("token"), baseUrl)
        }
    }

    /** Polls every 2 s until the user finishes (or [timeoutMs] passes). Short network drops are tolerated. */
    suspend fun poll(start: Start, timeoutMs: Long = 20 * 60_000L): Result {
        val until = System.currentTimeMillis() + timeoutMs
        var failures = 0
        while (System.currentTimeMillis() < until) {
            val req = Request.Builder().url(start.pollEndpoint).post(FormBody.Builder().add("token", start.token).build()).build()
            val res = try {
                http.newCall(req).execute().use { r -> if (r.code == 200) JSONObject(r.body.string()) else null }
                    .also { failures = 0 }
            } catch (e: java.io.IOException) {
                if (++failures >= 5) throw e
                null
            }
            if (res != null) return Result(res.getString("server").trimEnd('/'), res.getString("loginName"), res.getString("appPassword"))
            delay(2000)
        }
        throw HttpException(408, "Login timed out")
    }

    /** OCS user id + display name (the login name can be an e-mail, WebDAV paths need the id). */
    fun currentUser(baseUrl: String, auth: Map<String, String>): Pair<String, String> {
        val b = Request.Builder().url("$baseUrl/ocs/v2.php/cloud/user?format=json")
        auth.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "Could not read the Nextcloud user")
            val d = JSONObject(r.body.string()).getJSONObject("ocs").getJSONObject("data")
            val id = d.getString("id")
            return id to d.optString("display-name", id).ifBlank { id }
        }
    }
}

/** Keeps Login Flow v2 URLs on the address the user reached the server at (pure, unit-tested). */
object LoginUrls {
    /**
     * Moves a login/poll URL that Nextcloud built for another scheme or host name onto [base],
     * keeping the `/login/v2/...` part (and `/index.php` when it was used).
     */
    fun align(url: String, base: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val b = base.trimEnd('/').toHttpUrlOrNull() ?: return url
        if (u.host.equals(b.host, ignoreCase = true) && u.scheme == b.scheme && u.port == b.port) return url
        val path = u.encodedPath
        val i = path.indexOf("/login/v2")
        if (i < 0) return url
        val usedIndexPhp = path.substring(0, i).endsWith("/index.php")
        val baseStr = base.trimEnd('/')
        val prefix = if (usedIndexPhp && !baseStr.endsWith("/index.php")) "$baseStr/index.php" else baseStr
        return prefix + path.substring(i) + (u.encodedQuery?.let { "?$it" } ?: "")
    }

    /**
     * The addresses to save after sign-in: the one that worked (primary) and, when Nextcloud reports a
     * different host name for itself, that one as the alternate address (e.g. a LAN or VPN name).
     */
    fun accountAddresses(workingBase: String, reported: String): Pair<String, String?> {
        val w = workingBase.trimEnd('/')
        val t = w.toHttpUrlOrNull() ?: return w to null
        val r = reported.trimEnd('/').toHttpUrlOrNull() ?: return w to null
        if (r.host.equals(t.host, ignoreCase = true)) return w to null
        if (r.host == "localhost" || r.host.startsWith("127.") || r.host == "::1") return w to null
        return w to reported.trimEnd('/').removeSuffix("/index.php")
    }
}
