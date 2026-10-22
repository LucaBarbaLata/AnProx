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
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
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
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageBar
import io.github.lucabarbalata.anprox.core.designsystem.component.color
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.snapshot.PbsSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotState
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.TaskState
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import io.github.lucabarbalata.anprox.ui.common.statusAppearance
import io.github.lucabarbalata.anprox.ui.common.toUiText
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
                // One card per server, with what needs attention inside it; no totals above.
                items(state.servers, key = { it.server.id }) { s ->
                    ServerCard(
                        s,
                        alerts = state.alerts.filter { it.serverId == s.server.id },
                        onLogin = { onLogin(s.server.id) },
                        onNode = { node -> onNode(s.server.id, node) },
                        onResources = onResources,
                        onDatacenter = { title -> onDatacenter(s.server.id, title) },
                        onBackupServer = { onBackupServer(s.server.id, s.server.profile.name) },
                    )
                }
                if (state.favourites.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.dashboard_favourites)) }
                    item(span = { GridItemSpan(maxLineSpan) }) { FavouritesCard(state.favourites, onGuest) }
                }
            }
        }
    }
}

/** Pinned guests with their live state, as rows of one card; tap one to open it. */
@Composable
private fun FavouritesCard(favourites: List<FavouriteGuest>, onGuest: (GuestRef) -> Unit) {
    val c = AnProxTheme.colors
    val vm = stringResource(R.string.guest_type_vm)
    val ct = stringResource(R.string.guest_type_ct)
    val multipleServers = favourites.map { it.serverId }.distinct().size > 1
    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        favourites.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
            val r = f.resource
            val ref = f.ref
            val appearance = statusAppearance(r.status)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (ref != null) Modifier.clickable { onGuest(ref) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(appearance.tone)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name ?: r.vmid?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val type = if (GuestType.fromApi(r.type) == GuestType.QEMU) vm else ct
                    Text(
                        listOfNotNull("$type ${r.vmid}", r.node, f.serverName.takeIf { multipleServers }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                    )
                }
                Text(
                    if (r.status == "running") stringResource(R.string.dashboard_cpu_value, Formatters.percent(r.cpu)) else appearance.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
    }
}

/** A highlighted line inside a server's card: a full storage, an offline node, lost quorum. */
@Composable
private fun AttentionRow(text: String, tone: Tone, onClick: (() -> Unit)? = null) {
    val c = AnProxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tone.color().copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = tone.color(), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
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
private fun Alerts(alerts: List<DashboardAlert>, onLogin: () -> Unit) {
    alerts.forEach { alert ->
        val (tone, text) = alertText(alert)
        AttentionRow(text, tone, onClick = if (alert is DashboardAlert.SignInRequired) onLogin else null)
    }
}

@Composable
private fun ServerCard(
    s: SnapshotState,
    alerts: List<DashboardAlert>,
    onLogin: () -> Unit,
    onNode: (String) -> Unit,
    onResources: () -> Unit,
    onDatacenter: (title: String) -> Unit,
    onBackupServer: () -> Unit,
) {
    val c = AnProxTheme.colors
    val snapshot = s.snapshot
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            (snapshot as? PveSnapshot)?.clusterName ?: s.server.profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val nodes = (snapshot as? PveSnapshot)?.nodes?.size?.takeIf { it > 0 }
        Text(
            listOfNotNull(
                listOfNotNull(s.server.profile.product.displayName, snapshot?.version).joinToString(" "),
                nodes?.let { pluralStringResource(R.plurals.dashboard_nodes, it, it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                s.server.auth != AuthState.LOGGED_IN -> {
                    Alerts(alerts, onLogin)
                    AnProxButton(stringResource(R.string.action_sign_in), onClick = onLogin, style = ButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
                }
                snapshot == null && s.error != null -> ErrorBanner(s.error!!.toUiText().asString())
                snapshot == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.dashboard_connecting), color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                snapshot is PveSnapshot -> PveBody(snapshot, alerts, onNode, onResources) { onDatacenter(snapshot.clusterName ?: s.server.profile.name) }
                snapshot is PbsSnapshot -> PbsBody(snapshot, alerts, onBackupServer)
            }

            if (s.isStale) {
                Text(
                    stringResource(R.string.dashboard_stale, s.updatedAtMillis?.let { relativeTime(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.warning,
                )
            }
        }
    }
}

/** A big number with a quiet label under it; three of them sum up a cluster. */
@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = AnProxTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
    }
}

@Composable
private fun PveBody(snap: PveSnapshot, alerts: List<DashboardAlert>, onNode: (String) -> Unit, onResources: () -> Unit, onDatacenter: () -> Unit) {
    val c = AnProxTheme.colors
    val vms = snap.guestCounts(GuestType.QEMU)
    val cts = snap.guestCounts(GuestType.LXC)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("${vms.running + cts.running}/${vms.total + cts.total}", stringResource(R.string.dashboard_guests_running), Modifier.weight(1f), onResources)
        Stat(Formatters.percent(snap.cpuFraction), stringResource(R.string.metric_cpu), Modifier.weight(1f))
        Stat(Formatters.percent(snap.memFraction), stringResource(R.string.guest_memory), Modifier.weight(1f))
    }
    Alerts(alerts, onLogin = {})
    // Nodes as plain rows: a dot, the name and its load in words. Offline ones say so in red.
    Column {
        snap.nodes.forEach { node ->
            val appearance = statusAppearance(node.status)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = node.node) { node.node?.let(onNode) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(appearance.tone)
                Spacer(Modifier.width(12.dp))
                Text(node.node ?: node.id, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (node.status == "online") {
                    Text(
                        stringResource(R.string.dashboard_node_load, Formatters.percent(node.cpuFraction), Formatters.percent(node.memFraction)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                } else {
                    Text(appearance.label, style = MaterialTheme.typography.bodySmall, color = appearance.tone.color())
                }
            }
        }
    }
    AnProxButton(stringResource(R.string.dashboard_datacenter), onClick = onDatacenter, style = ButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PbsBody(snap: PbsSnapshot, alerts: List<DashboardAlert>, onManage: () -> Unit) {
    val c = AnProxTheme.colors
    snap.datastores.forEach { ds ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ds.store, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val full = ds.estimatedFullDate?.takeIf { it > 0 }
                Text(
                    Formatters.percent(ds.fraction) + (full?.let { " · " + stringResource(R.string.dashboard_full_in, relativeTime(it * 1000)) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                )
            }
            UsageBar(ds.fraction, height = 4.dp)
            Text(Formatters.usage(ds.used, ds.total), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
    val failed = snap.recentTasks.count { it.state == TaskState.FAILED }
    if (failed > 0) AttentionRow(stringResource(R.string.dashboard_failed_tasks, failed), Tone.DANGER)
    Alerts(alerts, onLogin = {})
    AnProxButton(stringResource(R.string.dashboard_backup_server), onClick = onManage, style = ButtonStyle.SECONDARY, modifier = Modifier.fillMaxWidth())
}
