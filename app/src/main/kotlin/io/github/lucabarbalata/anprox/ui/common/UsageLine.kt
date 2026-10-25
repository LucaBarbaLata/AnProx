package io.github.lucabarbalata.anprox.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageBar
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme

/** One meter of a summary card: a quiet label, the value on the right and a thin bar under them. */
@Composable
fun UsageLine(label: String, value: String, fraction: Double?) {
    val c = AnProxTheme.colors
    // Read as one: "CPU, 12 %".
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
        }
        UsageBar(fraction, height = 4.dp)
    }
}
