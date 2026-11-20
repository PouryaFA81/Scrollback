// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.files

/** Pure helpers for file names (unit-tested). */
object FileNames {
    private val illegal = Regex("""[\\/:*?"<>|\u0000]""")

    fun isValid(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." && !illegal.containsMatchIn(name)

    /** "clip.mp4" -> "clip (1).mp4", "clip (2).mp4", ... until [taken] says it's free. Folders have no extension. */
    fun unique(name: String, isDir: Boolean, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = if (isDir) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (!taken(candidate)) return candidate
            i++
        }
    }
}
