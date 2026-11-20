// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.sync.ItemState
import app.scrollback.sync.PlaylistState
import app.scrollback.sync.SyncDoc
import app.scrollback.sync.SyncJson
import app.scrollback.sync.SyncKeys
import app.scrollback.sync.SyncMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    @Test fun lastWriteWinsPerRecord() {
        val local = SyncDoc(
            items = mapOf("a" to ItemState(liked = true, updatedAt = 200), "b" to ItemState(note = "old", updatedAt = 100), "c" to ItemState(liked = true, updatedAt = 50)),
            playlists = mapOf("p1" to PlaylistState("Mine", 1, listOf("a"), 300)),
        )
        val remote = SyncDoc(
            items = mapOf("a" to ItemState(liked = false, updatedAt = 150), "b" to ItemState(note = "new", updatedAt = 180), "d" to ItemState(hidden = true, updatedAt = 10)),
            playlists = mapOf("p1" to PlaylistState("Theirs", 1, listOf("b"), 250), "p2" to PlaylistState("Remote", 2, emptyList(), 5)),
        )
        val m = SyncMerge.merge(local, remote, now = 999)
        assertEquals(true, m.items["a"]!!.liked)          // local newer
        assertEquals("new", m.items["b"]!!.note)          // remote newer
        assertTrue("c" in m.items && "d" in m.items)      // union
        assertEquals("Mine", m.playlists["p1"]!!.name)
        assertTrue("p2" in m.playlists)
        assertEquals(999, m.updatedAt)

        val incoming = SyncMerge.incoming(local, m)
        assertEquals(setOf("b", "d"), incoming.items.keys)
        assertEquals(setOf("p2"), incoming.playlists.keys)
    }

    @Test fun tiesGoToRemoteAndDeletesPropagate() {
        val local = SyncDoc(playlists = mapOf("p" to PlaylistState("A", 1, listOf("x"), 100)))
        val remote = SyncDoc(playlists = mapOf("p" to PlaylistState("", 0, emptyList(), 100, deleted = true)))
        assertTrue(SyncMerge.merge(local, remote).playlists["p"]!!.deleted)
        val old = SyncDoc(playlists = mapOf("p" to PlaylistState("", 0, emptyList(), 1, deleted = true)))
        assertTrue(SyncMerge.compact(old, now = 1L + 365L * 24 * 3600 * 1000).playlists.isEmpty())
    }

    @Test fun jsonRoundTrip() {
        val doc = SyncDoc(
            items = mapOf("nc:/Videos/a.mp4" to ItemState(liked = true, likedAt = 5, note = "hi \"there\"", tags = listOf("cats", "funny"), lastPositionMs = 1200, lastWatchedAt = 9, watchCount = 3, updatedAt = 10)),
            playlists = mapOf("u1" to PlaylistState("Best", 1, listOf("nc:/Videos/a.mp4", "f:b.mp4|7"), 11)),
            updatedAt = 12,
        )
        assertEquals(doc, SyncJson.decode(SyncJson.encode(doc)))
    }

    @Test fun keys() {
        assertEquals("nc:/V/a.mp4", SyncKeys.forMedia("https://x", "a.mp4", 1, "/V/a.mp4", null, null))
        assertEquals("f:clip.mp4|99", SyncKeys.forMedia("content://x", "Clip.MP4", 99, null, null, null))
        assertEquals("subsonic@h:7", SyncKeys.forMedia("subsonic:a/7", "s", 1, null, "subsonic@h", "7"))
    }
}
