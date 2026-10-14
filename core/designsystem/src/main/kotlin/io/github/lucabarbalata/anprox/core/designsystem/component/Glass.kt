package io.github.lucabarbalata.anprox.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/**
 * The page backdrop: a deep vertical gradient with two soft radial glows. Glass cards are
 * translucent, so this is what gives them their "frosted" depth.
 *
 * Real backdrop blur needs API 31+ and costs GPU time on every frame; the translucent layering
 * achieves the look on every supported device (API 26+). See docs/design-system.md.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = AnProxTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.backgroundTop, c.backgroundBottom)))
            .drawBehind {
                val r1 = size.maxDimension * 0.65f
                val p1 = Offset(size.width * 0.15f, size.height * 0.02f)
                drawCircle(Brush.radialGradient(listOf(c.glowPrimary, Color.Transparent), center = p1, radius = r1), radius = r1, center = p1)
                val r2 = size.maxDimension * 0.55f
                val p2 = Offset(size.width * 0.95f, size.height * 0.85f)
                drawCircle(Brush.radialGradient(listOf(c.glowSecondary, Color.Transparent), center = p2, radius = r2), radius = r2, center = p2)
            },
        content = content,
    )
}

/**
 * A frosted-glass card: translucent fill, a faint highlight on the top edge and a 1dp hairline.
 * Pass [onClick] to make the whole card a button.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AnProxTheme.colors
    var m = modifier
        .clip(shape)
        .background(if (strong) c.glassStrong else c.glass)
        .background(Brush.verticalGradient(0f to c.highlight, 0.35f to Color.Transparent))
        .border(1.dp, c.hairline, shape)
    if (onClick != null) m = m.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    Column(m.padding(contentPadding), content = content)
}
