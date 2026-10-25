package io.github.lucabarbalata.anprox.ui.guest

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.InfoRow
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.data.guest.GuestNetworkInfo
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.security.LocalDangerousActionGuard
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.CheckRow
import io.github.lucabarbalata.anprox.ui.common.CollapsibleHeader
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.ToolEntry
import io.github.lucabarbalata.anprox.ui.common.ToolGrid
import io.github.lucabarbalata.anprox.ui.common.UsageLine
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.formatDuration
import io.github.lucabarbalata.anprox.ui.common.statusAppearance
import io.github.lucabarbalata.anprox.ui.console.rememberSpiceLauncher
import io.github.lucabarbalata.anprox.ui.metrics.PerformanceSection
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Full-screen guest detail (phones). */
@Composable
fun GuestScreen(
    ref: GuestRef,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onTool: (tool: GuestTool, ref: GuestRef, guestName: String) -> Unit,
) {
    GuestDetail(ref = ref, onTask = onTask, onTool = onTool, onBack = onBack)
}

/**
 * Guest detail content. Used full-screen on phones and as the right pane of the resource list on
 * tablets. Exactly one of [onBack] (arrow) or [onClose] (×) is usually set.
 */
@Composable
fun GuestDetail(
    ref: GuestRef,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onTool: (tool: GuestTool, ref: GuestRef, guestName: String) -> Unit,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val key = "guest-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    val viewModel: GuestViewModel = koinViewModel(key = key) { parametersOf(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openSpice = rememberSpiceLauncher(snackbar)
    val copiedLabel = stringResource(R.string.guest_copied)
    var networkOpen by rememberSaveable { mutableStateOf(false) }
    var chartsOpen by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask, viewModel::consumeResult)

    val typeLabel = stringResource(if (ref.type == GuestType.QEMU) R.string.guest_type_vm else R.string.guest_type_ct)
    AnProxScaffold(
        title = state.title,
        subtitle = "$typeLabel ${ref.vmid} · ${ref.node} · ${state.serverName}",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::toggleFavourite) {
                if (state.favourite) {
                    Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.favourite_remove), tint = AnProxTheme.colors.warning)
                } else {
                    Icon(Icons.Outlined.StarOutline, contentDescription = stringResource(R.string.favourite_add))
                }
            }
            if (onClose != null) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close)) }
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error -> item { ErrorBanner(error.asString()) } }
            val status = state.status
            if (status != null) {
                item {
                    Summary(
                        status = status,
                        address = state.network?.nics?.flatMap { it.ipv4 }?.firstOrNull(),
                        actions = state.availableActions,
                        running = state.running,
                        onAction = viewModel::request,
                        onConsole = { onTool(GuestTool.CONSOLE, ref, state.title) },
                    )
                }
                item { ToolGrid(guestTools(ref, status, onTool = { tool -> onTool(tool, ref, state.title) }, openSpice = { openSpice(ref) }, onDelete = viewModel::requestDestroy)) }
                // Network, charts and details start folded, so the screen opens on what is used most.
                if (status.isRunning) {
                    item { CollapsibleHeader(stringResource(R.string.guest_network_section), networkOpen, { networkOpen = !networkOpen }) }
                    if (networkOpen) {
                        item { NetworkCard(ref.type, state.network, onCopied = { text -> scope.launch { snackbar.showSnackbar(copiedLabel.format(text)) } }) }
                    }
                }
                // Templates never run, so they have no history.
                if (!status.template) {
                    item { CollapsibleHeader(stringResource(R.string.perf_title), chartsOpen, { chartsOpen = !chartsOpen }) }
                    if (chartsOpen) item { PerformanceSection(MetricsSource.Guest(ref)) }
                }
                item { CollapsibleHeader(stringResource(R.string.guest_details), detailsOpen, { detailsOpen = !detailsOpen }) }
                if (detailsOpen) item { Details(ref, status) }
            }
        }
    }

    // The guest is gone: leave its screen.
    LaunchedEffect(state.destroyed) {
        if (state.destroyed) (onBack ?: onClose)?.invoke()
    }
    if (state.showDestroy) {
        val guard = LocalDangerousActionGuard.current
        val reason = stringResource(R.string.lock_reason_destroy, state.title)
        DestroyGuestDialog(
            ref = ref,
            name = state.title,
            running = state.status?.isRunning == true || state.status?.isPaused == true,
            busy = state.destroying,
            error = state.destroyError?.asString(),
            onConfirm = { purge, disks -> guard.confirm(reason) { viewModel.destroy(purge, disks) } },
            onDismiss = viewModel::dismissDestroy,
        )
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

/**
 * Deleting a guest (questionnaire Q12): type its ID, like the Proxmox web interface asks. Running
 * guests have to be shut down first, which the dialog explains instead of offering the button.
 */
@Composable
private fun DestroyGuestDialog(
    ref: GuestRef,
    name: String,
    running: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: (purge: Boolean, destroyUnreferencedDisks: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    val isVm = ref.type == GuestType.QEMU
    var purge by rememberSaveable { mutableStateOf(true) }
    var disks by rememberSaveable { mutableStateOf(true) }
    var typed by rememberSaveable { mutableStateOf("") }
    val enabled = !running && !busy && typed.trim() == ref.vmid.toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(if (isVm) R.string.destroy_vm_title else R.string.destroy_ct_title, ref.vmid, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    Text(stringResource(R.string.destroy_running), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                } else {
                    Text(stringResource(R.string.destroy_message), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    CheckRow(stringResource(R.string.destroy_purge), purge, enabled = !busy) { purge = !purge }
                    CheckRow(stringResource(R.string.destroy_unreferenced), disks, enabled = !busy) { disks = !disks }
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        enabled = !busy,
                        label = { Text(stringResource(R.string.confirm_type_to_confirm, ref.vmid.toString())) },
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.danger)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                }
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(onClick = { onConfirm(purge, disks) }, enabled = enabled) {
                    Text(stringResource(R.string.action_delete), color = if (enabled) c.danger else c.textTertiary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(if (running) R.string.action_close else R.string.action_cancel))
            }
        },
    )
}

/**
 * The guest at a glance, in one card: its state, how long it runs and its first address; CPU,
 * memory and disk; then Console, the power action that fits the state and the others in a menu.
 */
@Composable
private fun Summary(
    status: GuestStatus,
    address: String?,
    actions: List<PowerAction>,
    running: PowerAction?,
    onAction: (PowerAction) -> Unit,
    onConsole: () -> Unit,
) {
    val c = AnProxTheme.colors
    val appearance = statusAppearance(status.status, paused = status.isPaused)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(appearance.tone)
            Spacer(Modifier.width(8.dp))
            Text(appearance.label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            val extra = listOfNotNull(status.uptime?.takeIf { status.isRunning }?.let { formatDuration(it) }, address).joinToString(" · ")
            // Wraps rather than cut off the address with large fonts.
            if (extra.isNotEmpty()) Text(" · $extra", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
        }
        val tags = status.tags.orEmpty().split(';', ',', ' ').filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.take(4).forEach { TagChip(it) } }
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageLine(stringResource(R.string.metric_cpu), Formatters.percent(status.cpu), status.cpu)
            UsageLine(stringResource(R.string.guest_memory), Formatters.usage(status.mem, status.maxmem), status.memFraction)
            // VM disk usage is only known with the guest agent; containers report it.
            val diskKnown = (status.disk ?: 0) > 0
            UsageLine(
                stringResource(R.string.metric_disk),
                if (diskKnown) Formatters.usage(status.disk, status.maxdisk) else Formatters.bytes(status.maxdisk),
                status.diskFraction?.takeIf { diskKnown },
            )
        }
        if (!status.template) {
            Spacer(Modifier.height(16.dp))
            PowerRow(status.isRunning, actions, running, onAction, onConsole)
        }
    }
}

/**
 * Console and the one power action the state calls for (start a stopped guest, shut down a
 * running one, resume a paused one); the rest sit in the ⋯ menu.
 */
@Composable
private fun PowerRow(isRunning: Boolean, actions: List<PowerAction>, running: PowerAction?, onAction: (PowerAction) -> Unit, onConsole: () -> Unit) {
    val c = AnProxTheme.colors
    val main = actions.firstOrNull { it == PowerAction.START || it == PowerAction.RESUME } ?: actions.firstOrNull { it == PowerAction.SHUTDOWN }
    val others = actions.filter { it != main }
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val console: @Composable () -> Unit = {
            AnProxButton(
                stringResource(R.string.console_title),
                onClick = onConsole,
                style = if (isRunning) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                modifier = Modifier.weight(1f),
            )
        }
        val power: @Composable () -> Unit = {
            if (main != null) {
                AnProxButton(
                    actionLabel(main),
                    onClick = { onAction(main) },
                    style = if (main == PowerAction.SHUTDOWN) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY,
                    loading = running == main,
                    enabled = running == null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // The action that fits the state comes first: Start before Console on a stopped guest.
        if (isRunning) {
            console()
            power()
        } else {
            power()
            console()
        }
        if (others.isNotEmpty()) {
            Box {
                IconButton(onClick = { menu = true }, enabled = running == null) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.guest_power_other), tint = c.textPrimary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    others.forEach { action ->
                        val danger = action == PowerAction.STOP || action == PowerAction.RESET
                        DropdownMenuItem(
                            text = { Text(actionLabel(action), color = if (danger) c.danger else c.textPrimary) },
                            leadingIcon = { Icon(actionIcon(action), contentDescription = null, tint = if (danger) c.danger else c.textSecondary) },
                            onClick = {
                                menu = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The guest's tools for the [ToolGrid], most used first; [ToolGrid] folds the rest behind More.
 * Which ones show depends on the guest: templates have no console or snapshots, SPICE needs a
 * running VM, the agent's commands need the agent and a running VM.
 */
@Composable
private fun guestTools(ref: GuestRef, status: GuestStatus, onTool: (GuestTool) -> Unit, openSpice: () -> Unit, onDelete: () -> Unit): List<ToolEntry> {
    val isVm = ref.type == GuestType.QEMU
    return buildList {
        if (!status.template) add(ToolEntry(Icons.Outlined.PhotoCamera, stringResource(R.string.snapshots_title)) { onTool(GuestTool.SNAPSHOTS) })
        add(ToolEntry(Icons.Outlined.Backup, stringResource(R.string.backups_title)) { onTool(GuestTool.BACKUPS) })
        add(ToolEntry(Icons.Outlined.Memory, stringResource(R.string.hardware_title)) { onTool(GuestTool.HARDWARE) })
        add(ToolEntry(Icons.Outlined.Tune, stringResource(R.string.options_title)) { onTool(GuestTool.OPTIONS) })
        add(ToolEntry(Icons.Outlined.Shield, stringResource(R.string.firewall_title)) { onTool(GuestTool.FIREWALL) })
        if (!status.template) add(ToolEntry(Icons.Outlined.Key, stringResource(R.string.ssh_title)) { onTool(GuestTool.SSH) })
        add(ToolEntry(Icons.Outlined.ContentCopy, stringResource(R.string.clone_title)) { onTool(GuestTool.CLONE) })
        add(ToolEntry(Icons.Outlined.SwapHoriz, stringResource(R.string.migrate_title)) { onTool(GuestTool.MIGRATE) })
        if (isVm) add(ToolEntry(Icons.Outlined.CloudQueue, stringResource(R.string.cloudinit_title)) { onTool(GuestTool.CLOUD_INIT) })
        if (isVm && status.agent && status.isRunning) add(ToolEntry(Icons.Outlined.SupportAgent, stringResource(R.string.agent_title)) { onTool(GuestTool.AGENT) })
        if (isVm && status.isRunning) add(ToolEntry(Icons.Outlined.Monitor, stringResource(R.string.spice_title), onClick = openSpice))
        add(ToolEntry(Icons.Outlined.DeleteForever, stringResource(R.string.action_delete), danger = true, onClick = onDelete))
    }
}

/**
 * OS and IP addresses (questionnaire Q18). VMs need the QEMU guest agent; without it, explain how
 * to get the addresses. Tapping an address copies it.
 */
@Composable
private fun NetworkCard(type: GuestType, network: GuestNetworkInfo?, onCopied: (String) -> Unit) {
    val c = AnProxTheme.colors
    val clipboard = LocalClipboardManager.current
    GlassCard {
        when {
            network == null -> Text(stringResource(R.string.guest_network_loading), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            network.nics.isEmpty() -> {
                val hint = if (type == GuestType.QEMU) R.string.guest_agent_missing else R.string.guest_no_addresses
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                network.agentProblem?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.guest_agent_problem, it.trim()), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
            else -> {
                network.os?.let { InfoRow(stringResource(R.string.guest_os), it) }
                network.nics.forEach { nic ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        listOfNotNull(nic.name, nic.mac).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textTertiary,
                    )
                    (nic.ipv4 + nic.ipv6).forEach { address ->
                        val copyLabel = stringResource(R.string.guest_copy_address, address)
                        Text(
                            address,
                            style = MonoStyle,
                            color = c.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = copyLabel) {
                                    clipboard.setText(AnnotatedString(address))
                                    onCopied(address)
                                }
                                .padding(vertical = 14.dp), // 48 dp to tap
                        )
                    }
                }
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
