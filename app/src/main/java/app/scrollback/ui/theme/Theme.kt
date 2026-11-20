// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pourya FA
package app.scrollback.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

private val SbTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.015).em),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.02.em),
)

private val SbScheme = darkColorScheme(
    primary = SbColors.Accent,
    onPrimary = SbColors.OnAccent,
    primaryContainer = SbColors.SurfaceHigh,
    onPrimaryContainer = SbColors.TextPrimary,
    secondary = SbColors.TextSecondary,
    onSecondary = SbColors.Background,
    secondaryContainer = SbColors.SurfaceHigh,
    onSecondaryContainer = SbColors.TextPrimary,
    tertiary = SbColors.TextSecondary,
    background = SbColors.Background,
    onBackground = SbColors.TextPrimary,
    surface = SbColors.Background,
    onSurface = SbColors.TextPrimary,
    surfaceVariant = SbColors.SurfaceHigh,
    onSurfaceVariant = SbColors.TextSecondary,
    surfaceContainerLowest = SbColors.Background,
    surfaceContainerLow = SbColors.Surface,
    surfaceContainer = SbColors.Surface,
    surfaceContainerHigh = SbColors.SurfaceHigh,
    surfaceContainerHighest = SbColors.SurfaceHigh,
    outline = SbColors.TextTertiary,
    outlineVariant = SbColors.Divider,
    error = SbColors.Danger,
)

/** Dark-only theme. There is intentionally no light variant. */
@Composable
fun ScrollbackTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SbScheme, typography = SbTypography, content = content)
}
