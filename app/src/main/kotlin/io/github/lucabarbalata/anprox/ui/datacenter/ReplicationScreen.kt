package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.ReplicationJob
import io.github.lucabarbalata.anprox.model.pve.ReplicationJobForm
import io.github.lucabarbalata.anprox.model.pve.ReplicationState
import io.github.lucabarbalata.anprox.model.pve.ReplicationStatus
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.Locale

/** Datacenter → Replication (questionnaire Q21): guests' local disks copied to another node. */
@Composable
fun ReplicationScreen(serverId: String, onBack: () -> Unit, onTask: (serverId: String, node: String, upid: String) -> Unit) {
    val viewModel: ReplicationViewModel = koinViewModel(key = "replication-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)

    AnProxScaffold(
        title = stringResource(R.string.datacenter_replication),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AnProxButton(
                    stringResource(R.string.replication_add),
                    onClick = viewModel::add,
                    icon = Icons.Outlined.Add,
                    enabled = state.canAdd,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val overview = state.overview
            if (overview != null && overview.onlineNodes.size < 2) {
                item { FormHint(stringResource(R.string.replication_needs_nodes)) }
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            when {
                state.loading && overview == null && state.error == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                overview != null && overview.jobs.isEmpty() -> item {
                    EmptyState(Icons.Outlined.ContentCopy, stringResource(R.string.replication_empty_title), stringResource(R.string.replication_empty_body))
                }
                overview != null -> items(overview.jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        status = overview.status[job.id],
                        guest = state.guestLabel(job.guest),
                        enabled = !state.busy,
                        canRun = state.sourceOf(job) in overview.onlineNodes,
                        onToggle = { on -> viewModel.setEnabled(job, on) },
                        onRun = { viewModel.runNow(job) },
                        onEdit = { viewModel.edit(job) },
                    )
                }
            }
        }
    }

    state.editing?.let { edit -> JobDialog(edit, state, viewModel) }
    state.log?.let { log -> LogDialog(log, state.guestLabel(log.job.guest), viewModel::closeLog) }
    state.confirmDelete?.let { job -> DeleteDialog(job, state.guestLabel(job.guest), viewModel) }
}

@Composable
private fun JobCard(
    job: ReplicationJob,
    status: ReplicationStatus?,
    guest: String,
    enabled: Boolean,
    canRun: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = AnProxTheme.colors
    val context = LocalContext.current
    val state = ReplicationStatus.state(job, status)
    val schedule = replicationScheduleLabel(job.schedule)
    val rate = job.rate?.let { stringResource(R.string.replication_rate, formatRate(it)) }
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onEdit)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.replication_to, guest, job.target), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(
                        listOfNotNull(schedule, rate, job.comment).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = job.enabled, onCheckedChange = onToggle, enabled = enabled && !job.removing)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StatusPill(stateLabel(state, status?.failCount ?: 0), stateTone(state))
                    val last = status?.lastSync
                    Text(
                        if (last != null) {
                            stringResource(R.string.replication_last_sync, relativeTime(last * 1000), formatSeconds(status?.duration ?: 0.0))
                        } else {
                            stringResource(R.string.replication_never_synced)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textSecondary,
                    )
                    val next = status?.nextSync
                    if (job.enabled && next != null) {
                        Text(stringResource(R.string.replication_next, formatRun(context, next)), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    }
                    status?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
                IconButton(onClick = onRun, enabled = enabled && canRun && !job.removing) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.replication_run_now), tint = c.accent)
                }
            }
        }
    }
}

@Composable
private fun JobDialog(edit: ReplicationEdit, state: ReplicationUiState, viewModel: ReplicationViewModel) {
    val context = LocalContext.current
    val form = edit.form
    val overview = state.overview ?: return
    val creating = edit.job == null
    val guestNode = form.guest?.let { overview.guest(it)?.node }
    val problem = form.problem(guestNode, overview.jobs, creating)
    val presets = ReplicationJobForm.SCHEDULE_PRESETS + listOfNotNull(form.schedule.takeIf { it !in ReplicationJobForm.SCHEDULE_PRESETS })
    val presetLabels = presets.associateWith { replicationScheduleLabel(it) }
    val guestLabels = overview.guests.mapNotNull { it.vmid }.associateWith { state.guestLabel(it) }
    AlertDialog(
        onDismissRequest = viewModel::close,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = {
            val job = edit.job
            Text(if (job == null) stringResource(R.string.replication_add) else stringResource(R.string.replication_to, state.guestLabel(job.guest), job.target))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (creating) {
                    FormLabel(stringResource(R.string.replication_guest))
                    ChoiceChips(guestLabels.keys.toList(), form.guest ?: -1, { guestLabels[it].orEmpty() }) { g -> viewModel.editForm { copy(guest = g) } }
                    FormLabel(stringResource(R.string.replication_target))
                    ChoiceChips(overview.onlineNodes, form.target.orEmpty(), { it }) { n -> viewModel.editForm { copy(target = n) } }
                    FormHint(stringResource(R.string.replication_zfs_hint))
                }
                FormLabel(stringResource(R.string.backupjob_schedule))
                ChoiceChips(presets, form.schedule, { presetLabels[it] ?: it }) { s -> viewModel.editForm { copy(schedule = s) } }
                GlassTextField(
                    value = form.schedule,
                    onValueChange = { s -> viewModel.editForm { copy(schedule = s) } },
                    label = stringResource(R.string.backupjob_schedule_custom),
                    supportingText = stringResource(R.string.replication_schedule_hint),
                    isError = edit.scheduleInvalid,
                )
                val runs = edit.nextRuns
                when {
                    edit.scheduleInvalid -> FormHint(stringResource(R.string.backupjob_schedule_invalid), error = true)
                    runs != null && runs.isNotEmpty() -> FormHint(stringResource(R.string.backupjob_next_runs, runs.joinToString(" · ") { formatRun(context, it) }))
                }
                GlassTextField(
                    value = form.rate,
                    onValueChange = { v -> viewModel.editForm { copy(rate = v.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(8)) } },
                    label = stringResource(R.string.replication_rate_label),
                    supportingText = stringResource(R.string.replication_rate_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editForm { copy(comment = v) } }, label = stringResource(R.string.replication_comment))
                SwitchRow(stringResource(R.string.replication_enabled), form.enabled, { on -> viewModel.editForm { copy(enabled = on) } })
                problem?.let { FormHint(stringResource(problemText(it)), error = true) }
                val job = edit.job
                if (job != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { viewModel.runNow(job) }, enabled = !job.removing && state.sourceOf(job) in overview.onlineNodes) {
                            Text(stringResource(R.string.replication_run_now))
                        }
                        TextButton(onClick = { viewModel.showLog(job) }, enabled = state.sourceOf(job) in overview.onlineNodes) {
                            Text(stringResource(R.string.replication_log))
                        }
                    }
                    TextButton(onClick = { viewModel.requestDelete(job) }) {
                        Text(stringResource(R.string.replication_delete), color = AnProxTheme.colors.danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = problem == null && !edit.scheduleInvalid) {
                Text(stringResource(if (creating) R.string.replication_add_short else R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LogDialog(log: ReplicationLog, guest: String, onClose: () -> Unit) {
    val c = AnProxTheme.colors
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.replication_to, guest, log.job.target)) },
        text = {
            val lines = log.lines
            when {
                lines == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent)
                }
                log.error != null -> FormHint(log.error.asString(), error = true)
                lines.isEmpty() -> FormHint(stringResource(R.string.replication_log_empty))
                else -> Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    lines.forEach { line ->
                        Text(line.t, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.textPrimary, softWrap = false)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun DeleteDialog(job: ReplicationJob, guest: String, viewModel: ReplicationViewModel) {
    var keep by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDelete,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.replication_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.replication_delete_body, guest, job.target), style = MaterialTheme.typography.bodyMedium, color = AnProxTheme.colors.textSecondary)
                SwitchRow(stringResource(R.string.replication_keep_copy, job.target), keep, { keep = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.delete(keep) }) {
                Text(stringResource(R.string.action_delete), color = AnProxTheme.colors.danger)
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A friendly name for the replication presets; other schedules as Proxmox writes them. */
@Composable
private fun replicationScheduleLabel(schedule: String): String = when (schedule.trim()) {
    ReplicationJobForm.SCHEDULE_PRESETS[0] -> stringResource(R.string.schedule_15_minutes)
    ReplicationJobForm.SCHEDULE_PRESETS[1] -> stringResource(R.string.schedule_30_minutes)
    ReplicationJobForm.SCHEDULE_PRESETS[2] -> stringResource(R.string.schedule_two_hours)
    ReplicationJobForm.SCHEDULE_PRESETS[3] -> stringResource(R.string.schedule_nightly)
    else -> schedule
}

@Composable
private fun stateLabel(state: ReplicationState, failures: Int): String = when (state) {
    ReplicationState.OK -> stringResource(R.string.replication_state_ok)
    ReplicationState.SYNCING -> stringResource(R.string.replication_state_syncing)
    ReplicationState.FAILED -> stringResource(R.string.replication_state_failed, failures)
    ReplicationState.NEVER_RUN -> stringResource(R.string.replication_state_never)
    ReplicationState.DISABLED -> stringResource(R.string.replication_state_off)
    ReplicationState.REMOVING -> stringResource(R.string.replication_state_removing)
}

private fun stateTone(state: ReplicationState): Tone = when (state) {
    ReplicationState.OK -> Tone.SUCCESS
    ReplicationState.SYNCING -> Tone.INFO
    ReplicationState.FAILED -> Tone.DANGER
    ReplicationState.NEVER_RUN -> Tone.NEUTRAL
    ReplicationState.DISABLED -> Tone.NEUTRAL
    ReplicationState.REMOVING -> Tone.WARNING
}

private fun problemText(p: ReplicationJobForm.Problem): Int = when (p) {
    ReplicationJobForm.Problem.GUEST -> R.string.replication_problem_guest
    ReplicationJobForm.Problem.TARGET -> R.string.replication_problem_target
    ReplicationJobForm.Problem.SAME_NODE -> R.string.replication_problem_same_node
    ReplicationJobForm.Problem.DUPLICATE -> R.string.replication_problem_duplicate
    ReplicationJobForm.Problem.SCHEDULE -> R.string.replication_problem_schedule
    ReplicationJobForm.Problem.RATE -> R.string.replication_problem_rate
}

private fun formatRate(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

private fun formatSeconds(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
