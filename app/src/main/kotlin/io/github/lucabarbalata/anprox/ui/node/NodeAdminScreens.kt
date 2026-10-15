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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
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
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.ServiceAction
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
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
    onUpgrade: () -> Unit,
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
                    AnProxButton(
                        stringResource(R.string.updates_upgrade),
                        onClick = onUpgrade,
                        icon = Icons.Outlined.Terminal,
                        enabled = state.updates.isNotEmpty() && !state.refreshing,
                        modifier = Modifier.weight(1f),
                    )
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
}

private fun pageBody(page: NodeAdminPage): Int = when (page) {
    NodeAdminPage.UPDATES -> R.string.updates_open_body
    NodeAdminPage.REPOSITORIES -> R.string.repos_open_body
    NodeAdminPage.SERVICES -> R.string.services_open_body
    NodeAdminPage.JOURNAL -> R.string.journal_open_body
}
