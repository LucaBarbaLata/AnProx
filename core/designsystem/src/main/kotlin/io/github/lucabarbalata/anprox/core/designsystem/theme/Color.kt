package io.github.lucabarbalata.anprox.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Midnight Glass colour tokens (docs/design-system.md → "Colour").
 *
 * The app does not use Material You dynamic colour: the bespoke palette is the identity
 * (questionnaire Q29/Q37). Material components still get a matching [androidx.compose.material3.ColorScheme]
 * derived from these tokens in [AnProxTheme].
 */
@Immutable
data class AnProxColors(
    val isDark: Boolean,
    /** Page background gradient, top → bottom. */
    val backgroundTop: Color,
    val backgroundBottom: Color,
    /** Soft radial glows painted behind content. */
    val glowPrimary: Color,
    val glowSecondary: Color,
    /** Translucent fill of glass cards. */
    val glass: Color,
    /** Denser glass for floating bars and sheets. */
    val glassStrong: Color,
    /** Opaque fallback for dialogs and menus. */
    val surfaceSolid: Color,
    /** 1dp border of glass surfaces. */
    val hairline: Color,
    /** Light catching the top edge of a glass card. */
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
    val Ink850 = Color(0xFF111827)
    val Ink800 = Color(0xFF161D2E)
    val Mist50 = Color(0xFFF6F8FC)
    val Mist100 = Color(0xFFEDF1F8)
    val Mist200 = Color(0xFFE2E8F3)
    val Indigo300 = Color(0xFF7C9CFF)
    val Indigo600 = Color(0xFF4458E0)
    val Violet300 = Color(0xFFA78BFA)
    val Violet600 = Color(0xFF7C4DDB)
    val Teal300 = Color(0xFF2DD4BF)
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
    backgroundTop = Palette.Ink900,
    backgroundBottom = Palette.Ink850,
    glowPrimary = Palette.Indigo300.copy(alpha = 0.16f),
    glowSecondary = Palette.Teal300.copy(alpha = 0.08f),
    glass = Color.White.copy(alpha = 0.06f),
    glassStrong = Color(0xE6161D2E),
    surfaceSolid = Palette.Ink800,
    hairline = Color.White.copy(alpha = 0.10f),
    highlight = Color.White.copy(alpha = 0.05f),
    textPrimary = Color(0xFFE6EAF2),
    textSecondary = Color(0xFF9AA4B8),
    textTertiary = Color(0xFF6B7488),
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

/** Pure black background for OLED screens (questionnaire Q30). Glows are dimmed. */
val AmoledColors = DarkColors.copy(
    backgroundTop = Color.Black,
    backgroundBottom = Color.Black,
    glowPrimary = Palette.Indigo300.copy(alpha = 0.08f),
    glowSecondary = Color.Transparent,
    glass = Color.White.copy(alpha = 0.05f),
    glassStrong = Color(0xF00A0D14),
    surfaceSolid = Color(0xFF0D1117),
)

val LightColors = AnProxColors(
    isDark = false,
    backgroundTop = Palette.Mist50,
    backgroundBottom = Palette.Mist200,
    glowPrimary = Palette.Indigo300.copy(alpha = 0.22f),
    glowSecondary = Palette.Teal300.copy(alpha = 0.14f),
    glass = Color.White.copy(alpha = 0.66f),
    glassStrong = Color(0xF2FFFFFF),
    surfaceSolid = Color.White,
    hairline = Palette.Ink900.copy(alpha = 0.08f),
    highlight = Color.White.copy(alpha = 0.55f),
    textPrimary = Color(0xFF0F1729),
    textSecondary = Color(0xFF4A5568),
    textTertiary = Color(0xFF718096),
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
