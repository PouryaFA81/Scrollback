// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.core

/** Stable ordering helpers for feeds. */
object FeedOrder {
    /**
     * Deterministic shuffle: the same ids and seed always give the same order. v0.15: each id gets
     * its own seeded sort key, so adding or removing a video never reshuffles the others (the
     * Random feed keeps its order across rescans, restarts and screen lock).
     */
    fun shuffle(ids: List<Long>, seed: Long): List<Long> = ids.sortedWith(compareBy<Long> { mix(it xor seed) }.thenBy { it })

    /** SplitMix64 finaliser: a cheap, well-spread hash. */
    internal fun mix(v: Long): Long {
        var z = v + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /**
     * Keeps an existing order stable while the underlying data changes: known ids keep
     * their position, removed ids disappear, new ids are appended in the given order.
     */
    fun merge(snapshot: List<Long>, latest: List<Long>): List<Long> {
        val latestSet = latest.toHashSet()
        val kept = snapshot.filter { it in latestSet }
        val keptSet = kept.toHashSet()
        return kept + latest.filter { it !in keptSet }
    }
}

/**
 * v0.15: where you were in a feed tab (the item, its index and, for Random, the shuffle seed).
 * Saved to disk so switching Random/Saved, leaving the tab, locking the phone or Android
 * closing the app in the background never sends the feed back to the top.
 */
data class FeedPos(val seed: Long, val id: Long, val index: Int) {
    fun encode(): String = "$seed,$id,$index"

    companion object {
        fun decode(s: String?): FeedPos? {
            val p = s?.split(',') ?: return null
            if (p.size != 3) return null
            return FeedPos(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
        }

        /**
         * The page to open: an explicit [startId] wins (opening a video from a list), then the saved
         * item, then — if that item is gone — the nearest index, so you land where you were.
         * A saved position from another shuffle ([seed] mismatch) is ignored.
         */
        fun startIndex(ids: List<Long>, startId: Long, saved: FeedPos?, seed: Long = 0L): Int {
            if (ids.isEmpty()) return 0
            if (startId >= 0) ids.indexOf(startId).takeIf { it >= 0 }?.let { return it }
            if (saved == null || saved.seed != seed) return 0
            ids.indexOf(saved.id).takeIf { it >= 0 }?.let { return it }
            return saved.index.coerceIn(0, ids.size - 1)
        }
    }
}

/** v0.15: the Saved feed's folder chooser. "" = all folders; otherwise "<sourceId>:<sub/path>". */
data class FolderFilter(val sourceId: Long, val path: String = "") {
    fun encode(): String = "$sourceId:$path"

    fun matches(sourceId: Long, relativePath: String): Boolean =
        sourceId == this.sourceId && (path.isEmpty() || relativePath == path || relativePath.startsWith("$path/"))

    companion object {
        fun decode(s: String?): FolderFilter? {
            if (s.isNullOrBlank()) return null
            val id = s.substringBefore(':').toLongOrNull() ?: return null
            return FolderFilter(id, s.substringAfter(':', "").trim('/'))
        }

        /**
         * Every folder that holds videos, with its parents, sorted by path: what the chooser lists
         * under each source. [paths] are the items' relativePath values ("" = the source's root).
         */
        fun subfolders(paths: Collection<String>): List<String> {
            val out = sortedSetOf<String>()
            for (p in paths) {
                val parts = p.trim('/').split('/').filter { it.isNotEmpty() }
                for (i in 1..parts.size) out += parts.subList(0, i).joinToString("/")
            }
            return out.toList()
        }
    }
}
