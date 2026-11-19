package app.scrollback

import app.scrollback.nearby.DeviceInfo
import app.scrollback.nearby.FileOffer
import app.scrollback.nearby.Identity
import app.scrollback.nearby.NearbyClient
import app.scrollback.nearby.NearbyServer
import app.scrollback.nearby.Peer
import app.scrollback.nearby.ReceiveCallbacks
import app.scrollback.nearby.ReceiveTarget
import app.scrollback.nearby.SendException
import app.scrollback.nearby.SendItem
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Server + client against each other over real TLS on localhost (no network beyond loopback). */
class NearbyTransferTest {
    private val dirA = Files.createTempDirectory("a").toFile()
    private val dirB = Files.createTempDirectory("b").toFile()
    private lateinit var server: NearbyServer
    private lateinit var receiverInfo: DeviceInfo
    private lateinit var sender: DeviceInfo
    private lateinit var client: NearbyClient
    private var port = 0
    private val saved = ConcurrentHashMap<String, ByteArray>()
    private var decide: (List<FileOffer>) -> Set<String>? = { f -> f.map { it.id }.toSet() }
    private var pin: String? = null
    private var ended = mutableListOf<Boolean>()
    private var texts = mutableListOf<String>()

    @Before fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        val idB = Identity.loadOrCreate(dirB)
        receiverInfo = DeviceInfo("Receiver", idB.fingerprint, port = port)
        server = NearbyServer({ receiverInfo }, idB, port, object : ReceiveCallbacks {
            override fun pin() = pin
            override fun onOffer(sessionId: String, sender: Peer, files: List<FileOffer>) = decide(files)
            override fun onText(sender: Peer, text: String) { texts += text }
            override fun openTarget(file: FileOffer): ReceiveTarget = object : ReceiveTarget {
                val buf = ByteArrayOutputStream()
                override val out = buf
                override fun commit(): String { saved[file.fileName] = buf.toByteArray(); return "mem:${file.fileName}" }
                override fun abort() {}
            }
            override fun onSessionEnd(sessionId: String, complete: Boolean) { synchronized(ended) { ended += complete } }
        }, host = "127.0.0.1")
        server.startServer()
        val idA = Identity.loadOrCreate(dirA)
        sender = DeviceInfo("Sender", idA.fingerprint)
        client = NearbyClient(OkHttpClient(), idA)
    }

    @After fun tearDown() { server.stop(); dirA.deleteRecursively(); dirB.deleteRecursively() }

    private fun peer(fp: String = receiverInfo.fingerprint) = Peer(receiverInfo.copy(fingerprint = fp), "127.0.0.1")
    private fun item(name: String, data: ByteArray) = SendItem(name, data.size.toLong(), "application/octet-stream") { data.inputStream() }

    @Test fun registerAndSend() {
        val p = client.register(sender, "127.0.0.1", port)
        assertNotNull(p); assertEquals("Receiver", p!!.info.alias); assertEquals(receiverInfo.fingerprint, p.info.fingerprint)
        val big = ByteArray(700_000) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        assertTrue(client.send(sender, p, listOf(item("a.bin", big), item("b.txt", "hi".toByteArray())), onProgress = { i, n -> if (i == 0) progress += n }))
        assertArrayEquals(big, saved["a.bin"]); assertArrayEquals("hi".toByteArray(), saved["b.txt"])
        assertEquals(700_000L, progress.last())
        assertEquals(listOf(true), ended)
    }

    @Test fun pinnedFingerprintMismatchFails() {
        try { client.send(sender, peer("00".repeat(32)), listOf(item("x", byteArrayOf(1)))); fail() }
        catch (e: java.io.IOException) { assertFalse(e is SendException) }
        assertTrue(saved.isEmpty())
    }

    @Test fun pinAndDecline() {
        pin = "4321"
        try { client.send(sender, peer(), listOf(item("x", byteArrayOf(1)))); fail() } catch (e: SendException) { assertEquals(401, e.code) }
        try { client.send(sender, peer(), listOf(item("x", byteArrayOf(1))), pin = "0000"); fail() } catch (e: SendException) { assertEquals(401, e.code) }
        assertTrue(client.send(sender, peer(), listOf(item("x", byteArrayOf(1))), pin = "4321"))
        decide = { null }
        try { client.send(sender, peer(), listOf(item("y", byteArrayOf(1))), pin = "4321"); fail() } catch (e: SendException) { assertEquals(403, e.code) }
        // Partial accept: only the second file is uploaded.
        decide = { f -> f.filter { it.fileName == "keep" }.map { it.id }.toSet() }
        assertTrue(client.send(sender, peer(), listOf(item("skip", byteArrayOf(1)), item("keep", byteArrayOf(2))), pin = "4321"))
        assertFalse(saved.containsKey("skip")); assertTrue(saved.containsKey("keep"))
    }

    @Test fun textMessagesAnswer204() {
        // A text "file" with a preview needs no upload.
        val http = OkHttpClient.Builder().build()
        assertNotNull(http) // client API sends files; text is exercised through the server directly
        val body = app.scrollback.nearby.PrepareRequest(sender, listOf(FileOffer("t", "t.txt", 2, "text/plain", preview = "hey"))).toJson()
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val c = OkHttpClient.Builder().sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
        val r = c.newCall(okhttp3.Request.Builder().url("https://127.0.0.1:$port/api/localsend/v2/prepare-upload")
            .post(okhttp3.RequestBody.create(null, body)).build()).execute()
        assertEquals(204, r.code); r.close()
        assertEquals(listOf("hey"), texts)
        val info = c.newCall(okhttp3.Request.Builder().url("https://127.0.0.1:$port/api/localsend/v2/info").build()).execute()
        val j = org.json.JSONObject(info.body.string())
        assertEquals("Receiver", j.getString("alias")); assertFalse(j.has("port"))
    }
}
