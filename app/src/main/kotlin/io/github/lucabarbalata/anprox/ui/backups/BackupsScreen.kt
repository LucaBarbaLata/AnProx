package io.github.lucabarbalata.anprox.ui.backups

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.security.LocalDangerousActionGuard
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Backups of one guest (with **Back up now**) or of one storage, each restorable as a new guest or
 * over the existing one (questionnaire Q20).
 */
@Composable
fun BackupsScreen(
    scope: BackupScope,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val key = when (scope) {
        is BackupScope.Guest -> "backups-${scope.ref.serverId}-${scope.ref.node}-${scope.ref.type.apiName}-${scope.ref.vmid}"
        is BackupScope.Storage -> "backups-${scope.serverId}-${scope.node}-${scope.storage}"
    }
    val viewModel: BackupsViewModel = koinViewModel(key = key) { parametersOf(scope) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, scope.serverId, scope.node, onTask, viewModel::consumeResult)

    val (title, subtitle) = when (scope) {
        is BackupScope.Guest -> stringResource(R.string.backups_title) to "${scope.name} · ${typeLabel(scope.ref.type)} ${scope.ref.vmid}"
        is BackupScope.Storage -> scope.storage to stringResource(R.string.storage_subtitle, scope.node)
    }
    AnProxScaffold(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scope is BackupScope.Guest) {
                item {
                    AnProxButton(
                        text = stringResource(R.string.backup_now),
                        onClick = viewModel::openBackup,
                        icon = Icons.Outlined.Backup,
                        enabled = state.busy == null,
                        loading = state.busy == BackupOp.BACKUP,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.busy?.let { op -> item { BusyCard(op) } }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::refresh) }
            }
            if (state.failedStorages.isNotEmpty()) {
                item { ErrorBanner(stringResource(R.string.backups_storage_failed, state.failedStorages.joinToString()), tone = Tone.WARNING) }
            }
            when {
                state.loading && state.backups.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                state.backups.isEmpty() && state.error == null -> item {
                    EmptyState(
                        Icons.Outlined.Backup,
                        stringResource(R.string.backups_empty_title),
                        stringResource(if (scope is BackupScope.Guest) R.string.backups_empty_body_guest else R.string.backups_empty_body_storage),
                    )
                }
                else -> items(state.backups, key = { it.volid }) { item ->
                    BackupRow(item, scope, enabled = state.busy == null, onRestore = { viewModel.openRestore(item) })
                }
            }
        }
    }

    if (state.showBackupDialog && scope is BackupScope.Guest) {
        BackupNowDialog(state.storages, onBackup = viewModel::backupNow, onDismiss = viewModel::dismissBackup)
    }

    state.restoring?.let { item ->
        RestoreDialog(
            item = item,
            nextVmid = state.nextVmid,
            replaceLabel = replaceLabel(scope, item),
            onAsNew = { vmid -> viewModel.restoreAsNew(item, vmid) },
            onReplace = { viewModel.requestReplace(item) },
            onDismiss = viewModel::dismissRestore,
        )
    }

    val replacing = state.confirmReplace
    val target = replacing?.let { replaceLabel(scope, it) }
    if (replacing != null && target != null) {
        val guard = LocalDangerousActionGuard.current
        val reason = stringResource(R.string.lock_reason_restore, target)
        ConfirmActionDialog(
            title = stringResource(R.string.backup_replace_confirm_title, target),
            message = stringResource(R.string.backup_replace_confirm_message),
            confirmLabel = stringResource(R.string.backup_restore_start),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            // Type the guest's name, or its id when browsing a storage.
            confirmText = when (scope) {
                is BackupScope.Guest -> scope.name
                is BackupScope.Storage -> viewModel.replaceTarget(replacing)?.toString()
            },
            onConfirm = { guard.confirm(reason, viewModel::confirmReplace) },
            onDismiss = viewModel::dismissReplace,
        )
    }
}

@Composable
private fun typeLabel(type: GuestType?): String =
    stringResource(if (type == GuestType.LXC) R.string.guest_type_ct else R.string.guest_type_vm)

/** "web (ID 100)" for a guest's own backups, "VM 100" on a storage; null if the backup names no guest. */
@Composable
private fun replaceLabel(scope: BackupScope, item: StorageContentItem): String? = when (scope) {
    is BackupScope.Guest -> stringResource(R.string.backup_target_guest, scope.name, scope.ref.vmid)
    is BackupScope.Storage -> item.vmid?.let { "${typeLabel(item.guestType)} $it" }
}

/** "14 Oct 2026, 02:00", following the device's date and 12/24-hour settings. */
@Composable
private fun dateTime(epochSeconds: Long): String {
    val context = LocalContext.current
    return remember(epochSeconds) {
        DateUtils.formatDateTime(
            context,
            epochSeconds * 1000,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
}

@Composable
private fun BackupRow(item: StorageContentItem, scope: BackupScope, enabled: Boolean, onRestore: () -> Unit) {
    val c = AnProxTheme.colors
    val created = item.ctime?.let { dateTime(it) }
    GlassCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = when (scope) {
                    is BackupScope.Guest -> created ?: item.volid
                    is BackupScope.Storage -> listOfNotNull("${typeLabel(item.guestType)} ${item.vmid ?: "?"}", item.notes?.takeIf { it.isNotBlank() }).joinToString(" · ")
                }
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(
                    if (scope is BackupScope.Storage) created else item.ctime?.let { relativeTime(it * 1000) },
                    if (scope is BackupScope.Guest) item.storage else null,
                    item.size?.let { Formatters.bytes(it) },
                ).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                if (scope is BackupScope.Guest) {
                    item.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.isProtected || item.verification != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.isProtected) TagChip(stringResource(R.string.backup_protected))
                        item.verification?.let { v ->
                            StatusPill(
                                stringResource(if (v.ok) R.string.backup_verified else R.string.backup_verify_failed),
                                if (v.ok) Tone.SUCCESS else Tone.DANGER,
                            )
                        }
                    }
                }
            }
            if (item.guestType != null) {
                TextButton(onClick = onRestore, enabled = enabled) { Text(stringResource(R.string.backup_restore)) }
            }
        }
    }
}

@Composable
private fun BusyCard(op: BackupOp) {
    val c = AnProxTheme.colors
    GlassCard {
        Text(
            stringResource(if (op == BackupOp.BACKUP) R.string.backups_busy_backup else R.string.backups_busy_restore),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent)
    }
}

@Composable
private fun BackupNowDialog(
    storages: List<NodeStorage>?,
    onBackup: (NodeStorage, BackupMode, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    var storageId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(BackupMode.SNAPSHOT) }
    // Same default as the Proxmox web UI.
    var notes by rememberSaveable { mutableStateOf("{{guestname}}") }
    val selected = storages?.firstOrNull { it.storage == storageId } ?: storages?.firstOrNull()
    // SegmentedPicker's label lambda isn't composable: resolve the strings first.
    val modeLabels = mapOf(
        BackupMode.SNAPSHOT to stringResource(R.string.backup_mode_snapshot),
        BackupMode.SUSPEND to stringResource(R.string.backup_mode_suspend),
        BackupMode.STOP to stringResource(R.string.backup_mode_stop),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.backup_now_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.backup_storage), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                when {
                    storages == null -> CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
                    storages.isEmpty() -> Text(stringResource(R.string.backup_no_storage), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    else -> storages.forEach { s ->
                        val free = Formatters.bytes(s.avail)
                        RadioRow(
                            selected = s == selected,
                            title = s.storage,
                            body = if (s.isPbs) stringResource(R.string.backup_storage_pbs, free) else stringResource(R.string.backup_storage_other, s.type, free),
                        ) { storageId = s.storage }
                    }
                }
                Text(stringResource(R.string.backup_mode), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                SegmentedPicker(BackupMode.entries, mode, { modeLabels.getValue(it) }, { mode = it })
                Text(
                    stringResource(
                        when (mode) {
                            BackupMode.SNAPSHOT -> R.string.backup_mode_snapshot_body
                            BackupMode.SUSPEND -> R.string.backup_mode_suspend_body
                            BackupMode.STOP -> R.string.backup_mode_stop_body
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                GlassTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = stringResource(R.string.backup_notes),
                    supportingText = stringResource(R.string.backup_notes_help),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onBackup(it, mode, notes) } }, enabled = selected != null) {
                Text(stringResource(R.string.backup_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RestoreDialog(
    item: StorageContentItem,
    nextVmid: Int?,
    replaceLabel: String?,
    onAsNew: (Int) -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    var replace by rememberSaveable { mutableStateOf(false) }
    // Filled in once the next free id has loaded.
    var idText by rememberSaveable(nextVmid) { mutableStateOf(nextVmid?.toString().orEmpty()) }
    val id = idText.toIntOrNull()
    val idValid = id != null && id in 100..999_999_999
    val kind = stringResource(if (item.guestType == GuestType.LXC) R.string.backup_type_ct else R.string.backup_type_vm)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(item.ctime?.let { dateTime(it) }, item.storage).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                RadioRow(
                    selected = !replace,
                    title = stringResource(R.string.backup_restore_new, kind),
                    body = stringResource(R.string.backup_restore_new_body),
                ) { replace = false }
                if (!replace) {
                    GlassTextField(
                        value = idText,
                        onValueChange = { v -> idText = v.filter(Char::isDigit) },
                        label = stringResource(R.string.backup_restore_id),
                        isError = idText.isNotEmpty() && !idValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                if (replaceLabel != null) {
                    RadioRow(
                        selected = replace,
                        title = stringResource(R.string.backup_restore_replace, replaceLabel),
                        body = stringResource(R.string.backup_restore_replace_body),
                    ) { replace = true }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (replace) onReplace() else id?.let(onAsNew) }, enabled = replace || idValid) {
                Text(stringResource(R.string.backup_restore_start), color = if (replace) c.danger else Color.Unspecified)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
