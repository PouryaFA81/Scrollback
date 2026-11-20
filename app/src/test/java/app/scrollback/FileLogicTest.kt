// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback

import app.scrollback.files.FileNames
import app.scrollback.files.FileNode
import app.scrollback.files.FileSort
import app.scrollback.files.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileLogicTest {
    @Test fun uniqueNames() {
        val taken = setOf("clip.mp4", "clip (1).mp4", "Photos")
        assertEquals("clip (2).mp4", FileNames.unique("clip.mp4", false) { it in taken })
        assertEquals("new.mp4", FileNames.unique("new.mp4", false) { it in taken })
        assertEquals("Photos (1)", FileNames.unique("Photos", true) { it in taken })
        assertEquals(".nomedia (1)", FileNames.unique(".nomedia", false) { it == ".nomedia" })
    }

    @Test fun validNames() {
        assertTrue(FileNames.isValid("My clip.mp4"))
        assertFalse(FileNames.isValid("a/b"))
        assertFalse(FileNames.isValid(" "))
        assertFalse(FileNames.isValid(".."))
    }

    private fun n(name: String, dir: Boolean = false, size: Long = 0, mod: Long = 0) =
        FileNode(NodeKind.RAW, "/x/$name", name, dir, size, mod)

    @Test fun sortFoldersFirst() {
        val list = listOf(n("b.mp4", size = 5, mod = 3), n("Zed", dir = true), n("a.txt", size = 9, mod = 1), n("alpha", dir = true))
        assertEquals(listOf("alpha", "Zed", "a.txt", "b.mp4"), FileSort.sort(list, "name", true).map { it.name })
        assertEquals(listOf("Zed", "alpha", "b.mp4", "a.txt"), FileSort.sort(list, "name", false).map { it.name })
        assertTrue(FileSort.sort(list, "size", false).take(2).all { it.isDir })
        assertEquals("a.txt", FileSort.sort(list, "size", false)[2].name)
        assertEquals("b.mp4", FileSort.sort(list, "date", false)[2].name)
    }
}
