package io.github.lucabarbalata.anprox.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/** One entry of a [ToolGrid]: a short label under an icon. [danger] draws it in red (delete). */
data class ToolEntry(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * A screen's tools as a grid of icons with short labels, in one card. Up to [visibleCount] fit
 * without folding; with more, the last cell is "More" and opens the rest in place (remembered
 * while the screen lives). Order [tools] by how often they're used.
 */
@Composable
fun ToolGrid(tools: List<ToolEntry>, modifier: Modifier = Modifier, columns: Int = 4, visibleCount: Int = 8) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val more = stringResource(R.string.tools_more)
    val fewer = stringResource(R.string.tools_fewer)
    val cells = when {
        tools.size <= visibleCount -> tools
        expanded -> tools + ToolEntry(Icons.Outlined.ExpandLess, fewer) { expanded = false }
        else -> tools.take(visibleCount - 1) + ToolEntry(Icons.Outlined.ExpandMore, more) { expanded = true }
    }
    GlassCard(modifier, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { ToolTile(it, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ToolTile(entry: ToolEntry, modifier: Modifier) {
    val c = AnProxTheme.colors
    val tint = if (entry.danger) c.danger else c.textSecondary
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = entry.onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.glassStrong),
            contentAlignment = Alignment.Center,
        ) {
            Icon(entry.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.danger) c.danger else c.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A section title that opens and closes what follows it (performance charts, details), so a
 * screen starts short. TalkBack hears whether it is open.
 */
@Composable
fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = AnProxTheme.colors
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.textSecondary, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = c.textTertiary)
    }
}
