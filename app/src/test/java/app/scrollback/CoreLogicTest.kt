package app.scrollback

import app.scrollback.core.FeedOrder
import app.scrollback.core.security.PinHasher
import app.scrollback.core.text.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test fun tagsParse() {
        assertEquals(listOf("funny", "cooking", "recipes"), Tags.parse("#Funny, cooking  #recipes #funny"))
        assertEquals(emptyList<String>(), Tags.parse("  , # "))
        assertEquals("#a #b", Tags.format(listOf("a", "b")))
    }

    @Test fun shuffleIsDeterministicAndComplete() {
        val ids = (1L..50L).toList()
        val a = FeedOrder.shuffle(ids, 42)
        assertEquals(a, FeedOrder.shuffle(ids.reversed(), 42))
        assertEquals(ids.toSet(), a.toSet())
        assertNotEquals(a, FeedOrder.shuffle(ids, 43))
    }

    @Test fun mergeKeepsOrderStable() {
        assertEquals(listOf(3L, 1L, 4L), FeedOrder.merge(listOf(3L, 1L, 2L), listOf(1L, 3L, 4L)))
        assertEquals(listOf(1L, 2L), FeedOrder.merge(emptyList(), listOf(1L, 2L)))
    }

    @Test fun pinHashing() {
        val h = PinHasher.hash("1234")
        assertTrue(PinHasher.verify("1234", h.salt, h.hash))
        assertFalse(PinHasher.verify("4321", h.salt, h.hash))
        assertNotEquals(h.hash, PinHasher.hash("1234").hash) // salted
        assertTrue(PinHasher.isValidPin("0000"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin("123"))
    }
}
