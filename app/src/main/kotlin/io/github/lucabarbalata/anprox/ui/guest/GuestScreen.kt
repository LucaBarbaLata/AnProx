package io.github.lucabarbalata.anprox.ui.guest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.InfoRow
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageRing
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.formatDuration
import io.github.lucabarbalata.anprox.ui.common.statusAppearance
import io.github.lucabarbalata.anprox.ui.metrics.PerformanceSection
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Full-screen guest detail (phones). */
@Composable
fun GuestScreen(
    ref: GuestRef,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onSnapshots: (ref: GuestRef, guestName: String) -> Unit,
    onBackups: (ref: GuestRef, guestName: String) -> Unit,
) {
    GuestDetail(ref = ref, onTask = onTask, onSnapshots = onSnapshots, onBackups = onBackups, onBack = onBack)
}

/**
 * Guest detail content. Used full-screen on phones and as the right pane of the resource list on
 * tablets. Exactly one of [onBack] (arrow) or [onClose] (×) is usually set.
 */
@Composable
fun GuestDetail(
    ref: GuestRef,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onSnapshots: (ref: GuestRef, guestName: String) -> Unit,
    onBackups: (ref: GuestRef, guestName: String) -> Unit,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val key = "guest-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    val viewModel: GuestViewModel = koinViewModel(key = key) { parametersOf(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask, viewModel::consumeResult)

    val typeLabel = stringResource(if (ref.type == GuestType.QEMU) R.string.guest_type_vm else R.string.guest_type_ct)
    AnProxScaffold(
        title = state.title,
        subtitle = "$typeLabel ${ref.vmid} · ${ref.node} · ${state.serverName}",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (onClose != null) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close)) }
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error -> item { ErrorBanner(error.asString()) } }
            val status = state.status
            if (status != null) {
                item { Overview(status) }
                if (state.availableActions.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.guest_power)) }
                    item { PowerActions(state.availableActions, state.running, viewModel::request) }
                }
                // Templates never run, so they have no history.
                if (!status.template) {
                    item { SectionHeader(stringResource(R.string.perf_title)) }
                    item { PerformanceSection(MetricsSource.Guest(ref)) }
                }
                item { SectionHeader(stringResource(R.string.guest_details)) }
                item { Details(ref, status) }
                item { SectionHeader(stringResource(R.string.guest_more_tools)) }
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                        // Templates can't have snapshots in Proxmox.
                        if (!status.template) {
                            MenuRow(
                                Icons.Outlined.PhotoCamera,
                                stringResource(R.string.snapshots_title),
                                stringResource(R.string.snapshots_open_body),
                            ) { onSnapshots(ref, state.title) }
                        }
                        MenuRow(
                            Icons.Outlined.Backup,
                            stringResource(R.string.backups_title),
                            stringResource(R.string.backups_open_body),
                        ) { onBackups(ref, state.title) }
                    }
                }
                item { SectionHeader(stringResource(R.string.guest_coming_later)) }
                item { ComingSoon() }
            }
        }
    }

    state.confirm?.let { action ->
        val name = state.title
        ConfirmActionDialog(
            title = stringResource(R.string.guest_confirm_title, actionLabel(action), name),
            message = stringResource(confirmMessage(action)),
            confirmLabel = actionLabel(action),
            danger = action.danger,
            confirmText = if (action.danger == DangerLevel.TYPE_TO_CONFIRM) name else null,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

@Composable
private fun Overview(status: GuestStatus) {
    val c = AnProxTheme.colors
    val appearance = statusAppearance(status.status, paused = status.isPaused)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(appearance.label, appearance.tone)
            Spacer(Modifier.width(10.dp))
            if (status.isRunning) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(formatDuration(status.uptime), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            }
        }
        val tags = status.tags.orEmpty().split(';', ',', ' ').filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.take(6).forEach { TagChip(it) } }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val cpu = stringResource(R.string.metric_cpu)
            val ram = stringResource(R.string.metric_ram)
            val disk = stringResource(R.string.metric_disk)
            UsageRing(status.cpu, Formatters.percent(status.cpu), caption = cpu)
            UsageRing(status.memFraction, Formatters.percent(status.memFraction), caption = ram)
            // VM disk usage is only known with the guest agent; containers report it.
            UsageRing(status.diskFraction?.takeIf { (status.disk ?: 0) > 0 }, Formatters.bytes(status.maxdisk), caption = disk)
        }
    }
}

@Composable
private fun PowerActions(actions: List<PowerAction>, running: PowerAction?, onAction: (PowerAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { action ->
                    AnProxButton(
                        text = actionLabel(action),
                        onClick = { onAction(action) },
                        icon = actionIcon(action),
                        style = when (action) {
                            PowerAction.START, PowerAction.RESUME -> ButtonStyle.PRIMARY
                            PowerAction.STOP, PowerAction.RESET -> ButtonStyle.DANGER
                            else -> ButtonStyle.SECONDARY
                        },
                        loading = running == action,
                        enabled = running == null,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Details(ref: GuestRef, s: GuestStatus) {
    val yes = stringResource(R.string.common_yes)
    val no = stringResource(R.string.common_no)
    GlassCard {
        InfoRow(stringResource(R.string.guest_node), ref.node)
        InfoRow(stringResource(R.string.guest_vmid), ref.vmid.toString(), mono = true)
        s.cpus?.let { InfoRow(stringResource(R.string.guest_cpus), it.toInt().toString()) }
        InfoRow(stringResource(R.string.guest_memory), Formatters.usage(s.mem, s.maxmem))
        InfoRow(stringResource(R.string.guest_boot_disk), Formatters.bytes(s.maxdisk))
        InfoRow(stringResource(R.string.guest_network), "↓ ${Formatters.bytes(s.netin)}  ↑ ${Formatters.bytes(s.netout)}")
        InfoRow(stringResource(R.string.guest_disk_io), "R ${Formatters.bytes(s.diskread)}  W ${Formatters.bytes(s.diskwrite)}")
        InfoRow(stringResource(R.string.guest_ha), if (s.ha?.managed == true) yes else no)
        if (ref.type == GuestType.QEMU) {
            InfoRow(stringResource(R.string.guest_agent), if (s.agent) yes else no)
            s.runningQemu?.let { InfoRow(stringResource(R.string.guest_qemu), it) }
            s.runningMachine?.let { InfoRow(stringResource(R.string.guest_machine), it, mono = true) }
        }
        s.lock?.let { InfoRow(stringResource(R.string.guest_lock), it) }
    }
}

/** Honest pointer to what is planned, matching docs/roadmap.md. */
@Composable
private fun ComingSoon() {
    val c = AnProxTheme.colors
    val items = listOf(
        R.string.tool_console to "M2",
        R.string.tool_hardware to "M2",
        R.string.tool_clone to "M2",
        R.string.tool_migrate to "M2",
    )
    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items.forEach { (label, milestone) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                TagChip(stringResource(R.string.coming_in, milestone))
            }
        }
    }
}

@Composable
fun actionLabel(action: PowerAction): String = stringResource(
    when (action) {
        PowerAction.START -> R.string.power_start
        PowerAction.SHUTDOWN -> R.string.power_shutdown
        PowerAction.REBOOT -> R.string.power_reboot
        PowerAction.STOP -> R.string.power_stop
        PowerAction.RESET -> R.string.power_reset
        PowerAction.SUSPEND -> R.string.power_pause
        PowerAction.RESUME -> R.string.power_resume
    },
)

private fun actionIcon(action: PowerAction) = when (action) {
    PowerAction.START, PowerAction.RESUME -> Icons.Outlined.PlayArrow
    PowerAction.SHUTDOWN -> Icons.Outlined.PowerSettingsNew
    PowerAction.REBOOT -> Icons.Outlined.RestartAlt
    PowerAction.STOP -> Icons.Outlined.StopCircle
    PowerAction.RESET -> Icons.Outlined.Bolt
    PowerAction.SUSPEND -> Icons.Outlined.PauseCircle
}

private fun confirmMessage(action: PowerAction): Int = when (action) {
    PowerAction.STOP -> R.string.confirm_stop
    PowerAction.RESET -> R.string.confirm_reset
    PowerAction.SHUTDOWN -> R.string.confirm_shutdown
    PowerAction.REBOOT -> R.string.confirm_reboot
    PowerAction.SUSPEND -> R.string.confirm_pause
    else -> R.string.confirm_generic
}
