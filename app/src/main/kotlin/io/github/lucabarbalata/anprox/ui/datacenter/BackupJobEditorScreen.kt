package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.BackupJobForm
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.BackupSelection
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.Retention
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

internal enum class RetentionChoice { STORAGE, CUSTOM, KEEP_ALL }

/** Creates or edits a backup job (questionnaire Q20). [jobId] is null for a new one. */
@Composable
fun BackupJobEditorScreen(serverId: String, jobId: String?, onBack: () -> Unit) {
    val viewModel: BackupJobEditorViewModel = koinViewModel(key = "backup-job-$serverId-${jobId.orEmpty()}") { parametersOf(serverId, jobId.orEmpty()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done != null) onBack() }

    val form = state.form
    val context = LocalContext.current
    val selectionLabels = mapOf(
        BackupSelection.ALL to stringResource(R.string.backupjob_sel_all),
        BackupSelection.INCLUDE to stringResource(R.string.backupjob_sel_include),
        BackupSelection.EXCLUDE to stringResource(R.string.backupjob_sel_exclude),
        BackupSelection.POOL to stringResource(R.string.backupjob_sel_pool),
    )
    val modeLabels = BackupMode.entries.associateWith { modeLabel(it) }
    val retentionLabels = mapOf(
        RetentionChoice.STORAGE to stringResource(R.string.backupjob_retention_storage),
        RetentionChoice.CUSTOM to stringResource(R.string.backupjob_retention_custom),
        RetentionChoice.KEEP_ALL to stringResource(R.string.backupjob_retention_all),
    )
    val presetLabels = BackupJobForm.SCHEDULE_PRESETS.associateWith { scheduleLabel(it) }
    val allNodes = stringResource(R.string.backupjob_all_nodes)
    val noCompression = stringResource(R.string.backupjob_compress_none)

    AnProxScaffold(
        title = stringResource(if (state.isNew) R.string.backupjob_new else R.string.backupjob_edit),
        onBack = onBack,
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                return@LazyColumn
            }
            if (state.error != null) return@LazyColumn

            item {
                GlassCard {
                    SwitchRow(
                        title = stringResource(R.string.backupjob_enabled),
                        description = stringResource(R.string.backupjob_enabled_body),
                        checked = form.enabled,
                        onCheckedChange = { on -> viewModel.edit { copy(enabled = on) } },
                    )
                }
            }
            item {
                GlassCard {
                    FormLabel(stringResource(R.string.backupjob_schedule))
                    Spacer(Modifier.height(8.dp))
                    ChoiceChips(BackupJobForm.SCHEDULE_PRESETS + listOfNotNull(form.schedule.takeIf { it !in BackupJobForm.SCHEDULE_PRESETS }), form.schedule, { presetLabels[it] ?: it }) { s ->
                        viewModel.edit { copy(schedule = s) }
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(
                        value = form.schedule,
                        onValueChange = { s -> viewModel.edit { copy(schedule = s) } },
                        label = stringResource(R.string.backupjob_schedule_custom),
                        supportingText = stringResource(R.string.backupjob_schedule_hint),
                        isError = state.scheduleInvalid,
                    )
                    val runs = state.nextRuns
                    when {
                        state.scheduleInvalid -> FormHint(stringResource(R.string.backupjob_schedule_invalid), error = true)
                        runs != null && runs.isNotEmpty() -> FormHint(stringResource(R.string.backupjob_next_runs, runs.joinToString(" · ") { formatRun(context, it) }))
                    }
                }
            }
            item {
                GlassCard {
                    FormLabel(stringResource(R.string.backupjob_storage))
                    Spacer(Modifier.height(8.dp))
                    if (state.storages.isEmpty()) {
                        FormHint(stringResource(R.string.backupjob_no_storage), error = true)
                    } else {
                        ChoiceChips(state.storages.map { it.storage }, form.storage.orEmpty(), { it }) { s -> viewModel.edit { copy(storage = s) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    FormLabel(stringResource(R.string.backupjob_node))
                    Spacer(Modifier.height(8.dp))
                    ChoiceChips(listOf("") + state.nodes, form.node.orEmpty(), { it.ifEmpty { allNodes } }) { n ->
                        viewModel.edit { copy(node = n.ifEmpty { null }) }
                    }
                }
            }
            item {
                GlassCard {
                    FormLabel(stringResource(R.string.backupjob_selection))
                    Spacer(Modifier.height(8.dp))
                    SegmentedPicker(BackupSelection.entries, form.selection, { selectionLabels.getValue(it) }, { s -> viewModel.edit { copy(selection = s) } })
                    when (form.selection) {
                        BackupSelection.ALL -> Unit
                        BackupSelection.INCLUDE, BackupSelection.EXCLUDE -> {
                            Spacer(Modifier.height(8.dp))
                            state.guests.filter { form.node == null || it.node == form.node }.forEach { g ->
                                val vmid = g.vmid ?: return@forEach
                                GuestCheckRow("$vmid · ${g.name.orEmpty()}", vmid in form.vmids) { viewModel.toggleGuest(vmid) }
                            }
                        }
                        BackupSelection.POOL -> {
                            Spacer(Modifier.height(8.dp))
                            if (state.pools.isEmpty()) {
                                FormHint(stringResource(R.string.backupjob_no_pools))
                            } else {
                                ChoiceChips(state.pools.map { it.id }, form.pool.orEmpty(), { it }) { p -> viewModel.edit { copy(pool = p) } }
                            }
                        }
                    }
                }
            }
            item {
                GlassCard {
                    FormLabel(stringResource(R.string.backup_mode))
                    Spacer(Modifier.height(8.dp))
                    SegmentedPicker(BackupMode.entries, form.mode, { modeLabels.getValue(it) }, { m -> viewModel.edit { copy(mode = m) } })
                    // A Backup Server compresses and deduplicates on its own.
                    if (state.storages.firstOrNull { it.storage == form.storage }?.isPbs != true) {
                        Spacer(Modifier.height(12.dp))
                        FormLabel(stringResource(R.string.backupjob_compression))
                        Spacer(Modifier.height(8.dp))
                        ChoiceChips(BackupJobForm.COMPRESSIONS, form.compress, { if (it == "0") noCompression else it.uppercase() }) { c ->
                            viewModel.edit { copy(compress = c) }
                        }
                    }
                }
            }
            item { RetentionCard(form.retention, retentionLabels) { r -> viewModel.edit { copy(retention = r) } } }
            item {
                GlassCard {
                    GlassTextField(
                        value = form.notesTemplate,
                        onValueChange = { t -> viewModel.edit { copy(notesTemplate = t) } },
                        label = stringResource(R.string.backup_notes),
                        supportingText = stringResource(R.string.backup_notes_help),
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(
                        value = form.comment,
                        onValueChange = { t -> viewModel.edit { copy(comment = t) } },
                        label = stringResource(R.string.backupjob_comment),
                    )
                }
            }
            state.problem?.let { problem -> item { FormHint(problemText(problem), error = true) } }
            state.saveError?.let { error -> item { ErrorBanner(error.asString()) } }
            item {
                AnProxButton(
                    text = stringResource(if (state.isNew) R.string.backupjob_create else R.string.action_save),
                    onClick = viewModel::save,
                    icon = Icons.Outlined.Save,
                    enabled = state.canSave,
                    loading = state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!state.isNew) {
                item {
                    AnProxButton(
                        text = stringResource(R.string.backupjob_delete),
                        onClick = viewModel::requestDelete,
                        style = ButtonStyle.DANGER,
                        icon = Icons.Outlined.Delete,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (state.confirmDelete) {
        ConfirmActionDialog(
            title = stringResource(R.string.backupjob_delete_confirm_title),
            message = stringResource(R.string.backupjob_delete_confirm_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun GuestCheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AnProxTheme.colors.textPrimary)
    }
}

/**
 * Which backups to keep. With [inherit] (a backup job) "Storage" leaves it to the storage; without
 * (a storage's own setting) null means keep all, Proxmox's default for a storage.
 */
@Composable
internal fun RetentionCard(retention: Retention?, labels: Map<RetentionChoice, String>, inherit: Boolean = true, onChange: (Retention?) -> Unit) {
    val choice = when {
        retention == null -> if (inherit) RetentionChoice.STORAGE else RetentionChoice.KEEP_ALL
        retention.keepAll -> RetentionChoice.KEEP_ALL
        else -> RetentionChoice.CUSTOM
    }
    val choices = if (inherit) RetentionChoice.entries else listOf(RetentionChoice.KEEP_ALL, RetentionChoice.CUSTOM)
    GlassCard {
        FormLabel(stringResource(R.string.backupjob_retention))
        Spacer(Modifier.height(8.dp))
        SegmentedPicker(choices, choice, { labels.getValue(it) }, { c ->
            onChange(
                when (c) {
                    RetentionChoice.STORAGE -> null
                    RetentionChoice.KEEP_ALL -> if (inherit) Retention(keepAll = true) else null
                    RetentionChoice.CUSTOM -> retention?.takeIf { !it.keepAll } ?: Retention(daily = 7, weekly = 4)
                },
            )
        })
        Spacer(Modifier.height(8.dp))
        when (choice) {
            RetentionChoice.STORAGE -> FormHint(stringResource(R.string.backupjob_retention_storage_body))
            RetentionChoice.KEEP_ALL -> FormHint(stringResource(R.string.backupjob_retention_all_body))
            RetentionChoice.CUSTOM -> {
                val r = retention ?: Retention()
                fun Int?.text() = this?.toString().orEmpty()
                NumberField(r.last.text(), { onChange(r.copy(last = it.toIntOrNull())) }, stringResource(R.string.backupjob_keep_last))
                NumberField(r.hourly.text(), { onChange(r.copy(hourly = it.toIntOrNull())) }, stringResource(R.string.backupjob_keep_hourly))
                NumberField(r.daily.text(), { onChange(r.copy(daily = it.toIntOrNull())) }, stringResource(R.string.backupjob_keep_daily))
                NumberField(r.weekly.text(), { onChange(r.copy(weekly = it.toIntOrNull())) }, stringResource(R.string.backupjob_keep_weekly))
                NumberField(r.monthly.text(), { onChange(r.copy(monthly = it.toIntOrNull())) }, stringResource(R.string.backupjob_keep_monthly))
                NumberField(r.yearly.text(), { onChange(r.copy(yearly = it.toIntOrNull())) }, stringResource(R.string.backupjob_keep_yearly))
            }
        }
    }
}

@Composable
private fun problemText(problem: BackupJobForm.Problem): String = stringResource(
    when (problem) {
        BackupJobForm.Problem.SCHEDULE -> R.string.backupjob_problem_schedule
        BackupJobForm.Problem.STORAGE -> R.string.backupjob_problem_storage
        BackupJobForm.Problem.NO_GUESTS -> R.string.backupjob_problem_guests
        BackupJobForm.Problem.POOL -> R.string.backupjob_problem_pool
        BackupJobForm.Problem.RETENTION -> R.string.backupjob_problem_retention
    },
)
