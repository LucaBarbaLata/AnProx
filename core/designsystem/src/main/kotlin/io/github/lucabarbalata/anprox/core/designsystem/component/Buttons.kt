package io.github.lucabarbalata.anprox.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER }

/**
 * Pill button. PRIMARY has the indigo→violet glow gradient, SECONDARY is glass, DANGER is a
 * tinted red outline. Height ≥ 48dp for touch accessibility.
 */
@Composable
fun AnProxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = AnProxTheme.colors
    val shape = RoundedCornerShape(50)
    val (background, content) = when (style) {
        ButtonStyle.PRIMARY -> Brush.horizontalGradient(listOf(c.accent, c.accentAlt)) to c.onAccent
        ButtonStyle.SECONDARY -> Brush.linearGradient(listOf(c.glass, c.glass)) to c.textPrimary
        ButtonStyle.DANGER -> Brush.linearGradient(listOf(c.danger.copy(alpha = 0.14f), c.danger.copy(alpha = 0.14f))) to c.danger
    }
    val border = when (style) {
        ButtonStyle.PRIMARY -> Color.Transparent
        ButtonStyle.SECONDARY -> c.hairline
        ButtonStyle.DANGER -> c.danger.copy(alpha = 0.4f)
    }
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = content, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
