package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.StorageKind
import io.github.lucabarbalata.anprox.model.pve.StorageSetupForm
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Datacenter → Storage → add a storage ([storageId] null) or edit one. */
@Composable
fun StorageEditorScreen(serverId: String, storageId: String?, onDone: () -> Unit) {
    val viewModel: StorageEditorViewModel = koinViewModel(key = "storage-editor-$serverId-${storageId.orEmpty()}") { parametersOf(serverId, storageId.orEmpty()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val form = state.form
    val retentionLabels = mapOf(
        RetentionChoice.STORAGE to stringResource(R.string.backupjob_retention_storage),
        RetentionChoice.CUSTOM to stringResource(R.string.backupjob_retention_custom),
        RetentionChoice.KEEP_ALL to stringResource(R.string.backupjob_retention_all),
    )
    val kindLabels = StorageKind.entries.associateWith { kindLabel(it) }
    val fixed = !state.creating

    AnProxScaffold(
        title = if (state.creating) stringResource(R.string.storage_add) else storageId.orEmpty(),
        subtitle = if (state.creating) null else state.existing?.type,
        onBack = onDone,
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                return@LazyColumn
            }
            if (state.unsupported) {
                item { GlassCard { FormHint(stringResource(R.string.storage_unsupported, state.existing?.type.orEmpty())) } }
            } else {
                if (state.creating) {
                    item {
                        GlassCard {
                            FormLabel(stringResource(R.string.storage_type))
                            Spacer(Modifier.height(8.dp))
                            ChoiceChips(StorageKind.entries, form.kind, { kindLabels.getValue(it) }, viewModel::setKind)
                            Spacer(Modifier.height(8.dp))
                            GlassTextField(
                                value = form.id,
                                onValueChange = { v -> viewModel.edit { copy(id = v) } },
                                label = stringResource(R.string.storage_id),
                                supportingText = stringResource(R.string.storage_id_hint),
                                isError = form.id.isNotEmpty() && !StorageSetupForm.STORAGE_ID.matches(form.id.trim()),
                            )
                        }
                    }
                }
                item { ConnectionCard(state, viewModel, fixed) }
                if (form.kind.contents.size > 1) {
                    item {
                        GlassCard {
                            FormLabel(stringResource(R.string.storage_content))
                            Spacer(Modifier.height(4.dp))
                            form.kind.contents.forEach { type ->
                                val label = ContentType.fromApi(type)?.let { contentLabel(it) } ?: type
                                CheckRow(label, type in form.content) { viewModel.toggleContent(type) }
                            }
                        }
                    }
                }
                item {
                    GlassCard {
                        FormLabel(stringResource(R.string.storage_nodes))
                        SwitchRow(stringResource(R.string.storage_all_nodes_switch), form.nodes.isEmpty(), viewModel::allNodes)
                        if (form.nodes.isNotEmpty()) {
                            (state.onlineNodes + form.nodes).distinct().sorted().forEach { node ->
                                CheckRow(node, node in form.nodes) { viewModel.toggleNode(node) }
                            }
                        }
                        SwitchRow(stringResource(R.string.storage_enabled), form.enabled, { on -> viewModel.edit { copy(enabled = on) } })
                        if (form.kind.canBeShared) {
                            SwitchRow(
                                stringResource(R.string.storage_shared_switch),
                                form.shared,
                                { on -> viewModel.edit { copy(shared = on) } },
                                description = stringResource(R.string.storage_shared_hint),
                            )
                        }
                    }
                }
                if ("backup" in form.content) {
                    item { RetentionCard(form.retention, retentionLabels, inherit = false) { r -> viewModel.edit { copy(retention = r) } } }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.problem?.let { FormHint(problemText(it), error = true) }
                        state.saveError?.let { FormHint(it.asString(), error = true) }
                        AnProxButton(
                            stringResource(if (state.creating) R.string.storage_create else R.string.action_save),
                            onClick = viewModel::save,
                            icon = Icons.Outlined.Save,
                            enabled = state.canSave,
                            loading = state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (!state.creating) {
                item {
                    AnProxButton(
                        stringResource(R.string.storage_remove),
                        onClick = viewModel::requestRemove,
                        style = ButtonStyle.DANGER,
                        icon = Icons.Outlined.Delete,
                        enabled = !state.busy && state.existing != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.unsupported) state.saveError?.let { item { FormHint(it.asString(), error = true) } }
            }
        }
    }

    if (state.confirmRemove) {
        ConfirmActionDialog(
            title = stringResource(R.string.storage_remove_title, storageId.orEmpty()),
            message = stringResource(R.string.storage_remove_body),
            confirmLabel = stringResource(R.string.storage_remove),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            onConfirm = viewModel::remove,
            onDismiss = viewModel::dismissRemove,
            confirmText = storageId,
        )
    }
}

/** Where the storage is: path, server and share, volume group or pool, with scans to fill them in. */
@Composable
private fun ConnectionCard(state: StorageEditorState, viewModel: StorageEditorViewModel, fixed: Boolean) {
    val form = state.form
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (form.kind) {
                StorageKind.DIR -> Field(form.path, stringResource(R.string.storage_path), fixed, hint = stringResource(R.string.storage_path_hint)) { v -> viewModel.edit { copy(path = v) } }
                StorageKind.NFS -> {
                    Field(form.server, stringResource(R.string.storage_server), fixed, keyboard = KeyboardType.Uri) { v -> viewModel.edit { copy(server = v) } }
                    Field(form.export, stringResource(R.string.storage_export), fixed) { v -> viewModel.edit { copy(export = v) } }
                    if (!fixed) ScanRow(state, viewModel, ScanField.EXPORT, R.string.storage_scan_exports, form.server.isNotBlank())
                }
                StorageKind.CIFS -> {
                    Field(form.server, stringResource(R.string.storage_server), fixed, keyboard = KeyboardType.Uri) { v -> viewModel.edit { copy(server = v) } }
                    Field(form.username, stringResource(R.string.storage_username), false) { v -> viewModel.edit { copy(username = v) } }
                    Password(form.password, fixed) { v -> viewModel.edit { copy(password = v) } }
                    Field(form.domain, stringResource(R.string.storage_domain), false) { v -> viewModel.edit { copy(domain = v) } }
                    Field(form.share, stringResource(R.string.storage_share), fixed) { v -> viewModel.edit { copy(share = v) } }
                    if (!fixed) {
                        ScanRow(state, viewModel, ScanField.SHARE, R.string.storage_scan_shares, form.server.isNotBlank())
                        Field(form.subdir, stringResource(R.string.storage_subdir), false) { v -> viewModel.edit { copy(subdir = v) } }
                    }
                }
                StorageKind.PBS -> {
                    Field(form.server, stringResource(R.string.storage_server), fixed, keyboard = KeyboardType.Uri) { v -> viewModel.edit { copy(server = v) } }
                    Field(form.username, stringResource(R.string.storage_username), false, hint = stringResource(R.string.storage_pbs_user_hint)) { v -> viewModel.edit { copy(username = v) } }
                    Password(form.password, fixed) { v -> viewModel.edit { copy(password = v) } }
                    Field(form.fingerprint, stringResource(R.string.storage_fingerprint), false, hint = stringResource(R.string.storage_fingerprint_hint)) { v -> viewModel.edit { copy(fingerprint = v) } }
                    Field(form.datastore, stringResource(R.string.storage_datastore), fixed) { v -> viewModel.edit { copy(datastore = v) } }
                    if (!fixed) ScanRow(state, viewModel, ScanField.DATASTORE, R.string.storage_scan_datastores, form.server.isNotBlank() && form.username.isNotBlank() && form.password.isNotEmpty())
                    Field(form.namespace, stringResource(R.string.storage_namespace), false) { v -> viewModel.edit { copy(namespace = v) } }
                }
                StorageKind.LVM -> {
                    Field(form.vgname, stringResource(R.string.storage_vg), fixed) { v -> viewModel.edit { copy(vgname = v) } }
                    if (!fixed) ScanRow(state, viewModel, ScanField.VOLUME_GROUP, R.string.storage_scan_vgs, true)
                }
                StorageKind.LVMTHIN -> {
                    Field(form.vgname, stringResource(R.string.storage_vg), fixed) { v -> viewModel.edit { copy(vgname = v) } }
                    if (!fixed) ScanRow(state, viewModel, ScanField.VOLUME_GROUP, R.string.storage_scan_vgs, true)
                    Field(form.thinpool, stringResource(R.string.storage_thinpool), fixed) { v -> viewModel.edit { copy(thinpool = v) } }
                    if (!fixed) ScanRow(state, viewModel, ScanField.THIN_POOL, R.string.storage_scan_thinpools, form.vgname.isNotBlank())
                }
                StorageKind.ZFSPOOL -> {
                    Field(form.pool, stringResource(R.string.storage_zfs_pool), fixed) { v -> viewModel.edit { copy(pool = v) } }
                    if (!fixed) ScanRow(state, viewModel, ScanField.POOL, R.string.storage_scan_pools, true)
                    SwitchRow(stringResource(R.string.storage_sparse), form.sparse, { on -> viewModel.edit { copy(sparse = on) } }, description = stringResource(R.string.storage_sparse_hint))
                }
            }
            if (fixed) FormHint(stringResource(R.string.storage_fixed_hint))
        }
    }
}

@Composable
private fun Field(value: String, label: String, readOnly: Boolean, hint: String? = null, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    GlassTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        supportingText = hint,
        enabled = !readOnly,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

@Composable
private fun Password(value: String, editing: Boolean, onChange: (String) -> Unit) {
    GlassTextField(
        value = value,
        onValueChange = onChange,
        label = stringResource(R.string.storage_password),
        supportingText = if (editing) stringResource(R.string.storage_password_keep) else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

/** A "find" button for one field, and what it found as chips to pick from. */
@Composable
private fun ScanRow(state: StorageEditorState, viewModel: StorageEditorViewModel, field: ScanField, label: Int, ready: Boolean) {
    val node = state.scanNode
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { viewModel.scan(field) }, enabled = ready && node != null && state.scanning == null) {
            if (state.scanning == field) {
                CircularProgressIndicator(Modifier.size(16.dp), color = AnProxTheme.colors.accent, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(label))
        }
        node?.let { Text(stringResource(R.string.storage_scan_on, it), style = MaterialTheme.typography.labelSmall, color = AnProxTheme.colors.textTertiary) }
    }
    val scan = state.scan
    if (scan != null && scan.field == field) {
        if (scan.options.isEmpty()) {
            FormHint(stringResource(R.string.storage_scan_nothing))
        } else {
            val labels = scan.options.associate { (value, description) -> value to (description?.let { "$value · $it" } ?: value) }
            ChoiceChips(scan.options.map { it.first }, "", { labels.getValue(it) }) { v -> viewModel.pick(field, v) }
        }
    }
    val failure = state.scanError
    if (failure != null && failure.field == field) FormHint(failure.message.asString(), error = true)
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AnProxTheme.colors.textPrimary)
    }
}

@Composable
private fun kindLabel(kind: StorageKind): String = stringResource(
    when (kind) {
        StorageKind.DIR -> R.string.storage_kind_dir
        StorageKind.NFS -> R.string.storage_kind_nfs
        StorageKind.CIFS -> R.string.storage_kind_cifs
        StorageKind.PBS -> R.string.storage_kind_pbs
        StorageKind.LVM -> R.string.storage_kind_lvm
        StorageKind.LVMTHIN -> R.string.storage_kind_lvmthin
        StorageKind.ZFSPOOL -> R.string.storage_kind_zfs
    },
)

@Composable
private fun problemText(problem: StorageSetupForm.Problem): String = stringResource(
    when (problem) {
        StorageSetupForm.Problem.ID -> R.string.storage_problem_id
        StorageSetupForm.Problem.CONTENT -> R.string.storage_problem_content
        StorageSetupForm.Problem.PATH -> R.string.storage_problem_path
        StorageSetupForm.Problem.SERVER -> R.string.storage_problem_server
        StorageSetupForm.Problem.EXPORT -> R.string.storage_problem_export
        StorageSetupForm.Problem.SHARE -> R.string.storage_problem_share
        StorageSetupForm.Problem.CREDENTIALS -> R.string.storage_problem_credentials
        StorageSetupForm.Problem.DATASTORE -> R.string.storage_problem_datastore
        StorageSetupForm.Problem.FINGERPRINT -> R.string.storage_problem_fingerprint
        StorageSetupForm.Problem.VOLUME_GROUP -> R.string.storage_problem_vg
        StorageSetupForm.Problem.THIN_POOL -> R.string.storage_problem_thinpool
        StorageSetupForm.Problem.POOL -> R.string.storage_problem_pool
        StorageSetupForm.Problem.RETENTION -> R.string.backupjob_problem_retention
    },
)
