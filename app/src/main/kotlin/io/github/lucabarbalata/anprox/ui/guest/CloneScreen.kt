package io.github.lucabarbalata.anprox.ui.guest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.CloneRequest
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.security.LocalDangerousActionGuard
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Clone a VM or container (full or linked, any node, any storage), or make it a template (Q18). */
@Composable
fun CloneScreen(
    ref: GuestRef,
    guestName: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onOpenGuest: (GuestRef) -> Unit,
) {
    val key = "clone-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    val viewModel: CloneViewModel = koinViewModel(key = key) { parametersOf(ref, guestName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask, viewModel::consumeResult)

    val isVm = ref.type == GuestType.QEMU
    val typeLabel = stringResource(if (isVm) R.string.guest_type_vm else R.string.guest_type_ct)
    // The result card with **Open it** appears at the end of the form: bring it into view.
    val scroll = rememberScrollState()
    LaunchedEffect(state.created) {
        if (state.created != null) scroll.animateScrollTo(scroll.maxValue)
    }

    AnProxScaffold(
        title = stringResource(R.string.clone_title),
        subtitle = "$guestName · $typeLabel ${ref.vmid}",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading && state.busy == null) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        Column(
            Modifier.verticalScroll(scroll).padding(contentPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load)
            }
            when {
                state.loading && state.nodes.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent)
                }
                state.error == null -> {
                    CloneForm(state, isVm, viewModel)
                    state.busy?.let { busy ->
                        BusyCard(stringResource(if (busy == CloneBusy.CLONE) R.string.clone_busy else R.string.clone_template_busy))
                    }
                    state.created?.let { created ->
                        GlassCard {
                            Text(
                                stringResource(R.string.clone_created, typeLabel, created.vmid, created.node),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AnProxTheme.colors.textPrimary,
                            )
                            AnProxButton(
                                text = stringResource(R.string.clone_open),
                                onClick = { onOpenGuest(created) },
                                style = ButtonStyle.SECONDARY,
                                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            )
                        }
                    }
                    if (!state.isTemplate) TemplateCard(state, isVm, viewModel::requestTemplate)
                }
            }
        }
    }

    if (state.confirmTemplate) {
        val guard = LocalDangerousActionGuard.current
        val reason = stringResource(R.string.clone_template_confirm_title, guestName)
        ConfirmActionDialog(
            title = reason,
            message = stringResource(R.string.clone_template_confirm_body),
            confirmLabel = stringResource(R.string.clone_template_action),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            confirmText = guestName,
            onConfirm = { guard.confirm(reason, viewModel::convertToTemplate) },
            onDismiss = viewModel::dismissTemplate,
        )
    }
}

@Composable
private fun CloneForm(state: CloneState, isVm: Boolean, viewModel: CloneViewModel) {
    val c = AnProxTheme.colors
    val problem = state.problem
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = state.idText,
                onValueChange = viewModel::setId,
                label = stringResource(R.string.clone_id),
                supportingText = if (problem == CloneRequest.Problem.ID) stringResource(R.string.clone_id_error) else null,
                isError = problem == CloneRequest.Problem.ID,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            GlassTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = stringResource(if (isVm) R.string.options_name_vm else R.string.options_name_ct),
                supportingText = stringResource(if (problem == CloneRequest.Problem.NAME) R.string.options_name_error else R.string.clone_name_help),
                isError = problem == CloneRequest.Problem.NAME,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
            )
        }
    }

    SectionHeader(stringResource(R.string.clone_mode))
    GlassCard {
        RadioRow(
            selected = state.full,
            title = stringResource(R.string.clone_full),
            body = stringResource(R.string.clone_full_body),
        ) { viewModel.setFull(true) }
        RadioRow(
            selected = !state.full,
            title = stringResource(R.string.clone_linked),
            body = stringResource(if (state.isTemplate) R.string.clone_linked_body else R.string.clone_linked_needs_template),
            enabled = state.isTemplate,
        ) { viewModel.setFull(false) }
    }

    if (state.nodes.size > 1) {
        SectionHeader(stringResource(R.string.clone_target_node))
        GlassCard {
            state.nodes.entries.sortedBy { it.key }.forEach { (node, online) ->
                RadioRow(
                    selected = state.targetNode == node,
                    title = node,
                    body = when {
                        !online -> stringResource(R.string.clone_node_offline)
                        node == state.ref.node -> stringResource(R.string.clone_same_node)
                        else -> stringResource(R.string.clone_other_node)
                    },
                    enabled = online,
                ) { viewModel.setTarget(node) }
            }
        }
    }

    if (state.full) {
        SectionHeader(stringResource(R.string.clone_storage))
        GlassCard {
            RadioRow(
                selected = state.storage == null,
                title = stringResource(R.string.clone_storage_same),
                body = null,
            ) { viewModel.setStorage(null) }
            val storages = state.storages
            if (storages == null) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent)
                }
            } else {
                storages.forEach { s ->
                    RadioRow(
                        selected = state.storage == s.storage,
                        title = s.storage,
                        body = stringResource(R.string.backup_storage_other, s.type, Formatters.bytes(s.avail)),
                    ) { viewModel.setStorage(s.storage) }
                }
            }
        }
    }

    AnProxButton(
        text = stringResource(R.string.clone_action),
        onClick = viewModel::clone,
        icon = Icons.Outlined.ContentCopy,
        enabled = state.canClone,
        loading = state.busy == CloneBusy.CLONE,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TemplateCard(state: CloneState, isVm: Boolean, onConvert: () -> Unit) {
    val c = AnProxTheme.colors
    SectionHeader(stringResource(R.string.clone_template_title))
    GlassCard {
        Text(stringResource(R.string.clone_template_body), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (state.running) {
            Text(
                stringResource(if (isVm) R.string.clone_template_running_vm else R.string.clone_template_running_ct),
                style = MaterialTheme.typography.bodySmall,
                color = c.warning,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        AnProxButton(
            text = stringResource(R.string.clone_template_action),
            onClick = onConvert,
            style = ButtonStyle.DANGER,
            icon = Icons.Outlined.Inventory2,
            enabled = !state.running && state.busy == null && !state.loading,
            loading = state.busy == CloneBusy.TEMPLATE,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
    }
}
