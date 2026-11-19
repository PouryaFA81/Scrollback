package app.scrollback.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Scrollback design tokens.
 *
 * References: X / Grok (true-black OLED, white/grey type, monochrome line icons, near-zero accent),
 * iOS "liquid glass" (frosted translucent floating surfaces), Samsung One UI (large collapsing titles,
 * generous spacing, big radii), Telegram (springy motion + haptics), Claude / Obsidian (calm typography).
 */
object SbColors {
    val Background = Color(0xFF000000)          // true black, OLED
    val Surface = Color(0xFF0B0B0C)             // barely lifted cards
    val SurfaceHigh = Color(0xFF151517)
    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFF9A9A9E)
    val TextTertiary = Color(0xFF5C5C61)
    val Divider = Color(0x1AFFFFFF)

    /** The one restrained accent: near-white, used for selection and primary actions. */
    val Accent = Color(0xFFE7E9EA)
    val OnAccent = Color(0xFF000000)

    /** Semantic only: the like heart. */
    val Like = Color(0xFFF91880)
    val Danger = Color(0xFFFF453A)

    // Glass
    val GlassFill = Color(0x1FFFFFFF)           // 12% white over blur
    val GlassFillStrong = Color(0x33FFFFFF)
    val GlassScrim = Color(0x8C000000)          // dark tint under the frost
    val GlassOverVideo = Color(0x59000000)      // translucent disc over video (no blur, cheap)
    val GlassBorderTop = Color(0x33FFFFFF)
    val GlassBorderBottom = Color(0x0DFFFFFF)

    /**
     * Fixed surface for persistent chrome (bottom nav, mini player). Near-opaque so it looks identical
     * over video, grids, lists and empty black screens — no backdrop sampling, no varying tint.
     */
    val ChromeSurface = Color(0xF0121214)       // 94% #121214
    val ChromeHighlight = Color(0x24FFFFFF)     // selection bubble, 14% white
}

object SbRadius {
    val Small = RoundedCornerShape(14.dp)
    val Card = RoundedCornerShape(20.dp)
    val Large = RoundedCornerShape(24.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

object SbSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screenH = 20.dp
}

object SbGlass {
    val blur = 28.dp
    val border = 1.dp
    val shadow = 18.dp
    val noise = 0.04f
}

object SbMotion {
    /** Telegram-like: quick, slightly bouncy. */
    fun <T> spring() = spring<T>(dampingRatio = 0.78f, stiffness = 420f)
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
