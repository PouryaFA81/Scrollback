// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbGlass
import app.scrollback.ui.theme.SbMotion
import app.scrollback.ui.theme.SbRadius
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** The haze source of the current screen (scrolling content), if any. Null over video. */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

private val FrostStyle: HazeBlurStyle = HazeBlurStyle {
    blurRadius(SbGlass.blur)
    noiseFactor(SbGlass.noise)
    backgroundColor(SbColors.Background)
    colorEffects(listOf(HazeColorEffect.tint(SbColors.GlassScrim)))
}

private val glassBorder = Brush.verticalGradient(listOf(SbColors.GlassBorderTop, SbColors.GlassBorderBottom))

/**
 * Liquid-glass surface: frosted backdrop blur (RenderEffect on API 31+) when [hazeState] is given,
 * otherwise a cheap translucent dark fill — used over the video feed, where a SurfaceView can't be
 * sampled and blur would cost frames. Both get a 1dp light gradient border and a soft shadow.
 */
fun Modifier.glass(
    shape: Shape,
    hazeState: HazeState? = null,
    elevation: Dp = SbGlass.shadow,
    fill: Color = SbColors.GlassFill,
): Modifier {
    val base = this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
    val frosted = if (hazeState != null) {
        base.hazeBlur(HazeInput.Sources(hazeState), FrostStyle).background(fill)
    } else {
        base.background(SbColors.GlassOverVideo)
    }
    return frosted.border(SbGlass.border, glassBorder, shape)
}

/**
 * Persistent chrome surface (bottom nav, mini player): one fixed token on every screen, so the
 * bar never changes colour with what scrolls behind it. Same border and shadow as [glass].
 */
fun Modifier.chromeGlass(shape: Shape, elevation: Dp = SbGlass.shadow): Modifier =
    this
        .shadow(elevation, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(SbColors.ChromeSurface)
        .border(SbGlass.border, glassBorder, shape)

/** Haptic helpers (Telegram-like crisp ticks). */
@Stable
class Haptics(private val view: android.view.View) {
    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun reject() = view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    fun press() = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Spring scale-down while pressed. */
@Composable
fun Modifier.pressable(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.9f,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, SbMotion.spring(), label = "press")
    return this.scale(s)
}

/** Round glass button for the feed overlay and floating bars. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = SbColors.TextPrimary,
    label: String? = null,
    hazeState: HazeState? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .pressable(interaction)
                .size(size)
                .glass(SbRadius.Pill, hazeState, elevation = 10.dp)
                .clickable(interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.48f))
        }
        if (label != null) {
            Text(
                label,
                color = SbColors.TextPrimary.copy(alpha = 0.85f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier,
            )
        }
    }
}
