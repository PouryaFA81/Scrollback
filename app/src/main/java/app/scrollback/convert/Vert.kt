package app.scrollback.convert

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** VERT (vert.sh) settings. The web app converts images/audio/docs in the browser; video goes to a vertd server. */
data class VertConfig(val instance: String = DEFAULT_INSTANCE, val vertd: String = DEFAULT_VERTD) {
    companion object {
        const val DEFAULT_INSTANCE = "https://vert.sh"
        const val DEFAULT_VERTD = "https://vertd.vert.sh"
        const val HOMELAB_INSTANCE = "https://vert.example.com"

        /** "vert.sh/" -> "https://vert.sh"; blank stays blank. */
        fun normalize(url: String): String {
            val t = url.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        }

        fun wsUrl(vertd: String): String = normalize(vertd).replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/ws"
    }
}

/** Turns network exceptions into a short message for the UI (offline, DNS, timeouts, TLS). */
fun vertErrorMessage(t: Throwable): String {
    var c: Throwable? = t
    while (c != null) {
        when (c) {
            is java.net.UnknownHostException -> return "Can't reach the server. Check that you're online and the URL is right."
            is java.net.ConnectException -> return "The server refused the connection or is down."
            is java.net.SocketTimeoutException -> return "The server took too long to answer. Try again later."
            is javax.net.ssl.SSLException -> return "Secure connection failed (${c.message ?: "TLS error"})."
        }
        c = c.cause
    }
    return t.message?.ifBlank { null } ?: t.javaClass.simpleName
}

object VertFormats {
    /** Targets vertd can produce that make sense on a phone. */
    val VIDEO_TARGETS = listOf("mp4", "webm", "mkv", "mov", "avi", "gif", "webp", "apng", "m4v", "3gp")
    private val VIDEO_INPUTS = setOf(
        "mp4", "webm", "mkv", "mov", "avi", "wmv", "mts", "ts", "m2ts", "mpeg", "mpg", "flv", "f4v", "vob", "m4v",
        "3gp", "3g2", "mxf", "ogv", "rm", "rmvb", "divx", "asf", "gif",
    )
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "heic", "heif", "avif", "ico", "svg", "jxl")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "aiff", "alac")
    private val DOCS = setOf("docx", "doc", "odt", "rtf", "md", "html", "epub", "pdf", "txt", "pptx", "xlsx")

    enum class Kind(val label: String) { VIDEO("Video"), IMAGE("Image"), AUDIO("Audio"), DOCUMENT("Document"), OTHER("File") }

    fun kind(name: String): Kind {
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            in VIDEO_INPUTS -> if (e == "gif") Kind.IMAGE else Kind.VIDEO
            in IMAGE -> Kind.IMAGE
            in AUDIO -> Kind.AUDIO
            in DOCS -> Kind.DOCUMENT
            else -> Kind.OTHER
        }
    }

    fun outputName(input: String, to: String): String = input.substringBeforeLast('.', input) + "." + to
}

data class VertJob(val id: String, val auth: String, val from: String, val totalFrames: Long?)

sealed interface VertEvent {
    data class Progress(val frame: Long?, val fps: Double?) : VertEvent
    data class Finished(val jobId: String) : VertEvent
    data class Failed(val message: String) : VertEvent
    data object Ignored : VertEvent
}

object VertProtocol {
    fun startJob(job: VertJob, to: String, speed: Int = 3): String = JSONObject()
        .put("type", "startJob")
        .put("data", JSONObject()
            .put("token", job.auth)
            .put("jobId", job.id)
            .put("to", to)
            .put("settings", JSONObject().put("metadata", true).put("vertdSpeed", speed.coerceIn(0, 5))))
        .toString()

    fun parseUpload(body: String): VertJob {
        val o = JSONObject(body)
        if (o.optString("type") != "success") throw IOException(o.optString("data").ifBlank { "Upload rejected" })
        val d = o.getJSONObject("data")
        return VertJob(d.getString("id"), d.getString("auth"), d.optString("from"), d.optLong("totalFrames", -1).takeIf { it > 0 })
    }

    fun parse(message: String): VertEvent {
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return VertEvent.Ignored
        val data = o.opt("data")
        return when (o.optString("type")) {
            "progressUpdate" -> {
                val p = data as? JSONObject ?: return VertEvent.Ignored
                when (p.optString("type")) {
                    "frame" -> VertEvent.Progress(p.optLong("data"), null)
                    "fps" -> VertEvent.Progress(null, p.optDouble("data"))
                    "error" -> VertEvent.Ignored // ffmpeg log line; the server retries or sends "error"
                    else -> VertEvent.Ignored
                }
            }
            "jobFinished" -> VertEvent.Finished((data as? JSONObject)?.optString("jobId").orEmpty())
            "error" -> VertEvent.Failed((data as? JSONObject)?.optString("message")?.ifBlank { null } ?: "Conversion failed")
            "jobCancelled" -> VertEvent.Failed("The server cancelled the job")
            else -> VertEvent.Ignored
        }
    }
}

/** Minimal vertd client: upload → websocket job → download → confirm. */
class VertdClient(shared: OkHttpClient, base: String, private val extraHeader: Pair<String, String>? = null) {
    private val base = VertConfig.normalize(base)
    private val http = shared.newBuilder()
        .cache(null)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun Request.Builder.headers(): Request.Builder = apply { extraHeader?.let { header(it.first, it.second) } }

    private fun check(r: Response, what: String) {
        if (!r.isSuccessful) {
            val msg = runCatching { JSONObject(r.body.string()).optString("data") }.getOrNull()?.ifBlank { null }
            throw IOException("$what failed (HTTP ${r.code})" + (msg?.let { ": $it" } ?: ""))
        }
    }

    fun upload(name: String, size: Long, open: () -> InputStream, onProgress: (Float) -> Unit): VertJob {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = if (size > 0) size else -1
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        sink.write(buf, 0, n); done += n
                        if (size > 0) onProgress(done.toFloat() / size)
                    }
                }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, body).build()
        http.newCall(Request.Builder().url("$base/api/upload").headers().post(multipart).build()).execute().use { r ->
            check(r, "Upload")
            return VertProtocol.parseUpload(r.body.string())
        }
    }

    suspend fun convert(job: VertJob, to: String, speed: Int, onEvent: (VertEvent.Progress) -> Unit) = suspendCancellableCoroutine { cont ->
        val req = Request.Builder().url(VertConfig.wsUrl(base)).headers().build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(VertProtocol.startJob(job, to, speed)) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val e = VertProtocol.parse(text)) {
                    is VertEvent.Progress -> onEvent(e)
                    is VertEvent.Finished -> if (e.jobId.isEmpty() || e.jobId == job.id) {
                        webSocket.close(1000, null); if (cont.isActive) cont.resume(Unit)
                    }
                    is VertEvent.Failed -> { webSocket.close(1000, null); if (cont.isActive) cont.resumeWithException(IOException(e.message)) }
                    VertEvent.Ignored -> Unit
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) cont.resumeWithException(IOException("Connection to the conversion server failed: ${t.message ?: t.javaClass.simpleName}"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) cont.resumeWithException(IOException("The conversion server closed the connection"))
            }
        })
        cont.invokeOnCancellation {
            ws.send(JSONObject().put("type", "cancelJob").put("data", JSONObject().put("token", job.auth).put("jobId", job.id)).toString())
            ws.close(1000, null)
        }
    }

    fun download(job: VertJob, out: OutputStream, onProgress: (Float) -> Unit) {
        http.newCall(Request.Builder().url("$base/api/download/${job.id}/${job.auth}").headers().build()).execute().use { r ->
            check(r, "Download")
            val total = r.body.contentLength()
            r.body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        // Tell the server we're done so it deletes the output now rather than after its timeout.
        runCatching { http.newCall(Request.Builder().url("$base/api/confirm/${job.id}/${job.auth}").headers().build()).execute().close() }
    }

    fun version(): String = http.newCall(Request.Builder().url("$base/api/version").headers().build()).execute().use { r ->
        check(r, "Connection"); JSONObject(r.body.string()).optString("data")
    }
}
