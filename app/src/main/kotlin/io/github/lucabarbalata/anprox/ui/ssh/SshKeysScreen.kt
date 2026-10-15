package io.github.lucabarbalata.anprox.ui.ssh

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.ssh.KnownHost
import io.github.lucabarbalata.anprox.model.ssh.SshKeyInfo
import io.github.lucabarbalata.anprox.model.ssh.SshKeyType
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

/**
 * The app's SSH keys and the hosts it trusts (questionnaire Q15): create a key on the phone or
 * import one, copy or share its public half to put it on servers, delete it; forget a host key.
 */
@Composable
fun SshKeysScreen(onBack: () -> Unit, viewModel: SshKeysViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.consumeMessage()
            snackbar.showSnackbar(message)
        }
    }
    AnProxScaffold(title = stringResource(R.string.ssh_keys_title), onBack = onBack, snackbarHostState = snackbar) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnProxButton(stringResource(R.string.ssh_key_new), viewModel::openNew, icon = Icons.Outlined.Add)
                    AnProxButton(stringResource(R.string.ssh_key_import), viewModel::openImport, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.FileOpen)
                }
            }
            if (state.keys.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.Key, stringResource(R.string.ssh_keys_empty_title), stringResource(R.string.ssh_keys_empty_body))
                }
            }
            items(state.keys, key = { it.id }) { key ->
                KeyCard(key, onCopied = viewModel::copied, onDelete = { viewModel.askDelete(key) })
            }
            item { SectionHeader(stringResource(R.string.ssh_hosts_title)) }
            if (state.hosts.isEmpty()) {
                item {
                    Text(stringResource(R.string.ssh_hosts_empty), style = MaterialTheme.typography.bodySmall, color = AnProxTheme.colors.textSecondary)
                }
            }
            items(state.hosts, key = { it.address }) { host -> HostCard(host) { viewModel.askForget(host) } }
        }
    }

    state.dialog?.let { KeyDialogView(it, state.working, viewModel) }
    state.confirmDelete?.let { key ->
        ConfirmActionDialog(
            title = stringResource(R.string.ssh_key_delete_title, key.name),
            message = stringResource(R.string.ssh_key_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
    state.confirmForget?.let { host ->
        ConfirmActionDialog(
            title = stringResource(R.string.ssh_host_forget_title, host.address),
            message = stringResource(R.string.ssh_host_forget_body),
            confirmLabel = stringResource(R.string.ssh_host_forget),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::forget,
            onDismiss = viewModel::dismissForget,
        )
    }
}

@Composable
private fun KeyCard(key: SshKeyInfo, onCopied: () -> Unit, onDelete: () -> Unit) {
    val c = AnProxTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.ssh_key_share)
    GlassCard(contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(
                    listOf(key.type, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(key.createdAt))).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(key.publicKey))
                onCopied()
            }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.ssh_key_copy), tint = c.textSecondary) }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key.publicKey)
                context.startActivity(Intent.createChooser(send, shareTitle))
            }) { Icon(Icons.Outlined.Share, contentDescription = shareTitle, tint = c.textSecondary) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = c.textSecondary) }
        }
        Spacer(Modifier.height(4.dp))
        Text(key.fingerprint, style = MonoStyle, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun HostCard(host: KnownHost, onForget: () -> Unit) {
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(host.address, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(host.type, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                Text(host.fingerprint, style = MonoStyle, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onForget) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.ssh_host_forget), tint = c.textSecondary) }
        }
    }
}

@Composable
private fun KeyDialogView(dialog: KeyDialog, working: Boolean, viewModel: SshKeysViewModel) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= MAX_KEY_FILE }?.decodeToString() }
            }.getOrNull()
            viewModel.picked(text)
        }
    }
    val typeLabels = mapOf(
        SshKeyType.ED25519 to stringResource(R.string.ssh_key_type_ed25519),
        SshKeyType.ECDSA to stringResource(R.string.ssh_key_type_ecdsa),
        SshKeyType.RSA to stringResource(R.string.ssh_key_type_rsa),
    )
    AlertDialog(
        onDismissRequest = viewModel::closeDialog,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(if (dialog.importing) R.string.ssh_key_import else R.string.ssh_key_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = dialog.name,
                    onValueChange = { v -> viewModel.edit { copy(name = v) } },
                    label = stringResource(R.string.ssh_key_name),
                    supportingText = stringResource(R.string.ssh_key_name_hint),
                )
                if (dialog.importing) {
                    GlassTextField(
                        value = dialog.privateKey,
                        onValueChange = { v -> viewModel.edit { copy(privateKey = v) } },
                        label = stringResource(R.string.ssh_key_private),
                        placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                        singleLine = false,
                        minLines = 3,
                    )
                    TextButton(onClick = { pick.launch("*/*") }) { Text(stringResource(R.string.ssh_key_pick_file)) }
                    GlassTextField(
                        value = dialog.passphrase,
                        onValueChange = { v -> viewModel.edit { copy(passphrase = v) } },
                        label = stringResource(R.string.ssh_key_passphrase),
                        supportingText = stringResource(R.string.ssh_key_passphrase_hint),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                } else {
                    SegmentedPicker(SshKeyType.entries, dialog.type, { typeLabels.getValue(it) }, { t -> viewModel.edit { copy(type = t) } }, Modifier.fillMaxWidth())
                    FormHint(stringResource(R.string.ssh_key_type_hint))
                }
                dialog.error?.let { FormHint(it.asString(), error = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = dialog.valid && !working) {
                Text(stringResource(if (working) R.string.ssh_key_working else if (dialog.importing) R.string.ssh_key_import_short else R.string.ssh_key_create))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeDialog) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Private key files are a few kilobytes; anything much larger isn't one. */
private const val MAX_KEY_FILE = 64 * 1024
