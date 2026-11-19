package app.scrollback.nearby

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Something to send: a name, size and a way to open it (content://, file, remote stream). */
class SendItem(val name: String, val size: Long, val mime: String, val open: () -> InputStream) {
    val id: String = "f" + Hex.random(6)
}

class SendException(val code: Int, message: String) : IOException(message)

/**
 * LocalSend v2 client on OkHttp (spec 3.2, 4), ported from RSend's Sender/Http: self-signed
 * certificates are accepted, and pinned to the fingerprint learned from discovery when known.
 * Our own certificate is offered as client certificate (newer official apps ask for one).
 */
class NearbyClient(base: OkHttpClient, private val identity: Identity?) {
    private val base = base.newBuilder().proxy(Proxy.NO_PROXY).cache(null).build()

    private fun clientFor(pin: String?, connectMs: Long = 5_000, readMs: Long = 60_000): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
                if (pin != null && !Fingerprint.of(leaf).equals(pin, ignoreCase = true)) throw CertificateException("certificate fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val kms = identity?.let { id -> KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(id.keyStore, Identity.PASSWORD) }.keyManagers }
        val ctx = SSLContext.getInstance("TLS").apply { init(kms, arrayOf(tm), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // self-signed: no hostname-valid chain
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private val jsonType = "application/json".toMediaType()

    /** POST /register to host:port; returns the peer, or null if nothing LocalSend answers there. */
    fun register(self: DeviceInfo, host: String, port: Int = LocalSend.PORT, protocol: String = "https", connectMs: Long = 3_000): Peer? {
        val url = "$protocol://${if (host.contains(':')) "[$host]" else host}:$port${LocalSend.API}/register"
        val req = Request.Builder().url(url).post(self.toJson(full = true).toString().toRequestBody(jsonType)).build()
        clientFor(null, connectMs, 5_000).newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val info = DeviceInfo.fromJson(JSONObject(r.body.string()), port, protocol) ?: return null
            if (info.fingerprint == self.fingerprint) return null
            return Peer(info, host)
        }
    }

    /** Sends [items]; [onProgress] gets (item index, bytes sent of that item). Returns false if the receiver needed nothing (204). */
    fun send(
        self: DeviceInfo, to: Peer, items: List<SendItem>, pin: String? = null,
        onSession: (String) -> Unit = {}, onProgress: (Int, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false },
    ): Boolean {
        val files = items.map { FileOffer(it.id, it.name, it.size, it.mime) }
        val q = pin?.let { "?pin=" + enc(it) }.orEmpty()
        // The receiver may wait for a person to tap Accept: allow a long read timeout here.
        val prep = Request.Builder().url("${to.baseUrl}${LocalSend.API}/prepare-upload$q")
            .post(PrepareRequest(self, files).toJson().toRequestBody(jsonType)).build()
        val resp = clientFor(to.pin, readMs = 130_000).newCall(prep).execute().use { r ->
            if (r.code == 204) return false
            if (!r.isSuccessful) throw SendException(r.code, Status.message(r.code))
            PrepareResponse.parse(r.body.string()) ?: throw IOException("Malformed answer from ${to.info.alias}")
        }
        onSession(resp.sessionId)
        val http = clientFor(to.pin, readMs = 120_000)
        try {
            items.forEachIndexed { i, item ->
                val token = resp.tokens[item.id] ?: return@forEachIndexed // declined file
                if (isCancelled()) throw IOException("Cancelled")
                val body = object : RequestBody() {
                    override fun contentType() = item.mime.toMediaType()
                    override fun contentLength() = item.size
                    override fun writeTo(sink: BufferedSink) {
                        item.open().use { input ->
                            val buf = ByteArray(128 * 1024); var sent = 0L; var last = 0L
                            while (true) {
                                if (isCancelled()) throw IOException("Cancelled")
                                val n = input.read(buf); if (n < 0) break
                                sink.write(buf, 0, n); sent += n
                                if (sent - last > 256 * 1024) { last = sent; onProgress(i, sent) }
                            }
                            onProgress(i, sent)
                        }
                    }
                }
                val url = "${to.baseUrl}${LocalSend.API}/upload?sessionId=${enc(resp.sessionId)}&fileId=${enc(item.id)}&token=${enc(token)}"
                http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) throw SendException(r.code, if (r.code == 403) "The receiver stopped the transfer" else "Upload of ${item.name} failed (HTTP ${r.code})")
                }
            }
        } catch (e: IOException) {
            runCatching { cancel(to, resp.sessionId) }
            throw e
        }
        return true
    }

    fun cancel(to: Peer, sessionId: String) {
        val req = Request.Builder().url("${to.baseUrl}${LocalSend.API}/cancel?sessionId=${enc(sessionId)}").post(ByteArray(0).toRequestBody()).build()
        clientFor(to.pin, 3_000, 5_000).newCall(req).execute().close()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
