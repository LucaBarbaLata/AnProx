package io.github.lucabarbalata.anprox.ui.snapshots

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.SnapshotName
import io.github.lucabarbalata.anprox.model.pve.SnapshotRow
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Snapshots of a VM or container as a tree, with take / roll back / delete (questionnaire Q18). */
@Composable
fun SnapshotsScreen(
    ref: GuestRef,
    guestName: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val key = "snapshots-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    val viewModel: SnapshotsViewModel = koinViewModel(key = key) { parametersOf(ref, guestName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask, viewModel::consumeResult)

    val typeLabel = stringResource(if (ref.type == GuestType.QEMU) R.string.guest_type_vm else R.string.guest_type_ct)
    AnProxScaffold(
        title = stringResource(R.string.snapshots_title),
        subtitle = "$guestName · $typeLabel ${ref.vmid}",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AnProxButton(
                    text = stringResource(R.string.snapshots_take),
                    onClick = viewModel::openCreate,
                    icon = Icons.Outlined.PhotoCamera,
                    enabled = state.busy == null && !state.loading && state.error == null,
                    loading = state.busy?.action == SnapshotAction.CREATE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.busy?.let { op -> item { BusyCard(op) } }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::refresh) }
            }
            when {
                state.loading && state.rows.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                state.isEmpty -> item {
                    EmptyState(
                        Icons.Outlined.PhotoCamera,
                        stringResource(R.string.snapshots_empty_title),
                        stringResource(R.string.snapshots_empty_body),
                    )
                }
                state.rows.isNotEmpty() -> item {
                    GlassCard(contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
                        state.rows.forEach { row ->
                            SnapshotItem(
                                row = row,
                                enabled = state.busy == null,
                                onRollback = { viewModel.requestRollback(row.snapshot.name) },
                                onDelete = { viewModel.requestDelete(row.snapshot.name) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.showCreate) {
        CreateSnapshotDialog(
            suggested = remember { viewModel.suggestedName() },
            isVm = ref.type == GuestType.QEMU,
            canSaveRam = state.canSaveRam,
            problemOf = viewModel::nameProblem,
            onCreate = viewModel::create,
            onDismiss = viewModel::dismissCreate,
        )
    }

    state.confirm?.let { op ->
        val rollback = op.action == SnapshotAction.ROLLBACK
        ConfirmActionDialog(
            title = stringResource(if (rollback) R.string.snapshot_rollback_title else R.string.snapshot_delete_title, op.name),
            message = if (rollback) stringResource(R.string.snapshot_rollback_message, guestName) else stringResource(R.string.snapshot_delete_message),
            confirmLabel = stringResource(if (rollback) R.string.snapshot_rollback else R.string.action_delete),
            danger = op.action.danger,
            confirmText = if (op.action.danger == DangerLevel.TYPE_TO_CONFIRM) op.name else null,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

@Composable
private fun SnapshotItem(row: SnapshotRow, enabled: Boolean, onRollback: () -> Unit, onDelete: () -> Unit) {
    val c = AnProxTheme.colors
    val s = row.snapshot
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (minOf(row.depth, MAX_INDENT) * 18).dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tree marker: the current state is highlighted, saved snapshots are muted.
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (s.isCurrent) c.accent else c.textTertiary),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (s.isCurrent) {
                Text(stringResource(R.string.snapshot_current), style = MaterialTheme.typography.titleSmall, color = c.accent)
                Text(stringResource(R.string.snapshot_current_body), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (s.vmstate) {
                        Spacer(Modifier.width(8.dp))
                        TagChip(stringResource(R.string.snapshot_ram))
                    }
                }
                s.snaptime?.let { Text(snapshotTime(it), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                s.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!s.isCurrent) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.snapshot_options, s.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.snapshot_rollback)) },
                        leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        onClick = { menu = false; onRollback() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = c.danger) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = c.danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** "14 Oct 2026, 15:30 · 2 days ago", following the device's date and 12/24-hour settings. */
@Composable
private fun snapshotTime(epochSeconds: Long): String {
    val context = LocalContext.current
    val millis = epochSeconds * 1000
    val absolute = remember(millis) {
        DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    return "$absolute · ${relativeTime(millis)}"
}

@Composable
private fun BusyCard(op: SnapshotOp) {
    val c = AnProxTheme.colors
    val text = when (op.action) {
        SnapshotAction.CREATE -> R.string.snapshot_busy_create
        SnapshotAction.ROLLBACK -> R.string.snapshot_busy_rollback
        SnapshotAction.DELETE -> R.string.snapshot_busy_delete
    }
    GlassCard {
        Text(stringResource(text, op.name), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent)
    }
}

@Composable
private fun CreateSnapshotDialog(
    suggested: String,
    isVm: Boolean,
    canSaveRam: Boolean,
    problemOf: (String) -> SnapshotName.Problem?,
    onCreate: (name: String, description: String, includeRam: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    var name by rememberSaveable { mutableStateOf(suggested) }
    var description by rememberSaveable { mutableStateOf("") }
    // Same default as the Proxmox web UI: include the RAM of a running VM.
    var includeRam by rememberSaveable { mutableStateOf(canSaveRam) }
    val problem = problemOf(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.snapshot_take_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.snapshot_name),
                    supportingText = stringResource(problem?.messageRes() ?: R.string.snapshot_name_help),
                    isError = problem != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                )
                GlassTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.snapshot_description),
                    singleLine = false,
                    minLines = 2,
                )
                if (isVm) {
                    if (canSaveRam) {
                        SwitchRow(
                            title = stringResource(R.string.snapshot_include_ram),
                            description = stringResource(R.string.snapshot_include_ram_body),
                            checked = includeRam,
                            onCheckedChange = { includeRam = it },
                        )
                    } else {
                        Text(stringResource(R.string.snapshot_include_ram_stopped), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, description, includeRam) }, enabled = problem == null) {
                Text(stringResource(R.string.snapshots_take))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun SnapshotName.Problem.messageRes(): Int = when (this) {
    SnapshotName.Problem.EMPTY -> R.string.snapshot_name_error_empty
    SnapshotName.Problem.FORMAT -> R.string.snapshot_name_error_format
    SnapshotName.Problem.TOO_LONG -> R.string.snapshot_name_error_too_long
    SnapshotName.Problem.RESERVED -> R.string.snapshot_name_error_reserved
    SnapshotName.Problem.DUPLICATE -> R.string.snapshot_name_error_duplicate
}

/** Deeper branches stop indenting so names keep enough room on phones. */
private const val MAX_INDENT = 6
