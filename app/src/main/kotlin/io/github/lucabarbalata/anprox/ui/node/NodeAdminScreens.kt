package io.github.lucabarbalata.anprox.ui.node

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.DiskStorageForm
import io.github.lucabarbalata.anprox.model.pve.DiskStorageKind
import io.github.lucabarbalata.anprox.model.pve.NetworkInterfaceForm
import io.github.lucabarbalata.anprox.model.pve.NetworkKind
import io.github.lucabarbalata.anprox.model.pve.NodeDnsForm
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import io.github.lucabarbalata.anprox.model.pve.ServiceAction
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ------------------------------------------------------------------------------ updates

/**
 * Node → Updates (questionnaire Q22): packages with a newer version, refreshing the package
 * index, changelogs, and the upgrade itself in a terminal, as the web interface does.
 */
@Composable
fun UpdatesScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onRepositories: () -> Unit,
    /** Null where the app has no shell to run the upgrade in (a Backup Server). */
    onUpgrade: (() -> Unit)?,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: UpdatesViewModel = koinViewModel(key = "updates-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.updates_title),
        subtitle = node,
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
            state.subscription?.let { sub ->
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.updates_subscription), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                            StatusPill(
                                if (sub.active) sub.level ?: stringResource(R.string.updates_subscription_active) else stringResource(R.string.updates_subscription_none),
                                if (sub.active) Tone.SUCCESS else Tone.NEUTRAL,
                            )
                        }
                        if (!sub.active) {
                            Spacer(Modifier.height(4.dp))
                            FormHint(stringResource(R.string.updates_subscription_none_body))
                            TextButton(onClick = viewModel::startKey) { Text(stringResource(R.string.updates_enter_key)) }
                        } else {
                            sub.nextDueDate?.let { FormHint(stringResource(R.string.updates_next_due, it)) }
                            TextButton(onClick = viewModel::removeKey) { Text(stringResource(R.string.updates_remove_key), color = c.danger) }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnProxButton(
                        stringResource(R.string.updates_refresh),
                        onClick = viewModel::refresh,
                        style = ButtonStyle.SECONDARY,
                        icon = Icons.Outlined.Refresh,
                        enabled = !state.refreshing,
                        loading = state.refreshing,
                        modifier = Modifier.weight(1f),
                    )
                    if (onUpgrade != null) {
                        AnProxButton(
                            stringResource(R.string.updates_upgrade),
                            onClick = onUpgrade,
                            icon = Icons.Outlined.Terminal,
                            enabled = state.updates.isNotEmpty() && !state.refreshing,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRepositories) { Text(stringResource(R.string.repos_title)) }
                    TextButton(onClick = viewModel::showVersions) { Text(stringResource(R.string.updates_versions)) }
                }
            }
            if (state.loading && state.updates.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            } else if (state.error == null && state.updates.isEmpty()) {
                item { EmptyState(Icons.Outlined.CheckCircle, stringResource(R.string.updates_none_title), stringResource(R.string.updates_none_body)) }
            } else if (state.updates.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.updates_available, state.updates.size)) }
                items(state.updates, key = { it.name }) { update ->
                    GlassCard(contentPadding = PaddingValues(0.dp)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClickLabel = stringResource(R.string.updates_changelog)) { viewModel.openChangelog(update) }
                                .padding(16.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(update.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                                update.origin?.let { TagChip(it) }
                            }
                            Text(
                                stringResource(R.string.updates_version_change, update.oldVersion.orEmpty(), update.version.orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = c.textSecondary,
                            )
                            update.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }

    state.changelog?.let { log ->
        TextDialog(
            title = log.update.name,
            text = log.text,
            error = log.error?.asString(),
            onClose = viewModel::closeChangelog,
        )
    }
    state.enteringKey?.let { key ->
        AlertDialog(
            onDismissRequest = viewModel::closeKey,
            containerColor = c.surfaceSolid,
            title = { Text(stringResource(R.string.updates_enter_key)) },
            text = {
                GlassTextField(
                    value = key,
                    onValueChange = viewModel::editKey,
                    label = stringResource(R.string.updates_key),
                    supportingText = stringResource(R.string.updates_key_hint),
                    isError = key.isNotBlank() && !NodeSubscription.isValidKey(key),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::saveKey, enabled = NodeSubscription.isValidKey(key)) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = viewModel::closeKey) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (state.showVersions) {
        TextDialog(
            title = stringResource(R.string.updates_versions),
            text = state.versions?.joinToString("\n") { "${it.name}: ${it.version.orEmpty()}" },
            error = null,
            onClose = viewModel::hideVersions,
        )
    }
}

// ------------------------------------------------------------------------------ repositories

/** Node → Repositories: where the node gets its packages, with Proxmox's warnings. */
@Composable
fun RepositoriesScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: RepositoriesViewModel = koinViewModel(key = "repos-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.repos_title),
        subtitle = node,
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
            val repos = state.repos
            if (repos == null) {
                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.accent)
                        }
                    }
                }
                return@LazyColumn
            }
            items(repos.warnings.distinctBy { it.message }, key = { "w-${it.message}" }) { warning ->
                ErrorBanner(warning.message, tone = Tone.WARNING)
            }
            repos.errors.forEach { e -> item { ErrorBanner("${e.path}: ${e.error}") } }
            repos.files.forEach { file ->
                item { SectionHeader(file.path) }
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        file.repositories.forEachIndexed { index, r ->
                            val origin = repos.infosFor(file.path, index).firstOrNull { it.kind == "origin" }?.message
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.line,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (r.enabled) c.textPrimary else c.textTertiary,
                                    )
                                    listOfNotNull(origin, r.comment).takeIf { it.isNotEmpty() }?.let {
                                        Text(it.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                                    }
                                }
                                Switch(checked = r.enabled, onCheckedChange = { on -> viewModel.setEnabled(file.path, index, on) }, enabled = !state.busy)
                            }
                        }
                    }
                }
            }
            if (repos.addable.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.repos_add)) }
                items(repos.addable, key = { "a-${it.handle}" }) { standard ->
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(standard.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                                standard.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                            }
                            IconButton(onClick = { viewModel.add(standard.handle, standard.name) }, enabled = !state.busy) {
                                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.repos_add_one, standard.name), tint = c.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ services

/** Node → Services: the systemd units behind Proxmox, with start, stop and restart. */
@Composable
fun ServicesScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: ServicesViewModel = koinViewModel(key = "services-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.services_title),
        subtitle = node,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            state.running?.let { item { BusyCard(stringResource(R.string.services_running, it)) } }
            if (state.loading && state.services.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            }
            items(state.services, key = { it.id }) { service ->
                GlassCard(contentPadding = PaddingValues(0.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = state.running == null && !service.missing) { viewModel.select(service) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(service.id, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            service.desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        StatusPill(serviceState(service), serviceTone(service))
                    }
                }
            }
        }
    }

    state.selected?.let { service ->
        AlertDialog(
            onDismissRequest = viewModel::closeSelection,
            containerColor = c.surfaceSolid,
            title = { Text(service.id) },
            text = {
                Column {
                    service.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary) }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!service.running) TextButton(onClick = { viewModel.request(service, ServiceAction.START) }) { Text(stringResource(R.string.services_start)) }
                        if (service.running) {
                            TextButton(onClick = { viewModel.request(service, ServiceAction.RESTART) }) { Text(stringResource(R.string.services_restart)) }
                            TextButton(onClick = { viewModel.request(service, ServiceAction.RELOAD) }) { Text(stringResource(R.string.services_reload)) }
                            TextButton(onClick = { viewModel.request(service, ServiceAction.STOP) }) { Text(stringResource(R.string.services_stop), color = c.danger) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeSelection) { Text(stringResource(R.string.action_close)) } },
        )
    }
    state.confirm?.let { (service, action) ->
        ConfirmActionDialog(
            title = stringResource(R.string.services_confirm_title, actionLabel(action), service.id),
            message = stringResource(R.string.services_confirm_body),
            confirmLabel = actionLabel(action),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

@Composable
private fun serviceState(s: NodeService): String = when {
    s.missing -> stringResource(R.string.services_not_installed)
    s.running -> stringResource(R.string.status_running)
    else -> stringResource(R.string.status_stopped)
}

private fun serviceTone(s: NodeService): Tone = when {
    s.missing -> Tone.NEUTRAL
    s.running -> Tone.SUCCESS
    else -> Tone.WARNING
}

@Composable
private fun actionLabel(action: ServiceAction): String = stringResource(
    when (action) {
        ServiceAction.START -> R.string.services_start
        ServiceAction.STOP -> R.string.services_stop
        ServiceAction.RESTART -> R.string.services_restart
        ServiceAction.RELOAD -> R.string.services_reload
    },
)

// ------------------------------------------------------------------------------ journal

/** Node → System log: the newest journal lines, with a filter; newest at the bottom. */
@Composable
fun JournalScreen(serverId: String, node: String, onBack: () -> Unit) {
    val viewModel: JournalViewModel = koinViewModel(key = "journal-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val list = rememberLazyListState()
    val shown = state.shown
    LaunchedEffect(state.lines.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size + 1) }
    AnProxScaffold(
        title = stringResource(R.string.journal_title),
        subtitle = node,
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(state = list, contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { GlassTextField(value = state.filter, onValueChange = viewModel::setFilter, label = stringResource(R.string.firewall_log_filter)) }
            item { Spacer(Modifier.height(8.dp)) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.lines.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            }
            itemsIndexed(shown) { _, line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        line.contains("error", ignoreCase = true) || line.contains("fail", ignoreCase = true) -> c.danger
                        line.contains("warn", ignoreCase = true) -> c.warning
                        else -> c.textSecondary
                    },
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------ shared

/** Plain text in a scrolling dialog; [text] null shows a spinner. */
@Composable
private fun TextDialog(title: String, text: String?, error: String?, onClose: () -> Unit) {
    val c = AnProxTheme.colors
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.surfaceSolid,
        title = { Text(title) },
        text = {
            when {
                error != null -> FormHint(error, error = true)
                text == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent)
                }
                else -> Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.textPrimary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}

/** Node → Administration: the M5 pages. */
@Composable
fun NodeAdminCard(onOpen: (NodeAdminPage) -> Unit) {
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        NodeAdminPage.entries.forEach { page ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(page) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (page) {
                        NodeAdminPage.UPDATES -> Icons.Outlined.SystemUpdateAlt
                        NodeAdminPage.REPOSITORIES -> Icons.Outlined.CloudDownload
                        NodeAdminPage.SERVICES -> Icons.Outlined.Settings
                        NodeAdminPage.JOURNAL -> Icons.Outlined.Description
                        NodeAdminPage.SYSTEM -> Icons.Outlined.Language
                        NodeAdminPage.NETWORK -> Icons.Outlined.SettingsEthernet
                        NodeAdminPage.DISKS -> Icons.Outlined.Storage
                        NodeAdminPage.TASKS -> Icons.Outlined.History
                    },
                    contentDescription = null,
                    tint = c.accent,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(pageTitle(page)), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text(stringResource(pageBody(page)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
    }
}

private fun pageTitle(page: NodeAdminPage): Int = when (page) {
    NodeAdminPage.UPDATES -> R.string.updates_title
    NodeAdminPage.REPOSITORIES -> R.string.repos_title
    NodeAdminPage.SERVICES -> R.string.services_title
    NodeAdminPage.JOURNAL -> R.string.journal_title
    NodeAdminPage.SYSTEM -> R.string.system_title
    NodeAdminPage.NETWORK -> R.string.network_title
    NodeAdminPage.DISKS -> R.string.disks_title
    NodeAdminPage.TASKS -> R.string.taskhistory_title
}

private fun pageBody(page: NodeAdminPage): Int = when (page) {
    NodeAdminPage.UPDATES -> R.string.updates_open_body
    NodeAdminPage.REPOSITORIES -> R.string.repos_open_body
    NodeAdminPage.SERVICES -> R.string.services_open_body
    NodeAdminPage.JOURNAL -> R.string.journal_open_body
    NodeAdminPage.SYSTEM -> R.string.system_open_body
    NodeAdminPage.NETWORK -> R.string.network_open_body
    NodeAdminPage.DISKS -> R.string.disks_open_body
    NodeAdminPage.TASKS -> R.string.taskhistory_open_body
}

// ------------------------------------------------------------------------------ DNS, hosts, time

/** Node → DNS, hosts and time: the resolver, `/etc/hosts` and the time zone. */
@Composable
fun SystemSettingsScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: SystemSettingsViewModel = koinViewModel(key = "system-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    var zoneQuery by rememberSaveable { mutableStateOf("") }
    val zones = remember { java.util.TimeZone.getAvailableIDs().filter { '/' in it || it == "UTC" }.sorted() }
    AnProxScaffold(
        title = stringResource(R.string.system_title),
        subtitle = node,
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
            if (state.loading && state.dns == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            }
            state.dns?.let { dns ->
                item { SectionHeader(stringResource(R.string.system_dns)) }
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassTextField(value = dns.search, onValueChange = { v -> viewModel.editDns { copy(search = v) } }, label = stringResource(R.string.system_search_domain))
                            dns.servers.forEachIndexed { i, server ->
                                GlassTextField(
                                    value = server,
                                    onValueChange = { v -> viewModel.setServer(i, v) },
                                    label = stringResource(R.string.system_dns_server, i + 1),
                                    isError = server.isNotBlank() && dns.problem() == NodeDnsForm.Problem.SERVER,
                                )
                            }
                            dns.problem()?.let {
                                FormHint(stringResource(if (it == NodeDnsForm.Problem.SEARCH) R.string.system_problem_search else R.string.system_problem_server), error = true)
                            }
                            AnProxButton(stringResource(R.string.action_save), onClick = viewModel::saveDns, enabled = !state.busy && dns.problem() == null, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            state.time?.let { time ->
                item { SectionHeader(stringResource(R.string.system_time)) }
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            time.localtime?.let { local ->
                                Text(
                                    stringResource(R.string.system_node_time, java.time.Instant.ofEpochSecond(local).toString().replace('T', ' ').removeSuffix("Z")),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textSecondary,
                                )
                            }
                            Text(stringResource(R.string.system_timezone, state.timezone), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            GlassTextField(value = zoneQuery, onValueChange = { zoneQuery = it }, label = stringResource(R.string.system_timezone_search))
                            if (zoneQuery.length >= 2) {
                                zones.filter { it.contains(zoneQuery.trim(), ignoreCase = true) }.take(8).forEach { zone ->
                                    Text(
                                        zone,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (zone == state.timezone) c.accent else c.textPrimary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.setTimezone(zone)
                                                zoneQuery = ""
                                            }
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                            AnProxButton(
                                stringResource(R.string.system_timezone_save),
                                onClick = viewModel::saveTimezone,
                                enabled = !state.busy && state.timezoneChanged,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            if (state.hosts != null) {
                item { SectionHeader(stringResource(R.string.system_hosts)) }
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormHint(stringResource(R.string.system_hosts_body))
                            GlassTextField(
                                value = state.hostsText,
                                onValueChange = viewModel::editHosts,
                                label = stringResource(R.string.system_hosts_file),
                                singleLine = false,
                                minLines = 6,
                            )
                            AnProxButton(stringResource(R.string.action_save), onClick = viewModel::saveHosts, enabled = !state.busy && state.hostsChanged, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ network

/**
 * Node → Network (questionnaire Q22): bridges, bonds, VLANs and ports. Edits collect in a pending
 * configuration; **Apply** reloads the network, **Revert** drops the changes.
 */
@Composable
fun NetworkScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: NetworkViewModel = koinViewModel(key = "network-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.network_title),
        subtitle = node,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            val config = state.config
            if (config == null) {
                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.accent)
                        }
                    }
                }
                return@LazyColumn
            }
            if (config.pending) {
                item {
                    GlassCard {
                        Text(stringResource(R.string.network_pending), style = MaterialTheme.typography.titleSmall, color = c.warning)
                        FormHint(stringResource(R.string.network_pending_body))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.showChanges(true) }) { Text(stringResource(R.string.network_show_changes)) }
                            TextButton(onClick = viewModel::requestApply, enabled = !state.busy) { Text(stringResource(R.string.network_apply)) }
                            TextButton(onClick = viewModel::requestRevert, enabled = !state.busy) { Text(stringResource(R.string.network_revert), color = c.danger) }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetworkKind.entries.forEach { kind ->
                        AnProxButton(
                            kindLabel(kind),
                            onClick = { viewModel.add(kind) },
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.Add,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            items(config.interfaces, key = { it.iface }) { iface ->
                GlassCard(contentPadding = PaddingValues(0.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = !state.busy) { viewModel.edit(iface) }
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(iface.iface, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                            if (!iface.exists) {
                                StatusPill(stringResource(R.string.network_state_pending), Tone.WARNING)
                            } else {
                                StatusPill(stringResource(if (iface.active) R.string.network_state_active else R.string.network_state_inactive), if (iface.active) Tone.SUCCESS else Tone.NEUTRAL)
                            }
                        }
                        Text(
                            listOfNotNull(typeLabel(iface.type), iface.cidr, iface.gateway?.let { stringResource(R.string.network_via, it) }, iface.members).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                        iface.comments?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textTertiary) }
                    }
                }
            }
        }
    }

    state.editing?.let { edit -> NetworkDialog(edit, state, viewModel) }
    if (state.showChanges) {
        TextDialog(stringResource(R.string.network_changes), state.config?.changes.orEmpty(), null, onClose = { viewModel.showChanges(false) })
    }
    if (state.confirmApply) {
        ConfirmActionDialog(
            title = stringResource(R.string.network_apply_title, node),
            message = stringResource(R.string.network_apply_body),
            confirmLabel = stringResource(R.string.network_apply),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            onConfirm = viewModel::apply,
            onDismiss = viewModel::dismissConfirm,
            confirmText = node,
        )
    }
    if (state.confirmRevert) {
        ConfirmActionDialog(
            title = stringResource(R.string.network_revert_title),
            message = stringResource(R.string.network_revert_body),
            confirmLabel = stringResource(R.string.network_revert),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::revert,
            onDismiss = viewModel::dismissConfirm,
        )
    }
    state.confirmDelete?.let { iface ->
        ConfirmActionDialog(
            title = stringResource(R.string.network_delete_title, iface.iface),
            message = stringResource(R.string.network_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun NetworkDialog(edit: NetworkEdit, state: NetworkState, viewModel: NetworkViewModel) {
    val c = AnProxTheme.colors
    val form = edit.form
    val creating = edit.original == null
    val problem = form.problem(creating, state.names)
    AlertDialog(
        onDismissRequest = viewModel::close,
        containerColor = c.surfaceSolid,
        title = { Text(if (creating) stringResource(R.string.network_add, form.kind?.let { kindLabel(it) }.orEmpty()) else form.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creating) {
                    GlassTextField(value = form.name, onValueChange = { v -> viewModel.editForm { copy(name = v) } }, label = stringResource(R.string.network_name))
                }
                GlassTextField(
                    value = form.cidr,
                    onValueChange = { v -> viewModel.editForm { copy(cidr = v) } },
                    label = stringResource(R.string.network_cidr),
                    supportingText = stringResource(R.string.network_cidr_hint),
                )
                GlassTextField(value = form.gateway, onValueChange = { v -> viewModel.editForm { copy(gateway = v) } }, label = stringResource(R.string.network_gateway))
                GlassTextField(value = form.cidr6, onValueChange = { v -> viewModel.editForm { copy(cidr6 = v) } }, label = stringResource(R.string.network_cidr6))
                GlassTextField(value = form.gateway6, onValueChange = { v -> viewModel.editForm { copy(gateway6 = v) } }, label = stringResource(R.string.network_gateway6))
                when (form.kind) {
                    NetworkKind.BRIDGE -> {
                        GlassTextField(
                            value = form.bridgePorts,
                            onValueChange = { v -> viewModel.editForm { copy(bridgePorts = v) } },
                            label = stringResource(R.string.network_ports),
                            supportingText = stringResource(R.string.network_ports_hint),
                        )
                        SwitchRow(stringResource(R.string.network_vlan_aware), form.vlanAware, { on -> viewModel.editForm { copy(vlanAware = on) } })
                    }
                    NetworkKind.BOND -> {
                        GlassTextField(
                            value = form.slaves,
                            onValueChange = { v -> viewModel.editForm { copy(slaves = v) } },
                            label = stringResource(R.string.network_slaves),
                            supportingText = stringResource(R.string.network_ports_hint),
                        )
                        FormLabel(stringResource(R.string.network_bond_mode))
                        ChoiceChips(NetworkInterfaceForm.BOND_MODES, form.bondMode, { it }) { m -> viewModel.editForm { copy(bondMode = m) } }
                        if (form.bondMode in NetworkInterfaceForm.HASH_MODES) {
                            FormLabel(stringResource(R.string.network_hash_policy))
                            ChoiceChips(NetworkInterfaceForm.HASH_POLICIES, form.hashPolicy, { it }) { h -> viewModel.editForm { copy(hashPolicy = h) } }
                        }
                    }
                    NetworkKind.VLAN -> {
                        FormHint(stringResource(R.string.network_vlan_hint))
                        GlassTextField(value = form.vlanRawDevice, onValueChange = { v -> viewModel.editForm { copy(vlanRawDevice = v) } }, label = stringResource(R.string.network_vlan_device))
                        GlassTextField(value = form.vlanId, onValueChange = { v -> viewModel.editForm { copy(vlanId = v.filter(Char::isDigit).take(4)) } }, label = stringResource(R.string.network_vlan_tag))
                    }
                    null -> Unit
                }
                GlassTextField(value = form.mtu, onValueChange = { v -> viewModel.editForm { copy(mtu = v.filter(Char::isDigit).take(5)) } }, label = stringResource(R.string.network_mtu))
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editForm { copy(comment = v) } }, label = stringResource(R.string.network_comment))
                SwitchRow(stringResource(R.string.network_autostart), form.autostart, { on -> viewModel.editForm { copy(autostart = on) } })
                problem?.let { FormHint(stringResource(networkProblem(it)), error = true) }
                val original = edit.original
                if (original != null && original.type != "eth") {
                    TextButton(onClick = { viewModel.requestDelete(original) }) { Text(stringResource(R.string.network_delete), color = c.danger) }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save, enabled = problem == null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun kindLabel(kind: NetworkKind): String = stringResource(
    when (kind) {
        NetworkKind.BRIDGE -> R.string.network_kind_bridge
        NetworkKind.BOND -> R.string.network_kind_bond
        NetworkKind.VLAN -> R.string.network_kind_vlan
    },
)

@Composable
private fun typeLabel(type: String?): String = NetworkKind.fromApi(type)?.let { kindLabel(it) }
    ?: if (type == "eth") stringResource(R.string.network_kind_port) else type.orEmpty()

private fun networkProblem(p: NetworkInterfaceForm.Problem): Int = when (p) {
    NetworkInterfaceForm.Problem.NAME -> R.string.network_problem_name
    NetworkInterfaceForm.Problem.NAME_TAKEN -> R.string.network_problem_name_taken
    NetworkInterfaceForm.Problem.ADDRESS -> R.string.network_problem_address
    NetworkInterfaceForm.Problem.GATEWAY -> R.string.network_problem_gateway
    NetworkInterfaceForm.Problem.ADDRESS6 -> R.string.network_problem_address6
    NetworkInterfaceForm.Problem.GATEWAY6 -> R.string.network_problem_gateway6
    NetworkInterfaceForm.Problem.MTU -> R.string.network_problem_mtu
    NetworkInterfaceForm.Problem.SLAVES -> R.string.network_problem_slaves
    NetworkInterfaceForm.Problem.VLAN -> R.string.network_problem_vlan
}

// ------------------------------------------------------------------------------ disks

/** Node → Disks (questionnaire Q22): health and wear, SMART, and storage on unused disks. */
@Composable
fun DisksScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: DisksViewModel = koinViewModel(key = "disks-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.disks_title),
        subtitle = node,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            state.running?.let { item { BusyCard(stringResource(R.string.disks_running, it)) } }
            item {
                AnProxButton(
                    stringResource(R.string.disks_create),
                    onClick = { viewModel.startCreate(null) },
                    icon = Icons.Outlined.Add,
                    enabled = state.running == null && state.unused.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.loading && state.disks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            }
            items(state.disks, key = { it.devpath }) { disk ->
                GlassCard(contentPadding = PaddingValues(0.dp)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = state.running == null) { viewModel.open(disk) }
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(disk.devpath, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                            val healthy = disk.healthy
                            StatusPill(disk.health ?: stringResource(R.string.disks_health_unknown), if (healthy == true) Tone.SUCCESS else if (healthy == false) Tone.DANGER else Tone.NEUTRAL)
                        }
                        Text(
                            listOfNotNull(disk.model, Formatters.bytes(disk.size), disk.type?.uppercase()).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                        Text(
                            listOfNotNull(
                                disk.used?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.disks_used_by, it) } ?: stringResource(R.string.disks_unused),
                                disk.wearoutPercent?.let { stringResource(R.string.disks_wearout, it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (disk.unused) c.accent else c.textTertiary,
                        )
                    }
                }
            }
        }
    }

    state.detail?.let { detail -> DiskDialog(detail, state.running == null, viewModel) }
    state.creating?.let { form -> CreateDiskStorageDialog(form, state, viewModel) }
    state.confirmWipe?.let { disk ->
        ConfirmActionDialog(
            title = stringResource(R.string.disks_wipe_title, disk.devpath),
            message = stringResource(R.string.disks_wipe_body),
            confirmLabel = stringResource(R.string.disks_wipe),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            onConfirm = viewModel::wipe,
            onDismiss = viewModel::dismissWipe,
            confirmText = disk.devpath,
        )
    }
}

@Composable
private fun DiskDialog(detail: DiskDetail, enabled: Boolean, viewModel: DisksViewModel) {
    val c = AnProxTheme.colors
    val disk = detail.disk
    AlertDialog(
        onDismissRequest = viewModel::closeDetail,
        containerColor = c.surfaceSolid,
        title = { Text(disk.devpath) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(listOfNotNull(disk.model, disk.serial).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                val smart = detail.smart
                when {
                    detail.error != null -> FormHint(detail.error.asString(), error = true)
                    smart == null -> CircularProgressIndicator(color = c.accent)
                    smart.attributes.isNotEmpty() -> smart.attributes.forEach { a ->
                        Row {
                            Text(a.name, style = MaterialTheme.typography.bodySmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                            Text(a.raw.orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.textSecondary)
                        }
                    }
                    !smart.text.isNullOrBlank() -> Text(smart.text.orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.textPrimary)
                    else -> FormHint(stringResource(R.string.disks_no_smart))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (disk.unused) {
                        TextButton(onClick = { viewModel.startCreate(disk) }, enabled = enabled) { Text(stringResource(R.string.disks_create_short)) }
                        if (!disk.gpt) TextButton(onClick = { viewModel.initGpt(disk) }, enabled = enabled) { Text(stringResource(R.string.disks_init_gpt)) }
                    }
                    TextButton(onClick = { viewModel.requestWipe(disk) }, enabled = enabled) { Text(stringResource(R.string.disks_wipe), color = c.danger) }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::closeDetail) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun CreateDiskStorageDialog(form: DiskStorageForm, state: DisksState, viewModel: DisksViewModel) {
    val c = AnProxTheme.colors
    val kindLabels = mapOf(
        DiskStorageKind.DIRECTORY to stringResource(R.string.storage_kind_dir),
        DiskStorageKind.LVM to stringResource(R.string.storage_kind_lvm),
        DiskStorageKind.LVMTHIN to stringResource(R.string.storage_kind_lvmthin),
        DiskStorageKind.ZFS to stringResource(R.string.storage_kind_zfs),
    )
    val problem = form.problem()
    AlertDialog(
        onDismissRequest = viewModel::closeCreate,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.disks_create)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(DiskStorageKind.entries, form.kind, { kindLabels.getValue(it) }) { k ->
                    viewModel.editCreate { copy(kind = k, devices = if (k == DiskStorageKind.ZFS) devices else devices.take(1)) }
                }
                GlassTextField(value = form.name, onValueChange = { v -> viewModel.editCreate { copy(name = v) } }, label = stringResource(R.string.disks_name))
                FormLabel(stringResource(if (form.kind == DiskStorageKind.ZFS) R.string.disks_devices else R.string.disks_device))
                state.unused.forEach { disk ->
                    val checked = disk.devpath in form.devices
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { viewModel.toggleDevice(disk.devpath) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("${disk.devpath} · ${Formatters.bytes(disk.size)}", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
                when (form.kind) {
                    DiskStorageKind.DIRECTORY -> {
                        FormLabel(stringResource(R.string.disks_filesystem))
                        ChoiceChips(DiskStorageForm.FILESYSTEMS, form.filesystem, { it }) { f -> viewModel.editCreate { copy(filesystem = f) } }
                    }
                    DiskStorageKind.ZFS -> {
                        FormLabel(stringResource(R.string.disks_raid))
                        ChoiceChips(DiskStorageForm.RAID_LEVELS, form.raidLevel, { it }) { r -> viewModel.editCreate { copy(raidLevel = r) } }
                        FormLabel(stringResource(R.string.disks_compression))
                        ChoiceChips(DiskStorageForm.COMPRESSIONS, form.compression, { it }) { z -> viewModel.editCreate { copy(compression = z) } }
                    }
                    else -> Unit
                }
                SwitchRow(stringResource(R.string.disks_add_storage), form.addStorage, { on -> viewModel.editCreate { copy(addStorage = on) } })
                FormHint(stringResource(R.string.disks_create_warning))
                problem?.let {
                    FormHint(
                        stringResource(
                            when (it) {
                                DiskStorageForm.Problem.NAME -> R.string.disks_problem_name
                                DiskStorageForm.Problem.DEVICES -> R.string.disks_problem_devices
                                DiskStorageForm.Problem.RAID -> R.string.disks_problem_raid
                            },
                        ),
                        error = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::create, enabled = problem == null) { Text(stringResource(R.string.disks_create_short)) } },
        dismissButton = { TextButton(onClick = viewModel::closeCreate) { Text(stringResource(R.string.action_cancel)) } },
    )
}

