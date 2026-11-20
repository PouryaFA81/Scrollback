// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.remote.fs.SmbFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only against a throwaway SMB2 server: SB_SMB_TEST=host:port:share:user:password:localdir. */
class SmbIntegrationTest {
    private val cfg = System.getenv("SB_SMB_TEST")?.split(':', limit = 6)

    @Test fun listReadSeekWrite() {
        assumeTrue(cfg != null && cfg.size == 6)
        val (host, port, share, user, pass) = cfg!!
        val local = java.io.File(cfg[5])
        SmbFs(host, port.toInt(), share, user, pass, null).use { fs ->
            val names = fs.list("/").map { it.name }.toSet()
            assertTrue(names.toString(), names.containsAll(setOf("blob.bin", "sub")))
            assertTrue(fs.list("/sub").any { it.name == "test-clip.mp4" && !it.isDir && it.size > 0 })
            assertEquals(3_000_000L, fs.stat("/blob.bin")!!.size)
            assertTrue(fs.stat("/sub")!!.isDir)
            val expected = java.io.File(local, "blob.bin").readBytes()
            val tail = fs.openRead("/blob.bin", 1_000_000).use { it.readBytes() }
            assertTrue(expected.copyOfRange(1_000_000, 3_000_000).contentEquals(tail))
            fs.openWrite("/sub/w.txt").use { it.write("smb write".toByteArray()) }
            assertEquals("smb write", fs.openRead("/sub/w.txt").use { String(it.readBytes()) })
            fs.delete("/sub/w.txt", isDir = false)
        }
    }
}
