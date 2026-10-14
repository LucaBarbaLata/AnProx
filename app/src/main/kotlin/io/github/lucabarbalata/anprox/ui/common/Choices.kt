package io.github.lucabarbalata.anprox.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/** A tappable row with a radio button, for choices with an explanation. Disabled rows say why in [body]. */
@Composable
fun RadioRow(selected: Boolean, title: String, body: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) c.textPrimary else c.textSecondary)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

/** A card with a message and an indeterminate progress bar, shown while a task runs. */
@Composable
fun BusyCard(text: String) {
    val c = AnProxTheme.colors
    GlassCard {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent)
    }
}
