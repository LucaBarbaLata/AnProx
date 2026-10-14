package io.github.lucabarbalata.anprox.ui.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.model.pve.NodePowerAction
import io.github.lucabarbalata.anprox.security.LocalDangerousActionGuard
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageRing
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
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
    AnProxScaffold(title = node, subtitle = stringResource(R.string.node_subtitle), onBack = onBack, snackbarHostState = snackbar) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.online) item { ErrorBanner(stringResource(R.string.node_offline)) }
            state.error?.let { e -> if (state.online) item { ErrorBanner(e.asString()) } }
            state.status?.let { s ->
                item { Gauges(s) }
                if (state.online) {
                    item { SectionHeader(stringResource(R.string.node_tools)) }
                    item {
                        GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                            MenuRow(Icons.Outlined.Terminal, stringResource(R.string.node_shell), stringResource(R.string.node_shell_body), onShell)
                        }
                    }
                }
                if (state.online) {
                    item { SectionHeader(stringResource(R.string.perf_title)) }
                    item { PerformanceSection(MetricsSource.Node(serverId, node)) }
                }
                item { SectionHeader(stringResource(R.string.node_system)) }
                item { SystemInfo(s) }
            }
            if (state.guests.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.node_guests, state.guests.size)) }
                items(state.guests, key = { it.id }) { g ->
                    val type = GuestType.fromApi(g.type)
                    val appearance = statusAppearance(g.status)
                    GlassCard(
                        onClick = if (type != null && g.vmid != null) {
                            { onGuest(GuestRef(serverId, node, type, g.vmid!!)) }
                        } else {
                            null
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(appearance.tone)
                            Spacer(Modifier.width(10.dp))
                            Text("${g.vmid}", style = MonoStyle, color = AnProxTheme.colors.textTertiary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                g.name.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                color = AnProxTheme.colors.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(appearance.label, style = MaterialTheme.typography.labelMedium, color = AnProxTheme.colors.textSecondary)
                        }
                    }
                }
            }
            if (state.online && state.status != null) {
                item { SectionHeader(stringResource(R.string.node_power)) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnProxButton(
                            text = stringResource(R.string.node_reboot),
                            onClick = { viewModel.requestPower(NodePowerAction.REBOOT) },
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.RestartAlt,
                            enabled = state.busy == null,
                            loading = state.busy == NodePowerAction.REBOOT,
                            modifier = Modifier.weight(1f),
                        )
                        AnProxButton(
                            text = stringResource(R.string.node_shutdown),
                            onClick = { viewModel.requestPower(NodePowerAction.SHUTDOWN) },
                            style = ButtonStyle.DANGER,
                            icon = Icons.Outlined.PowerSettingsNew,
                            enabled = state.busy == null,
                            loading = state.busy == NodePowerAction.SHUTDOWN,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

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

@Composable
private fun Gauges(s: NodeStatus) {
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val cpu = stringResource(R.string.metric_cpu)
            val ram = stringResource(R.string.metric_ram)
            val root = stringResource(R.string.metric_root)
            UsageRing(s.cpu, Formatters.percent(s.cpu), caption = cpu)
            UsageRing(s.memory.fraction, Formatters.percent(s.memory.fraction), caption = ram)
            UsageRing(s.rootfs?.fraction, Formatters.percent(s.rootfs?.fraction), caption = root)
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
