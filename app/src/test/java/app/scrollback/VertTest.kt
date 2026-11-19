package app.scrollback

import app.scrollback.convert.VertConfig
import app.scrollback.convert.VertEvent
import app.scrollback.convert.VertFormats
import app.scrollback.convert.VertProtocol
import app.scrollback.convert.VertdClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class VertTest {
    @Test fun configAndFormats() {
        assertEquals("https://vert.sh", VertConfig.normalize(" vert.sh/ "))
        assertEquals("http://10.0.0.2:24153", VertConfig.normalize("http://10.0.0.2:24153"))
        assertEquals("wss://vertd.vert.sh/api/ws", VertConfig.wsUrl("https://vertd.vert.sh/"))
        assertEquals("ws://h:1/api/ws", VertConfig.wsUrl("http://h:1"))
        assertEquals(VertFormats.Kind.VIDEO, VertFormats.kind("a.MKV"))
        assertEquals(VertFormats.Kind.IMAGE, VertFormats.kind("a.gif"))
        assertEquals(VertFormats.Kind.AUDIO, VertFormats.kind("a.flac"))
        assertEquals(VertFormats.Kind.DOCUMENT, VertFormats.kind("a.docx"))
        assertEquals("clip.webm", VertFormats.outputName("clip.mp4", "webm"))
    }

    @Test fun friendlyErrors() {
        assertTrue(app.scrollback.convert.vertErrorMessage(java.io.IOException("x", java.net.UnknownHostException("h"))).startsWith("Can't reach"))
        assertEquals("boom", app.scrollback.convert.vertErrorMessage(java.io.IOException("boom")))
    }

    @Test fun protocolMessages() {
        val job = VertProtocol.parseUpload("""{"type":"success","data":{"id":"abc","auth":"tok","from":".mp4","to":null,"state":"pending","totalFrames":250}}""")
        assertEquals("abc", job.id); assertEquals(250L, job.totalFrames)
        val start = JSONObject(VertProtocol.startJob(job, "webm", 9))
        assertEquals("startJob", start.getString("type"))
        assertEquals("tok", start.getJSONObject("data").getString("token"))
        assertEquals(5, start.getJSONObject("data").getJSONObject("settings").getInt("vertdSpeed"))
        assertTrue(start.getJSONObject("data").getJSONObject("settings").getBoolean("metadata"))
        assertEquals(VertEvent.Progress(42, null), VertProtocol.parse("""{"type":"progressUpdate","data":{"type":"frame","data":42}}"""))
        assertEquals(VertEvent.Finished("abc"), VertProtocol.parse("""{"type":"jobFinished","data":{"jobId":"abc"}}"""))
        assertEquals(VertEvent.Failed("boom"), VertProtocol.parse("""{"type":"error","data":{"message":"boom"}}"""))
        assertEquals(VertEvent.Ignored, VertProtocol.parse("not json"))
    }

    @Test fun fullFlowAgainstMockVertd() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("""{"type":"success","data":{"id":"j1","auth":"a1","from":".mp4","totalFrames":10}}""").build())
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val o = JSONObject(text)
                    if (o.getString("type") == "startJob") {
                        webSocket.send("""{"type":"progressUpdate","data":{"type":"frame","data":5}}""")
                        webSocket.send("""{"type":"jobFinished","data":{"jobId":"j1"}}""")
                    }
                }
                override fun onOpen(webSocket: WebSocket, response: Response) {}
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }).build())
            server.enqueue(MockResponse.Builder().body("CONVERTED").build())
            server.enqueue(MockResponse.Builder().body("""{"type":"success","data":null}""").build())
            server.start()
            val c = VertdClient(OkHttpClient(), server.url("/").toString(), "X-Test" to "1")
            val job = c.upload("clip.mp4", 5, { "hello".byteInputStream() }) {}
            val frames = mutableListOf<Long>()
            c.convert(job, "webm", 3) { p -> p.frame?.let { frames += it } }
            val out = ByteArrayOutputStream()
            c.download(job, out) {}
            assertEquals("CONVERTED", out.toString())
            assertEquals(listOf(5L), frames)
            val up = server.takeRequest()
            assertEquals("/api/upload", up.url.encodedPath)
            assertEquals("1", up.headers["X-Test"])
            assertTrue(up.body!!.utf8().contains("filename=\"clip.mp4\""))
            assertEquals("/api/ws", server.takeRequest().url.encodedPath)
            assertEquals("/api/download/j1/a1", server.takeRequest().url.encodedPath)
            assertEquals("/api/confirm/j1/a1", server.takeRequest().url.encodedPath)
        }
    }
}
