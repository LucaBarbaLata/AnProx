package io.github.lucabarbalata.anprox.ui.datacenter

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.BackupJob
import io.github.lucabarbalata.anprox.model.pve.BackupJobForm
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.BackupSelection
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Datacenter → Backup jobs: scheduled backups, run now, and guests no job covers (Q20). */
@Composable
fun BackupJobsScreen(
    serverId: String,
    onBack: () -> Unit,
    onEdit: (jobId: String?) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: BackupJobsViewModel = koinViewModel(key = "backup-jobs-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Also after coming back from the editor.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)

    AnProxScaffold(
        title = stringResource(R.string.backupjobs_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AnProxButton(
                    text = stringResource(R.string.backupjobs_add),
                    onClick = { onEdit(null) },
                    icon = Icons.Outlined.Add,
                    enabled = !state.loading && state.error == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.running?.let { item { BusyCard(stringResource(R.string.backupjobs_running)) } }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::refresh) }
            }
            when {
                state.loading && state.jobs.isEmpty() && state.error == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                state.isEmpty -> item {
                    EmptyState(Icons.Outlined.Backup, stringResource(R.string.backupjobs_empty_title), stringResource(R.string.backupjobs_empty_body))
                }
                else -> items(state.jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        guestNames = state.guestNames,
                        enabled = state.running == null,
                        onToggle = { viewModel.setEnabled(job, it) },
                        onRun = { viewModel.requestRun(job) },
                        onEdit = { onEdit(job.id) },
                    )
                }
            }
            state.notBackedUp?.let { missing ->
                item { SectionHeader(stringResource(R.string.backupjobs_not_backed_up)) }
                item { NotBackedUpCard(missing.map { "${it.vmid} · ${it.name ?: ""}".trimEnd(' ', '·') }) }
            }
        }
    }

    state.confirmRun?.let { job ->
        ConfirmActionDialog(
            title = stringResource(R.string.backupjobs_run_confirm_title),
            message = stringResource(R.string.backupjobs_run_confirm_body, selectionLabel(job, state.guestNames), job.storage.orEmpty()),
            confirmLabel = stringResource(R.string.backupjobs_run),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::confirmRun,
            onDismiss = viewModel::dismissRun,
        )
    }
}

@Composable
private fun JobCard(
    job: BackupJob,
    guestNames: Map<Int, String>,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = AnProxTheme.colors
    val context = LocalContext.current
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
                    Text(scheduleLabel(job.schedule), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(
                        listOfNotNull(job.storage, selectionLabel(job, guestNames), modeLabel(job.mode)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = job.enabled, onCheckedChange = onToggle, enabled = enabled)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val next = job.nextRun
                    when {
                        !job.enabled -> TagChip(stringResource(R.string.backupjobs_disabled))
                        next != null -> Text(
                            stringResource(R.string.backupjobs_next, formatRun(context, next)),
                            style = MaterialTheme.typography.labelMedium,
                            color = c.textSecondary,
                        )
                    }
                    job.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                IconButton(onClick = onRun, enabled = enabled) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.backupjobs_run), tint = c.accent)
                }
            }
        }
    }
}

@Composable
private fun NotBackedUpCard(guests: List<String>) {
    val c = AnProxTheme.colors
    GlassCard {
        if (guests.isEmpty()) {
            FormHint(stringResource(R.string.backupjobs_all_covered))
        } else {
            FormHint(stringResource(R.string.backupjobs_not_backed_up_body))
            Spacer(Modifier.height(8.dp))
            guests.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.warning, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

/** A friendly name for the app's schedule presets; other schedules as Proxmox writes them. */
@Composable
fun scheduleLabel(schedule: String): String = when (schedule.trim()) {
    BackupJobForm.SCHEDULE_PRESETS[0] -> stringResource(R.string.schedule_daily)
    BackupJobForm.SCHEDULE_PRESETS[1] -> stringResource(R.string.schedule_weekdays)
    BackupJobForm.SCHEDULE_PRESETS[2] -> stringResource(R.string.schedule_weekly)
    BackupJobForm.SCHEDULE_PRESETS[3] -> stringResource(R.string.schedule_six_hours)
    else -> schedule
}

@Composable
private fun selectionLabel(job: BackupJob, names: Map<Int, String>): String {
    val listed = job.vmids.joinToString(", ") { names[it] ?: it.toString() }
    return when (job.selection) {
        BackupSelection.ALL -> job.node?.let { stringResource(R.string.backupjobs_all_node, it) } ?: stringResource(R.string.backupjobs_all)
        BackupSelection.INCLUDE -> stringResource(R.string.backupjobs_include, listed)
        BackupSelection.EXCLUDE -> stringResource(R.string.backupjobs_exclude, listed)
        BackupSelection.POOL -> stringResource(R.string.backupjobs_pool, job.pool.orEmpty())
    }
}

@Composable
fun modeLabel(mode: BackupMode): String = stringResource(
    when (mode) {
        BackupMode.SNAPSHOT -> R.string.backup_mode_snapshot
        BackupMode.SUSPEND -> R.string.backup_mode_suspend
        BackupMode.STOP -> R.string.backup_mode_stop
    },
)

fun formatRun(context: Context, epochSeconds: Long): String = DateUtils.formatDateTime(
    context,
    epochSeconds * 1000,
    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
)
