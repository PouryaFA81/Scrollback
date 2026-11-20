// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.scrollback.ui.theme.SbColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Standard non-feed screen: scrolling content that feeds the frosted glass, a One UI large title
 * (rendered by the content as its first item) and a compact bar that appears when it scrolls away.
 */
@Composable
fun GlassScreen(
    title: String,
    collapsed: Boolean,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val provided = LocalHazeState.current
    val own = rememberHazeState()
    val haze = provided ?: own
    CompositionLocalProvider(LocalHazeState provides haze) {
        Box(Modifier.fillMaxSize().background(SbColors.Background)) {
            content(Modifier.fillMaxSize().hazeSource(haze))
            val openSettings = app.scrollback.ui.LocalOpenSettings.current
            CollapsingTopBar(title, collapsed, onBack) {
                actions()
                if (onBack == null && openSettings != null) SettingsButton(openSettings)
            }
            overlay()
        }
    }
}

@Composable
fun LazyListState.collapsedState(): State<Boolean> =
    remember(this) { derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 220 } }

@Composable
fun LazyGridState.collapsedState(): State<Boolean> =
    remember(this) { derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 220 } }

/** Gear in the top bar of every main tab: Settings, servers and accounts live behind it. */
@Composable
fun SettingsButton(onClick: () -> Unit) = GlassIconButton(
    androidx.compose.material.icons.Icons.Outlined.Settings, "Settings and servers", onClick,
    size = 40.dp, hazeState = LocalHazeState.current,
)
