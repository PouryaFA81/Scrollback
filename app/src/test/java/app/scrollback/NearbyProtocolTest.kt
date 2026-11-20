// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.nearby.DeviceInfo
import app.scrollback.nearby.DeviceType
import app.scrollback.nearby.FileOffer
import app.scrollback.nearby.Fingerprint
import app.scrollback.nearby.Hex
import app.scrollback.nearby.Identity
import app.scrollback.nearby.Peer
import app.scrollback.nearby.PrepareRequest
import app.scrollback.nearby.PrepareResponse
import app.scrollback.nearby.SafeName
import app.scrollback.nearby.SelfSigned
import app.scrollback.nearby.Subnet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class NearbyProtocolTest {
    private val me = DeviceInfo(alias = "Test phone", fingerprint = "abc123", deviceModel = "Scrollback", deviceType = DeviceType.MOBILE)

    @Test fun deviceInfoJson() {
        val full = me.toJson(full = true, announce = true)
        assertEquals(53317, full.getInt("port")); assertEquals("https", full.getString("protocol"))
        assertEquals("mobile", full.getString("deviceType")); assertTrue(full.getBoolean("announce"))
        assertEquals("2.1", full.getString("version"))
        val short = me.toJson(full = false)
        assertFalse(short.has("port")); assertFalse(short.has("announce"))
        assertEquals(me, DeviceInfo.fromJson(full))
        // Official /info answer: no port/protocol, unknown device type, null model.
        val official = JSONObject("""{"alias":"Laptop","version":"2.0","deviceModel":null,"deviceType":"toaster","fingerprint":"ff","download":true}""")
        val d = DeviceInfo.fromJson(official, fallbackPort = 53318, fallbackProtocol = "http")!!
        assertEquals(DeviceType.DESKTOP, d.deviceType); assertNull(d.deviceModel)
        assertEquals(53318, d.port); assertEquals("http", d.protocol); assertTrue(d.download)
        assertNull(DeviceInfo.fromJson(JSONObject("""{"alias":"x"}""")))
        assertNull(DeviceType.from(null))
    }

    @Test fun peerUrlsAndPinning() {
        val p = Peer(me, "192.168.1.6")
        assertEquals("https://192.168.1.6:53317", p.baseUrl); assertEquals("abc123", p.pin)
        val h = Peer(me.copy(protocol = "http", port = 8080), "fe80::1")
        assertEquals("http://[fe80::1]:8080", h.baseUrl); assertNull(h.pin)
    }

    @Test fun prepareUploadRoundTrip() {
        val files = listOf(
            FileOffer("f1", "clip.mp4", 1234, "video/mp4", sha256 = "aa"),
            FileOffer("t1", "note.txt", 5, "text/plain", preview = "hello"),
        )
        val req = PrepareRequest.parse(PrepareRequest(me, files).toJson())!!
        assertEquals(me, req.info); assertEquals(files.toSet(), req.files.toSet())
        assertTrue(req.files.first { it.id == "t1" }.isText); assertFalse(req.files.first { it.id == "f1" }.isText)
        // Keys are ids; a missing "id" falls back to the key, and odd values are tolerated.
        val loose = PrepareRequest.parse("""{"info":{"alias":"L","fingerprint":"z"},"files":{"k9":{"fileName":"a/b/../x.png","size":-4,"fileType":"","sha256":null}}}""")!!
        val f = loose.files.single()
        assertEquals("k9", f.id); assertEquals(0, f.size); assertEquals("application/octet-stream", f.fileType); assertNull(f.sha256)
        assertNull(PrepareRequest.parse("""{"info":{"alias":"L","fingerprint":"z"},"files":{}}"""))
        assertNull(PrepareRequest.parse("not json"))
        val resp = PrepareResponse.parse(PrepareResponse("s1", mapOf("f1" to "tok")).toJson())!!
        assertEquals("s1", resp.sessionId); assertEquals(mapOf("f1" to "tok"), resp.tokens)
        assertNull(PrepareResponse.parse("""{"files":{}}"""))
    }

    @Test fun selfSignedCertAndFingerprint() {
        val kp = SelfSigned.newKeyPair()
        val cert = SelfSigned.create(kp)
        cert.checkValidity(); cert.verify(kp.public)
        assertEquals("CN=LocalSend User", cert.subjectX500Principal.name)
        assertEquals(3, cert.version)
        val fp = Fingerprint.of(cert)
        assertEquals(64, fp.length); assertEquals(fp.lowercase(), fp)
        assertEquals(Hex.of(MessageDigest.getInstance("SHA-256").digest(cert.encoded)), fp)
        assertEquals(fp.take(4).uppercase() + " " + fp.substring(4, 8).uppercase(), Fingerprint.short(fp))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hex.sha256("abc".toByteArray()))
    }

    @Test fun identityPersists() {
        val dir = Files.createTempDirectory("nearby").toFile()
        val a = Identity.loadOrCreate(dir)
        val b = Identity.loadOrCreate(dir)
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotNull(b.keyStore.getKey(Identity.ALIAS, Identity.PASSWORD))
        dir.deleteRecursively()
    }

    @Test fun safeNames() {
        assertEquals("passwd", SafeName.clean("../../etc/passwd"))
        assertEquals("x.png", SafeName.clean("C:\\Users\\a\\x.png"))
        assertEquals("file", SafeName.clean(".."))
        assertEquals("file", SafeName.clean(""))
        assertEquals("a__b.txt", SafeName.clean("a..b.txt"))
        assertEquals("what_.txt", SafeName.clean("what?.txt"))
        assertTrue(SafeName.clean("é".repeat(300)).toByteArray().size <= 200)
    }

    @Test fun subnetHosts() {
        val h = Subnet.hosts("192.168.1.62", 24)
        assertEquals(253, h.size); assertFalse("192.168.1.62" in h)
        assertEquals("192.168.1.1", h.first()); assertEquals("192.168.1.254", h.last())
        assertEquals(253, Subnet.hosts("10.1.2.3", 8).size) // capped to a /24
        assertEquals(listOf("10.0.0.2"), Subnet.hosts("10.0.0.1", 30))
        assertTrue(Subnet.hosts("bad", 24).isEmpty())
    }
}
