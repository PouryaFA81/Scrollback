// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.accounts.AccountType
import app.scrollback.accounts.ServerAccount
import app.scrollback.remote.RemoteAuth
import app.scrollback.remote.nextcloud.NextcloudPaths
import app.scrollback.remote.webdav.DavParser
import app.scrollback.remote.webdav.WebDavClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogicTest {
    private val multistatus = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/me/Videos/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>10</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/me/Videos/My%20Clip.mp4</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength>
   <d:getlastmodified>Wed, 18 Nov 2026 10:00:00 GMT</d:getlastmodified><d:getcontenttype>video/mp4</d:getcontenttype>
   <d:getetag>"abc"</d:getetag><oc:fileid>42</oc:fileid><nc:has-preview>true</nc:has-preview></d:prop>
   <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><oc:size/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/me/Videos/Sub%20Folder/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:size>99</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    private val account = ServerAccount(id = "a", type = AccountType.NEXTCLOUD, baseUrl = "https://cloud.example.com", loginName = "me@example.com", userId = "me")

    @Test fun parsesPropfind() {
        val e = DavParser.parse(multistatus.byteInputStream())
        assertEquals(3, e.size)
        val clip = e[1]
        assertEquals("My Clip.mp4", clip.name)
        assertFalse(clip.isDir)
        assertEquals(1234L, clip.size)
        assertEquals("video/mp4", clip.mime)
        assertEquals("abc", clip.etag)
        assertEquals("42", clip.fileId)
        assertTrue(clip.hasPreview)
        assertEquals(1794996000000L, clip.modified)
        assertTrue(e[2].isDir)
        assertEquals("Sub Folder", e[2].name)
        assertEquals(99L, e[2].size)
    }

    @Test fun nextcloudPaths() {
        assertEquals("https://cloud.example.com/remote.php/dav/files/me", NextcloudPaths.davUrl(account, "/"))
        assertEquals("https://cloud.example.com/remote.php/dav/files/me/My%20Videos/T%C3%BCk", NextcloudPaths.davUrl(account, "/My Videos/Tük"))
        assertEquals("/My Videos/a b.mp4", NextcloudPaths.relativePath(account, "https://cloud.example.com/remote.php/dav/files/me/My%20Videos/a%20b.mp4"))
        assertEquals("/", NextcloudPaths.relativePath(account, "https://cloud.example.com/remote.php/dav/files/me/").let { if (it == "") "/" else it })
        assertNull(NextcloudPaths.relativePath(account, "content://x"))
    }

    @Test fun normalisesUrls() {
        assertEquals("https://cloud.example.com", ServerAccount.normaliseUrl(" cloud.example.com/ "))
        assertEquals("http://10.0.0.2:8080/nc", ServerAccount.normaliseUrl("http://10.0.0.2:8080/nc/index.php"))
        assertEquals("https://music.example.com", ServerAccount.normaliseUrl("https://music.example.com/web/"))
    }

    @Test fun authHeadersAndResolversArePerServer() {
        val auth = RemoteAuth()
        auth.setHeaders("https://cloud.example.com", mapOf("Authorization" to "x"))
        assertEquals("x", auth.headersFor("https://cloud.example.com/remote.php/dav")["Authorization"])
        assertTrue(auth.headersFor("https://cloud.example.com.evil.net/").isEmpty())
        auth.setResolver("subsonic") { "https://music.example.com/rest/stream?id=" + it.substringAfterLast('/') }
        assertEquals("https://music.example.com/rest/stream?id=7", auth.resolve("subsonic:acc/7"))
        assertEquals("content://a", auth.resolve("content://a"))
        assertTrue(auth.isRemote("subsonic:acc/7"))
        assertFalse(auth.isRemote("content://a"))
    }

    @Test fun webDavListDropsTheFolderItselfAndSendsAuth() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(207).body(multistatus).build())
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            val auth = RemoteAuth().apply { setHeaders(base, mapOf("Authorization" to "Basic dGVzdA==")) }
            val dav = WebDavClient(OkHttpClient.Builder().addInterceptor(auth.interceptor).build())
            val list = dav.list("$base/remote.php/dav/files/me/Videos")
            assertEquals(listOf("My Clip.mp4", "Sub Folder"), list.map { it.name })
            val req = server.takeRequest()
            assertEquals("PROPFIND", req.method)
            assertEquals("1", req.headers["Depth"])
            assertEquals("Basic dGVzdA==", req.headers["Authorization"])
        }
    }
}
