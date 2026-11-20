// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.remote.fs.HostKeyMismatchException
import app.scrollback.remote.fs.SftpAuth
import app.scrollback.remote.fs.SftpFs
import app.scrollback.remote.fs.UnknownHostKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs only against a throwaway local sshd: SB_SFTP_TEST=host:port:user:/path/to/private_key:expected_fp
 * (see scripts/test-servers.sh). Skipped everywhere else.
 */
class SftpIntegrationTest {
    private val cfg = System.getenv("SB_SFTP_TEST")?.split(':', limit = 5)

    @Test fun tofuThenFullRoundTrip() {
        assumeTrue(cfg != null && cfg.size == 5)
        val (host, port, user, keyPath, fp) = cfg!!
        val auth = SftpAuth.Key(File(keyPath).readText(), null)

        val unknown = runCatching { SftpFs(host, port.toInt(), user, auth, null) }.exceptionOrNull()
        assertTrue("expected TOFU prompt, got $unknown", unknown is UnknownHostKeyException)
        assertEquals(fp, (unknown as UnknownHostKeyException).fingerprint)

        val wrong = runCatching { SftpFs(host, port.toInt(), user, auth, "SHA256:AAAA") }.exceptionOrNull()
        assertTrue(wrong is HostKeyMismatchException)

        SftpFs(host, port.toInt(), user, auth, fp).use { fs ->
            val root = "/tmp/sbtest/data"
            val names = fs.list(root).map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("blob.bin", "sub")))
            val blob = fs.stat("$root/blob.bin")!!
            assertEquals(3_000_000L, blob.size)
            val expected = File("$root/blob.bin").readBytes()
            val tail = fs.openRead("$root/blob.bin", 2_999_000).use { it.readBytes() }
            assertTrue(expected.copyOfRange(2_999_000, 3_000_000).contentEquals(tail))
            fs.mkdir("$root/new")
            fs.openWrite("$root/new/a.txt").use { it.write("written by scrollback".toByteArray()) }
            fs.rename("$root/new/a.txt", "$root/new/b.txt")
            assertEquals("written by scrollback", fs.openRead("$root/new/b.txt").use { String(it.readBytes()) })
            fs.delete("$root/new", isDir = true)
            assertEquals(null, fs.stat("$root/new"))
        }
    }
}
