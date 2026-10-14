package io.github.lucabarbalata.anprox.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel

/**
 * Tiered confirmation (questionnaire Q12). CONFIRM shows a plain dialog; TYPE_TO_CONFIRM also
 * requires typing [confirmText] (a guest name or ID) before the button enables.
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    danger: DangerLevel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String? = null,
) {
    val c = AnProxTheme.colors
    var typed by rememberSaveable { mutableStateOf("") }
    val needsTyping = danger == DangerLevel.TYPE_TO_CONFIRM && confirmText != null
    val enabled = !needsTyping || typed.trim() == confirmText
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                if (needsTyping) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.confirm_type_to_confirm, confirmText!!)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(confirmLabel, color = if (enabled) c.danger else c.textTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
