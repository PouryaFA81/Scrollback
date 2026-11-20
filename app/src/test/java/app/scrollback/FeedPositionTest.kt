// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.core.FeedOrder
import app.scrollback.core.FeedPos
import app.scrollback.core.FolderFilter
import app.scrollback.feature.feed.FeedSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.15: the Feed keeps its place (Random/Saved, shuffle, current item) across rebuilds. */
class FeedPositionTest {
    private val ids = (1L..200L).toList()

    @Test fun shuffleIsStableWhenVideosAreAddedOrRemoved() {
        val a = FeedOrder.shuffle(ids, 7)
        val withNew = FeedOrder.shuffle(ids + listOf(500L, 501L), 7)
        assertEquals(a, withNew.filter { it in ids.toSet() })
        val without = FeedOrder.shuffle(ids - 50L, 7)
        assertEquals(a - 50L, without)
        assertEquals(a, FeedOrder.shuffle(ids.shuffled(), 7))
    }

    @Test fun posEncodeDecode() {
        val p = FeedPos(1732100000000L, 42L, 17)
        assertEquals(p, FeedPos.decode(p.encode()))
        assertNull(FeedPos.decode("garbage"))
        assertNull(FeedPos.decode(null))
        assertNull(FeedPos.decode("1,2"))
    }

    /**
     * Regression for "locking the phone sends the feed back to the start": the screen is rebuilt
     * (PIN re-lock, Android closing the app, config change) and must reopen at the same item.
     */
    @Test fun rebuiltFeedReopensAtSavedItem() {
        val seed = 99L
        val order = FeedOrder.shuffle(ids, seed)
        val watching = order[123]
        val saved = FeedPos(seed, watching, 123)
        // Rebuild: same seed (now persisted) gives the same order and the saved item opens.
        val rebuilt = FeedOrder.shuffle(ids, seed)
        assertEquals(watching, rebuilt[FeedPos.startIndex(rebuilt, -1, FeedPos.decode(saved.encode()), seed)])
        // A rescan added videos meanwhile: still the same item.
        val grown = FeedOrder.shuffle(ids + (300L..320L), seed)
        assertEquals(watching, grown[FeedPos.startIndex(grown, -1, saved, seed)])
        // The saved video was deleted: stay near where you were, not the top.
        val shrunk = rebuilt - watching
        assertEquals(123, FeedPos.startIndex(shrunk, -1, saved, seed))
    }

    @Test fun startIndexRules() {
        val l = listOf(10L, 20L, 30L)
        assertEquals(2, FeedPos.startIndex(l, 30L, FeedPos(0, 10L, 0)))       // explicit start wins
        assertEquals(1, FeedPos.startIndex(l, -1, FeedPos(0, 20L, 0)))        // saved item
        assertEquals(0, FeedPos.startIndex(l, -1, FeedPos(5, 20L, 1), seed = 6)) // other shuffle -> top
        assertEquals(2, FeedPos.startIndex(l, -1, FeedPos(0, 99L, 9)))        // gone -> clamp
        assertEquals(0, FeedPos.startIndex(emptyList(), -1, FeedPos(0, 1L, 3)))
        assertEquals(0, FeedPos.startIndex(l, -1, null))
    }

    @Test fun positionKeysPerTab() {
        assertEquals("random", FeedSpec.Random(1).positionKey)
        assertEquals(FeedSpec.Random(1).positionKey, FeedSpec.Random(2).positionKey)
        assertTrue(FeedSpec.Saved(true).positionKey != FeedSpec.Saved(false).positionKey)
        assertTrue(FeedSpec.Saved(true, "3:").positionKey != FeedSpec.Saved(true).positionKey)
        assertNull(FeedSpec.Liked.positionKey)
    }

    @Test fun folderFilter() {
        val f = FolderFilter.decode("3:Clips/2024")!!
        assertEquals(FolderFilter(3, "Clips/2024"), f)
        assertTrue(f.matches(3, "Clips/2024"))
        assertTrue(f.matches(3, "Clips/2024/June"))
        assertFalse(f.matches(3, "Clips/20245"))
        assertFalse(f.matches(4, "Clips/2024"))
        val whole = FolderFilter.decode("3:")!!
        assertTrue(whole.matches(3, "")); assertTrue(whole.matches(3, "a/b"))
        assertNull(FolderFilter.decode("")); assertNull(FolderFilter.decode("x:y"))
        assertEquals(f, FolderFilter.decode(f.encode()))
        assertEquals(listOf("a", "a/b", "a/b/c", "z"), FolderFilter.subfolders(listOf("", "a/b/c", "a", "z/")))
    }
}
