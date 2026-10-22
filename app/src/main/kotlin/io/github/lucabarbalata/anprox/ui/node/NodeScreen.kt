package io.github.lucabarbalata.anprox.ui.node

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.InfoRow
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodePowerAction
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.security.LocalDangerousActionGuard
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.CheckRow
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.CollapsibleHeader
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.ToolEntry
import io.github.lucabarbalata.anprox.ui.common.ToolGrid
import io.github.lucabarbalata.anprox.ui.common.UsageLine
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.formatDuration
import io.github.lucabarbalata.anprox.ui.common.statusAppearance
import io.github.lucabarbalata.anprox.ui.metrics.PerformanceSection
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NodeScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onGuest: (GuestRef) -> Unit,
    onShell: () -> Unit,
    onSsh: () -> Unit,
    onCreate: (GuestType) -> Unit,
    onFirewall: () -> Unit,
    onAdmin: (NodeAdminPage) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    viewModel: NodeViewModel = koinViewModel(key = "node-$serverId-$node") { parametersOf(serverId, node) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(state.message) {
        if (message != null) {
            viewModel.consumeMessage()
            snackbar.showSnackbar(message)
        }
    }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    var chartsOpen by rememberSaveable { mutableStateOf(false) }
    var systemOpen by rememberSaveable { mutableStateOf(false) }
    AnProxScaffold(
        title = node,
        subtitle = stringResource(R.string.node_subtitle),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = { if (state.online && state.status != null) PowerMenu(state.busy, viewModel::requestPower) },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.online) item { ErrorBanner(stringResource(R.string.node_offline)) }
            state.error?.let { e -> if (state.online) item { ErrorBanner(e.asString()) } }
            state.status?.let { s ->
                item { Summary(s, state.online) }
                if (state.online) {
                    item { ToolGrid(nodeTools(onShell, onSsh, onCreate, onFirewall, onAdmin)) }
                }
            }
            if (state.guests.isNotEmpty()) {
                item { GuestsHeader(state.guests.size, if (state.online) state.bulkBusy else null, state.online, viewModel::requestBulk) }
                item { GuestRows(state.guests) { type, vmid -> onGuest(GuestRef(serverId, node, type, vmid)) } }
            }
            state.status?.let { s ->
                // Charts and the system details start folded: the screen opens on what is used most.
                if (state.online) {
                    item { CollapsibleHeader(stringResource(R.string.perf_title), chartsOpen, { chartsOpen = !chartsOpen }) }
                    if (chartsOpen) item { PerformanceSection(MetricsSource.Node(serverId, node)) }
                }
                item { CollapsibleHeader(stringResource(R.string.node_system), systemOpen, { systemOpen = !systemOpen }) }
                if (systemOpen) item { SystemInfo(s) }
            }
        }
    }

    state.bulk?.let { bulk -> BulkDialog(bulk, node, state.otherNodes, viewModel) }

    state.confirm?.let { action ->
        val guard = LocalDangerousActionGuard.current
        val reboot = action == NodePowerAction.REBOOT
        val title = stringResource(if (reboot) R.string.node_reboot_title else R.string.node_shutdown_title, node)
        ConfirmActionDialog(
            title = title,
            message = stringResource(if (reboot) R.string.node_reboot_body else R.string.node_shutdown_body),
            confirmLabel = stringResource(if (reboot) R.string.node_reboot else R.string.node_shutdown),
            danger = action.danger,
            confirmText = node,
            onConfirm = { guard.confirm(title, viewModel::power) },
            onDismiss = viewModel::dismissPower,
        )
    }
}

/**
 * The guests' header, with a ⋯ menu that starts, shuts down or migrates every guest on the node,
 * each as one Proxmox task. A bar under it runs while that happens.
 */
@Composable
private fun GuestsHeader(count: Int, busy: NodeBulk?, online: Boolean, onRequest: (NodeBulk) -> Unit) {
    val c = AnProxTheme.colors
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.node_guests, count),
                style = MaterialTheme.typography.titleSmall,
                color = c.textSecondary,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (online) {
                Box {
                    IconButton(onClick = { menu = true }, enabled = busy == null) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.node_guest_actions), tint = c.textPrimary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(
                            Triple(NodeBulk.START_ALL, R.string.node_start_all, Icons.Outlined.PlayArrow),
                            Triple(NodeBulk.STOP_ALL, R.string.node_stop_all, Icons.Outlined.StopCircle),
                            Triple(NodeBulk.MIGRATE_ALL, R.string.node_migrate_all, Icons.Outlined.SwapHoriz),
                        ).forEach { (bulk, label, icon) ->
                            val danger = bulk == NodeBulk.STOP_ALL
                            DropdownMenuItem(
                                text = { Text(stringResource(label), color = if (danger) c.danger else c.textPrimary) },
                                leadingIcon = { Icon(icon, contentDescription = null, tint = if (danger) c.danger else c.textSecondary) },
                                onClick = {
                                    menu = false
                                    onRequest(bulk)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = c.accent)
    }
}

/** The node's guests as rows of one card: state, name and ID, CPU while running. */
@Composable
private fun GuestRows(guests: List<ClusterResource>, onOpen: (GuestType, Int) -> Unit) {
    val c = AnProxTheme.colors
    val vm = stringResource(R.string.guest_type_vm)
    val ct = stringResource(R.string.guest_type_ct)
    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        guests.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
            val type = GuestType.fromApi(g.type)
            val vmid = g.vmid
            val appearance = statusAppearance(g.status)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (type != null && vmid != null) Modifier.clickable { onOpen(type, vmid) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(appearance.tone)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${if (type == GuestType.QEMU) vm else ct} $vmid", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                Text(
                    when {
                        g.template -> stringResource(R.string.resources_template)
                        g.status == "running" -> Formatters.percent(g.cpuFraction)
                        else -> appearance.label
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
    }
}

/** Reboot and shut down, behind one button in the top bar: rare, and both ask first. */
@Composable
private fun PowerMenu(busy: NodePowerAction?, onRequest: (NodePowerAction) -> Unit) {
    val c = AnProxTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = busy == null) {
            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = stringResource(R.string.node_power))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.node_reboot)) },
                leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = c.textSecondary) },
                onClick = {
                    menu = false
                    onRequest(NodePowerAction.REBOOT)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.node_shutdown), color = c.danger) },
                leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = c.danger) },
                onClick = {
                    menu = false
                    onRequest(NodePowerAction.SHUTDOWN)
                },
            )
        }
    }
}

/** The node's tools, most used first; [ToolGrid] folds the rest behind More. */
@Composable
private fun nodeTools(
    onShell: () -> Unit,
    onSsh: () -> Unit,
    onCreate: (GuestType) -> Unit,
    onFirewall: () -> Unit,
    onAdmin: (NodeAdminPage) -> Unit,
): List<ToolEntry> = listOf(
    ToolEntry(Icons.Outlined.Terminal, stringResource(R.string.node_shell), onClick = onShell),
    ToolEntry(Icons.Outlined.Computer, stringResource(R.string.node_create_vm)) { onCreate(GuestType.QEMU) },
    ToolEntry(Icons.Outlined.Inventory2, stringResource(R.string.node_create_ct)) { onCreate(GuestType.LXC) },
    ToolEntry(Icons.Outlined.SystemUpdateAlt, stringResource(R.string.updates_title)) { onAdmin(NodeAdminPage.UPDATES) },
    ToolEntry(Icons.Outlined.Settings, stringResource(R.string.services_title)) { onAdmin(NodeAdminPage.SERVICES) },
    ToolEntry(Icons.Outlined.SettingsEthernet, stringResource(R.string.network_title)) { onAdmin(NodeAdminPage.NETWORK) },
    ToolEntry(Icons.Outlined.Description, stringResource(R.string.journal_title)) { onAdmin(NodeAdminPage.JOURNAL) },
    ToolEntry(Icons.Outlined.Key, stringResource(R.string.ssh_title), onClick = onSsh),
    ToolEntry(Icons.Outlined.Shield, stringResource(R.string.firewall_title), onClick = onFirewall),
    ToolEntry(Icons.Outlined.Storage, stringResource(R.string.disks_title)) { onAdmin(NodeAdminPage.DISKS) },
    ToolEntry(Icons.Outlined.CloudDownload, stringResource(R.string.repos_title)) { onAdmin(NodeAdminPage.REPOSITORIES) },
    ToolEntry(Icons.Outlined.Language, stringResource(R.string.system_title)) { onAdmin(NodeAdminPage.SYSTEM) },
    ToolEntry(Icons.Outlined.VerifiedUser, stringResource(R.string.cert_title)) { onAdmin(NodeAdminPage.CERTIFICATES) },
    ToolEntry(Icons.Outlined.History, stringResource(R.string.taskhistory_title)) { onAdmin(NodeAdminPage.TASKS) },
)

@Composable
private fun BulkDialog(bulk: NodeBulk, node: String, otherNodes: List<String>, viewModel: NodeViewModel) {
    val c = AnProxTheme.colors
    when (bulk) {
        NodeBulk.STOP_ALL -> {
            val guard = LocalDangerousActionGuard.current
            val title = stringResource(R.string.node_stop_all_title, node)
            ConfirmActionDialog(
                title = title,
                message = stringResource(R.string.node_stop_all_message),
                confirmLabel = stringResource(R.string.node_stop_all),
                danger = bulk.danger,
                confirmText = node,
                onConfirm = { guard.confirm(title, viewModel::stopAll) },
                onDismiss = viewModel::dismissBulk,
            )
        }
        NodeBulk.START_ALL -> {
            var force by rememberSaveable { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = viewModel::dismissBulk,
                containerColor = c.surfaceSolid,
                title = { Text(stringResource(R.string.node_start_all_title, node)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.node_start_all_message), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        CheckRow(stringResource(R.string.node_start_all_force), force) { force = !force }
                    }
                },
                confirmButton = { TextButton(onClick = { viewModel.startAll(force) }) { Text(stringResource(R.string.node_start_all)) } },
                dismissButton = { TextButton(onClick = viewModel::dismissBulk) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        NodeBulk.MIGRATE_ALL -> {
            var target by rememberSaveable { mutableStateOf(otherNodes.firstOrNull()) }
            var localDisks by rememberSaveable { mutableStateOf(false) }
            val chosen = target?.takeIf { it in otherNodes }
            AlertDialog(
                onDismissRequest = viewModel::dismissBulk,
                containerColor = c.surfaceSolid,
                title = { Text(stringResource(R.string.node_migrate_all_title, node)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (otherNodes.isEmpty()) {
                            Text(stringResource(R.string.node_migrate_all_no_target), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        } else {
                            Text(stringResource(R.string.node_migrate_all_message), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            FormLabel(stringResource(R.string.node_migrate_all_target))
                            ChoiceChips(otherNodes, chosen ?: otherNodes.first(), { it }) { target = it }
                            CheckRow(stringResource(R.string.node_migrate_all_local), localDisks) { localDisks = !localDisks }
                        }
                    }
                },
                confirmButton = {
                    if (otherNodes.isNotEmpty()) {
                        TextButton(onClick = { viewModel.migrateAll(chosen ?: otherNodes.first(), localDisks) }) {
                            Text(stringResource(R.string.node_migrate_all))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissBulk) {
                        Text(stringResource(if (otherNodes.isEmpty()) R.string.action_close else R.string.action_cancel))
                    }
                },
            )
        }
    }
}

/** The node at a glance: whether it is up and for how long, then CPU, memory and the root disk. */
@Composable
private fun Summary(s: NodeStatus, online: Boolean) {
    val c = AnProxTheme.colors
    val appearance = statusAppearance(if (online) "online" else "offline")
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(appearance.tone)
            Spacer(Modifier.width(8.dp))
            Text(appearance.label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            val version = s.pveversion?.substringAfter('/')?.substringBefore('/')
            val extra = listOfNotNull(formatDuration(s.uptime).takeIf { online && s.uptime > 0 }, version).joinToString(" · ")
            if (extra.isNotEmpty()) Text(" · $extra", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 1)
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UsageLine(stringResource(R.string.metric_cpu), Formatters.percent(s.cpu), s.cpu)
            UsageLine(stringResource(R.string.guest_memory), Formatters.usage(s.memory.used, s.memory.total), s.memory.fraction)
            s.rootfs?.let { UsageLine(stringResource(R.string.metric_root), Formatters.usage(it.used, it.total), it.fraction) }
        }
    }
}

@Composable
private fun SystemInfo(s: NodeStatus) {
    GlassCard {
        s.cpuinfo?.model?.let { InfoRow(stringResource(R.string.node_cpu_model), it) }
        s.cpuinfo?.let { InfoRow(stringResource(R.string.node_cpu_topology), stringResource(R.string.node_cpu_topology_value, it.sockets ?: 1, it.cores ?: 0, it.cpus ?: 0)) }
        InfoRow(stringResource(R.string.node_memory), Formatters.usage(s.memory.used, s.memory.total))
        s.swap?.let { InfoRow(stringResource(R.string.node_swap), Formatters.usage(it.used, it.total)) }
        s.rootfs?.let { InfoRow(stringResource(R.string.node_rootfs), Formatters.usage(it.used, it.total)) }
        InfoRow(stringResource(R.string.node_load), s.loadavg.joinToString("  "))
        s.wait?.let { InfoRow(stringResource(R.string.node_io_wait), Formatters.percent(it)) }
        InfoRow(stringResource(R.string.node_uptime), formatDuration(s.uptime))
        s.pveversion?.let { InfoRow(stringResource(R.string.node_pve_version), it.substringAfter('/').substringBefore('/')) }
        s.kversion?.let { InfoRow(stringResource(R.string.node_kernel), it.split(' ').take(2).joinToString(" "), mono = true) }
        s.bootInfo?.mode?.let { mode ->
            val secure = if (s.bootInfo?.secureboot == 1) " · Secure Boot" else ""
            InfoRow(stringResource(R.string.node_boot_mode), mode.uppercase() + secure)
        }
    }
}
