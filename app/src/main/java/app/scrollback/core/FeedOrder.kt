// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.core

import kotlin.random.Random

/** Stable ordering helpers for feeds. */
object FeedOrder {
    /** Deterministic shuffle: the same ids and seed always give the same order. */
    fun shuffle(ids: List<Long>, seed: Long): List<Long> = ids.sorted().shuffled(Random(seed))

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
