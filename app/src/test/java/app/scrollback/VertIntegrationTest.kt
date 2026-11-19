package app.scrollback

import app.scrollback.convert.VertdClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Real vertd round trip. Opt-in: SB_VERT_TEST="https://vertd.vert.sh:/path/to/small.mp4" (optional SB_PROXY=host:port). Uploads that file. */
class VertIntegrationTest {
    @Test fun convertsSmallClip() = runBlocking {
        val env = System.getenv("SB_VERT_TEST"); assumeTrue(env != null)
        val base = env!!.substringBeforeLast(':'); val file = File(env.substringAfterLast(':'))
        val proxy = System.getenv("SB_PROXY")?.let { java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(it.substringBefore(':'), it.substringAfter(':').toInt())) }
        val c = VertdClient(OkHttpClient.Builder().apply { if (proxy != null) proxy(proxy) }.build(), base)
        val job = c.upload(file.name, file.length(), { file.inputStream() }) {}
        var frames = 0L
        c.convert(job, "webm", 5) { p -> p.frame?.let { frames = it } }
        val out = ByteArrayOutputStream()
        c.download(job, out) {}
        val b = out.toByteArray()
        // WebM/Matroska EBML magic
        assertTrue("got ${b.size} bytes", b.size > 1000 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte())
        println("vertd ok: ${b.size} bytes, last frame $frames, totalFrames ${job.totalFrames}")
    }
}
