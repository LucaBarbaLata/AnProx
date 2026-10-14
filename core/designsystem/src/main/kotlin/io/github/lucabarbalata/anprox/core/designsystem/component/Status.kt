package io.github.lucabarbalata.anprox.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/** Semantic colour roles for status. Never rely on colour alone — always pair with text or an icon. */
enum class Tone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER, INFO, PAUSED }

@Composable
@ReadOnlyComposable
fun Tone.color(): Color {
    val c = AnProxTheme.colors
    return when (this) {
        Tone.NEUTRAL -> c.neutral
        Tone.ACCENT -> c.accent
        Tone.SUCCESS -> c.success
        Tone.WARNING -> c.warning
        Tone.DANGER -> c.danger
        Tone.INFO -> c.info
        Tone.PAUSED -> c.paused
    }
}

/** Usage colour: accent below 75 %, warning from 75 %, danger from 90 %. */
fun toneForUsage(fraction: Double?): Tone = when {
    fraction == null -> Tone.NEUTRAL
    fraction >= 0.9 -> Tone.DANGER
    fraction >= 0.75 -> Tone.WARNING
    else -> Tone.ACCENT
}

/** A small glowing dot. */
@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val color = tone.color()
    Box(
        modifier
            .size(size + 6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** Dot + label in a tinted capsule, e.g. "● Running". */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = tone.color()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A neutral tag chip (Proxmox guest tags, realm names, …). */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    val c = AnProxTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = c.textSecondary,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.hairline)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
