package io.github.lucabarbalata.anprox.ui.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.ssh.HostKeyPrompt
import io.github.lucabarbalata.anprox.model.ssh.SshConnectForm
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.console.ConsoleView
import io.github.lucabarbalata.anprox.ui.console.ConsoleViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * SSH to a node or guest with the app's own client (questionnaire Q15): a short form, then the
 * same terminal as the Proxmox consoles. [vmid] is 0 for the node itself.
 */
@Composable
fun SshScreen(
    serverId: String,
    node: String,
    vmid: Int,
    guestType: String,
    label: String,
    onBack: () -> Unit,
    onKeys: () -> Unit,
) {
    val key = "ssh-$serverId-$node-$vmid"
    val model: SshViewModel = koinViewModel(key = key) { parametersOf(serverId, node, vmid, guestType, label) }
    // The terminal is an ordinary console fed by the SSH shell; it lives as long as this screen.
    val terminal: ConsoleViewModel = viewModel(key = "$key-terminal") { ConsoleViewModel(ConsoleTarget(ConsoleKind.SSH, node), model.opener) }
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            model.consumeMessage()
            snackbar.showSnackbar(message)
        }
    }

    if (state.terminal) {
        ConsoleView(
            terminal,
            state.consoleTarget,
            onBack = onBack,
            actions = {
                IconButton(onClick = {
                    terminal.disconnect()
                    model.editConnection()
                }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.ssh_edit_connection)) }
            },
            extra = {
                if (state.passwordSession && state.keys.isNotEmpty()) {
                    ErrorBanner(
                        stringResource(if (state.installing) R.string.ssh_key_installing else R.string.ssh_add_key_hint),
                        actionLabel = stringResource(R.string.ssh_add_key),
                        onAction = model::chooseKey,
                        tone = Tone.INFO,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            },
            snackbarHostState = snackbar,
        )
    } else {
        Form(state, model, label, snackbar, onBack, onKeys, onConnect = { if (model.start()) terminal.reconnect() })
    }

    state.prompt?.let { HostKeyDialog(it, model::answerHostKey) }
    if (state.choosingKey) {
        AlertDialog(
            onDismissRequest = model::dismissChooseKey,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.ssh_add_key)) },
            text = {
                Column {
                    Text(stringResource(R.string.ssh_add_key_body), style = MaterialTheme.typography.bodySmall, color = AnProxTheme.colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    state.keys.forEach { k -> RadioRow(selected = false, title = k.name, body = k.type) { model.installKey(k) } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = model::dismissChooseKey) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Form(
    state: SshState,
    viewModel: SshViewModel,
    label: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onKeys: () -> Unit,
    onConnect: () -> Unit,
) {
    val c = AnProxTheme.colors
    val form = state.form
    val problem = state.problem.takeIf { state.showProblems }
    val authLabels = mapOf(false to stringResource(R.string.ssh_auth_password), true to stringResource(R.string.ssh_auth_key))
    AnProxScaffold(title = stringResource(R.string.ssh_title), subtitle = label, onBack = onBack, snackbarHostState = snackbar) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard {
                    Text(stringResource(R.string.ssh_intro), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    GlassTextField(
                        value = form.host,
                        onValueChange = { v -> viewModel.edit { copy(host = v) } },
                        label = stringResource(R.string.ssh_host),
                        supportingText = stringResource(if (state.resolving) R.string.ssh_host_resolving else R.string.ssh_host_hint),
                        isError = problem == SshConnectForm.Problem.HOST,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            GlassTextField(
                                value = form.user,
                                onValueChange = { v -> viewModel.edit { copy(user = v) } },
                                label = stringResource(R.string.ssh_user),
                                isError = problem == SshConnectForm.Problem.USER,
                            )
                        }
                        Column(Modifier.weight(0.6f)) {
                            NumberField(
                                value = form.port,
                                onValueChange = { v -> viewModel.edit { copy(port = v) } },
                                label = stringResource(R.string.ssh_port),
                                isError = problem == SshConnectForm.Problem.PORT,
                            )
                        }
                    }
                }
            }
            item {
                GlassCard {
                    SegmentedPicker(listOf(false, true), form.useKey, { authLabels.getValue(it) }, { k -> viewModel.edit { copy(useKey = k) } }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    if (form.useKey) {
                        if (state.keys.isEmpty()) {
                            FormHint(stringResource(R.string.ssh_no_keys), error = problem == SshConnectForm.Problem.KEY)
                        }
                        state.keys.forEach { k ->
                            RadioRow(selected = form.keyId == k.id, title = k.name, body = k.fingerprint) { viewModel.edit { copy(keyId = k.id) } }
                        }
                        if (problem == SshConnectForm.Problem.KEY && state.keys.isNotEmpty()) FormHint(stringResource(R.string.ssh_pick_key), error = true)
                        TextButton(onClick = onKeys) { Text(stringResource(R.string.ssh_manage_keys)) }
                    } else {
                        GlassTextField(
                            value = form.password,
                            onValueChange = { v -> viewModel.edit { copy(password = v) } },
                            label = stringResource(R.string.ssh_password),
                            supportingText = stringResource(R.string.ssh_password_hint),
                            isError = problem == SshConnectForm.Problem.PASSWORD,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    }
                }
            }
            item { AnProxButton(stringResource(R.string.ssh_connect), onConnect, icon = Icons.Outlined.Terminal, enabled = !state.resolving) }
            item {
                AnProxButton(stringResource(R.string.ssh_keys_title), onKeys, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Key)
            }
        }
    }
}

/** Trust on first use: the fingerprint to compare with the server's, and a loud warning when it changed. */
@Composable
private fun HostKeyDialog(prompt: HostKeyPrompt, onAnswer: (Boolean) -> Unit) {
    val c = AnProxTheme.colors
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(if (prompt.changed) R.string.ssh_host_changed_title else R.string.ssh_host_new_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (prompt.changed) R.string.ssh_host_changed_body else R.string.ssh_host_new_body, prompt.address),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (prompt.changed) c.danger else c.textPrimary,
                )
                Text(prompt.type, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                Text(prompt.fingerprint, style = MonoStyle, color = c.textPrimary)
                prompt.previous?.let {
                    Text(stringResource(R.string.ssh_host_previous), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    Text(it, style = MonoStyle, color = c.textTertiary)
                }
                Text(stringResource(R.string.ssh_host_check), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(stringResource(if (prompt.changed) R.string.ssh_host_trust_new else R.string.ssh_host_trust), color = if (prompt.changed) c.danger else c.accent)
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
