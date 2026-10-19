package io.github.lucabarbalata.anprox.ui.guest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.AgentPassword
import io.github.lucabarbalata.anprox.model.pve.AgentTrimPath
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.FsFreezeStatus
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.PasswordField
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** QEMU guest agent tools of a running VM (questionnaire Q18). */
@Composable
fun AgentScreen(ref: GuestRef, guestName: String, onBack: () -> Unit) {
    val viewModel: AgentViewModel = koinViewModel(key = "agent-${ref.serverId}-${ref.node}-${ref.vmid}") { parametersOf(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Agent commands aren't tasks, so there is no log to open.
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask = { _, _, _ -> }, onConsumed = viewModel::consumeResult)
    val c = AnProxTheme.colors

    AnProxScaffold(
        title = stringResource(R.string.agent_title),
        subtitle = "$guestName · ${stringResource(R.string.guest_type_vm)} ${ref.vmid}",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item {
                    ErrorBanner(
                        stringResource(R.string.agent_unavailable, error.asString()),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = viewModel::refresh,
                    )
                }
            }
            if (state.loading && state.freeze == null && state.error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            }
            val freeze = state.freeze
            if (freeze != null) {
                item { SectionHeader(stringResource(R.string.agent_filesystems)) }
                item { FreezeCard(freeze, state.busy, onFreeze = viewModel::requestFreeze, onThaw = viewModel::thaw) }
                item {
                    GlassCard {
                        Text(stringResource(R.string.agent_trim), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        FormHint(stringResource(R.string.agent_trim_body))
                        Spacer(Modifier.height(10.dp))
                        AnProxButton(
                            stringResource(R.string.agent_trim),
                            onClick = viewModel::trim,
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.CleaningServices,
                            enabled = state.busy == null && freeze != FsFreezeStatus.FROZEN,
                            loading = state.busy == AgentAction.TRIM,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        state.trimmed?.let { paths -> TrimResults(paths) }
                    }
                }
                item { SectionHeader(stringResource(R.string.agent_users)) }
                item {
                    GlassCard {
                        Text(stringResource(R.string.agent_password), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        FormHint(stringResource(R.string.agent_password_body))
                        Spacer(Modifier.height(10.dp))
                        AnProxButton(
                            stringResource(R.string.agent_password),
                            onClick = viewModel::openPassword,
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.Password,
                            enabled = state.busy == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (state.confirmFreeze) {
        ConfirmActionDialog(
            title = stringResource(R.string.agent_freeze_title, guestName),
            message = stringResource(R.string.agent_freeze_message),
            confirmLabel = stringResource(R.string.agent_freeze),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::freeze,
            onDismiss = viewModel::dismissFreeze,
        )
    }
    if (state.showPassword) {
        PasswordDialog(
            busy = state.busy == AgentAction.PASSWORD,
            error = state.passwordError?.asString(),
            problemOf = viewModel::passwordProblem,
            onSave = viewModel::setPassword,
            onDismiss = viewModel::dismissPassword,
        )
    }
}

@Composable
private fun FreezeCard(status: FsFreezeStatus, busy: AgentAction?, onFreeze: () -> Unit, onThaw: () -> Unit) {
    val c = AnProxTheme.colors
    val frozen = status == FsFreezeStatus.FROZEN
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (frozen) Tone.WARNING else Tone.SUCCESS)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(
                    when (status) {
                        FsFreezeStatus.FROZEN -> R.string.agent_state_frozen
                        FsFreezeStatus.THAWED -> R.string.agent_state_thawed
                        FsFreezeStatus.UNKNOWN -> R.string.agent_state_unknown
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
            )
        }
        FormHint(stringResource(if (frozen) R.string.agent_frozen_body else R.string.agent_thawed_body), error = frozen)
        Spacer(Modifier.height(10.dp))
        if (frozen) {
            AnProxButton(
                stringResource(R.string.agent_thaw),
                onClick = onThaw,
                icon = Icons.Outlined.WbSunny,
                enabled = busy == null,
                loading = busy == AgentAction.THAW,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AnProxButton(
                stringResource(R.string.agent_freeze),
                onClick = onFreeze,
                style = ButtonStyle.SECONDARY,
                icon = Icons.Outlined.AcUnit,
                enabled = busy == null,
                loading = busy == AgentAction.FREEZE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrimResults(paths: List<AgentTrimPath>) {
    val c = AnProxTheme.colors
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (paths.isEmpty()) FormHint(stringResource(R.string.agent_trim_none))
        paths.forEach { p ->
            Row {
                Text(p.path, style = MaterialTheme.typography.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    p.error ?: p.trimmed?.let { stringResource(R.string.agent_trim_freed, Formatters.bytes(it)) } ?: stringResource(R.string.agent_trim_ok),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.error != null) c.danger else c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    busy: Boolean,
    error: String?,
    problemOf: (String, String, String) -> AgentPassword.Problem?,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val problem = problemOf(username, password, repeat)
    // Only complain once the user has typed something.
    val shown = problem?.takeIf { password.isNotEmpty() || it == AgentPassword.Problem.NO_USER }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.agent_password)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.agent_password_user),
                    supportingText = stringResource(R.string.agent_password_user_hint),
                    isError = shown == AgentPassword.Problem.NO_USER,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                )
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.agent_password_new),
                    supportingText = stringResource(R.string.agent_password_length, AgentPassword.MIN),
                    isError = shown == AgentPassword.Problem.TOO_SHORT || shown == AgentPassword.Problem.TOO_LONG,
                )
                PasswordField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = stringResource(R.string.agent_password_repeat),
                    supportingText = if (shown == AgentPassword.Problem.MISMATCH && repeat.isNotEmpty()) stringResource(R.string.agent_password_mismatch) else null,
                    isError = shown == AgentPassword.Problem.MISMATCH && repeat.isNotEmpty(),
                )
                error?.let { FormHint(it, error = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(username, password, repeat) }, enabled = problem == null && !busy) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
