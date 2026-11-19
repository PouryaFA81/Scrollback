package app.scrollback.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.scrollback.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
