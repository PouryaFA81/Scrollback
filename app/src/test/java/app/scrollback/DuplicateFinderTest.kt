// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.core.hash.DuplicateFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class DuplicateFinderTest {
    private class Mem(val bytes: ByteArray) : DuplicateFinder.ByteSource {
        var opens = 0
        override val size: Long get() = bytes.size.toLong()
        override fun readRange(offset: Long, length: Int): ByteArray { opens++; return bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }
        override fun openStream() = ByteArrayInputStream(bytes).also { opens++ }
    }

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    @Test fun identicalFilesAreGrouped() {
        val a = bytes(300_000, 1)
        val files = mapOf("a" to Mem(a), "b" to Mem(a.copyOf()), "c" to Mem(bytes(300_000, 2)))
        val groups = DuplicateFinder.findGroups(files.map { DuplicateFinder.Candidate(it.key, it.value.size) }, { files.getValue(it) })
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups.single().toSet())
    }

    @Test fun middleDifferenceCaughtByFullHash() {
        val a = bytes(400_000, 3)
        val b = a.copyOf().also { it[200_000] = (it[200_000] + 1).toByte() } // head/tail identical
        val sa = Mem(a); val sb = Mem(b)
        assertEquals(DuplicateFinder.partialHash(sa), DuplicateFinder.partialHash(sb))
        assertNotEquals(DuplicateFinder.fullHash(sa), DuplicateFinder.fullHash(sb))
        val groups = DuplicateFinder.findGroups(
            listOf(DuplicateFinder.Candidate("a", sa.size), DuplicateFinder.Candidate("b", sb.size)),
            { if (it == "a") sa else sb },
        )
        assertTrue(groups.isEmpty())
    }

    @Test fun differentSizesNeverOpened() {
        val x = Mem(bytes(1000, 4)); val y = Mem(bytes(2000, 4))
        val groups = DuplicateFinder.findGroups(
            listOf(DuplicateFinder.Candidate("x", x.size), DuplicateFinder.Candidate("y", y.size)),
            { if (it == "x") x else y },
        )
        assertTrue(groups.isEmpty())
        assertEquals(0, x.opens + y.opens)
    }

    @Test fun cachedHashesSkipIo() {
        val m = Mem(bytes(10, 5))
        val groups = DuplicateFinder.findGroups(
            listOf(
                DuplicateFinder.Candidate("a", 10, "p", "f"),
                DuplicateFinder.Candidate("b", 10, "p", "f"),
            ),
            { m },
        )
        assertEquals(listOf(listOf("a", "b")), groups)
        assertEquals(0, m.opens)
    }

    @Test fun smallFilesAndCallbacks() {
        val a = bytes(100, 6)
        val hashed = mutableListOf<String>()
        val groups = DuplicateFinder.findGroups(
            listOf(DuplicateFinder.Candidate("a", 100), DuplicateFinder.Candidate("b", 100)),
            { Mem(a) },
            onHashed = { k, _, _ -> hashed += k },
        )
        assertEquals(1, groups.size)
        assertEquals(4, hashed.size) // partial + full for each
    }
}
