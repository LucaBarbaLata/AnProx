package io.github.lucabarbalata.anprox.ui.guest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestOptions
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.StartupOrder
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The everyday options of a VM or container (questionnaire Q19, common settings first). Only
 * changed fields are sent, with the config digest, so a concurrent edit in the web UI is refused
 * instead of overwritten.
 */
@Composable
fun OptionsScreen(ref: GuestRef, guestName: String, onBack: () -> Unit) {
    val key = "options-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    val viewModel: OptionsViewModel = koinViewModel(key = key) { parametersOf(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.options_saved)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            snackbar.showSnackbar(savedText)
        }
    }

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (state.dirty) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    val isVm = ref.type == GuestType.QEMU
    val typeLabel = stringResource(if (isVm) R.string.guest_type_vm else R.string.guest_type_ct)
    val title = state.original?.name?.ifBlank { null } ?: guestName
    AnProxScaffold(
        title = stringResource(R.string.options_title),
        subtitle = "$title · $typeLabel ${ref.vmid}",
        onBack = leave,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::reload, enabled = !state.loading && !state.saving) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(contentPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::reload)
            }
            when {
                state.original == null && state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent)
                }
                state.original != null -> OptionsForm(state, isVm, viewModel)
            }
        }
    }

    if (confirmDiscard) {
        ConfirmActionDialog(
            title = stringResource(R.string.options_discard_title),
            message = stringResource(R.string.options_discard_body),
            confirmLabel = stringResource(R.string.options_discard),
            danger = DangerLevel.CONFIRM,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** Start order, start delay and shutdown timeout (`startup`), under "Start at boot". */
@Composable
private fun StartOrderFields(startup: StartupOrder, editable: Boolean, viewModel: OptionsViewModel) {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormLabel(stringResource(R.string.options_start_order_title))
        FormHint(stringResource(R.string.options_start_order_body))
        NumberField(
            value = startup.order?.toString().orEmpty(),
            onValueChange = { if (editable) viewModel.setStartOrder(it) },
            label = stringResource(R.string.options_start_order),
            supportingText = stringResource(R.string.options_start_order_help),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                NumberField(
                    value = startup.up?.toString().orEmpty(),
                    onValueChange = { if (editable) viewModel.setStartDelay(it) },
                    label = stringResource(R.string.options_start_delay),
                )
            }
            Box(Modifier.weight(1f)) {
                NumberField(
                    value = startup.down?.toString().orEmpty(),
                    onValueChange = { if (editable) viewModel.setShutdownTimeout(it) },
                    label = stringResource(R.string.options_shutdown_timeout),
                )
            }
        }
    }
}

@Composable
private fun OptionsForm(state: OptionsState, isVm: Boolean, viewModel: OptionsViewModel) {
    val c = AnProxTheme.colors
    val draft = state.draft
    val editable = !state.saving
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = draft.name,
                onValueChange = viewModel::setName,
                label = stringResource(if (isVm) R.string.options_name_vm else R.string.options_name_ct),
                supportingText = if (state.problem == GuestOptions.Problem.NAME) stringResource(R.string.options_name_error) else null,
                isError = state.problem == GuestOptions.Problem.NAME,
                enabled = editable,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
            )
            GlassTextField(
                value = state.tagsText,
                onValueChange = viewModel::setTagsText,
                label = stringResource(R.string.options_tags),
                supportingText = stringResource(
                    if (state.problem == GuestOptions.Problem.TAG) R.string.options_tags_error else R.string.options_tags_help,
                ),
                isError = state.problem == GuestOptions.Problem.TAG,
                enabled = editable,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
            )
            if (draft.tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    draft.tags.forEach { TagChip(it) }
                }
            }
            GlassTextField(
                value = draft.description,
                onValueChange = viewModel::setDescription,
                label = stringResource(R.string.options_notes),
                supportingText = stringResource(R.string.options_notes_help),
                singleLine = false,
                minLines = 3,
                enabled = editable,
            )
        }
    }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SwitchRow(
                title = stringResource(R.string.options_onboot),
                description = stringResource(R.string.options_onboot_body),
                checked = draft.onBoot,
                onCheckedChange = { if (editable) viewModel.setOnBoot(it) },
            )
            StartOrderFields(draft.startup, editable, viewModel)
            SwitchRow(
                title = stringResource(R.string.options_protection),
                description = stringResource(R.string.options_protection_body),
                checked = draft.protection,
                onCheckedChange = { if (editable) viewModel.setProtection(it) },
            )
            if (isVm && draft.agent != null) {
                SwitchRow(
                    title = stringResource(R.string.options_agent),
                    description = stringResource(R.string.options_agent_body),
                    checked = draft.agent == true,
                    onCheckedChange = { if (editable) viewModel.setAgent(it) },
                )
            }
        }
    }
    state.saveError?.let { error ->
        ErrorBanner(error.asString(), actionLabel = stringResource(R.string.options_reload), onAction = viewModel::reload)
    }
    AnProxButton(
        text = stringResource(R.string.action_save),
        onClick = viewModel::save,
        icon = Icons.Outlined.Save,
        enabled = state.canSave,
        loading = state.saving,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!state.dirty && !state.saving) {
        Text(
            stringResource(R.string.options_no_changes),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
