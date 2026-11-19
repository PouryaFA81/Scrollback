package app.scrollback.remote.webdav

import app.scrollback.remote.HttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Minimal WebDAV client. Auth is added by the shared OkHttp interceptor
 * ([app.scrollback.remote.RemoteAuth]), so this class never sees the password.
 */
class WebDavClient(private val http: OkHttpClient) {
    private val xml = "application/xml; charset=utf-8".toMediaType()

    /** PROPFIND [url] with Depth 1 (the folder itself + children). The folder itself is dropped. */
    fun list(url: String): List<DavEntry> {
        val req = Request.Builder().url(url)
            .method("PROPFIND", DavParser.PROPFIND_BODY.toRequestBody(xml))
            .header("Depth", "1")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code != 207 && !r.isSuccessful) throw HttpException(r.code, "PROPFIND failed")
            val all = DavParser.parse(r.body.byteStream())
            val selfPath = url.toHttpPath().trimEnd('/')
            return all.filterNot { it.href.trimEnd('/') == selfPath || DavParser.decode(it.href.trimEnd('/')) == DavParser.decode(selfPath) }
        }
    }

    /** Returns the response for a GET (caller closes). Null on 404. */
    fun get(url: String, range: LongRange? = null): Response? {
        val b = Request.Builder().url(url)
        if (range != null) b.header("Range", "bytes=${range.first}-${range.last}")
        val r = http.newCall(b.build()).execute()
        if (r.code == 404) { r.close(); return null }
        if (!r.isSuccessful) { val c = r.code; r.close(); throw HttpException(c, "GET failed") }
        return r
    }

    /** PUT bytes. With [ifMatch] the write fails (412) if someone else changed the file meanwhile. */
    fun put(url: String, bytes: ByteArray, mime: String, ifMatch: String? = null, ifNoneMatch: Boolean = false): String? {
        val b = Request.Builder().url(url).put(bytes.toRequestBody(mime.toMediaType()))
        if (ifMatch != null) b.header("If-Match", "\"$ifMatch\"")
        if (ifNoneMatch) b.header("If-None-Match", "*")
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "PUT failed")
            return r.header("ETag")?.trim('"')
        }
    }

    fun mkcol(url: String) {
        http.newCall(Request.Builder().url(url).method("MKCOL", null).build()).execute().use { r ->
            if (!r.isSuccessful && r.code != 405) throw HttpException(r.code, "MKCOL failed") // 405 = exists
        }
    }

    fun delete(url: String): Boolean =
        http.newCall(Request.Builder().url(url).delete().build()).execute().use { it.isSuccessful || it.code == 404 }

    private fun String.toHttpPath(): String = substringAfter("://").let { "/" + it.substringAfter('/', "") }
}
