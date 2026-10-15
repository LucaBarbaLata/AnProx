package io.github.lucabarbalata.anprox.ui.datacenter

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.FirewallAliasForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The scaffold, snackbar, loading and error parts every screen below shares. */
@Composable
private fun ObjectsScaffold(
    title: String,
    subtitle: String,
    serverId: String,
    status: FirewallObjectsViewModel.Status,
    onRefresh: () -> Unit,
    onConsumed: () -> Unit,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(status.result, snackbar, serverId, "", onTask, onConsumed)
    AnProxScaffold(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = onRefresh, enabled = !status.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = onRefresh) }
            }
            if (status.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
            }
            content()
        }
    }
}

// ------------------------------------------------------------------------------ security groups

/** Datacenter → Firewall → Security groups: rule sets that guests and nodes insert by name. */
@Composable
fun SecurityGroupsScreen(
    serverId: String,
    subtitle: String,
    onBack: () -> Unit,
    onGroup: (group: String) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: SecurityGroupsViewModel = koinViewModel(key = "fw-groups-$serverId") { parametersOf(serverId) }
    val status by viewModel.state.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    // Also after coming back from a group's rules.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    ObjectsScaffold(stringResource(R.string.firewall_groups), subtitle, serverId, status, viewModel::load, viewModel::consumeResult, onBack, onTask) {
        item {
            AnProxButton(stringResource(R.string.firewall_group_add), onClick = viewModel::startCreate, icon = Icons.Outlined.Add, enabled = !status.busy, modifier = Modifier.fillMaxWidth())
        }
        item { FormHint(stringResource(R.string.firewall_groups_body)) }
        if (!status.loading && status.error == null && content.groups.isEmpty()) {
            item { EmptyState(Icons.Outlined.Shield, stringResource(R.string.firewall_groups_empty), stringResource(R.string.firewall_groups_body)) }
        }
        items(content.groups, key = { it.group }) { group ->
            ObjectRow(
                title = group.group,
                subtitle = group.comment,
                onClick = { onGroup(group.group) },
                trailing = {
                    IconButton(onClick = { viewModel.requestDelete(group) }, enabled = !status.busy) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.firewall_group_delete, group.group), tint = AnProxTheme.colors.textSecondary)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = AnProxTheme.colors.textTertiary)
                },
            )
        }
    }
    content.creating?.let { new ->
        NameDialog(
            title = stringResource(R.string.firewall_group_add),
            new = new,
            onName = { v -> viewModel.editNew { copy(name = v) } },
            onComment = { v -> viewModel.editNew { copy(comment = v) } },
            onConfirm = viewModel::create,
            onDismiss = viewModel::closeCreate,
        )
    }
    content.confirmDelete?.let { group ->
        ConfirmActionDialog(
            title = stringResource(R.string.firewall_group_delete, group.group),
            message = stringResource(R.string.firewall_group_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

// ------------------------------------------------------------------------------ aliases

/** Aliases: names for addresses, usable as a rule's source or destination. */
@Composable
fun FirewallAliasesScreen(
    serverId: String,
    scope: FirewallScope,
    subtitle: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: AliasesViewModel = koinViewModel(key = "fw-aliases-$serverId-${scope.path}") { parametersOf(serverId, scope) }
    val status by viewModel.state.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    ObjectsScaffold(stringResource(R.string.firewall_aliases), subtitle, serverId, status, viewModel::load, viewModel::consumeResult, onBack, onTask) {
        item {
            AnProxButton(stringResource(R.string.firewall_alias_add), onClick = viewModel::add, icon = Icons.Outlined.Add, enabled = !status.busy, modifier = Modifier.fillMaxWidth())
        }
        item { FormHint(stringResource(R.string.firewall_aliases_body)) }
        if (!status.loading && status.error == null && content.aliases.isEmpty()) {
            item { EmptyState(Icons.AutoMirrored.Outlined.Label, stringResource(R.string.firewall_aliases_empty), stringResource(R.string.firewall_aliases_body)) }
        }
        items(content.aliases, key = { it.name }) { alias ->
            ObjectRow(
                title = alias.name,
                subtitle = listOfNotNull(alias.cidr, alias.comment).joinToString(" · "),
                onClick = { viewModel.edit(alias) },
            )
        }
    }
    content.editing?.let { edit ->
        val problem = edit.form.problem()
        AlertDialog(
            onDismissRequest = viewModel::close,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(edit.alias?.name ?: stringResource(R.string.firewall_alias_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextField(
                        value = edit.form.name,
                        onValueChange = { v -> viewModel.editForm { copy(name = v) } },
                        label = stringResource(R.string.firewall_name),
                        isError = edit.form.name.isNotEmpty() && problem == FirewallAliasForm.Problem.NAME,
                        supportingText = stringResource(R.string.firewall_name_hint),
                    )
                    GlassTextField(
                        value = edit.form.cidr,
                        onValueChange = { v -> viewModel.editForm { copy(cidr = v) } },
                        label = stringResource(R.string.firewall_address),
                        isError = edit.form.cidr.isNotEmpty() && problem == FirewallAliasForm.Problem.ADDRESS,
                        supportingText = stringResource(R.string.firewall_alias_address_hint),
                    )
                    GlassTextField(value = edit.form.comment, onValueChange = { v -> viewModel.editForm { copy(comment = v) } }, label = stringResource(R.string.firewall_comment))
                    if (edit.alias != null) {
                        TextButton(onClick = viewModel::delete) { Text(stringResource(R.string.firewall_alias_delete), color = AnProxTheme.colors.danger) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::save, enabled = problem == null) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// ------------------------------------------------------------------------------ IP sets

/** IP sets: named address lists for rules (`+name`), and the guests' `ipfilter-net*` sets. */
@Composable
fun FirewallIpSetsScreen(
    serverId: String,
    scope: FirewallScope,
    subtitle: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: IpSetsViewModel = koinViewModel(key = "fw-ipsets-$serverId-${scope.path}") { parametersOf(serverId, scope) }
    val status by viewModel.state.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    ObjectsScaffold(stringResource(R.string.firewall_ipsets), subtitle, serverId, status, viewModel::load, viewModel::consumeResult, onBack, onTask) {
        item {
            AnProxButton(stringResource(R.string.firewall_ipset_add), onClick = viewModel::startCreate, icon = Icons.Outlined.Add, enabled = !status.busy, modifier = Modifier.fillMaxWidth())
        }
        item { FormHint(stringResource(R.string.firewall_ipsets_body)) }
        if (!status.loading && status.error == null && content.sets.isEmpty()) {
            item { EmptyState(Icons.AutoMirrored.Outlined.List, stringResource(R.string.firewall_ipsets_empty), stringResource(R.string.firewall_ipsets_body)) }
        }
        items(content.sets, key = { it.first.name }) { (set, entries) ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(set.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                        set.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                    }
                    IconButton(onClick = { viewModel.requestDelete(set) }, enabled = !status.busy) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.firewall_ipset_delete, set.name), tint = c.textSecondary)
                    }
                }
                if (entries.isEmpty()) FormHint(stringResource(R.string.firewall_ipset_no_entries))
                entries.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.cidr, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, fontFamily = FontFamily.Monospace)
                            entry.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                        }
                        if (entry.nomatch) TagChip(stringResource(R.string.firewall_ipset_except))
                        IconButton(onClick = { viewModel.removeEntry(set, entry) }, enabled = !status.busy) {
                            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.firewall_ipset_remove, entry.cidr), tint = c.textSecondary)
                        }
                    }
                }
                TextButton(onClick = { viewModel.startAdd(set) }, enabled = !status.busy) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.firewall_ipset_add_entry))
                }
            }
        }
    }
    content.creating?.let { new ->
        NameDialog(
            title = stringResource(R.string.firewall_ipset_add),
            new = new,
            onName = { v -> viewModel.editNew { copy(name = v) } },
            onComment = { v -> viewModel.editNew { copy(comment = v) } },
            onConfirm = viewModel::create,
            onDismiss = viewModel::closeCreate,
        )
    }
    content.adding?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::closeAdd,
            containerColor = c.surfaceSolid,
            title = { Text(stringResource(R.string.firewall_ipset_add_to, entry.set)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTextField(
                        value = entry.cidr,
                        onValueChange = { v -> viewModel.editEntry { copy(cidr = v) } },
                        label = stringResource(R.string.firewall_address),
                        isError = entry.cidr.isNotEmpty() && !entry.valid,
                        supportingText = stringResource(R.string.firewall_ipset_entry_hint),
                    )
                    GlassTextField(value = entry.comment, onValueChange = { v -> viewModel.editEntry { copy(comment = v) } }, label = stringResource(R.string.firewall_comment))
                    SwitchRow(
                        stringResource(R.string.firewall_ipset_except),
                        entry.nomatch,
                        { on -> viewModel.editEntry { copy(nomatch = on) } },
                        description = stringResource(R.string.firewall_ipset_except_body),
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::addEntry, enabled = entry.valid) { Text(stringResource(R.string.firewall_add)) } },
            dismissButton = { TextButton(onClick = viewModel::closeAdd) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    content.confirmDelete?.let { set ->
        ConfirmActionDialog(
            title = stringResource(R.string.firewall_ipset_delete, set.name),
            message = stringResource(R.string.firewall_ipset_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

// ------------------------------------------------------------------------------ log

/** The firewall log of a node or guest: what rules with logging on saw, newest last. */
@Composable
fun FirewallLogScreen(
    serverId: String,
    scope: FirewallScope,
    subtitle: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: FirewallLogViewModel = koinViewModel(key = "fw-log-$serverId-${scope.path}") { parametersOf(serverId, scope) }
    val status by viewModel.state.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(status.result, snackbar, serverId, "", onTask, viewModel::consumeResult)
    val list = rememberLazyListState()
    val shown = content.shown
    // Start at the newest line.
    LaunchedEffect(content.lines.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size + 1) }
    AnProxScaffold(
        title = stringResource(R.string.firewall_log_title),
        subtitle = subtitle,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !status.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(state = list, contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                GlassTextField(value = content.filter, onValueChange = viewModel::setFilter, label = stringResource(R.string.firewall_log_filter))
            }
            item { Spacer(Modifier.height(8.dp)) }
            status.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (status.loading && content.lines.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            } else if (shown.isEmpty() && status.error == null) {
                item { FormHint(stringResource(R.string.firewall_log_empty)) }
            }
            items(shown, key = { it.n }) { line ->
                Text(
                    line.t,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        "DROP" in line.t -> c.danger
                        "REJECT" in line.t -> c.warning
                        else -> c.textSecondary
                    },
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------ shared pieces

@Composable
private fun ObjectRow(title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            trailing()
        }
    }
}

@Composable
private fun NameDialog(title: String, new: NamedNew, onName: (String) -> Unit, onComment: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = new.name,
                    onValueChange = onName,
                    label = stringResource(R.string.firewall_name),
                    isError = new.name.isNotEmpty() && !new.valid,
                    supportingText = stringResource(R.string.firewall_name_hint),
                )
                GlassTextField(value = new.comment, onValueChange = onComment, label = stringResource(R.string.firewall_comment))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = new.valid) { Text(stringResource(R.string.firewall_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The links under a firewall's rules to what else it has. */
@Composable
fun FirewallExtrasCard(scope: FirewallScope, onOpen: (FirewallExtra) -> Unit) {
    val entries = buildList {
        if (scope is FirewallScope.Datacenter) add(FirewallExtra.GROUPS)
        if (scope.hasAliases) {
            add(FirewallExtra.ALIASES)
            add(FirewallExtra.IPSETS)
        }
        if (scope.hasLog) add(FirewallExtra.LOG)
    }
    if (entries.isEmpty()) return
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        entries.forEach { extra ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(extra) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        when (extra) {
                            FirewallExtra.GROUPS -> R.string.firewall_groups
                            FirewallExtra.ALIASES -> R.string.firewall_aliases
                            FirewallExtra.IPSETS -> R.string.firewall_ipsets
                            FirewallExtra.LOG -> R.string.firewall_log_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textTertiary)
            }
        }
    }
}
