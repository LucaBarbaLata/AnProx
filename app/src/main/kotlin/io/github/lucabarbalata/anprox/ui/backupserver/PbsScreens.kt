package io.github.lucabarbalata.anprox.ui.backupserver

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.SystemUpdateAlt
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import io.github.lucabarbalata.anprox.data.backupserver.DatastoreSummary
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pbs.BackupGroup
import io.github.lucabarbalata.anprox.model.pbs.BackupSnapshot
import io.github.lucabarbalata.anprox.model.pbs.CatalogEntry
import io.github.lucabarbalata.anprox.model.pbs.DatastoreMaintenance
import io.github.lucabarbalata.anprox.model.pbs.PbsJob
import io.github.lucabarbalata.anprox.model.pbs.PbsJobKind
import io.github.lucabarbalata.anprox.model.pbs.PbsNamespace
import io.github.lucabarbalata.anprox.model.pbs.PbsRemoteForm
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.PasswordField
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.formatDuration
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import io.github.lucabarbalata.anprox.ui.datacenter.AccessPage
import io.github.lucabarbalata.anprox.ui.node.NodeAdminPage
import java.text.DateFormat
import java.util.Date
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AnProxTheme.colors.accent)
    }
}

private fun dateTime(epochSeconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))

@Composable
private fun typeIcon(type: String): ImageVector = when (type) {
    "vm" -> Icons.Outlined.Computer
    "ct" -> Icons.Outlined.Inventory
    else -> Icons.Outlined.Dns
}

@Composable
private fun typeLabel(type: String): String = when (type) {
    "vm" -> stringResource(R.string.pbs_type_vm)
    "ct" -> stringResource(R.string.pbs_type_ct)
    "host" -> stringResource(R.string.pbs_type_host)
    else -> type
}

@Composable
private fun namespaceLabel(ns: String): String = ns.ifEmpty { stringResource(R.string.pbs_namespace_root) }

/** A tappable card with an icon, a title, lines below and an optional pill. */
@Composable
private fun TapCard(icon: ImageVector, title: String, lines: List<String>, pill: Pair<String, Tone>? = null, onClick: () -> Unit, extra: @Composable () -> Unit = {}) {
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                pill?.let { (text, tone) -> StatusPill(text, tone) }
            }
            lines.filter { it.isNotEmpty() }.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
            extra()
        }
    }
}

// ------------------------------------------------------------------------------ the server

/** Dashboard → a Backup Server: its node, datastores, jobs, remotes and administration. */
@Composable
fun BackupServerScreen(
    serverId: String,
    title: String,
    onBack: () -> Unit,
    onDatastore: (store: String) -> Unit,
    onJobs: () -> Unit,
    onRemotes: () -> Unit,
    onAdmin: (NodeAdminPage) -> Unit,
    onAccess: (AccessPage) -> Unit,
) {
    val viewModel: BackupServerViewModel = koinViewModel(key = "pbs-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    AnProxScaffold(
        title = title,
        subtitle = stringResource(R.string.pbs_title),
        onBack = onBack,
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
            state.status?.let { st ->
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FormLabel(stringResource(R.string.pbs_node))
                            InfoRow(stringResource(R.string.pbs_cpu), listOfNotNull(Formatters.percent(st.cpu), st.cpus?.let { stringResource(R.string.pbs_cpus, it) }).joinToString(" · "))
                            UsageBar(st.cpu)
                            InfoRow(stringResource(R.string.pbs_memory), Formatters.usage(st.memoryUsed, st.memoryTotal))
                            val total = st.memoryTotal
                            val used = st.memoryUsed
                            UsageBar(if (total != null && used != null && total > 0) used.toDouble() / total else null)
                            InfoRow(stringResource(R.string.pbs_root_disk), Formatters.usage(st.rootUsed, st.rootTotal))
                            st.uptime?.let { InfoRow(stringResource(R.string.pbs_uptime), formatDuration(it)) }
                            st.kernel?.let { InfoRow(stringResource(R.string.pbs_kernel), it) }
                            st.cpuModel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AnProxTheme.colors.textSecondary) }
                        }
                    }
                }
            }
            state.statusError?.let { item { FormHint(it.asString(), error = true) } }
            item { SectionHeader(stringResource(R.string.pbs_datastores, state.datastores.size)) }
            if (state.loading && state.datastores.isEmpty()) item { Spinner() }
            items(state.datastores, key = { it.info.store }) { ds -> DatastoreCard(ds) { onDatastore(ds.info.store) } }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Schedule, stringResource(R.string.pbs_jobs), stringResource(R.string.pbs_jobs_body), onJobs)
                    MenuRow(Icons.Outlined.Hub, stringResource(R.string.pbs_remotes), stringResource(R.string.pbs_remotes_body), onRemotes)
                    MenuRow(Icons.Outlined.SystemUpdateAlt, stringResource(R.string.updates_title), stringResource(R.string.pbs_updates_body)) { onAdmin(NodeAdminPage.UPDATES) }
                    MenuRow(Icons.Outlined.Settings, stringResource(R.string.services_title), stringResource(R.string.pbs_services_body)) { onAdmin(NodeAdminPage.SERVICES) }
                    MenuRow(Icons.AutoMirrored.Outlined.List, stringResource(R.string.journal_title), stringResource(R.string.pbs_journal_body)) { onAdmin(NodeAdminPage.JOURNAL) }
                    MenuRow(Icons.Outlined.History, stringResource(R.string.taskhistory_title), stringResource(R.string.taskhistory_open_body)) { onAdmin(NodeAdminPage.TASKS) }
                }
            }
            item { SectionHeader(stringResource(R.string.access_section)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Person, stringResource(R.string.access_users), null) { onAccess(AccessPage.USERS) }
                    MenuRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.access_acl), null) { onAccess(AccessPage.ACL) }
                    MenuRow(Icons.Outlined.Badge, stringResource(R.string.access_roles), null) { onAccess(AccessPage.ROLES) }
                }
            }
        }
    }
}

@Composable
private fun DatastoreCard(ds: DatastoreSummary, onClick: () -> Unit) {
    val maintenance = DatastoreMaintenance.parse(ds.info.maintenance)
    val u = ds.usage
    TapCard(
        icon = Icons.Outlined.Storage,
        title = ds.info.store,
        lines = listOfNotNull(ds.info.comment, u?.let { Formatters.usage(it.used, it.total) }, u?.error),
        pill = maintenance.type?.let { stringResource(R.string.pbs_maintenance_pill, it) to Tone.WARNING },
        onClick = onClick,
    ) { u?.fraction?.let { UsageBar(it) } }
}

// ------------------------------------------------------------------------------ one datastore

/** A datastore: usage, garbage collection, maintenance, namespaces and the backup groups. */
@Composable
fun DatastoreScreen(
    serverId: String,
    store: String,
    onBack: () -> Unit,
    onGroup: (ns: String, type: String, id: String) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: DatastoreViewModel = koinViewModel(key = "pbs-ds-$serverId-$store") { parametersOf(serverId, store) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "localhost", onTask, viewModel::consumeResult)
    val c = AnProxTheme.colors
    val idle = state.busy == null && state.error == null
    AnProxScaffold(
        title = store,
        subtitle = stringResource(R.string.pbs_datastore),
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
            state.busy?.let { item { BusyCard(it.asString()) } }
            state.status?.let { st ->
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Formatters.usage(st.used, st.total), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                                state.maintenance.type?.let { StatusPill(stringResource(R.string.pbs_maintenance_pill, it), Tone.WARNING) }
                            }
                            UsageBar(st.fraction)
                            st.counts.forEach { (type, counts) ->
                                Text(stringResource(R.string.pbs_counts, typeLabel(type), counts.first, counts.second), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            }
                            state.maintenance.message?.let { FormHint(it) }
                        }
                    }
                }
            }
            state.gc?.let { gc ->
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FormLabel(stringResource(R.string.pbs_gc))
                                Spacer(Modifier.weight(1f))
                                gc.lastRunState?.let { s -> StatusPill(if (s == "OK") stringResource(R.string.pbs_ok) else s, if (s == "OK") Tone.SUCCESS else Tone.DANGER) }
                            }
                            gc.lastRunEnd?.let { Text(stringResource(R.string.pbs_gc_last, relativeTime(it * 1000)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                            gc.removedBytes?.let { Text(stringResource(R.string.pbs_gc_removed, Formatters.bytes(it), Formatters.bytes(gc.pendingBytes)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                            gc.dedupFactor?.let { Text(stringResource(R.string.pbs_dedup, String.format(java.util.Locale.getDefault(), "%.1f", it)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                            gc.nextRun?.let { Text(stringResource(R.string.pbs_next_run, dateTime(it)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnProxButton(stringResource(R.string.pbs_gc_start), onClick = viewModel::startGc, style = ButtonStyle.SECONDARY, enabled = idle, modifier = Modifier.weight(1f))
                    AnProxButton(stringResource(R.string.pbs_verify), onClick = viewModel::requestVerify, style = ButtonStyle.SECONDARY, enabled = idle, modifier = Modifier.weight(1f))
                }
            }
            item { AnProxButton(stringResource(R.string.pbs_maintenance), onClick = viewModel::startMaintenance, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Security, enabled = idle, modifier = Modifier.fillMaxWidth()) }

            item { SectionHeader(stringResource(R.string.pbs_namespaces)) }
            if (state.namespaces.size > 1) {
                item { ChoiceChips(state.namespaces.map { it.ns }, state.ns, { it.ifEmpty { "/" } }, viewModel::selectNamespace) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = viewModel::startNamespace, enabled = idle && PbsNamespace(state.ns).depth < PbsNamespace.MAX_DEPTH) {
                        Text(stringResource(R.string.pbs_namespace_add))
                    }
                    if (state.ns.isNotEmpty()) {
                        TextButton(onClick = viewModel::requestDeleteNamespace, enabled = idle) { Text(stringResource(R.string.pbs_namespace_delete), color = c.danger) }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.pbs_groups, namespaceLabel(state.ns), state.groups.size)) }
            if (state.groupsLoading && state.groups.isEmpty()) item { Spinner() }
            if (!state.groupsLoading && state.groups.isEmpty()) item { FormHint(stringResource(R.string.pbs_groups_none)) }
            items(state.groups, key = { it.key }) { g -> GroupCard(g) { onGroup(state.ns, g.type, g.id) } }
        }
    }

    if (state.confirmVerify) {
        var onlyNew by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = viewModel::dismissVerify,
            containerColor = c.surfaceSolid,
            title = { Text(stringResource(R.string.pbs_verify_title, namespaceLabel(state.ns))) },
            text = {
                Column {
                    FormHint(stringResource(R.string.pbs_verify_body))
                    SwitchRow(stringResource(R.string.pbs_verify_only_new), onlyNew, { onlyNew = it })
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.verify(onlyNew) }) { Text(stringResource(R.string.pbs_verify)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissVerify) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.maintenanceDraft?.let { draft ->
        val none = stringResource(R.string.pbs_maintenance_none)
        val labels = mapOf("read-only" to stringResource(R.string.pbs_maintenance_read_only), "offline" to stringResource(R.string.pbs_maintenance_offline))
        AlertDialog(
            onDismissRequest = viewModel::closeMaintenance,
            containerColor = c.surfaceSolid,
            title = { Text(stringResource(R.string.pbs_maintenance)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioRow(draft.type == null, none, stringResource(R.string.pbs_maintenance_none_body)) { viewModel.editMaintenance { copy(type = null) } }
                    DatastoreMaintenance.TYPES.forEach { t ->
                        RadioRow(draft.type == t, labels.getValue(t), stringResource(if (t == "offline") R.string.pbs_maintenance_offline_body else R.string.pbs_maintenance_read_only_body)) {
                            viewModel.editMaintenance { copy(type = t) }
                        }
                    }
                    if (draft.type != null) {
                        GlassTextField(value = draft.message, onValueChange = { v -> viewModel.editMaintenance { copy(message = v) } }, label = stringResource(R.string.pbs_maintenance_message))
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::saveMaintenance) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = viewModel::closeMaintenance) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.newNamespace?.let { name ->
        val valid = PbsNamespace.NAME.matches(name.trim())
        AlertDialog(
            onDismissRequest = viewModel::closeNamespace,
            containerColor = c.surfaceSolid,
            title = { Text(stringResource(R.string.pbs_namespace_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormHint(stringResource(R.string.pbs_namespace_in, namespaceLabel(state.ns)))
                    GlassTextField(
                        value = name,
                        onValueChange = viewModel::editNamespace,
                        label = stringResource(R.string.pbs_namespace_name),
                        supportingText = stringResource(R.string.pbs_namespace_name_hint),
                        isError = name.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::createNamespace, enabled = valid) { Text(stringResource(R.string.pbs_create)) } },
            dismissButton = { TextButton(onClick = viewModel::closeNamespace) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (state.confirmDeleteNamespace) {
        TypedConfirm(
            title = stringResource(R.string.pbs_namespace_delete_title, state.ns),
            message = stringResource(R.string.pbs_namespace_delete_body),
            confirmText = PbsNamespace(state.ns).name,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::deleteNamespace,
            onDismiss = viewModel::dismissDeleteNamespace,
        ) {
            SwitchRow(stringResource(R.string.pbs_namespace_delete_groups), state.deleteGroupsToo, viewModel::setDeleteGroupsToo, tone = Tone.DANGER)
        }
    }
}

@Composable
private fun GroupCard(g: BackupGroup, onClick: () -> Unit) {
    TapCard(
        icon = typeIcon(g.type),
        title = g.key,
        lines = listOfNotNull(
            g.comment,
            listOfNotNull(
                pluralStringResource(R.plurals.pbs_snapshot_count, g.count, g.count),
                g.lastBackup?.let { stringResource(R.string.pbs_last_backup, relativeTime(it * 1000)) },
            ).joinToString(" · "),
        ),
        onClick = onClick,
    )
}

/** Asks to type [confirmText] before [onConfirm]; [extra] goes above the field. */
@Composable
private fun TypedConfirm(title: String, message: String, confirmText: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}) {
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
                OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, label = { Text(stringResource(R.string.confirm_type_to_confirm, confirmText)) })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirmLabel, color = if (enabled) c.danger else c.textTertiary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------ one group

/** The snapshots of one guest or host in a datastore. */
@Composable
fun BackupGroupScreen(
    serverId: String,
    store: String,
    ns: String,
    type: String,
    id: String,
    onBack: () -> Unit,
    onFiles: (time: Long) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: BackupGroupViewModel = koinViewModel(key = "pbs-group-$serverId-$store-$ns-$type-$id") { parametersOf(serverId, store, ns, BackupGroup(type, id)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "localhost", onTask, viewModel::consumeResult)
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val idle = state.busy == null
    val key = "$type/$id"
    AnProxScaffold(
        title = key,
        subtitle = listOf(store, namespaceLabel(ns)).joinToString(" · "),
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
            state.busy?.let { item { BusyCard(it.asString()) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnProxButton(stringResource(R.string.pbs_verify), onClick = { viewModel.verify(null) }, style = ButtonStyle.SECONDARY, enabled = idle && state.snapshots.isNotEmpty(), modifier = Modifier.weight(1f))
                    AnProxButton(stringResource(R.string.pbs_prune), onClick = viewModel::startPrune, style = ButtonStyle.SECONDARY, enabled = idle && state.snapshots.isNotEmpty(), modifier = Modifier.weight(1f))
                }
            }
            if (state.loading && state.snapshots.isEmpty()) item { Spinner() }
            if (!state.loading && state.snapshots.isEmpty() && state.error == null) {
                item { EmptyState(typeIcon(type), stringResource(R.string.pbs_snapshots_none), stringResource(R.string.pbs_snapshots_none_body)) }
            }
            items(state.snapshots, key = { it.time }) { s -> SnapshotCard(s) { viewModel.select(s) } }
            if (state.snapshots.isNotEmpty()) {
                item {
                    AnProxButton(stringResource(R.string.pbs_group_delete), onClick = viewModel::requestDeleteGroup, style = ButtonStyle.DANGER, icon = Icons.Outlined.Delete, enabled = idle, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    state.selected?.let { s -> SnapshotDialog(s, idle, viewModel, onFiles) }
    state.notes?.let { notes ->
        AlertDialog(
            onDismissRequest = { viewModel.select(state.selected) },
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.pbs_notes)) },
            text = { GlassTextField(value = notes, onValueChange = viewModel::changeNotes, label = stringResource(R.string.pbs_notes), singleLine = false, minLines = 3) },
            confirmButton = { TextButton(onClick = viewModel::saveNotes) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { viewModel.select(state.selected) }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.confirmForget?.let { s ->
        ConfirmActionDialog(
            title = stringResource(R.string.pbs_forget_title, dateTime(s.time)),
            message = stringResource(R.string.pbs_forget_body),
            confirmLabel = stringResource(R.string.pbs_forget),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::forget,
            onDismiss = viewModel::dismissForget,
        )
    }
    state.prune?.let { draft -> PruneDialog(draft, viewModel) }
    if (state.confirmDeleteGroup) {
        TypedConfirm(
            title = stringResource(R.string.pbs_group_delete_title, key),
            message = stringResource(R.string.pbs_group_delete_body, state.snapshots.size),
            confirmText = id,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::deleteGroup,
            onDismiss = viewModel::dismissDeleteGroup,
        )
    }
}

@Composable
private fun verifyLook(state: String?): Pair<String, Tone> = when (state) {
    "ok" -> stringResource(R.string.pbs_verified) to Tone.SUCCESS
    "failed" -> stringResource(R.string.pbs_verify_bad) to Tone.DANGER
    else -> stringResource(R.string.pbs_not_verified) to Tone.NEUTRAL
}

@Composable
private fun SnapshotCard(s: BackupSnapshot, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    val (verify, tone) = verifyLook(s.verifyState)
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dateTime(s.time), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            s.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                s.size?.let { Text(Formatters.bytes(it), style = MaterialTheme.typography.labelMedium, color = c.textSecondary) }
                StatusPill(verify, tone)
                if (s.protected) StatusPill(stringResource(R.string.pbs_protected), Tone.ACCENT)
                if (s.encrypted) StatusPill(stringResource(R.string.pbs_encrypted), Tone.INFO)
            }
        }
    }
}

@Composable
private fun SnapshotDialog(s: BackupSnapshot, enabled: Boolean, viewModel: BackupGroupViewModel, onFiles: (Long) -> Unit) {
    val c = AnProxTheme.colors
    AlertDialog(
        onDismissRequest = { viewModel.select(null) },
        containerColor = c.surfaceSolid,
        title = { Text(dateTime(s.time)) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.size?.let { InfoRow(stringResource(R.string.pbs_size), Formatters.bytes(it)) }
                s.owner?.let { InfoRow(stringResource(R.string.pbs_owner), it) }
                InfoRow(stringResource(R.string.pbs_verification), verifyLook(s.verifyState).first)
                FormLabel(stringResource(R.string.pbs_notes))
                Text(s.comment ?: stringResource(R.string.pbs_notes_none), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                TextButton(onClick = viewModel::editNotes, enabled = enabled) { Text(stringResource(R.string.pbs_notes_edit)) }
                SwitchRow(stringResource(R.string.pbs_protected), s.protected, { on -> viewModel.setProtected(s, on) }, description = stringResource(R.string.pbs_protected_hint))
                Spacer(Modifier.height(4.dp))
                if (s.hasCatalog) {
                    AnProxButton(stringResource(R.string.pbs_browse_files), onClick = { viewModel.select(null); onFiles(s.time) }, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Folder, modifier = Modifier.fillMaxWidth())
                } else {
                    FormHint(stringResource(if (s.encrypted) R.string.pbs_files_encrypted else R.string.pbs_files_vm))
                }
                AnProxButton(stringResource(R.string.pbs_verify), onClick = { viewModel.verify(s) }, style = ButtonStyle.SECONDARY, enabled = enabled, modifier = Modifier.fillMaxWidth())
                AnProxButton(stringResource(R.string.pbs_forget), onClick = { viewModel.requestForget(s) }, style = ButtonStyle.DANGER, enabled = enabled && !s.protected, modifier = Modifier.fillMaxWidth())
                if (s.protected) FormHint(stringResource(R.string.pbs_forget_protected))
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.select(null) }) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun PruneDialog(draft: PruneDraft, viewModel: BackupGroupViewModel) {
    val c = AnProxTheme.colors
    val keep = draft.keep
    AlertDialog(
        onDismissRequest = viewModel::closePrune,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.pbs_prune)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormHint(stringResource(R.string.pbs_prune_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField(keep.last, { v -> viewModel.editPrune { copy(last = v) } }, stringResource(R.string.pbs_keep_last)) }
                    Box(Modifier.weight(1f)) { NumberField(keep.hourly, { v -> viewModel.editPrune { copy(hourly = v) } }, stringResource(R.string.pbs_keep_hourly)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField(keep.daily, { v -> viewModel.editPrune { copy(daily = v) } }, stringResource(R.string.pbs_keep_daily)) }
                    Box(Modifier.weight(1f)) { NumberField(keep.weekly, { v -> viewModel.editPrune { copy(weekly = v) } }, stringResource(R.string.pbs_keep_weekly)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumberField(keep.monthly, { v -> viewModel.editPrune { copy(monthly = v) } }, stringResource(R.string.pbs_keep_monthly)) }
                    Box(Modifier.weight(1f)) { NumberField(keep.yearly, { v -> viewModel.editPrune { copy(yearly = v) } }, stringResource(R.string.pbs_keep_yearly)) }
                }
                if (!keep.valid) FormHint(stringResource(R.string.pbs_keep_invalid), error = true)
                TextButton(onClick = viewModel::preview, enabled = keep.valid && !draft.previewing) { Text(stringResource(R.string.pbs_prune_preview)) }
                if (draft.previewing) CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                draft.error?.let { FormHint(it.asString(), error = true) }
                draft.preview?.let { items ->
                    Text(stringResource(R.string.pbs_prune_result, items.count { it.keep }, draft.removes), style = MaterialTheme.typography.titleSmall, color = if (draft.removes > 0) c.warning else c.textPrimary)
                    items.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dateTime(item.time), style = MaterialTheme.typography.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                            StatusPill(
                                stringResource(if (item.keep) (if (item.protected) R.string.pbs_protected else R.string.pbs_keep) else R.string.pbs_remove),
                                if (item.keep) Tone.SUCCESS else Tone.DANGER,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val ready = keep.valid && draft.preview != null && draft.removes > 0
            TextButton(onClick = viewModel::prune, enabled = ready) { Text(stringResource(R.string.pbs_prune_confirm, draft.removes), color = if (ready) c.danger else c.textTertiary) }
        },
        dismissButton = { TextButton(onClick = viewModel::closePrune) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------ file restore

/** A snapshot's files from its catalog: open folders, save a file, or a folder as a zip. */
@Composable
fun FileRestoreScreen(serverId: String, store: String, ns: String, type: String, id: String, time: Long, onBack: () -> Unit) {
    val viewModel: FileRestoreViewModel = koinViewModel(key = "pbs-files-$serverId-$store-$ns-$type-$id-$time") {
        parametersOf(serverId, store, ns, BackupSnapshot(type, id, time))
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "localhost", { _, _, _ -> }, viewModel::consumeResult)
    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) viewModel.cancelSave() else viewModel.save { context.contentResolver.openOutputStream(uri) }
    }
    LaunchedEffect(state.saving) {
        state.saving?.let { entry -> launcher.launch(if (entry.type == "d") entry.name + ".zip" else entry.name) }
    }
    val c = AnProxTheme.colors
    AnProxScaffold(
        title = stringResource(R.string.pbs_files),
        subtitle = "$type/$id · ${dateTime(time)}",
        onBack = { if (!viewModel.up()) onBack() },
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.jump(0) }) { Text(stringResource(R.string.pbs_files_top)) }
                    state.path.forEachIndexed { i, e ->
                        Text("/", color = c.textTertiary)
                        TextButton(onClick = { viewModel.jump(i + 1) }) { Text(e.name) }
                    }
                }
            }
            state.downloading?.let { item { BusyCard(stringResource(R.string.pbs_file_saving, it.name)) } }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.entries.isEmpty()) item { Spinner() }
            if (!state.loading && state.error == null && state.entries.isEmpty()) item { FormHint(stringResource(R.string.pbs_files_empty)) }
            items(state.entries, key = { it.filepath }) { e -> EntryRow(e, state.downloading == null, viewModel) }
        }
    }
}

@Composable
private fun EntryRow(e: CatalogEntry, enabled: Boolean, viewModel: FileRestoreViewModel) {
    val c = AnProxTheme.colors
    val icon = when {
        e.browsable -> Icons.Outlined.Folder
        e.type == "l" -> Icons.Outlined.Link
        else -> Icons.Outlined.Description
    }
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(enabled = e.browsable || e.downloadable) { if (e.browsable) viewModel.open(e) else viewModel.requestSave(e) }
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (e.browsable) c.accent else c.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(e.size?.takeIf { !e.browsable }?.let { Formatters.bytes(it) }, e.mtime?.let { dateTime(it) }).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            if (e.downloadable) {
                IconButton(onClick = { viewModel.requestSave(e) }, enabled = enabled) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(if (e.browsable) R.string.pbs_save_zip else R.string.pbs_save_file), tint = c.textSecondary)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ jobs

/** A Backup Server's sync, verify and prune jobs. */
@Composable
fun PbsJobsScreen(serverId: String, onBack: () -> Unit, onTask: (serverId: String, node: String, upid: String) -> Unit) {
    val viewModel: PbsJobsViewModel = koinViewModel(key = "pbs-jobs-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "localhost", onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.pbs_jobs),
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
            state.busy?.let { item { BusyCard(it.asString()) } }
            if (state.loading && state.jobs.isEmpty()) item { Spinner() }
            if (!state.loading && state.error == null && state.jobs.isEmpty()) {
                item { EmptyState(Icons.Outlined.Schedule, stringResource(R.string.pbs_jobs_none), stringResource(R.string.pbs_jobs_none_body)) }
            }
            PbsJobKind.entries.forEach { kind ->
                val jobs = state.jobs.filter { it.kind == kind }
                if (jobs.isNotEmpty()) {
                    item(key = "h-" + kind.name) { SectionHeader(stringResource(jobKindTitle(kind), jobs.size)) }
                    items(jobs, key = { kind.name + it.id }) { job -> JobCard(job) { viewModel.select(job) } }
                }
            }
        }
    }

    state.selected?.let { job ->
        AlertDialog(
            onDismissRequest = { viewModel.select(null) },
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(job.id) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow(stringResource(R.string.pbs_datastore), listOfNotNull(job.store, job.ns?.takeIf { it.isNotEmpty() }).joinToString(" · "))
                    job.remote?.let { InfoRow(stringResource(R.string.pbs_job_from), listOfNotNull(it, job.remoteStore).joinToString(" · ")) }
                    job.schedule?.let { InfoRow(stringResource(R.string.pbs_job_schedule), it) }
                    job.lastRunState?.let { InfoRow(stringResource(R.string.pbs_job_last), it) }
                    job.comment?.let { FormHint(it) }
                    if (job.kind == PbsJobKind.PRUNE) {
                        SwitchRow(stringResource(R.string.pbs_job_enabled_switch), !job.disabled, { on -> viewModel.setEnabled(job, on) })
                    }
                    AnProxButton(stringResource(R.string.pbs_job_run), onClick = { viewModel.run(job) }, style = ButtonStyle.SECONDARY, enabled = state.busy == null, modifier = Modifier.fillMaxWidth())
                    job.lastRunUpid?.let { upid ->
                        TextButton(onClick = { viewModel.select(null); onTask(serverId, "localhost", upid) }) { Text(stringResource(R.string.pbs_job_last_log)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.select(null) }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

private fun jobKindTitle(kind: PbsJobKind): Int = when (kind) {
    PbsJobKind.SYNC -> R.string.pbs_jobs_sync
    PbsJobKind.VERIFY -> R.string.pbs_jobs_verify
    PbsJobKind.PRUNE -> R.string.pbs_jobs_prune
}

@Composable
private fun JobCard(job: PbsJob, onClick: () -> Unit) {
    val pill = when {
        job.disabled -> stringResource(R.string.pbs_job_off) to Tone.NEUTRAL
        job.failed -> stringResource(R.string.pbs_job_failed_pill) to Tone.DANGER
        job.lastRunState != null -> stringResource(R.string.pbs_ok) to Tone.SUCCESS
        else -> null
    }
    TapCard(
        icon = if (job.kind == PbsJobKind.SYNC) Icons.Outlined.SyncAlt else Icons.Outlined.Schedule,
        title = job.id,
        lines = listOfNotNull(
            listOfNotNull(job.store, job.remote?.let { r -> stringResource(R.string.pbs_job_from_value, r, job.remoteStore.orEmpty()) }).joinToString(" · "),
            job.schedule?.let { stringResource(R.string.pbs_job_schedule_value, it) },
            job.lastRunEnd?.let { stringResource(R.string.pbs_job_last_value, relativeTime(it * 1000)) },
            job.lastRunState?.takeIf { job.failed },
            job.nextRun?.takeIf { !job.disabled }?.let { stringResource(R.string.pbs_next_run, dateTime(it)) },
        ),
        pill = pill,
        onClick = onClick,
    )
}

// ------------------------------------------------------------------------------ remotes

/** The Backup Servers this one syncs from. */
@Composable
fun PbsRemotesScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: PbsRemotesViewModel = koinViewModel(key = "pbs-remotes-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "localhost", { _, _, _ -> }, viewModel::consumeResult)
    val c = AnProxTheme.colors
    AnProxScaffold(
        title = stringResource(R.string.pbs_remotes),
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
                AnProxButton(stringResource(R.string.pbs_remote_add), onClick = viewModel::startAdd, enabled = !state.busy && state.error == null, modifier = Modifier.fillMaxWidth())
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.remotes.isEmpty()) item { Spinner() }
            if (!state.loading && state.error == null && state.remotes.isEmpty()) item { FormHint(stringResource(R.string.pbs_remotes_none)) }
            items(state.remotes, key = { it.name }) { r ->
                GlassCard(contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            Text(listOfNotNull(r.host + (r.port?.let { ":$it" } ?: ""), r.authId).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            r.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                        }
                        IconButton(onClick = { viewModel.requestDelete(r) }, enabled = !state.busy) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = c.textSecondary)
                        }
                    }
                }
            }
        }
    }

    state.adding?.let { form -> RemoteDialog(form, viewModel) }
    state.confirmDelete?.let { r ->
        ConfirmActionDialog(
            title = stringResource(R.string.pbs_remote_delete_title, r.name),
            message = stringResource(R.string.pbs_remote_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun RemoteDialog(form: PbsRemoteForm, viewModel: PbsRemotesViewModel) {
    val problem = form.problem()
    AlertDialog(
        onDismissRequest = viewModel::closeAdd,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.pbs_remote_add)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = form.name,
                    onValueChange = { v -> viewModel.edit { copy(name = v) } },
                    label = stringResource(R.string.pbs_remote_name),
                    isError = form.name.isNotEmpty() && problem == PbsRemoteForm.Problem.NAME,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                GlassTextField(
                    value = form.host,
                    onValueChange = { v -> viewModel.edit { copy(host = v.trim()) } },
                    label = stringResource(R.string.pbs_remote_host),
                    isError = form.host.isNotEmpty() && problem == PbsRemoteForm.Problem.HOST,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                NumberField(form.port, { v -> viewModel.edit { copy(port = v) } }, stringResource(R.string.pbs_remote_port), stringResource(R.string.pbs_remote_port_hint), isError = problem == PbsRemoteForm.Problem.PORT)
                GlassTextField(
                    value = form.authId,
                    onValueChange = { v -> viewModel.edit { copy(authId = v.trim()) } },
                    label = stringResource(R.string.pbs_remote_auth_id),
                    supportingText = stringResource(R.string.pbs_remote_auth_id_hint),
                    isError = form.authId.isNotEmpty() && problem == PbsRemoteForm.Problem.AUTH_ID,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                PasswordField(form.password, { v -> viewModel.edit { copy(password = v) } }, stringResource(R.string.pbs_remote_password))
                GlassTextField(
                    value = form.fingerprint,
                    onValueChange = { v -> viewModel.edit { copy(fingerprint = v.trim()) } },
                    label = stringResource(R.string.pbs_remote_fingerprint),
                    supportingText = stringResource(R.string.pbs_remote_fingerprint_hint),
                    isError = problem == PbsRemoteForm.Problem.FINGERPRINT,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.edit { copy(comment = v) } }, label = stringResource(R.string.pbs_remote_comment))
            }
        },
        confirmButton = { TextButton(onClick = viewModel::add, enabled = problem == null) { Text(stringResource(R.string.pbs_create)) } },
        dismissButton = { TextButton(onClick = viewModel::closeAdd) { Text(stringResource(R.string.action_cancel)) } },
    )
}
