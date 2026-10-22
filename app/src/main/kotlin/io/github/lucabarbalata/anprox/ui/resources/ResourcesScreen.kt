package io.github.lucabarbalata.anprox.ui.resources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.component.isWideLayout
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.statusAppearance
import io.github.lucabarbalata.anprox.ui.guest.GuestDetail
import io.github.lucabarbalata.anprox.ui.guest.GuestTool
import io.github.lucabarbalata.anprox.ui.guest.actionLabel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The resource list. On wide screens (tablets, unfolded foldables) it becomes a list/detail
 * split view (questionnaire Q3); on phones a tap opens the detail screen.
 */
@Composable
fun ResourcesScreen(
    onGuest: (GuestRef) -> Unit,
    onNode: (serverId: String, node: String) -> Unit,
    onStorage: (serverId: String, node: String, storage: String) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onTool: (tool: GuestTool, ref: GuestRef, guestName: String) -> Unit,
    viewModel: ResourcesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bulk by viewModel.bulk.collectAsStateWithLifecycle()
    val wide = isWideLayout()
    val snackbar = remember { SnackbarHostState() }
    val bulkResult = bulk.result?.asString()
    LaunchedEffect(bulk.result) {
        if (bulkResult != null) {
            viewModel.consumeBulkResult()
            // A summary of several tasks: keep it up long enough to read.
            snackbar.showSnackbar(bulkResult, duration = SnackbarDuration.Long)
        }
    }
    BackHandler(enabled = bulk.active) { viewModel.clearSelection() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // A selected guest that moved to another node (migration, HA) is followed there.
    LaunchedEffect(state.items) {
        val ref = selected?.let(::parseRefKey) ?: return@LaunchedEffect
        val node = state.items.firstOrNull {
            it.serverId == ref.serverId && it.resource.vmid == ref.vmid && GuestType.fromApi(it.resource.type) == ref.type
        }?.resource?.node
        if (node != null && node != ref.node) selected = refKey(ref.copy(node = node))
    }

    AnProxScaffold(
        title = if (bulk.active) {
            pluralStringResource(R.plurals.resources_selected, bulk.selected.size, bulk.selected.size)
        } else {
            stringResource(R.string.resources_title)
        },
        subtitle = if (bulk.active) stringResource(R.string.resources_selected_hint) else stringResource(R.string.resources_subtitle, state.items.size),
        onBack = if (bulk.active) viewModel::clearSelection else null,
        snackbarHostState = snackbar,
        actions = {
            if (bulk.active) {
                BulkAction(PowerAction.START, Icons.Outlined.PlayArrow, bulk, viewModel::requestBulk)
                BulkAction(PowerAction.SHUTDOWN, Icons.Outlined.PowerSettingsNew, bulk, viewModel::requestBulk)
                BulkAction(PowerAction.STOP, Icons.Outlined.StopCircle, bulk, viewModel::requestBulk)
            }
        },
    ) { inner ->
        val list = @Composable { modifier: Modifier ->
            ResourceList(
                state = state,
                padding = contentPadding(inner),
                modifier = modifier,
                onQuery = viewModel::setQuery,
                onFilter = viewModel::setFilter,
                onRunningOnly = viewModel::setRunningOnly,
                selectedKey = if (wide) selected else null,
                checkedKeys = bulk.selected.keys,
                busyText = bulk.busy?.let { bulk.targets(it).size.let { n -> pluralStringResource(R.plurals.bulk_busy, n, n) } },
                onLongClick = viewModel::toggleSelected,
                onClick = { item ->
                    val r = item.resource
                    val type = GuestType.fromApi(r.type)
                    if (bulk.active) {
                        // While selecting, a tap adds or removes the guest.
                        viewModel.toggleSelected(item)
                    } else if (type != null && r.vmid != null && r.node != null) {
                        val ref = GuestRef(item.serverId, r.node!!, type, r.vmid!!)
                        if (wide) {
                            selected = refKey(ref)
                        } else {
                            onGuest(ref)
                        }
                    } else if (r.resourceType == ResourceType.NODE && r.node != null) {
                        onNode(item.serverId, r.node!!)
                    } else if (r.holdsBackups && r.node != null && r.storage != null) {
                        onStorage(item.serverId, r.node!!, r.storage!!)
                    }
                },
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                list(Modifier.weight(0.45f))
                Box(
                    Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .padding(top = inner.calculateTopPadding()),
                ) {
                    val ref = selected?.let(::parseRefKey)
                    if (ref == null) {
                        EmptyState(
                            icon = Icons.Outlined.TouchApp,
                            title = stringResource(R.string.resources_pick_title),
                            body = stringResource(R.string.resources_pick_body),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        GuestDetail(ref = ref, onTask = onTask, onTool = onTool, onClose = { selected = null })
                    }
                }
            }
        } else {
            list(Modifier.fillMaxSize())
        }
    }

    BulkConfirm(bulk, onConfirm = viewModel::runBulk, onDismiss = viewModel::dismissBulk)
}

@Composable
private fun BulkAction(action: PowerAction, icon: ImageVector, bulk: BulkState, onRequest: (PowerAction) -> Unit) {
    IconButton(onClick = { onRequest(action) }, enabled = bulk.busy == null && bulk.targets(action).isNotEmpty()) {
        Icon(icon, contentDescription = actionLabel(action))
    }
}

@Composable
private fun BulkConfirm(bulk: BulkState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val action = bulk.confirm ?: return
    val targets = bulk.targets(action)
    val names = targets.joinToString("\n") { "• " + (it.resource.name ?: it.resource.vmid.toString()) }
    ConfirmActionDialog(
        title = stringResource(
            when (action) {
                PowerAction.START -> R.string.bulk_confirm_start
                PowerAction.SHUTDOWN -> R.string.bulk_confirm_shutdown
                else -> R.string.bulk_confirm_stop
            },
        ),
        message = if (action == PowerAction.STOP) stringResource(R.string.bulk_confirm_stop_body, names) else names,
        confirmLabel = actionLabel(action),
        danger = DangerLevel.CONFIRM,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private fun refKey(ref: GuestRef) = listOf(ref.serverId, ref.node, ref.type.apiName, ref.vmid).joinToString("|")

private fun parseRefKey(key: String): GuestRef? {
    val p = key.split('|')
    if (p.size != 4) return null
    val type = GuestType.fromApi(p[2]) ?: return null
    return GuestRef(p[0], p[1], type, p[3].toIntOrNull() ?: return null)
}

@Composable
private fun ResourceList(
    state: ResourcesState,
    padding: PaddingValues,
    modifier: Modifier,
    onQuery: (String) -> Unit,
    onFilter: (ResourceFilter) -> Unit,
    onRunningOnly: (Boolean) -> Unit,
    selectedKey: String?,
    checkedKeys: Set<String>,
    busyText: String?,
    onLongClick: (ResourceItem) -> Unit,
    onClick: (ResourceItem) -> Unit,
) {
    LazyColumn(modifier, contentPadding = padding) {
        busyText?.let { text -> item { Box(Modifier.padding(bottom = 12.dp)) { BusyCard(text) } } }
        item {
            GlassTextField(
                value = state.query,
                onValueChange = onQuery,
                label = stringResource(R.string.resources_search),
                placeholder = stringResource(R.string.resources_search_hint),
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                } else {
                    null
                },
            )
        }
        item { Filters(state, onFilter, onRunningOnly, Modifier.padding(vertical = 8.dp)) }
        if (state.loaded && state.items.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.resources_empty_title),
                    body = stringResource(R.string.resources_empty_body),
                )
            }
        }
        // The rows share one surface: rounded at the ends, a hairline between two rows.
        itemsIndexed(state.items, key = { _, item -> item.key }) { i, item ->
            val r = item.resource
            val type = GuestType.fromApi(r.type)
            val isSelected = selectedKey != null && type != null && r.vmid != null && r.node != null &&
                selectedKey == refKey(GuestRef(item.serverId, r.node!!, type, r.vmid!!))
            ResourceRow(
                item,
                state.showServerNames,
                first = i == 0,
                last = i == state.items.lastIndex,
                selected = isSelected,
                checked = item.key in checkedKeys,
                onLongClick = if (item.selectable) {
                    { onLongClick(item) }
                } else {
                    null
                },
            ) { onClick(item) }
        }
    }
}

@Composable
private fun Filters(state: ResourcesState, onFilter: (ResourceFilter) -> Unit, onRunningOnly: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResourceFilter.entries.forEach { f ->
            FilterPill(filterLabel(f), state.filter == f, Modifier.selectable(selected = state.filter == f, role = Role.Tab) { onFilter(f) })
        }
        FilterPill(
            stringResource(R.string.resources_running_only),
            state.runningOnly,
            Modifier.toggleable(value = state.runningOnly, role = Role.Checkbox) { onRunningOnly(it) },
        )
    }
}

/** A filter as plain text; the chosen one sits on a soft pill. [modifier] makes it selectable. */
@Composable
private fun FilterPill(label: String, selected: Boolean, modifier: Modifier) {
    val c = AnProxTheme.colors
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) c.textPrimary else c.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.glassStrong else Color.Transparent)
            .then(modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun filterLabel(f: ResourceFilter): String = stringResource(
    when (f) {
        ResourceFilter.ALL -> R.string.filter_all
        ResourceFilter.VMS -> R.string.filter_vms
        ResourceFilter.CONTAINERS -> R.string.filter_containers
        ResourceFilter.NODES -> R.string.filter_nodes
        ResourceFilter.STORAGE -> R.string.filter_storage
    },
)

/**
 * One row of the list: a status dot, the name, what it is and where, and one number on the right
 * (CPU for a running guest or node, how full a storage is). Anything that isn't running says its
 * state there instead.
 */
@Composable
private fun ResourceRow(
    item: ResourceItem,
    showServer: Boolean,
    first: Boolean,
    last: Boolean,
    selected: Boolean,
    checked: Boolean,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val c = AnProxTheme.colors
    val r = item.resource
    val appearance = statusAppearance(r.status)
    val clickable = r.isGuest || r.resourceType == ResourceType.NODE || r.holdsBackups
    val corner = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp,
        topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp,
        bottomEnd = if (last) corner else 0.dp,
    )
    val number = when {
        r.template -> null
        r.resourceType == ResourceType.STORAGE && r.status == "available" -> Formatters.percent(r.diskFraction)
        r.isRunning || (r.resourceType == ResourceType.NODE && r.status == "online") -> Formatters.percent(r.cpuFraction)
        else -> null
    }
    GlassCard(
        onClick = if (clickable) onClick else null,
        onLongClick = onLongClick,
        shape = shape,
        strong = selected || checked,
        contentPadding = PaddingValues(0.dp),
    ) {
        if (!first) HorizontalDivider(Modifier.padding(start = 40.dp, end = 16.dp), thickness = 1.dp, color = c.hairline)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
                if (checked) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.resources_checked), tint = c.accent, modifier = Modifier.size(18.dp))
                } else {
                    // The state is in words on the right unless a number stands there; then the dot says it.
                    val label = appearance.label
                    StatusDot(appearance.tone, if (number != null) Modifier.semantics { contentDescription = label } else Modifier)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.name ?: r.storage ?: r.node ?: r.id,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (r.template) c.textSecondary else c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle(item, showServer),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            when {
                r.template -> Text(stringResource(R.string.resources_template), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                number != null -> Text(number, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                else -> Text(
                    appearance.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (appearance.tone == Tone.DANGER) c.danger else c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun subtitle(item: ResourceItem, showServer: Boolean): String {
    val r = item.resource
    val parts = mutableListOf<String>()
    when (r.resourceType) {
        ResourceType.QEMU, ResourceType.LXC -> {
            val type = stringResource(if (r.resourceType == ResourceType.QEMU) R.string.guest_type_vm else R.string.guest_type_ct)
            parts += "$type ${r.vmid ?: ""}".trim()
            r.node?.let { parts += it }
        }
        ResourceType.NODE -> {
            parts += stringResource(R.string.node_subtitle)
            r.maxcpu?.let { parts += stringResource(R.string.resources_cores, it.toInt()) }
        }
        ResourceType.STORAGE -> {
            parts += if (r.shared) stringResource(R.string.resources_shared) else r.node.orEmpty()
            r.plugintype?.let { parts += it }
        }
        else -> Unit
    }
    if (showServer) parts += item.serverName
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

/** Storages that accept backups open their backup list. */
private val ClusterResource.holdsBackups: Boolean
    get() = resourceType == ResourceType.STORAGE && content?.split(',')?.contains("backup") == true
