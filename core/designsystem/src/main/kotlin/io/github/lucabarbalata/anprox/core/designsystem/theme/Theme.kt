package io.github.lucabarbalata.anprox.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** User-selectable theme (questionnaire Q30). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalAnProxColors = staticCompositionLocalOf { DarkColors }

val AnProxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Root theme. Wrap every screen (and every @Preview) in it.
 *
 * @param pureBlack use the AMOLED palette when the resolved theme is dark.
 */
@Composable
fun AnProxTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = when {
        !dark -> LightColors
        pureBlack -> AmoledColors
        else -> DarkColors
    }
    CompositionLocalProvider(LocalAnProxColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = AnProxTypography,
            shapes = AnProxShapes,
            content = content,
        )
    }
}

/** Accessor for the bespoke tokens: `AnProxTheme.colors.accent`. */
object AnProxTheme {
    val colors: AnProxColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAnProxColors.current
}

private fun AnProxColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.18f),
        onPrimaryContainer = textPrimary,
        secondary = accentAlt,
        onSecondary = onAccent,
        secondaryContainer = accentAlt.copy(alpha = 0.18f),
        onSecondaryContainer = textPrimary,
        tertiary = info,
        background = backgroundTop,
        onBackground = textPrimary,
        surface = surfaceSolid,
        onSurface = textPrimary,
        surfaceVariant = glass,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = surfaceSolid,
        surfaceContainerLow = surfaceSolid,
        surfaceContainer = surfaceSolid,
        surfaceContainerHigh = surfaceSolid,
        surfaceContainerHighest = surfaceSolid,
        inverseSurface = textPrimary,
        inverseOnSurface = backgroundTop,
        outline = hairline,
        outlineVariant = hairline,
        error = danger,
        onError = onAccent,
        errorContainer = danger.copy(alpha = 0.16f),
        onErrorContainer = textPrimary,
        scrim = backgroundTop.copy(alpha = 0.6f),
    )
}
