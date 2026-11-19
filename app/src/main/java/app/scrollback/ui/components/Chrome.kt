package app.scrollback.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbMotion
import app.scrollback.ui.theme.SbRadius
import app.scrollback.ui.theme.SbSpace

/** Height reserved at the bottom of scrolling screens for the floating nav pill. */
val FloatingNavReserve: Dp = 96.dp

data class NavItem(val key: String, val label: String, val icon: ImageVector)

/** Detached, pill-shaped frosted-glass bottom navigation with a springy selection bubble. */
@Composable
fun FloatingGlassNav(
    items: List<NavItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    BoxWithConstraints(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = SbSpace.xl, vertical = SbSpace.m)
            .fillMaxWidth()
            .height(64.dp)
            .chromeGlass(SbRadius.Pill),
    ) {
        val itemWidth = maxWidth / items.size
        val index = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
        val bubbleX by animateDpAsState(itemWidth * index, SbMotion.spring(), label = "bubble")
        Box(
            Modifier
                .offset { IntOffset(bubbleX.roundToPx(), 0) }
                .width(itemWidth)
                .fillMaxHeight()
                .padding(6.dp)
                .clip(SbRadius.Pill)
                .background(SbColors.ChromeHighlight),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEach { item ->
                val isSel = item.key == selected
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .pressable(interaction, 0.92f)
                        .clickable(interaction, indication = null) {
                            if (!isSel) haptics.tick()
                            onSelect(item.key)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon, item.label,
                        tint = if (isSel) SbColors.TextPrimary else SbColors.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) SbColors.TextPrimary else SbColors.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One UI-style large title shown as the first item of a scrolling screen. */
@Composable
fun LargeTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, horizontalPadding: Dp = SbSpace.screenH) {
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = horizontalPadding, end = horizontalPadding, top = 72.dp, bottom = SbSpace.xl),
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = SbColors.TextPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(SbSpace.xs))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextSecondary)
        }
    }
}

/**
 * Compact top bar that fades in (frosted) once the large title has scrolled away.
 * Actions are always visible; the back arrow shows when [onBack] is set.
 */
@Composable
fun CollapsingTopBar(
    title: String,
    collapsed: Boolean,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val haze = LocalHazeState.current
    Box(Modifier.fillMaxWidth()) {
        AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp), haze, elevation = 0.dp)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = SbSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack, size = 40.dp, hazeState = haze)
                Spacer(Modifier.width(SbSpace.m))
            }
            AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SbColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!collapsed) Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(SbSpace.s), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** One UI grouped-list card. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(horizontal = SbSpace.l, vertical = SbSpace.s)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = SbColors.TextTertiary,
                modifier = Modifier.padding(start = SbSpace.l, bottom = SbSpace.s),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(SbRadius.Large)
                .background(SbColors.Surface),
            content = content,
        )
    }
}

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = SbSpace.l, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) SbColors.TextPrimary else SbColors.TextTertiary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(SbSpace.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) SbColors.TextPrimary else SbColors.TextTertiary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SbColors.TextSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(SbSpace.m))
            trailing()
        }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 54.dp), thickness = 0.5.dp, color = SbColors.Divider)

/** Small glass pill (segmented control item / chip). */
@Composable
fun GlassPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressable(interaction, 0.94f)
            .clip(SbRadius.Pill)
            .background(if (selected) SbColors.Accent else SbColors.GlassFill)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) SbColors.OnAccent else SbColors.TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) SbColors.OnAccent else SbColors.TextPrimary,
        )
    }
}

/** Calm empty state. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(SbSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = SbColors.TextTertiary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(SbSpace.l))
        Text(title, style = MaterialTheme.typography.titleLarge, color = SbColors.TextPrimary)
        Spacer(Modifier.height(SbSpace.s))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = SbColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(SbSpace.xl))
            action()
        }
    }
}

/** Monochrome switch used across settings screens. */
@Composable
fun SbSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = androidx.compose.material3.Switch(
    checked = checked,
    onCheckedChange = onChange,
    enabled = enabled,
    colors = androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = SbColors.OnAccent,
        checkedTrackColor = SbColors.Accent,
        uncheckedThumbColor = SbColors.TextSecondary,
        uncheckedTrackColor = SbColors.SurfaceHigh,
        uncheckedBorderColor = SbColors.Divider,
    ),
)
