package io.github.lucabarbalata.anprox.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeStorage

// Small pieces shared by the forms (hardware dialogs, the creation wizard).

@Composable
fun FormLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = AnProxTheme.colors.textPrimary)
}

@Composable
fun FormHint(text: String, error: Boolean = false) {
    val c = AnProxTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) c.danger else c.textSecondary,
        // Errors are read out when they appear or change, wherever the focus is.
        modifier = if (error) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
    )
}

@Composable
fun NumberField(value: String, onValueChange: (String) -> Unit, label: String, supportingText: String? = null, isError: Boolean = false) {
    GlassTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter(Char::isDigit).take(9)) },
        label = label,
        supportingText = supportingText,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** A row of tappable chips; the selected one is highlighted. */
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = AnProxTheme.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                label(option),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) c.textPrimary else c.textSecondary,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) c.accent.copy(alpha = 0.28f) else c.glassStrong)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Storage choices with their free space; the first is preselected. */
@Composable
fun StoragePicker(storages: List<NodeStorage>?, selected: String?, onSelect: (String) -> Unit, exclude: String? = null) {
    val c = AnProxTheme.colors
    val choices = storages?.filter { it.storage != exclude }
    when {
        choices == null -> CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
        choices.isEmpty() -> FormHint(stringResource(R.string.hardware_no_storage))
        else -> choices.forEach { s ->
            RadioRow(
                selected = s.storage == selected,
                title = s.storage,
                body = stringResource(R.string.hardware_storage_free, s.type, Formatters.bytes(s.avail)),
            ) { onSelect(s.storage) }
        }
    }
}

@Composable
fun BridgePicker(bridges: List<NodeNetwork>?, bridge: String, onBridge: (String) -> Unit) {
    val c = AnProxTheme.colors
    FormLabel(stringResource(R.string.hardware_bridge))
    when {
        bridges == null -> CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
        bridges.isEmpty() -> GlassTextField(value = bridge, onValueChange = { onBridge(it.trim()) }, label = stringResource(R.string.hardware_bridge))
        else -> {
            val names = (bridges.map { it.iface } + bridge).filter { it.isNotBlank() }.distinct()
            ChoiceChips(names, bridge, { it }, onBridge)
            bridges.firstOrNull { it.iface == bridge }?.comments?.trim()?.takeIf { it.isNotEmpty() }?.let { FormHint(it) }
        }
    }
}

/** A row with a checkbox; the whole row toggles. */
@Composable
fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AnProxTheme.colors.textPrimary)
    }
}

/** A text field that hides what is typed. */
@Composable
fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, supportingText: String? = null, isError: Boolean = false) {
    GlassTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
