package app.scrollback

import app.scrollback.remote.HttpException
import app.scrollback.remote.music.JellyfinAuth
import app.scrollback.remote.music.MusicDates
import app.scrollback.remote.music.SubsonicAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MusicLogicTest {
    @Test fun subsonicTokenMatchesSpecExample() {
        // Example from the Subsonic API docs: password "sesame", salt "c19b2d".
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicAuth.token("sesame", "c19b2d"))
    }

    @Test fun subsonicQueryNeverContainsThePassword() {
        val q = SubsonicAuth.query("joe doe", "sesame", "c19b2d")
        assertTrue(q.startsWith("u=joe+doe&t=26719a1196d2a940705a59634eb18eab&s=c19b2d&v=1.16.1&c=Scrollback&f=json"))
        assertFalse(q.contains("sesame"))
        val salts = (1..20).map { SubsonicAuth.newSalt() }.toSet()
        assertEquals(20, salts.size)
    }

    @Test fun subsonicErrorsAreSurfaced() {
        val ok = SubsonicAuth.unwrap("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
        assertEquals("1.16.1", ok.getString("version"))
        try {
            SubsonicAuth.unwrap("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""")
            fail()
        } catch (e: HttpException) {
            assertEquals(40, e.code)
            assertTrue(e.message!!.contains("Wrong username"))
        }
    }

    @Test fun jellyfinHeader() {
        assertEquals(
            "MediaBrowser Client=\"Scrollback\", Device=\"Android\", DeviceId=\"dev1\", Version=\"0.4.0\", Token=\"tok\"",
            JellyfinAuth.header("dev1", "0.4.0", "tok"),
        )
        assertEquals("tok", JellyfinAuth.authHeaders("d", "v", "tok")["X-Emby-Token"])
    }

    @Test fun parsesServerDates() {
        assertEquals(1794996000000L, MusicDates.parseIso("2026-11-18T10:00:00Z"))
        assertEquals(1794996000000L, MusicDates.parseIso("2026-11-18T10:00:00.0000000Z"))
        assertEquals(1794996000000L, MusicDates.parseIso("2026-11-18T10:00:00"))
        assertEquals(0L, MusicDates.parseIso(""))
    }
}
