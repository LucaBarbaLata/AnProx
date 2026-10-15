package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
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
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PoolDetail
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.statusAppearance
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Datacenter → Resource pools: every pool with what it holds; create one. */
@Composable
fun PoolsScreen(
    serverId: String,
    onBack: () -> Unit,
    onPool: (poolId: String) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: PoolsViewModel = koinViewModel(key = "pools-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Also after coming back from a pool.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)

    AnProxScaffold(
        title = stringResource(R.string.datacenter_pools),
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
                    stringResource(R.string.pools_create),
                    onClick = viewModel::startCreate,
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy && state.error == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            when {
                state.loading && state.pools.isEmpty() && state.error == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                !state.loading && state.error == null && state.pools.isEmpty() -> item {
                    EmptyState(Icons.Outlined.Workspaces, stringResource(R.string.pools_empty_title), stringResource(R.string.pools_empty_body))
                }
                else -> items(state.pools, key = { it.id }) { pool -> PoolCard(pool) { onPool(pool.id) } }
            }
        }
    }

    state.creating?.let { pool ->
        val taken = state.nameTaken(pool.id)
        AlertDialog(
            onDismissRequest = viewModel::closeCreate,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.pools_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = pool.id,
                        onValueChange = { v -> viewModel.editNew { copy(id = v) } },
                        label = stringResource(R.string.pools_name),
                        supportingText = stringResource(R.string.pools_name_hint),
                        isError = pool.id.isNotEmpty() && (!pool.valid || taken),
                    )
                    if (taken) FormHint(stringResource(R.string.pools_name_taken), error = true)
                    GlassTextField(value = pool.comment, onValueChange = { v -> viewModel.editNew { copy(comment = v) } }, label = stringResource(R.string.pools_comment))
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::create, enabled = pool.valid && !taken) { Text(stringResource(R.string.pools_create_short)) }
            },
            dismissButton = { TextButton(onClick = viewModel::closeCreate) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PoolCard(pool: PoolDetail, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(pool.id, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                pool.comment?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(
                    stringResource(R.string.pools_counts, pool.guests.size, pool.storages.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textTertiary)
        }
    }
}

/** One pool: its guests and storages, adding and removing them, the comment, deleting it. */
@Composable
fun PoolScreen(
    serverId: String,
    poolId: String,
    onBack: () -> Unit,
    onGuest: (node: String, type: GuestType, vmid: Int) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: PoolViewModel = koinViewModel(key = "pool-$serverId-$poolId") { parametersOf(serverId, poolId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    val pool = state.pool
    AnProxScaffold(
        title = poolId,
        subtitle = pool?.comment,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::editComment, enabled = pool != null && !state.busy) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.pool_edit_comment))
            }
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (pool == null) {
                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AnProxTheme.colors.accent)
                        }
                    }
                }
                return@LazyColumn
            }
            item { SectionHeader(stringResource(R.string.pool_guests, pool.guests.size)) }
            item {
                AnProxButton(
                    stringResource(R.string.pool_add_guests),
                    onClick = viewModel::pickGuests,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy && state.freeGuests.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (pool.guests.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        pool.guests.forEach { m ->
                            MemberRow(
                                title = "${m.vmid} · ${m.name.orEmpty()}",
                                subtitle = m.node,
                                status = m.status,
                                enabled = !state.busy,
                                onClick = {
                                    val node = m.node
                                    val vmid = m.vmid
                                    if (node != null && vmid != null) onGuest(node, if (m.type == "lxc") GuestType.LXC else GuestType.QEMU, vmid)
                                },
                                onRemove = { viewModel.requestRemove(m) },
                            )
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.pool_storages, pool.storages.size)) }
            item {
                AnProxButton(
                    stringResource(R.string.pool_add_storage),
                    onClick = viewModel::pickStorages,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy && state.freeStorages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (pool.storages.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        pool.storages.forEach { storage ->
                            val member = pool.members.first { it.isStorage && it.storage == storage }
                            MemberRow(title = storage, subtitle = null, status = null, enabled = !state.busy, onClick = null, onRemove = { viewModel.requestRemove(member) })
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(4.dp)) }
            item {
                AnProxButton(
                    stringResource(R.string.pool_delete),
                    onClick = viewModel::requestDelete,
                    style = ButtonStyle.DANGER,
                    icon = Icons.Outlined.Delete,
                    enabled = !state.busy && pool.isEmpty,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!pool.isEmpty) item { FormHint(stringResource(R.string.pool_delete_not_empty)) }
        }
    }

    state.picking?.let { pick -> PickDialog(pick, state, viewModel) }
    state.editingComment?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::closeComment,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.pool_edit_comment)) },
            text = { GlassTextField(value = text, onValueChange = viewModel::changeComment, label = stringResource(R.string.pools_comment)) },
            confirmButton = { TextButton(onClick = viewModel::saveComment) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = viewModel::closeComment) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.confirmRemove?.let { m ->
        val label = m.vmid?.let { "$it · ${m.name.orEmpty()}" } ?: m.storage.orEmpty()
        ConfirmActionDialog(
            title = stringResource(R.string.pool_remove_title),
            message = stringResource(R.string.pool_remove_body, label, poolId),
            confirmLabel = stringResource(R.string.pool_remove),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::remove,
            onDismiss = viewModel::dismissRemove,
        )
    }
    if (state.confirmDelete) {
        ConfirmActionDialog(
            title = stringResource(R.string.pool_delete_title, poolId),
            message = stringResource(R.string.pool_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun MemberRow(title: String, subtitle: String?, status: String?, enabled: Boolean, onClick: (() -> Unit)?, onRemove: () -> Unit) {
    val c = AnProxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        }
        if (status != null) {
            val look = statusAppearance(status)
            StatusPill(look.label, look.tone)
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.pool_remove), tint = c.textSecondary)
        }
    }
}

@Composable
private fun PickDialog(pick: PoolPick, state: PoolUiState, viewModel: PoolViewModel) {
    val c = AnProxTheme.colors
    val options: List<Pair<String, String>> = if (pick.guests) {
        state.freeGuests.map { g -> g.vmid.toString() to "${g.vmid} · ${g.name.orEmpty()}" }
    } else {
        state.freeStorages.map { it to it }
    }
    AlertDialog(
        onDismissRequest = viewModel::closePick,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(if (pick.guests) R.string.pool_add_guests else R.string.pool_add_storage)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (pick.guests) FormHint(stringResource(R.string.pool_add_guests_hint))
                options.forEach { (key, label) ->
                    val checked = key in pick.selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { viewModel.toggle(key) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::addPicked, enabled = pick.selected.isNotEmpty()) {
                Text(stringResource(R.string.pool_add_count, pick.selected.size))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closePick) { Text(stringResource(R.string.action_cancel)) } },
    )
}
