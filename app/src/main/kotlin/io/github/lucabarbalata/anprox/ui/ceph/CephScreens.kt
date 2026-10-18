package io.github.lucabarbalata.anprox.ui.ceph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.InfoRow
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageBar
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.data.datacenter.CephAction
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.CephFlag
import io.github.lucabarbalata.anprox.model.pve.CephFs
import io.github.lucabarbalata.anprox.model.pve.CephHealthCheck
import io.github.lucabarbalata.anprox.model.pve.CephOsd
import io.github.lucabarbalata.anprox.model.pve.CephOsdForm
import io.github.lucabarbalata.anprox.model.pve.CephOverview
import io.github.lucabarbalata.anprox.model.pve.CephPool
import io.github.lucabarbalata.anprox.model.pve.CephPoolForm
import io.github.lucabarbalata.anprox.model.pve.CephService
import io.github.lucabarbalata.anprox.model.pve.CephServiceType
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The pages under Datacenter → Ceph. */
enum class CephPage { OSDS, POOLS, SERVICES, CONFIG, CRUSH, LOG }

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AnProxTheme.colors.accent)
    }
}

@Composable
private fun healthLook(health: String): Pair<String, Tone> = when (health) {
    "HEALTH_OK" -> stringResource(R.string.ceph_health_ok) to Tone.SUCCESS
    "HEALTH_WARN" -> stringResource(R.string.ceph_health_warn) to Tone.WARNING
    "HEALTH_ERR" -> stringResource(R.string.ceph_health_err) to Tone.DANGER
    else -> stringResource(R.string.ceph_health_unknown) to Tone.NEUTRAL
}

/** Asks to type [confirmText] before [onConfirm]; [extra] goes above the field. */
@Composable
private fun TypedConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val c = AnProxTheme.colors
    var typed by rememberSaveable { mutableStateOf("") }
    val enabled = typed.trim() == confirmText
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                extra()
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.confirm_type_to_confirm, confirmText)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirmLabel, color = if (enabled) c.danger else c.textTertiary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------ overview

/** Datacenter → Ceph: health, capacity, daemons, OSD flags, and the pages below. */
@Composable
fun CephScreen(
    serverId: String,
    onBack: () -> Unit,
    onPage: (CephPage) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: CephViewModel = koinViewModel(key = "ceph-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, state.node.orEmpty(), onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.ceph_title),
        subtitle = state.node?.let { stringResource(R.string.ceph_read_from, it) },
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.notSetUp) {
                item { EmptyState(Icons.Outlined.Hub, stringResource(R.string.ceph_not_set_up), stringResource(R.string.ceph_not_set_up_body)) }
                return@LazyColumn
            }
            val overview = state.overview
            if (overview == null) {
                if (state.loading) item { Spinner() }
                return@LazyColumn
            }
            item { HealthCard(overview) }
            state.busy?.let { item { BusyCard(it.asString()) } }
            item { CapacityCard(overview) }
            item { DaemonsCard(overview) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Storage, stringResource(R.string.ceph_osds), stringResource(R.string.ceph_osds_body)) { onPage(CephPage.OSDS) }
                    MenuRow(Icons.Outlined.Inventory, stringResource(R.string.ceph_pools), stringResource(R.string.ceph_pools_body)) { onPage(CephPage.POOLS) }
                    MenuRow(Icons.Outlined.Dns, stringResource(R.string.ceph_services), stringResource(R.string.ceph_services_body)) { onPage(CephPage.SERVICES) }
                    MenuRow(Icons.Outlined.Settings, stringResource(R.string.ceph_config), stringResource(R.string.ceph_config_body)) { onPage(CephPage.CONFIG) }
                    MenuRow(Icons.Outlined.AccountTree, stringResource(R.string.ceph_crush), stringResource(R.string.ceph_crush_body)) { onPage(CephPage.CRUSH) }
                    MenuRow(Icons.Outlined.Description, stringResource(R.string.ceph_log), stringResource(R.string.ceph_log_body)) { onPage(CephPage.LOG) }
                }
            }
            if (state.flags.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ceph_flags)) }
                item { FormHint(stringResource(R.string.ceph_flags_hint)) }
                item {
                    GlassCard {
                        state.flags.forEach { flag ->
                            SwitchRow(
                                flag.name,
                                flag.value,
                                { on -> viewModel.setFlag(flag, on) },
                                description = flag.description,
                                tone = Tone.WARNING,
                            )
                        }
                    }
                }
            }
        }
    }

    state.confirmFlag?.let { flag -> FlagConfirm(flag, viewModel) }
}

@Composable
private fun FlagConfirm(flag: CephFlag, viewModel: CephViewModel) {
    ConfirmActionDialog(
        title = stringResource(R.string.ceph_flag_confirm_title, flag.name),
        message = listOfNotNull(flag.description, stringResource(R.string.ceph_flag_confirm_body)).joinToString("\n\n"),
        confirmLabel = stringResource(R.string.ceph_flag_confirm),
        danger = DangerLevel.CONFIRM,
        onConfirm = viewModel::confirmFlag,
        onDismiss = viewModel::dismissFlag,
    )
}

@Composable
private fun HealthCard(o: CephOverview) {
    val c = AnProxTheme.colors
    val (label, tone) = healthLook(o.health)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ceph_health), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
            StatusPill(label, tone)
        }
        Spacer(Modifier.height(6.dp))
        if (o.checks.isEmpty()) {
            FormHint(stringResource(R.string.ceph_health_none))
        } else {
            o.checks.forEach { check -> HealthCheckRow(check) }
        }
    }
}

@Composable
private fun HealthCheckRow(check: CephHealthCheck) {
    val c = AnProxTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = check.details.isNotEmpty()) { open = !open }
            .padding(vertical = 4.dp),
    ) {
        Text(
            check.summary + if (check.muted) " " + stringResource(R.string.ceph_health_muted) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = if (check.error) c.danger else c.warning,
        )
        Text(check.id, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        if (open) check.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
    }
}

@Composable
private fun CapacityCard(o: CephOverview) {
    val c = AnProxTheme.colors
    GlassCard {
        FormLabel(stringResource(R.string.ceph_capacity))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.ceph_capacity_used, Formatters.bytes(o.bytesUsed), Formatters.bytes(o.bytesTotal), Formatters.percent(o.usedFraction)),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        UsageBar(o.usedFraction)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ceph_io, Formatters.rate(o.readBytesPerSec.toDouble()), Formatters.rate(o.writeBytesPerSec.toDouble()), o.readOpsPerSec, o.writeOpsPerSec),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        if (o.recoveringBytesPerSec > 0) {
            Text(stringResource(R.string.ceph_recovering, Formatters.rate(o.recoveringBytesPerSec.toDouble())), style = MaterialTheme.typography.bodySmall, color = c.warning)
        }
    }
}

@Composable
private fun DaemonsCard(o: CephOverview) {
    val c = AnProxTheme.colors
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(stringResource(R.string.ceph_osds), stringResource(R.string.ceph_osds_summary, o.osdsUp, o.osdsIn, o.osds))
            InfoRow(
                stringResource(R.string.ceph_monitors),
                stringResource(R.string.ceph_mons_summary, o.quorum.size, o.monitors) + if (o.quorum.isEmpty()) "" else " (${o.quorum.joinToString(", ")})",
            )
            InfoRow(
                stringResource(R.string.ceph_managers),
                if (o.managerAvailable) stringResource(R.string.ceph_mgr_summary, o.managerStandbys) else stringResource(R.string.ceph_mgr_none),
            )
            InfoRow(stringResource(R.string.ceph_mds), stringResource(R.string.ceph_mds_summary, o.mdsUp, o.mdsStandby))
            InfoRow(
                stringResource(R.string.ceph_pgs),
                if (o.pgsNotClean == 0) stringResource(R.string.ceph_pgs_clean, o.pgs) else stringResource(R.string.ceph_pgs_not_clean, o.pgs, o.pgsNotClean),
            )
            o.pgStates.take(5).forEach { (stateName, count) ->
                Text("$count × $stateName", style = MaterialTheme.typography.bodySmall, color = if (stateName == "active+clean") c.textSecondary else c.warning)
            }
        }
    }
}

// ------------------------------------------------------------------------------ OSDs

/** Datacenter → Ceph → OSDs. */
@Composable
fun CephOsdsScreen(serverId: String, onBack: () -> Unit, onTask: (serverId: String, node: String, upid: String) -> Unit) {
    val viewModel: CephOsdsViewModel = koinViewModel(key = "ceph-osds-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, state.node.orEmpty(), onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.ceph_osds),
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
                    stringResource(R.string.ceph_osd_create),
                    onClick = viewModel::startCreate,
                    icon = Icons.Outlined.Add,
                    enabled = state.busy == null && state.error == null && state.node != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.busy?.let { item { BusyCard(it.asString()) } }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.tree.flags.isNotEmpty()) item { FormHint(stringResource(R.string.ceph_flags_set, state.tree.flags.joinToString(", "))) }
            if (state.loading && state.tree.hosts.isEmpty()) item { Spinner() }
            state.tree.hosts.forEach { host ->
                item(key = "h-" + host.name) {
                    SectionHeader(
                        listOfNotNull(host.name.ifEmpty { stringResource(R.string.ceph_osd_no_host) }, host.version?.let { "Ceph $it" }).joinToString(" · "),
                    )
                }
                item(key = "c-" + host.name) {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        host.osds.forEach { osd -> OsdRow(osd) { viewModel.select(osd) } }
                    }
                }
            }
        }
    }

    state.selected?.let { osd -> OsdDialog(osd, state.busy == null, viewModel) }
    state.destroying?.let { d ->
        TypedConfirmDialog(
            title = stringResource(R.string.ceph_osd_destroy_title, d.osd.name),
            message = stringResource(R.string.ceph_osd_destroy_body),
            confirmText = d.osd.name,
            confirmLabel = stringResource(R.string.ceph_destroy),
            onConfirm = viewModel::destroy,
            onDismiss = viewModel::dismissDestroy,
        ) {
            SwitchRow(stringResource(R.string.ceph_osd_cleanup), d.cleanup, viewModel::setCleanup, description = stringResource(R.string.ceph_osd_cleanup_hint))
        }
    }
    state.creating?.let { form -> CreateOsdDialog(form, state, viewModel) }
}

@Composable
private fun OsdRow(osd: CephOsd, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(osd.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            val details = listOfNotNull(
                osd.deviceClass,
                osd.percentUsed?.let { stringResource(R.string.ceph_used_percent, Formatters.percent(it / 100)) },
                osd.totalSpace?.let { Formatters.bytes(it) },
                osd.applyLatencyMs?.let { stringResource(R.string.ceph_latency, it) },
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            osd.percentUsed?.let {
                Spacer(Modifier.height(4.dp))
                UsageBar(it / 100, height = 4.dp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusPill(stringResource(if (osd.up) R.string.ceph_osd_up else R.string.ceph_osd_down), if (osd.up) Tone.SUCCESS else Tone.DANGER)
            StatusPill(stringResource(if (osd.inCluster) R.string.ceph_osd_in else R.string.ceph_osd_out), if (osd.inCluster) Tone.ACCENT else Tone.WARNING)
        }
    }
}

@Composable
private fun OsdDialog(osd: CephOsd, enabled: Boolean, viewModel: CephOsdsViewModel) {
    val c = AnProxTheme.colors
    AlertDialog(
        onDismissRequest = { viewModel.select(null) },
        containerColor = c.surfaceSolid,
        title = { Text(osd.name) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                osd.host?.let { InfoRow(stringResource(R.string.ceph_osd_host), it) }
                osd.device?.let { InfoRow(stringResource(R.string.ceph_osd_device), it, mono = true) }
                osd.dbDevice?.let { InfoRow(stringResource(R.string.ceph_osd_db), it, mono = true) }
                osd.version?.let { InfoRow(stringResource(R.string.ceph_version), it) }
                osd.commitLatencyMs?.let { InfoRow(stringResource(R.string.ceph_osd_latency), stringResource(R.string.ceph_latency, it)) }
                Spacer(Modifier.height(6.dp))
                ActionButton(stringResource(if (osd.inCluster) R.string.ceph_osd_mark_out else R.string.ceph_osd_mark_in), enabled) { viewModel.setIn(osd, !osd.inCluster) }
                if (osd.inCluster) FormHint(stringResource(R.string.ceph_osd_out_hint))
                ActionButton(stringResource(R.string.ceph_osd_scrub), enabled && osd.up) { viewModel.scrub(osd, deep = false) }
                ActionButton(stringResource(R.string.ceph_osd_deep_scrub), enabled && osd.up) { viewModel.scrub(osd, deep = true) }
                if (osd.up) {
                    ActionButton(stringResource(R.string.ceph_stop), enabled) { viewModel.service(osd, CephAction.STOP) }
                } else {
                    ActionButton(stringResource(R.string.ceph_start), enabled) { viewModel.service(osd, CephAction.START) }
                }
                ActionButton(stringResource(R.string.ceph_restart), enabled && osd.up) { viewModel.service(osd, CephAction.RESTART) }
                ActionButton(stringResource(R.string.ceph_destroy), enabled && osd.canDestroy, danger = true) { viewModel.requestDestroy(osd) }
                if (!osd.canDestroy) FormHint(stringResource(R.string.ceph_osd_destroy_hint))
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.select(null) }) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    AnProxButton(
        text,
        onClick = onClick,
        style = if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CreateOsdDialog(form: CephOsdForm, state: CephOsdsUiState, viewModel: CephOsdsViewModel) {
    val c = AnProxTheme.colors
    val auto = stringResource(R.string.ceph_osd_class_auto)
    val none = stringResource(R.string.ceph_osd_db_none)
    AlertDialog(
        onDismissRequest = viewModel::closeCreate,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.ceph_osd_create)) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel(stringResource(R.string.ceph_node))
                ChoiceChips(state.nodes, form.node, { it }) { n -> viewModel.editCreate { copy(node = n) } }
                FormLabel(stringResource(R.string.ceph_osd_disk))
                val disks = state.disks
                when {
                    disks == null -> CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                    disks.isEmpty() -> FormHint(state.disksError?.asString() ?: stringResource(R.string.ceph_osd_no_disks), error = state.disksError != null)
                    else -> disks.forEach { d ->
                        RadioRow(
                            selected = d.devpath == form.device,
                            title = d.devpath,
                            body = listOfNotNull(d.model, Formatters.bytes(d.size), d.type).joinToString(" · "),
                        ) { viewModel.editCreate { copy(device = d.devpath, dbDevice = if (dbDevice == d.devpath) "" else dbDevice) } }
                    }
                }
                FormLabel(stringResource(R.string.ceph_osd_class))
                ChoiceChips(CephOsdForm.DEVICE_CLASSES, form.deviceClass, { if (it.isEmpty()) auto else it.uppercase() }) { v -> viewModel.editCreate { copy(deviceClass = v) } }
                SwitchRow(stringResource(R.string.ceph_osd_encrypt), form.encrypted, { on -> viewModel.editCreate { copy(encrypted = on) } })
                val dbChoices = listOf("") + disks.orEmpty().map { it.devpath }.filter { it != form.device }
                if (dbChoices.size > 1) {
                    FormLabel(stringResource(R.string.ceph_osd_db))
                    FormHint(stringResource(R.string.ceph_osd_db_hint))
                    ChoiceChips(dbChoices, form.dbDevice, { if (it.isEmpty()) none else it }) { v -> viewModel.editCreate { copy(dbDevice = v) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::create, enabled = form.valid) { Text(stringResource(R.string.ceph_create)) } },
        dismissButton = { TextButton(onClick = viewModel::closeCreate) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------ pools

/** Datacenter → Ceph → Pools. */
@Composable
fun CephPoolsScreen(serverId: String, onBack: () -> Unit, onTask: (serverId: String, node: String, upid: String) -> Unit) {
    val viewModel: CephPoolsViewModel = koinViewModel(key = "ceph-pools-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, state.node.orEmpty(), onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.ceph_pools),
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
                    stringResource(R.string.ceph_pool_create),
                    onClick = viewModel::startCreate,
                    icon = Icons.Outlined.Add,
                    enabled = state.busy == null && state.error == null && state.node != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.busy?.let { item { BusyCard(it.asString()) } }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.pools.isEmpty()) item { Spinner() }
            items(state.pools, key = { it.name }) { pool -> PoolCard(pool) { viewModel.open(pool) } }
        }
    }

    state.draft?.let { draft -> PoolDialog(draft, state, viewModel) }
    state.deleting?.let { d ->
        TypedConfirmDialog(
            title = stringResource(R.string.ceph_pool_delete_title, d.pool.name),
            message = stringResource(R.string.ceph_pool_delete_body),
            confirmText = d.pool.name,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        ) {
            SwitchRow(stringResource(R.string.ceph_pool_remove_storages), d.removeStorages, viewModel::setRemoveStorages)
        }
    }
}

@Composable
private fun PoolCard(pool: CephPool, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pool.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                pool.applications.forEach { app ->
                    Spacer(Modifier.width(6.dp))
                    StatusPill(app, Tone.INFO)
                }
            }
            Text(
                stringResource(R.string.ceph_pool_line, pool.size, pool.minSize, pool.pgNum, pool.autoscale ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            pool.bytesUsed?.let { used ->
                Text(stringResource(R.string.ceph_pool_used, Formatters.bytes(used), Formatters.percent(pool.percentUsed)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            UsageBar(pool.percentUsed, height = 4.dp)
        }
    }
}

@Composable
private fun PoolDialog(draft: PoolDraft, state: CephPoolsUiState, viewModel: CephPoolsViewModel) {
    val c = AnProxTheme.colors
    val form = draft.form
    val existing = draft.existing
    val creating = existing == null
    val taken = creating && state.nameTaken(form.name)
    val problem = form.problem(creating)
    val autoscaleLabels = mapOf(
        "on" to stringResource(R.string.ceph_autoscale_on),
        "warn" to stringResource(R.string.ceph_autoscale_warn),
        "off" to stringResource(R.string.ceph_autoscale_off),
    )
    AlertDialog(
        onDismissRequest = viewModel::closeDraft,
        containerColor = c.surfaceSolid,
        title = { Text(existing?.name ?: stringResource(R.string.ceph_pool_create)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creating) {
                    GlassTextField(
                        value = form.name,
                        onValueChange = { v -> viewModel.edit { copy(name = v) } },
                        label = stringResource(R.string.ceph_pool_name),
                        isError = form.name.isNotEmpty() && (problem == CephPoolForm.Problem.NAME || taken),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    if (taken) FormHint(stringResource(R.string.ceph_name_taken), error = true)
                }
                NumberField(form.size, { v -> viewModel.edit { copy(size = v) } }, stringResource(R.string.ceph_pool_size), stringResource(R.string.ceph_pool_size_hint), isError = problem == CephPoolForm.Problem.SIZE)
                NumberField(form.minSize, { v -> viewModel.edit { copy(minSize = v) } }, stringResource(R.string.ceph_pool_min_size), stringResource(R.string.ceph_pool_min_size_hint), isError = problem == CephPoolForm.Problem.MIN_SIZE)
                if (form.risky) FormHint(stringResource(R.string.ceph_pool_risky), error = true)
                FormLabel(stringResource(R.string.ceph_pool_autoscale))
                ChoiceChips(CephPoolForm.AUTOSCALE, form.autoscale, { autoscaleLabels.getValue(it) }) { v -> viewModel.edit { copy(autoscale = v) } }
                NumberField(form.pgNum, { v -> viewModel.edit { copy(pgNum = v) } }, stringResource(R.string.ceph_pool_pg_num), isError = problem == CephPoolForm.Problem.PG_NUM)
                if (form.pgNotPowerOfTwo) FormHint(stringResource(R.string.ceph_pool_pg_power))
                val finalPg = existing?.pgNumFinal
                if (existing != null && finalPg != null && finalPg != existing.pgNum) FormHint(stringResource(R.string.ceph_pool_pg_final, finalPg))
                if (state.rules.size > 1) {
                    FormLabel(stringResource(R.string.ceph_pool_rule))
                    ChoiceChips(state.rules, form.crushRule, { it }) { v -> viewModel.edit { copy(crushRule = v) } }
                }
                if (creating) {
                    FormLabel(stringResource(R.string.ceph_pool_application))
                    ChoiceChips(CephPoolForm.APPLICATIONS, form.application, { it }) { v -> viewModel.edit { copy(application = v, addStorage = v == "rbd" && addStorage) } }
                    if (form.application == "rbd") {
                        SwitchRow(stringResource(R.string.ceph_pool_add_storage), form.addStorage, { on -> viewModel.edit { copy(addStorage = on) } }, description = stringResource(R.string.ceph_pool_add_storage_hint))
                    }
                }
                if (existing != null && !existing.internal) {
                    Spacer(Modifier.height(4.dp))
                    AnProxButton(stringResource(R.string.ceph_pool_delete), onClick = { viewModel.requestDelete(existing) }, style = ButtonStyle.DANGER, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = problem == null && !taken) {
                Text(stringResource(if (creating) R.string.ceph_create else R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeDraft) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------ monitors, managers, MDS, CephFS

/** Datacenter → Ceph → Monitors, managers, metadata servers and CephFS. */
@Composable
fun CephServicesScreen(serverId: String, onBack: () -> Unit, onTask: (serverId: String, node: String, upid: String) -> Unit) {
    val viewModel: CephServicesViewModel = koinViewModel(key = "ceph-services-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, state.node.orEmpty(), onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.ceph_services),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.busy?.let { item { BusyCard(it.asString()) } }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.node == null) item { Spinner() }
            if (state.node == null) return@LazyColumn
            CephServiceType.entries.forEach { type ->
                val list = state.ofType(type)
                item(key = "h-" + type.name) { SectionHeader(stringResource(serviceTitle(type), list.size)) }
                if (list.isNotEmpty()) {
                    item(key = "l-" + type.name) {
                        GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                            list.forEach { s -> ServiceRow(s) { viewModel.select(s) } }
                        }
                    }
                }
                item(key = "a-" + type.name) {
                    AnProxButton(
                        stringResource(serviceAdd(type)),
                        onClick = { viewModel.startAdd(type) },
                        style = ButtonStyle.SECONDARY,
                        icon = Icons.Outlined.Add,
                        enabled = state.busy == null && state.freeNodes(type).isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "fs-h") { SectionHeader(stringResource(R.string.ceph_fs_section, state.filesystems.size)) }
            if (state.filesystems.isNotEmpty()) {
                item(key = "fs-l") {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) { state.filesystems.forEach { fs -> FsRow(fs) } }
                }
            }
            item(key = "fs-a") {
                AnProxButton(
                    stringResource(R.string.ceph_fs_create),
                    onClick = viewModel::startFs,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.Add,
                    enabled = state.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.metadataServers.isEmpty()) item(key = "fs-hint") { FormHint(stringResource(R.string.ceph_fs_needs_mds)) }
        }
    }

    state.selected?.let { service -> ServiceDialog(service, state.busy == null, viewModel) }
    state.confirmDestroy?.let { service ->
        ConfirmActionDialog(
            title = stringResource(R.string.ceph_service_destroy_title, service.service),
            message = stringResource(if (service.type == CephServiceType.MON) R.string.ceph_mon_destroy_body else R.string.ceph_service_destroy_body),
            confirmLabel = stringResource(R.string.ceph_destroy),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::destroy,
            onDismiss = viewModel::dismissDestroy,
        )
    }
    state.adding?.let { type ->
        var node by remember(type) { mutableStateOf(state.freeNodes(type).firstOrNull().orEmpty()) }
        AlertDialog(
            onDismissRequest = viewModel::closeAdd,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(serviceAdd(type))) },
            text = {
                Column {
                    FormHint(stringResource(R.string.ceph_service_add_hint))
                    state.freeNodes(type).forEach { n -> RadioRow(n == node, n, null) { node = n } }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.add(node) }, enabled = node.isNotEmpty()) { Text(stringResource(R.string.ceph_create)) } },
            dismissButton = { TextButton(onClick = viewModel::closeAdd) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.fsDraft?.let { form ->
        AlertDialog(
            onDismissRequest = viewModel::closeFs,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.ceph_fs_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextField(
                        value = form.name,
                        onValueChange = { v -> viewModel.editFs { copy(name = v) } },
                        label = stringResource(R.string.ceph_fs_name),
                        isError = !CephPoolForm.NAME.matches(form.name.trim()),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    NumberField(form.pgNum, { v -> viewModel.editFs { copy(pgNum = v) } }, stringResource(R.string.ceph_pool_pg_num))
                    SwitchRow(stringResource(R.string.ceph_fs_add_storage), form.addStorage, { on -> viewModel.editFs { copy(addStorage = on) } })
                }
            },
            confirmButton = { TextButton(onClick = viewModel::createFs, enabled = form.valid) { Text(stringResource(R.string.ceph_create)) } },
            dismissButton = { TextButton(onClick = viewModel::closeFs) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun serviceTitle(type: CephServiceType): Int = when (type) {
    CephServiceType.MON -> R.string.ceph_mon_section
    CephServiceType.MGR -> R.string.ceph_mgr_section
    CephServiceType.MDS -> R.string.ceph_mds_section
}

private fun serviceAdd(type: CephServiceType): Int = when (type) {
    CephServiceType.MON -> R.string.ceph_mon_add
    CephServiceType.MGR -> R.string.ceph_mgr_add
    CephServiceType.MDS -> R.string.ceph_mds_add
}

@Composable
private fun serviceLook(s: CephService): Pair<String, Tone> = when (s.type) {
    CephServiceType.MON ->
        if (s.inQuorum == true) stringResource(R.string.ceph_in_quorum) to Tone.SUCCESS else stringResource(R.string.ceph_out_of_quorum) to Tone.DANGER
    CephServiceType.MGR, CephServiceType.MDS -> when (s.state?.removePrefix("up:")) {
        "active" -> stringResource(R.string.ceph_active) to Tone.SUCCESS
        "standby", "standby-replay" -> stringResource(R.string.ceph_standby) to Tone.INFO
        else -> stringResource(R.string.ceph_stopped) to Tone.DANGER
    }
}

@Composable
private fun ServiceRow(s: CephService, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    val (label, tone) = serviceLook(s)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            listOfNotNull(s.address, s.version?.let { "Ceph $it" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        StatusPill(label, tone)
    }
}

@Composable
private fun FsRow(fs: CephFs) {
    val c = AnProxTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(fs.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Text(
            stringResource(R.string.ceph_fs_pools, fs.metadataPool ?: "—", fs.dataPools.joinToString(", ").ifEmpty { "—" }),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
    }
}

@Composable
private fun ServiceDialog(s: CephService, enabled: Boolean, viewModel: CephServicesViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.select(null) },
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(s.service) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.host?.let { InfoRow(stringResource(R.string.ceph_osd_host), it) }
                s.address?.let { InfoRow(stringResource(R.string.ceph_address), it, mono = true) }
                s.version?.let { InfoRow(stringResource(R.string.ceph_version), it) }
                Spacer(Modifier.height(4.dp))
                ActionButton(stringResource(R.string.ceph_start), enabled && !s.running) { viewModel.action(s, CephAction.START) }
                ActionButton(stringResource(R.string.ceph_stop), enabled && s.running) { viewModel.action(s, CephAction.STOP) }
                ActionButton(stringResource(R.string.ceph_restart), enabled && s.running) { viewModel.action(s, CephAction.RESTART) }
                ActionButton(stringResource(R.string.ceph_destroy), enabled, danger = true) { viewModel.requestDestroy(s) }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.select(null) }) { Text(stringResource(R.string.action_close)) } },
    )
}

// ------------------------------------------------------------------------------ text pages

/** Datacenter → Ceph → Configuration, CRUSH map or Log. */
@Composable
fun CephTextScreen(serverId: String, kind: CephText, onBack: () -> Unit) {
    val viewModel: CephTextViewModel = koinViewModel(key = "ceph-text-$serverId-${kind.name}") { parametersOf(serverId, kind) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val list = rememberLazyListState()
    val shown = state.shown
    if (kind == CephText.LOG) {
        // Newest at the bottom, like the system log.
        LaunchedEffect(state.lines.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size + 1) }
    }
    AnProxScaffold(
        title = stringResource(
            when (kind) {
                CephText.CONFIG -> R.string.ceph_config
                CephText.CRUSH -> R.string.ceph_crush
                CephText.LOG -> R.string.ceph_log
            },
        ),
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(state = list, contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (kind == CephText.LOG) {
                item { GlassTextField(value = state.filter, onValueChange = viewModel::setFilter, label = stringResource(R.string.firewall_log_filter)) }
                item { Spacer(Modifier.height(8.dp)) }
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.lines.isEmpty()) item { Spinner() }
            items(shown.size) { i ->
                val line = shown[i]
                Text(
                    line.ifEmpty { " " },
                    style = MonoStyle,
                    color = when {
                        kind != CephText.LOG -> c.textPrimary
                        "[ERR]" in line -> c.danger
                        "[WRN]" in line -> c.warning
                        else -> c.textSecondary
                    },
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            if (kind == CephText.CONFIG && state.database.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                item { SectionHeader(stringResource(R.string.ceph_config_db)) }
                item {
                    GlassCard {
                        state.database.forEach { e ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(e.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                Text("[${e.section}] ${e.value}", style = MonoStyle, color = c.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
