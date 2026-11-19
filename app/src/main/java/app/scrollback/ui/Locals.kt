package app.scrollback.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.scrollback.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Opens Settings from the top bar of a main tab (profile/gear button); null outside the tabs. */
val LocalOpenSettings = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
