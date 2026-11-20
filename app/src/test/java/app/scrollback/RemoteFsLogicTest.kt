// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.accounts.AccountType
import app.scrollback.remote.fs.RemoteEndpoint
import app.scrollback.remote.fs.RemotePaths
import app.scrollback.remote.fs.RemoteSecret
import app.scrollback.remote.fs.RemoteStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteFsLogicTest {
    @Test fun pathsNormalizeAndJoin() {
        assertEquals("/", RemotePaths.normalize(""))
        assertEquals("/a/c", RemotePaths.normalize("a//b/../c/."))
        assertEquals("/srv/media/x.mp4", RemotePaths.join("/srv/media/", "x.mp4"))
        assertEquals("/srv", RemotePaths.parent("/srv/media"))
        assertEquals("/", RemotePaths.parent("/srv"))
        assertEquals("media", RemotePaths.name("/srv/media/"))
        assertEquals("/a/b", RemotePaths.normalize("\\a\\b"))
    }

    @Test fun rfsUrisRoundTripUnicodeAndSpaces() {
        val uri = RemotePaths.uri("acc-1", "/Videos/سفر 2024/clip #1.mp4")
        assertEquals(true, uri.startsWith("rfs://acc-1/Videos/"))
        assertEquals(false, uri.contains(" "))
        assertEquals("acc-1" to "/Videos/سفر 2024/clip #1.mp4", RemotePaths.parse(uri))
        assertNull(RemotePaths.parse("https://x/y"))
    }

    @Test fun endpointsParseAndPrint() {
        val s = RemoteEndpoint.parse(AccountType.SFTP, "sftp://nas.local:2222/home/me")!!
        assertEquals("nas.local", s.host); assertEquals(2222, s.port); assertEquals("/home/me", s.root)
        assertEquals("sftp://nas.local:2222/home/me", s.toUrl())
        val d = RemoteEndpoint.parse(AccountType.SFTP, "sftp://10.0.0.2")!!
        assertEquals(22, d.port); assertEquals("/", d.root)
        val m = RemoteEndpoint.parse(AccountType.SMB, "smb://192.168.1.5/media/Movies/4K")!!
        assertEquals("media", m.share); assertEquals("/Movies/4K", m.root); assertEquals(445, m.port)
        assertEquals("smb://192.168.1.5:445/media/Movies/4K", m.toUrl())
        assertNull(RemoteEndpoint.parse(AccountType.SMB, "smb://host/"))
    }

    @Test fun keysWithoutLineBreaksAreRewrapped() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(70) + "\n" + "B".repeat(30) + "\n-----END OPENSSH PRIVATE KEY-----\n"
        val flat = key.replace("\n", " ")
        assertEquals(key, app.scrollback.remote.fs.KeyText.normalize(flat))
        assertEquals(key.trim(), app.scrollback.remote.fs.KeyText.normalize(key))
    }

    @Test fun secretsRoundTripAndDomains() {
        val k = RemoteSecret(privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----", passphrase = "pp")
        assertEquals(k, RemoteSecret.parse(k.toJson()))
        assertEquals(RemoteSecret(password = "x"), RemoteSecret.parse(RemoteSecret(password = "x").toJson()))
        assertEquals(RemoteSecret(), RemoteSecret.parse("not json"))
        assertEquals("WORKGROUP" to "alice", RemoteStorage.splitDomain("WORKGROUP\\alice"))
        assertEquals(null to "bob", RemoteStorage.splitDomain("bob"))
    }
}
