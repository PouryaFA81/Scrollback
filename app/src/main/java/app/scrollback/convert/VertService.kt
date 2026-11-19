package app.scrollback.convert

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.scrollback.accounts.CredentialStore
import app.scrollback.files.Mime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.InputStream

/** Stores the VERT settings and runs video conversions through vertd, saving to Download/Scrollback. */
class VertService(private val context: Context, private val http: OkHttpClient, private val credentials: CredentialStore) {
    private val prefs = context.getSharedPreferences("vert", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<VertConfig> = _config

    private fun load() = VertConfig(
        prefs.getString("instance", null) ?: VertConfig.DEFAULT_INSTANCE,
        prefs.getString("vertd", null) ?: VertConfig.DEFAULT_VERTD,
    )

    fun save(instance: String, vertd: String) {
        val c = VertConfig(VertConfig.normalize(instance).ifBlank { VertConfig.DEFAULT_INSTANCE }, VertConfig.normalize(vertd))
        prefs.edit().putString("instance", c.instance).putString("vertd", c.vertd).apply()
        _config.value = c
    }

    /** Optional "Name: value" header for vertd behind an auth proxy (e.g. Cloudflare Access). Stored encrypted. */
    fun hasHeader() = credentials.get(HEADER_ID) != null
    fun setHeader(raw: String?) {
        if (raw.isNullOrBlank()) credentials.remove(HEADER_ID) else credentials.put(HEADER_ID, raw.trim())
    }
    private fun header(): Pair<String, String>? = credentials.get(HEADER_ID)?.let { h ->
        val i = h.indexOf(':'); if (i <= 0) null else h.substring(0, i).trim() to h.substring(i + 1).trim()
    }

    private fun client(): VertdClient {
        val vertd = _config.value.vertd
        require(vertd.isNotBlank()) { "Set a video server (vertd) URL first" }
        return VertdClient(http, vertd, header())
    }

    suspend fun test(): String = withContext(Dispatchers.IO) { client().version() }

    sealed interface Stage {
        data class Uploading(val fraction: Float) : Stage
        data class Converting(val fraction: Float?, val frame: Long?, val fps: Double?) : Stage
        data class Downloading(val fraction: Float) : Stage
    }

    /** Converts a video and saves it as Download/Scrollback/<name>.<to>. Returns the saved content Uri. */
    suspend fun convertVideo(name: String, size: Long, open: () -> InputStream, to: String, speed: Int, onStage: (Stage) -> Unit): Uri = withContext(Dispatchers.IO) {
        val c = client()
        onStage(Stage.Uploading(0f))
        val job = c.upload(name, size, open) { onStage(Stage.Uploading(it)) }
        onStage(Stage.Converting(null, null, null))
        var frame: Long? = null; var fps: Double? = null
        c.convert(job, to, speed) { p ->
            p.frame?.let { frame = it }; p.fps?.let { fps = it }
            val total = job.totalFrames
            onStage(Stage.Converting(if (total != null && frame != null) (frame!!.toFloat() / total).coerceIn(0f, 1f) else null, frame, fps))
        }
        onStage(Stage.Downloading(0f))
        val outName = VertFormats.outputName(name, to)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outName)
            put(MediaStore.Downloads.MIME_TYPE, Mime.guess(outName).takeIf { it != "application/octet-stream" } ?: "video/$to")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Scrollback")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the output file")
        try {
            resolver.openOutputStream(target)!!.use { out -> c.download(job, out) { onStage(Stage.Downloading(it)) } }
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            target
        } catch (e: Throwable) {
            resolver.delete(target, null, null)
            throw e
        }
    }

    companion object { const val HEADER_ID = "vert.header" }
}
