package app.scrollback.remote

import android.content.Context
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Maps custom media URIs (subsonic:…, jellyfin:…) to real HTTP URLs at request time, and adds
 * auth headers for known servers. Secrets therefore never end up in the database, in Coil's or
 * Media3's cache keys, or in logs: the DB stores stable URIs, credentials are applied on the wire.
 */
class RemoteAuth {
    /** Base URL prefix -> headers to add (e.g. Nextcloud Basic auth, Jellyfin token). */
    private val headers = ConcurrentHashMap<String, Map<String, String>>()

    /** URI scheme -> resolver (scheme-specific part -> https URL). */
    private val resolvers = ConcurrentHashMap<String, (String) -> String?>()

    fun setHeaders(prefix: String, h: Map<String, String>) { headers[prefix.trimEnd('/')] = h }
    fun clearHeaders(prefix: String) { headers.remove(prefix.trimEnd('/')) }
    fun setResolver(scheme: String, r: (String) -> String?) { resolvers[scheme] = r }

    fun headersFor(url: String): Map<String, String> =
        headers.entries.firstOrNull { url == it.key || url.startsWith(it.key + "/") || url.startsWith(it.key + "?") }?.value.orEmpty()

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
}

object Http {
    fun client(context: Context, auth: RemoteAuth): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth.interceptor)
        .addInterceptor(UserAgent)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "http"), 64L * 1024 * 1024))
        .build()

    private object UserAgent : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Scrollback (Android)").build())
    }
}

/** Thrown for non-2xx responses; message never contains credentials. */
class HttpException(val code: Int, message: String) : java.io.IOException("HTTP $code: $message")
