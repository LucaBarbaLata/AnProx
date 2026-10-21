package io.github.lucabarbalata.anprox.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

private val gaugeSpring = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)

/**
 * Circular usage gauge. Animates between values with a spring.
 *
 * @param fraction 0..1, or null when unknown (draws only the track).
 * @param label text in the middle, e.g. "42%".
 * @param caption small text under the label, e.g. "CPU".
 * @param description spoken by TalkBack, e.g. "CPU 42 percent".
 */
@Composable
fun UsageRing(
    fraction: Double?,
    label: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    description: String = listOfNotNull(caption, label).joinToString(" "),
    diameter: Dp = 76.dp,
    stroke: Dp = 7.dp,
    tone: Tone = toneForUsage(fraction),
) {
    val c = AnProxTheme.colors
    val color = tone.color()
    val animated by animateFloatAsState((fraction ?: 0.0).toFloat().coerceIn(0f, 1f), gaugeSpring, label = "ring")
    Box(
        modifier
            .size(diameter)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo((fraction ?: 0.0).toFloat(), 0f..1f)
            }
            .drawBehind {
                val w = stroke.toPx()
                val inset = w / 2
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(c.hairline, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
                if (fraction != null && animated > 0f) {
                    drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
    }
}

/** Thin horizontal usage bar. */
@Composable
fun UsageBar(
    fraction: Double?,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    tone: Tone = toneForUsage(fraction),
) {
    val c = AnProxTheme.colors
    val color = tone.color()
    val animated by animateFloatAsState((fraction ?: 0.0).toFloat().coerceIn(0f, 1f), gaugeSpring, label = "bar")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(c.hairline),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}
