package app.scrollback.remote.nextcloud

import app.scrollback.accounts.ServerAccount
import app.scrollback.remote.HttpException
import app.scrollback.remote.webdav.DavParser
import kotlinx.coroutines.delay
import okhttp3.Credentials
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
    data class Start(val loginUrl: String, val pollEndpoint: String, val token: String)
    data class Result(val server: String, val loginName: String, val appPassword: String)

    fun start(baseUrl: String): Start {
        val req = Request.Builder().url("$baseUrl/index.php/login/v2").post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "This does not look like a Nextcloud server")
            val o = JSONObject(r.body.string())
            val poll = o.getJSONObject("poll")
            return Start(o.getString("login"), poll.getString("endpoint"), poll.getString("token"))
        }
    }

    /** Polls every 2 s until the user finishes (or [timeoutMs] passes). */
    suspend fun poll(start: Start, timeoutMs: Long = 20 * 60_000L): Result {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val req = Request.Builder().url(start.pollEndpoint).post(FormBody.Builder().add("token", start.token).build()).build()
            val res = http.newCall(req).execute().use { r ->
                if (r.code == 200) JSONObject(r.body.string()) else null
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
