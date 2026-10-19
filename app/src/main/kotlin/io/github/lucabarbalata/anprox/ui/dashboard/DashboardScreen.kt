package io.github.lucabarbalata.anprox.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.MetricTile
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageBar
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageRing
import io.github.lucabarbalata.anprox.core.designsystem.component.color
import io.github.lucabarbalata.anprox.core.designsystem.component.isWideLayout
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.snapshot.PbsSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotState
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import io.github.lucabarbalata.anprox.ui.common.statusAppearance
import io.github.lucabarbalata.anprox.ui.common.toUiText
import io.github.lucabarbalata.anprox.ui.servers.AuthPill
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DashboardScreen(
    onAddServer: () -> Unit,
    onLogin: (serverId: String) -> Unit,
    onNode: (serverId: String, node: String) -> Unit,
    onResources: () -> Unit,
    onGuest: (GuestRef) -> Unit,
    onDatacenter: (serverId: String, title: String) -> Unit,
    onBackupServer: (serverId: String, title: String) -> Unit,
    viewModel: DashboardViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnProxScaffold(
        title = stringResource(R.string.dashboard_title),
        subtitle = if (state.servers.isEmpty()) null else stringResource(R.string.dashboard_subtitle, state.servers.size, state.refreshSeconds),
        actions = {
            IconButton(onClick = onAddServer) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.servers_add)) }
        },
    ) { inner ->
        when {
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.isEmpty -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.dashboard_welcome_title),
                    body = stringResource(R.string.dashboard_welcome_body),
                    action = { AnProxButton(stringResource(R.string.servers_add_first), onClick = onAddServer, icon = Icons.Outlined.Add) },
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                contentPadding = contentPadding(inner),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Totals(state.totals, onResources) }
                if (state.favourites.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.dashboard_favourites)) }
                    item(span = { GridItemSpan(maxLineSpan) }) { FavouritesCard(state.favourites, onGuest) }
                }
                if (state.alerts.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.dashboard_attention)) }
                    item(span = { GridItemSpan(maxLineSpan) }) { AlertsCard(state.alerts, onLogin) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.dashboard_servers)) }
                items(state.servers, key = { it.server.id }) { s ->
                    ServerCard(
                        s,
                        onLogin = { onLogin(s.server.id) },
                        onNode = { node -> onNode(s.server.id, node) },
                        onDatacenter = { title -> onDatacenter(s.server.id, title) },
                        onBackupServer = { onBackupServer(s.server.id, s.server.profile.name) },
                    )
                }
            }
        }
    }
}

/** Pinned guests with their live state; tap one to open it. */
@Composable
private fun FavouritesCard(favourites: List<FavouriteGuest>, onGuest: (GuestRef) -> Unit) {
    val c = AnProxTheme.colors
    val vm = stringResource(R.string.guest_type_vm)
    val ct = stringResource(R.string.guest_type_ct)
    val multipleServers = favourites.map { it.serverId }.distinct().size > 1
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        favourites.forEach { f ->
            val r = f.resource
            val ref = f.ref
            val appearance = statusAppearance(r.status)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (ref != null) Modifier.clickable { onGuest(ref) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(appearance.tone)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name ?: r.vmid?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val type = if (GuestType.fromApi(r.type) == GuestType.QEMU) vm else ct
                    Text(
                        listOfNotNull("$type ${r.vmid}", r.node, f.serverName.takeIf { multipleServers }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                Text(
                    if (r.status == "running") Formatters.percent(r.cpu) else appearance.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun Totals(t: DashboardTotals, onResources: () -> Unit) {
    val vmsLabel = stringResource(R.string.dashboard_vms_running)
    val ctsLabel = stringResource(R.string.dashboard_cts_running)
    val nodesLabel = stringResource(R.string.dashboard_nodes_online)
    val nodesTone = if (t.nodesOnline < t.nodesTotal) Tone.WARNING else Tone.SUCCESS
    if (isWideLayout()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("${t.vmsRunning} / ${t.vmsTotal}", vmsLabel, Modifier.weight(1f), Tone.ACCENT, Icons.Outlined.Computer, onResources)
            MetricTile("${t.ctsRunning} / ${t.ctsTotal}", ctsLabel, Modifier.weight(1f), Tone.INFO, Icons.Outlined.Inventory2, onResources)
            MetricTile("${t.nodesOnline} / ${t.nodesTotal}", nodesLabel, Modifier.weight(1f), nodesTone, Icons.Outlined.Dns, onResources)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile("${t.vmsRunning} / ${t.vmsTotal}", vmsLabel, Modifier.weight(1f), Tone.ACCENT, Icons.Outlined.Computer, onResources)
                MetricTile("${t.ctsRunning} / ${t.ctsTotal}", ctsLabel, Modifier.weight(1f), Tone.INFO, Icons.Outlined.Inventory2, onResources)
            }
            MetricTile("${t.nodesOnline} / ${t.nodesTotal}", nodesLabel, Modifier.fillMaxWidth(), nodesTone, Icons.Outlined.Dns, onResources)
        }
    }
}

@Composable
private fun AlertsCard(alerts: List<DashboardAlert>, onLogin: (String) -> Unit) {
    val c = AnProxTheme.colors
    GlassCard {
        alerts.forEachIndexed { i, alert ->
            if (i > 0) Spacer(Modifier.height(4.dp))
            val (tone, text) = alertText(alert)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (alert is DashboardAlert.SignInRequired) Modifier.clickable { onLogin(alert.serverId) } else Modifier)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = tone.color(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    Text(alert.serverName, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun alertText(alert: DashboardAlert): Pair<Tone, String> = when (alert) {
    is DashboardAlert.SignInRequired -> Tone.WARNING to stringResource(if (alert.expired) R.string.alert_session_expired else R.string.alert_signed_out)
    is DashboardAlert.NoQuorum -> Tone.DANGER to stringResource(R.string.alert_no_quorum)
    is DashboardAlert.NodeOffline -> Tone.DANGER to stringResource(R.string.alert_node_offline, alert.node)
    is DashboardAlert.StorageFull -> Tone.WARNING to stringResource(
        R.string.alert_storage_full,
        alert.storage + (alert.node?.let { " ($it)" } ?: ""),
        Formatters.percent(alert.fraction),
    )
    is DashboardAlert.DatastoreFull -> Tone.WARNING to stringResource(R.string.alert_storage_full, alert.store, Formatters.percent(alert.fraction))
}

@Composable
private fun ServerCard(s: SnapshotState, onLogin: () -> Unit, onNode: (String) -> Unit, onDatacenter: (title: String) -> Unit, onBackupServer: () -> Unit) {
    val c = AnProxTheme.colors
    val snapshot = s.snapshot
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (snapshot is PbsSnapshot) Icons.Outlined.Backup else Icons.Outlined.Dns,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (snapshot as? PveSnapshot)?.clusterName ?: s.server.profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(s.server.profile.product.displayName, snapshot?.version).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
            when {
                s.server.auth != AuthState.LOGGED_IN -> AuthPill(s.server.auth)
                snapshot is PveSnapshot && snapshot.quorate == true -> StatusPill(stringResource(R.string.status_quorate), Tone.SUCCESS)
                snapshot is PveSnapshot && snapshot.quorate == false -> StatusPill(stringResource(R.string.status_no_quorum), Tone.DANGER)
                snapshot is PveSnapshot -> StatusPill(stringResource(R.string.status_standalone), Tone.NEUTRAL)
                snapshot is PbsSnapshot -> StatusPill(stringResource(R.string.status_online), Tone.SUCCESS)
            }
        }
        Spacer(Modifier.height(14.dp))

        when {
            s.server.auth != AuthState.LOGGED_IN -> AnProxButton(
                stringResource(R.string.action_sign_in),
                onClick = onLogin,
                style = ButtonStyle.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
            snapshot == null && s.error != null -> ErrorBanner(s.error!!.toUiText().asString())
            snapshot == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.dashboard_connecting), color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            snapshot is PveSnapshot -> PveBody(snapshot, onNode) { onDatacenter(snapshot.clusterName ?: s.server.profile.name) }
            snapshot is PbsSnapshot -> PbsBody(snapshot, onBackupServer)
        }

        if (s.isStale) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.dashboard_stale, s.updatedAtMillis?.let { relativeTime(it) } ?: "—"),
                style = MaterialTheme.typography.labelSmall,
                color = c.warning,
            )
        }
    }
}

@Composable
private fun PveBody(snap: PveSnapshot, onNode: (String) -> Unit, onDatacenter: () -> Unit) {
    val c = AnProxTheme.colors
    val cpu = stringResource(R.string.metric_cpu)
    val ram = stringResource(R.string.metric_ram)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsageRing(snap.cpuFraction, Formatters.percent(snap.cpuFraction), caption = cpu)
        UsageRing(snap.memFraction, Formatters.percent(snap.memFraction), caption = ram)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val vms = snap.guestCounts(GuestType.QEMU)
            val cts = snap.guestCounts(GuestType.LXC)
            Text(stringResource(R.string.dashboard_vms_line, vms.running, vms.total), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Text(stringResource(R.string.dashboard_cts_line, cts.running, cts.total), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Text(Formatters.usage(snap.memUsed, snap.memTotal), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
    Spacer(Modifier.height(12.dp))
    snap.nodes.forEach { node ->
        val appearance = statusAppearance(node.status)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = node.node) { node.node?.let(onNode) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(appearance.tone)
            Spacer(Modifier.width(10.dp))
            Text(node.node ?: node.id, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (node.status == "online") {
                Column(Modifier.weight(1f)) {
                    UsageBar(node.cpuFraction)
                    Spacer(Modifier.height(4.dp))
                    UsageBar(node.memFraction)
                }
                Spacer(Modifier.width(10.dp))
                Text(Formatters.percent(node.cpuFraction), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.width(44.dp))
            } else {
                Text(appearance.label, style = MaterialTheme.typography.bodySmall, color = appearance.tone.color(), modifier = Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    AnProxButton(
        stringResource(R.string.dashboard_datacenter),
        onClick = onDatacenter,
        style = ButtonStyle.SECONDARY,
        icon = Icons.Outlined.Tune,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PbsBody(snap: PbsSnapshot, onManage: () -> Unit) {
    val c = AnProxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        snap.datastores.forEach { ds ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Storage, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ds.store, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(Formatters.percent(ds.fraction), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                }
                Spacer(Modifier.height(6.dp))
                UsageBar(ds.fraction)
                Spacer(Modifier.height(4.dp))
                val full = ds.estimatedFullDate?.takeIf { it > 0 }
                Text(
                    Formatters.usage(ds.used, ds.total) + (full?.let { " · " + stringResource(R.string.dashboard_full_in, relativeTime(it * 1000)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
        val failed = snap.recentTasks.count { it.state == io.github.lucabarbalata.anprox.model.pve.TaskState.FAILED }
        if (failed > 0) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.danger.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.dashboard_failed_tasks, failed), style = MaterialTheme.typography.labelMedium, color = c.danger)
            }
        }
        AnProxButton(
            stringResource(R.string.dashboard_backup_server),
            onClick = onManage,
            style = ButtonStyle.SECONDARY,
            icon = Icons.Outlined.Tune,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
