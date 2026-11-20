// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.files

/** Pure sorting for file lists: folders first, then by the chosen key (unit-tested). */
object FileSort {
    fun sort(nodes: List<FileNode>, by: String, ascending: Boolean): List<FileNode> {
        val cmp: Comparator<FileNode> = when (by) {
            "date" -> compareBy { it.modified }
            "size" -> compareBy { it.size }
            "type" -> compareBy<FileNode> { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (ascending) cmp else cmp.reversed()
        return nodes.sortedWith(compareBy<FileNode> { !it.isDir }.then(ordered))
    }
}
