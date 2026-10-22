package io.github.lucabarbalata.anprox.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Midnight colour tokens (docs/design-system.md → "Colour"): a flat navy page, solid cards, one
 * accent. (The token names still say "glass": the look was translucent before 0.1.0-beta.3.)
 *
 * The app does not use Material You dynamic colour: the bespoke palette is the identity
 * (questionnaire Q29/Q37). Material components still get a matching [androidx.compose.material3.ColorScheme]
 * derived from these tokens in [AnProxTheme].
 */
@Immutable
data class AnProxColors(
    val isDark: Boolean,
    /** Page background, top → bottom (the same colour: the page is flat). */
    val backgroundTop: Color,
    val backgroundBottom: Color,
    /** Radial glows behind content; transparent since the page went flat. */
    val glowPrimary: Color,
    val glowSecondary: Color,
    /** Fill of cards and the navigation bar. */
    val glass: Color,
    /** A raised surface on a card: secondary buttons, selected rows, icon tiles. */
    val glassStrong: Color,
    /** Dialogs and menus. */
    val surfaceSolid: Color,
    /** Dividers between rows and the track of bars and gauges. */
    val hairline: Color,
    /** Light on a card's top edge; transparent since cards went flat. */
    val highlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentAlt: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val paused: Color,
    val neutral: Color,
)

/** Raw palette. Screens should use [AnProxColors] roles, never these directly. */
internal object Palette {
    val Ink950 = Color(0xFF070A12)
    val Ink900 = Color(0xFF0B0F1A)
    val Ink875 = Color(0xFF0D1220)
    val Ink850 = Color(0xFF111827)
    val Ink825 = Color(0xFF171E30)
    val Ink800 = Color(0xFF161D2E)
    val Ink775 = Color(0xFF1F2840)
    val Ink750 = Color(0xFF252E45)
    val Mist100 = Color(0xFFEDF1F8)
    val Indigo300 = Color(0xFF7C9CFF)
    val Indigo600 = Color(0xFF4458E0)
    val Violet300 = Color(0xFFA78BFA)
    val Violet600 = Color(0xFF7C4DDB)
    val Emerald300 = Color(0xFF34D399)
    val Emerald700 = Color(0xFF047857)
    val Amber300 = Color(0xFFFBBF24)
    val Amber700 = Color(0xFFB45309)
    val Rose300 = Color(0xFFF87171)
    val Rose700 = Color(0xFFC81E1E)
    val Sky300 = Color(0xFF60A5FA)
    val Sky700 = Color(0xFF1D5FD1)
}

val DarkColors = AnProxColors(
    isDark = true,
    backgroundTop = Palette.Ink875,
    backgroundBottom = Palette.Ink875,
    glowPrimary = Color.Transparent,
    glowSecondary = Color.Transparent,
    glass = Palette.Ink825,
    glassStrong = Palette.Ink775,
    surfaceSolid = Palette.Ink825,
    hairline = Palette.Ink750,
    highlight = Color.Transparent,
    textPrimary = Color(0xFFE6EAF2),
    textSecondary = Color(0xFF9AA4B8),
    // 4.6:1 on cards (the old #6B7488 was 3.5:1).
    textTertiary = Color(0xFF7E879B),
    accent = Palette.Indigo300,
    accentAlt = Palette.Violet300,
    onAccent = Palette.Ink950,
    success = Palette.Emerald300,
    warning = Palette.Amber300,
    danger = Palette.Rose300,
    info = Palette.Sky300,
    paused = Palette.Violet300,
    neutral = Color(0xFF6B7488),
)

/** Pure black background for OLED screens (questionnaire Q30). */
val AmoledColors = DarkColors.copy(
    backgroundTop = Color.Black,
    backgroundBottom = Color.Black,
    glass = Color(0xFF0E1118),
    glassStrong = Color(0xFF181D29),
    surfaceSolid = Color(0xFF0E1118),
    hairline = Color(0xFF1C2230),
)

val LightColors = AnProxColors(
    isDark = false,
    backgroundTop = Palette.Mist100,
    backgroundBottom = Palette.Mist100,
    glowPrimary = Color.Transparent,
    glowSecondary = Color.Transparent,
    glass = Color.White,
    glassStrong = Color(0xFFE8EDF6),
    surfaceSolid = Color.White,
    hairline = Color(0xFFE2E8F0),
    highlight = Color.Transparent,
    textPrimary = Color(0xFF0F1729),
    textSecondary = Color(0xFF4A5568),
    // 4.8:1 on white (the old #718096 was 4.0:1).
    textTertiary = Color(0xFF64748B),
    accent = Palette.Indigo600,
    accentAlt = Palette.Violet600,
    onAccent = Color.White,
    success = Palette.Emerald700,
    warning = Palette.Amber700,
    danger = Palette.Rose700,
    info = Palette.Sky700,
    paused = Palette.Violet600,
    neutral = Color(0xFF718096),
)
