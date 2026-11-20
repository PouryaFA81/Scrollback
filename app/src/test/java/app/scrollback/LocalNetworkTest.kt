// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.remote.LocalNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.15: which server addresses need Android 17's local-network permission. */
class LocalNetworkTest {
    private val noDns: (String) -> List<ByteArray> = { emptyList() }

    @Test fun hostOf() {
        assertEquals("10.0.0.5", LocalNetwork.hostOf("https://10.0.0.5:8096/jellyfin"))
        assertEquals("nas.local", LocalNetwork.hostOf("sftp://me@nas.local:22/home"))
        assertEquals("fileserver", LocalNetwork.hostOf("smb://fileserver/share"))
        assertEquals("192.168.1.9", LocalNetwork.hostOf("192.168.1.9:4533"))
        assertEquals("fd00::1", LocalNetwork.hostOf("http://[fd00::1]:8080/"))
        assertEquals("cloud.example.com", LocalNetwork.hostOf("https://Cloud.Example.com"))
        assertNull(LocalNetwork.hostOf("")); assertNull(LocalNetwork.hostOf(null))
    }

    @Test fun literalAddresses() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.10", "169.254.1.1", "fd12::1", "fe80::1", "::ffff:192.168.1.2")
            .forEach { assertTrue(it, LocalNetwork.isLocalHost(it, noDns)) }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "1.1.1.1", "2001:4860::8888", "127.0.0.1", "localhost")
            .forEach { assertFalse(it, LocalNetwork.isLocalHost(it, noDns)) }
    }

    @Test fun hostNames() {
        assertTrue(LocalNetwork.isLocalHost("nas.local", noDns))
        assertTrue(LocalNetwork.isLocalHost("media.home.arpa", noDns))
        assertTrue(LocalNetwork.isLocalHost("jellyfin", noDns))
        assertFalse(LocalNetwork.isLocalHost("vert.sh", noDns))
        // Split DNS: a public-looking name that points at a LAN address counts as local.
        assertTrue(LocalNetwork.isLocalHost("cloud.example.com") { listOf(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)) })
        assertFalse(LocalNetwork.isLocalHost("cloud.example.com") { listOf(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)) })
        assertFalse(LocalNetwork.isLocalHost("broken.example.com") { throw java.net.UnknownHostException() })
    }
}
