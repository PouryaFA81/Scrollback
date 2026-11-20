// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.remote.DirectAddress
import app.scrollback.remote.Explain
import app.scrollback.remote.Hop
import app.scrollback.remote.ProbeKind
import app.scrollback.remote.RedirectInterceptor
import app.scrollback.remote.RedirectTrace
import app.scrollback.remote.Redirects
import app.scrollback.remote.RemoteAuth
import app.scrollback.remote.ServerProbe
import app.scrollback.remote.nextcloud.LoginFlowV2
import app.scrollback.remote.nextcloud.LoginUrls
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** v0.14 redirect / alternate-address handling, against MockWebServer stubs. */
class RedirectTest {
    private fun client(auth: RemoteAuth): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor(auth.fallback)
        .addInterceptor(RedirectInterceptor(auth::isTrusted))
        .addInterceptor(auth.interceptor)
        .build()

    private fun redirect(req: Request, code: Int, location: String): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x").header("Location", location).build()

    private val never: (okhttp3.HttpUrl) -> Boolean = { false }

    // ---- Pure rules -----------------------------------------------------------------------

    @Test fun keepsMethodRules() {
        val a = "https://h/remote.php/dav/files/u/".toHttpUrl()
        val b = "https://h/other".toHttpUrl()
        assertTrue(Redirects.keepsMethod(301, "PROPFIND", a, b))
        assertTrue(Redirects.keepsMethod(302, "PUT", a, b))
        assertTrue(Redirects.keepsMethod(307, "POST", a, b))
        assertTrue(Redirects.keepsMethod(308, "POST", a, b))
        assertFalse(Redirects.keepsMethod(303, "POST", a, b))
        assertFalse(Redirects.keepsMethod(302, "POST", a, b)) // classic post-redirect-get
        // Same path, only scheme/host changed (proxy canonicalisation): keep the POST.
        assertTrue(Redirects.keepsMethod(301, "POST", "http://h/index.php/login/v2".toHttpUrl(), "https://h/index.php/login/v2".toHttpUrl()))
    }

    @Test fun httpToHttpsKeepsExplicitCredentials() {
        val req = Request.Builder().url("http://cloud.example.com/ocs/v2.php/cloud/user").header("Authorization", "Basic abc").build()
        val next = Redirects.followUp(redirect(req, 301, "https://cloud.example.com/ocs/v2.php/cloud/user"), req, never)!!
        assertEquals("https", next.url.scheme)
        assertEquals("Basic abc", next.header("Authorization"))
    }

    @Test fun crossHostAndDowngradeStripCredentials() {
        val req = Request.Builder().url("https://cloud.example.com/a").header("Authorization", "Basic abc").header("X-Emby-Token", "t").build()
        val other = Redirects.followUp(redirect(req, 302, "https://evil.example.net/a"), req, never)!!
        assertNull(other.header("Authorization"))
        assertNull(other.header("X-Emby-Token"))
        val down = Redirects.followUp(redirect(req, 301, "http://cloud.example.com/a"), req, never)!!
        assertNull(down.header("Authorization"))
        // ...unless the target is an address the user configured for this server.
        val trusted = Redirects.followUp(redirect(req, 302, "https://nc.lan/a"), req) { it.host == "nc.lan" }!!
        assertEquals("Basic abc", trusted.header("Authorization"))
    }

    @Test fun postBecomesGetOn303AndBodyHeadersGo() {
        val req = Request.Builder().url("https://h/form").post("a=1".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        val next = Redirects.followUp(redirect(req, 303, "/done"), req, never)!!
        assertEquals("GET", next.method)
        assertNull(next.body)
        assertEquals("/done", next.url.encodedPath)
    }

    @Test fun nonRedirectsAndMissingLocationAreNotFollowed() {
        val req = Request.Builder().url("https://h/").build()
        assertNull(Redirects.followUp(Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("ok").build(), req, never))
        assertNull(Redirects.followUp(Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(302).message("x").build(), req, never))
    }

    @Test fun safeUrlDropsQuery() {
        assertEquals("https://h:8443/rest/ping.view", Redirects.safe("https://h:8443/rest/ping.view?u=a&t=secret&s=salt".toHttpUrl()))
        assertTrue(Explain.hop(Hop(308, "http://h/", "https://h/")).contains("permanent redirect"))
        assertTrue(Explain.chain(listOf(Hop(308, "http://h/x", "https://h/x")))!!.contains("upgrades http to https"))
        assertTrue(Explain.chain(listOf(Hop(302, "https://a.example/x", "https://b.example/x")))!!.contains("different host"))
    }

    @Test fun remoteAuthMatchesHttpsUpgradeAndAlias() {
        val auth = RemoteAuth()
        auth.setHeaders("http://cloud.example.com", mapOf("Authorization" to "Basic x"))
        assertEquals("Basic x", auth.headersFor("https://cloud.example.com/remote.php/dav")["Authorization"])
        assertTrue(auth.headersFor("https://cloud.example.com.evil.net/x").isEmpty())
        assertTrue(auth.headersFor("https://other.example.com/x").isEmpty())
        auth.setAlternate("http://cloud.example.com", "http://192.168.1.5:8080")
        assertEquals("Basic x", auth.headersFor("http://192.168.1.5:8080/remote.php/dav")["Authorization"])
        auth.setAlternate("http://cloud.example.com", null)
        assertTrue(auth.headersFor("http://192.168.1.5:8080/remote.php/dav").isEmpty())
    }

    // ---- Against stub servers -------------------------------------------------------------

    private fun stub(handler: (RecordedRequest) -> MockResponse): Pair<MockWebServer, MutableList<RecordedRequest>> {
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen += request; return handler(request) }
        }
        s.start()
        return s to seen
    }

    @Test fun propfindFollows301And308WithMethodBodyAndAuth() {
        val (server, seen) = stub { r ->
            when (r.url.encodedPath) {
                "/old/dav/" -> MockResponse.Builder().code(301).addHeader("Location", "/mid/dav/").build()
                "/mid/dav/" -> MockResponse.Builder().code(308).addHeader("Location", "/remote.php/dav/").build()
                else -> MockResponse.Builder().code(207).body("<d:multistatus xmlns:d=\"DAV:\"/>").build()
            }
        }
        server.use {
            val base = server.url("/").toString().trimEnd('/')
            val auth = RemoteAuth().apply { setHeaders(base, mapOf("Authorization" to "Basic dXNlcjpwdw==")) }
            val req = Request.Builder().url("$base/old/dav/").method("PROPFIND", "<propfind/>".toRequestBody("application/xml".toMediaType())).header("Depth", "1").build()
            client(auth).newCall(req).execute().use { assertEquals(207, it.code) }
            assertEquals(3, seen.size)
            seen.forEach {
                assertEquals("PROPFIND", it.method)
                assertEquals("<propfind/>", it.body?.utf8())
                assertEquals("1", it.headers["Depth"])
                assertEquals("Basic dXNlcjpwdw==", it.headers["Authorization"])
            }
        }
    }

    @Test fun crossHostRedirectDropsSignInUntilTrusted() {
        val (server, seen) = stub { r ->
            if (r.url.encodedPath == "/start") MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.1:${r.url.port}/end").build()
            else MockResponse.Builder().code(200).body("ok").build()
        }
        server.use {
            val base = "http://localhost:${server.port}"
            val auth = RemoteAuth().apply { setHeaders(base, mapOf("Authorization" to "Bearer secret")) }
            client(auth).newCall(Request.Builder().url("$base/start").build()).execute().use { assertEquals(200, it.code) }
            assertEquals("Bearer secret", seen[0].headers["Authorization"])
            assertNull("credentials must not follow to another host", seen[1].headers["Authorization"])
            seen.clear()
            auth.setAlternate(base, "http://127.0.0.1:${server.port}")
            client(auth).newCall(Request.Builder().url("$base/start").build()).execute().use { assertEquals(200, it.code) }
            assertEquals("Bearer secret", seen.last().headers["Authorization"])
        }
    }

    @Test fun redirectLoopStops() {
        val (server, seen) = stub { MockResponse.Builder().code(302).addHeader("Location", "/loop").build() }
        server.use {
            try {
                client(RemoteAuth()).newCall(Request.Builder().url(server.url("/loop")).build()).execute().close()
                fail("expected a redirect-loop error")
            } catch (e: java.net.ProtocolException) {
                assertTrue(e.message!!.startsWith("Too many redirects"))
                assertTrue(Explain.error(e, "h", 443).contains("loop"))
            }
            assertEquals(Redirects.MAX_HOPS + 1, seen.size)
        }
    }

    @Test fun fallsBackToAlternateAddressAndRemembersIt() {
        val dead = ServerSocket(0).use { it.localPort } // nothing listens here any more
        val (server, seen) = stub { MockResponse.Builder().code(200).body("hi").build() }
        server.use {
            val primary = "http://127.0.0.1:$dead"
            val alt = "http://localhost:${server.port}"
            val auth = RemoteAuth().apply {
                setHeaders(primary, mapOf("Authorization" to "Basic k"))
                setAlternate(primary, alt)
            }
            client(auth).newCall(Request.Builder().url("$primary/rest/ping.view?u=a").build()).execute().use { assertEquals("hi", it.body.string()) }
            assertEquals("/rest/ping.view", seen[0].url.encodedPath)
            assertEquals("a", seen[0].url.queryParameter("u"))
            assertEquals("Basic k", seen[0].headers["Authorization"])
            assertTrue(auth.route(primary)!!.preferAlternate)
            // DirectAddress (Test connection) does not fall back.
            try {
                client(auth).newCall(Request.Builder().url("$primary/x").tag(DirectAddress::class.java, DirectAddress).build()).execute().close()
                fail("direct request to a dead address should fail")
            } catch (_: java.io.IOException) { }
        }
    }

    @Test fun loginFlowStaysOnTheWorkingAddress() {
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val (server, _) = stub { r ->
            when (r.url.encodedPath) {
                "/index.php/login/v2" -> MockResponse.Builder().code(200).body(
                    """{"poll":{"token":"tok","endpoint":"http://nc.internal/login/v2/poll"},"login":"http://nc.internal/login/v2/flow/abc"}"""
                ).build()
                "/login/v2/poll" -> if (polls.incrementAndGet() < 2) MockResponse.Builder().code(404).build()
                else MockResponse.Builder().code(200).body("""{"server":"http://nc.internal","loginName":"alice","appPassword":"app-pw"}""").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.use {
            val base = server.url("/").toString().trimEnd('/')
            val flow = LoginFlowV2(client(RemoteAuth()))
            val start = flow.start(base)
            assertEquals("$base/login/v2/flow/abc", start.loginUrl)
            assertEquals("$base/login/v2/poll", start.pollEndpoint)
            val result = runBlocking { flow.poll(start, timeoutMs = 20_000) }
            assertEquals("http://nc.internal", result.server)
            val (primary, alt) = LoginUrls.accountAddresses(base, result.server)
            assertEquals(base, primary)
            assertEquals("http://nc.internal", alt)
        }
    }

    @Test fun loginUrlAlignment() {
        assertEquals("https://cloud.x/index.php/login/v2/poll", LoginUrls.align("http://cloud.x/index.php/login/v2/poll", "https://cloud.x"))
        assertEquals("https://cloud.x/nc/login/v2/flow/t", LoginUrls.align("http://10.0.0.2/login/v2/flow/t", "https://cloud.x/nc"))
        assertEquals("https://cloud.x/login/v2/poll", LoginUrls.align("https://cloud.x/login/v2/poll", "https://cloud.x"))
        assertEquals("https://cloud.x" to null, LoginUrls.accountAddresses("https://cloud.x", "http://cloud.x"))
        assertEquals("https://cloud.x" to null, LoginUrls.accountAddresses("https://cloud.x", "http://localhost"))
    }

    @Test fun probeFindsCanonicalAddressAndRecognisesServers() {
        val (server, _) = stub { r ->
            when (r.url.encodedPath) {
                "/status.php" -> MockResponse.Builder().code(301).addHeader("Location", "/nextcloud/status.php").build()
                "/nextcloud/status.php" -> MockResponse.Builder().code(200).body("""{"installed":true,"maintenance":false,"versionstring":"31.0.2","productname":"Nextcloud"}""").build()
                "/rest/ping.view" -> MockResponse.Builder().code(200).body("""{"subsonic-response":{"status":"failed","version":"1.16.1","type":"navidrome","serverVersion":"0.55","error":{"code":10,"message":"missing"}}}""").build()
                "/System/Info/Public" -> MockResponse.Builder().code(200).body("""{"Id":"x","Version":"10.11.1","ProductName":"Jellyfin Server","ServerName":"home"}""").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.use {
            val base = server.url("/").toString().trimEnd('/')
            val http = client(RemoteAuth())
            val nc = ServerProbe.probe(http, ProbeKind.NEXTCLOUD, base)
            assertTrue(nc.recognised)
            assertEquals("$base/nextcloud", nc.canonical)
            assertEquals(1, nc.hops.size)
            assertEquals("Nextcloud 31.0.2", nc.serverInfo)
            assertTrue(ServerProbe.probe(http, ProbeKind.SUBSONIC, base).serverInfo!!.startsWith("navidrome"))
            assertEquals("Jellyfin Server 10.11.1 · home", ServerProbe.probe(http, ProbeKind.JELLYFIN, base).serverInfo)
            val nothing = ServerProbe.probe(http, ProbeKind.JELLYFIN, "$base/nope")
            assertFalse(nothing.recognised)
            assertNotNull(nothing.problem)
            val dead = ServerSocket(0).use { it.localPort }
            val down = ServerProbe.probe(http, ProbeKind.NEXTCLOUD, "http://127.0.0.1:$dead")
            assertFalse(down.reachable)
            assertTrue(down.problem!!.startsWith("Nothing answered"))
        }
        assertEquals("https://x/nc", ServerProbe.baseFrom("https://x/nc/status.php", "/status.php"))
        assertNull(ServerProbe.baseFrom("https://x/login", "/status.php"))
    }

    @Test fun traceRecordsHopsWithoutQueries() {
        val (server, _) = stub { r ->
            if (r.url.encodedPath == "/a") MockResponse.Builder().code(307).addHeader("Location", "/b?t=secret").build()
            else MockResponse.Builder().code(200).build()
        }
        server.use {
            val trace = RedirectTrace()
            client(RemoteAuth()).newCall(Request.Builder().url(server.url("/a?t=secret")).tag(RedirectTrace::class.java, trace).build()).execute().close()
            assertEquals(1, trace.hops.size)
            assertFalse(trace.hops[0].from.contains("secret"))
            assertFalse(trace.hops[0].to.contains("secret"))
        }
    }
}
