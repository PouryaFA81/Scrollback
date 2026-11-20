// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Swipe left/right anywhere on a screen to move between its chip sub-tabs (Library, Music…).
 * Vertical scrolling keeps working because the drag only starts after horizontal touch slop,
 * and children that consume drags (sliders, horizontal chip rows) win.
 */
fun Modifier.swipeTabs(count: Int, index: Int, onChange: (Int) -> Unit): Modifier = composed {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val current by rememberUpdatedState(index)
    val change by rememberUpdatedState(onChange)
    pointerInput(count) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val target = SwipeTabsLogic.target(current, count, total, threshold)
                if (target != current) change(target)
            },
            onDragCancel = { total = 0f },
        ) { _, dx -> total += dx }
    }
}

/** Pure decision used by [swipeTabs]; unit-tested. */
object SwipeTabsLogic {
    fun target(current: Int, count: Int, dx: Float, threshold: Float): Int {
        val t = when { dx <= -threshold -> current + 1; dx >= threshold -> current - 1; else -> current }
        return if (t in 0 until count) t else current
    }
}
