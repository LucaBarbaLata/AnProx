package io.github.lucabarbalata.anprox.ui.access

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.AclEntry
import io.github.lucabarbalata.anprox.model.pve.AclForm
import io.github.lucabarbalata.anprox.model.pve.AclSubject
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.PveRole
import io.github.lucabarbalata.anprox.model.pve.privilegeGroups
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.CheckRow
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** A tappable card row with a title, an optional second line and a chevron. */
@Composable
private fun RowCard(title: String, body: String?, extra: String? = null, pill: Pair<String, Tone>? = null, onClick: () -> Unit) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
                    pill?.let { (text, tone) ->
                        Spacer(Modifier.width(8.dp))
                        StatusPill(text, tone)
                    }
                }
                body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                extra?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.textSecondary) }
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textTertiary)
        }
    }
}

/** Datacenter → Permissions → Groups. */
@Composable
fun GroupsScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: GroupsViewModel = koinViewModel(key = "groups-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", { _, _, _ -> }, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.access_groups),
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
                    stringResource(R.string.access_group_add),
                    onClick = viewModel::startCreate,
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy && state.error == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { FormHint(stringResource(R.string.access_groups_hint)) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            when {
                state.loading && state.groups.isEmpty() && state.error == null -> item { Loading() }
                !state.loading && state.error == null && state.groups.isEmpty() -> item {
                    EmptyState(Icons.Outlined.Group, stringResource(R.string.access_groups_none), stringResource(R.string.access_groups_hint))
                }
                else -> items(state.groups, key = { it.id }) { group ->
                    RowCard(
                        title = group.id,
                        body = group.comment,
                        extra = if (group.members.isEmpty()) stringResource(R.string.access_group_no_members) else group.members.joinToString(", "),
                    ) { viewModel.startEdit(group) }
                }
            }
        }
    }

    state.draft?.let { draft ->
        val taken = draft.existing == null && state.nameTaken(draft.id)
        AlertDialog(
            onDismissRequest = viewModel::closeDraft,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(draft.existing?.id ?: stringResource(R.string.access_group_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (draft.existing == null) {
                        GlassTextField(
                            value = draft.id,
                            onValueChange = { v -> viewModel.edit { copy(id = v) } },
                            label = stringResource(R.string.access_group_name),
                            supportingText = stringResource(R.string.access_id_hint),
                            isError = draft.id.isNotEmpty() && (!draft.valid || taken),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        if (taken) FormHint(stringResource(R.string.access_name_taken), error = true)
                    }
                    GlassTextField(value = draft.comment, onValueChange = { v -> viewModel.edit { copy(comment = v) } }, label = stringResource(R.string.access_comment))
                    draft.existing?.let { group ->
                        AnProxButton(
                            stringResource(R.string.access_group_delete),
                            onClick = { viewModel.requestDelete(group) },
                            style = ButtonStyle.DANGER,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::save, enabled = draft.valid && !taken) {
                    Text(stringResource(if (draft.existing == null) R.string.access_create else R.string.action_save))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::closeDraft) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.confirmDelete?.let { group ->
        ConfirmActionDialog(
            title = stringResource(R.string.access_group_delete_title, group.id),
            message = stringResource(R.string.access_group_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

/** Datacenter → Permissions → Roles. */
@Composable
fun RolesScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: RolesViewModel = koinViewModel(key = "roles-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", { _, _, _ -> }, viewModel::consumeResult)
    val custom = state.roles.filterNot { it.builtIn }
    val builtIn = state.roles.filter { it.builtIn }
    AnProxScaffold(
        title = stringResource(R.string.access_roles),
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
                    stringResource(R.string.access_role_add),
                    onClick = viewModel::startCreate,
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy && state.error == null && state.privileges.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.roles.isEmpty() && state.error == null) item { Loading() }
            if (custom.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.access_roles_custom)) }
                items(custom, key = { "c-" + it.id }) { role -> RoleCard(role) { viewModel.open(role) } }
            }
            if (builtIn.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.access_roles_builtin)) }
                items(builtIn, key = { "b-" + it.id }) { role -> RoleCard(role) { viewModel.open(role) } }
            }
        }
    }

    state.draft?.let { draft -> RoleDialog(draft, state, viewModel) }
    state.confirmDelete?.let { role ->
        ConfirmActionDialog(
            title = stringResource(R.string.access_role_delete_title, role.id),
            message = stringResource(R.string.access_role_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun RoleCard(role: PveRole, onClick: () -> Unit) {
    RowCard(
        title = role.id,
        body = role.privileges.sorted().joinToString(", ").ifEmpty { null },
        extra = stringResource(R.string.access_role_count, role.privileges.size),
        onClick = onClick,
    )
}

@Composable
private fun RoleDialog(draft: RoleDraft, state: RolesUiState, viewModel: RolesViewModel) {
    val c = AnProxTheme.colors
    val taken = draft.existing == null && state.nameTaken(draft.id)
    // A built-in role can hold privileges this list doesn't (an older or newer server): show those too.
    val groups = privilegeGroups(state.privileges + draft.privileges)
    AlertDialog(
        onDismissRequest = viewModel::closeDraft,
        containerColor = c.surfaceSolid,
        title = { Text(draft.existing?.id ?: stringResource(R.string.access_role_add)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (draft.readOnly) FormHint(stringResource(R.string.access_role_builtin_hint))
                if (draft.existing == null) {
                    GlassTextField(
                        value = draft.id,
                        onValueChange = viewModel::changeId,
                        label = stringResource(R.string.access_role_name),
                        supportingText = stringResource(R.string.access_id_hint),
                        isError = draft.id.isNotEmpty() && (!PveRole.ID.matches(draft.id.trim()) || taken),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    if (taken) FormHint(stringResource(R.string.access_name_taken), error = true)
                }
                groups.forEach { (group, privileges) ->
                    Spacer(Modifier.height(6.dp))
                    FormLabel(group)
                    privileges.forEach { p -> CheckRow(p, p in draft.privileges, enabled = !draft.readOnly) { viewModel.toggle(p) } }
                }
                if (draft.existing != null && !draft.readOnly) {
                    Spacer(Modifier.height(8.dp))
                    AnProxButton(
                        stringResource(R.string.access_role_delete),
                        onClick = { viewModel.requestDelete(draft.existing) },
                        style = ButtonStyle.DANGER,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (draft.readOnly) {
                TextButton(onClick = viewModel::closeDraft) { Text(stringResource(R.string.action_close)) }
            } else {
                TextButton(onClick = viewModel::save, enabled = draft.valid && !taken) {
                    Text(stringResource(if (draft.existing == null) R.string.access_create else R.string.action_save))
                }
            }
        },
        dismissButton = {
            if (!draft.readOnly) TextButton(onClick = viewModel::closeDraft) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Datacenter → Permissions → Permissions: who has which role where. */
@Composable
fun AclScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: AclViewModel = koinViewModel(key = "acl-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", { _, _, _ -> }, viewModel::consumeResult)
    val subjectLabels = AclSubject.entries.associateWith { subjectLabel(it) }
    AnProxScaffold(
        title = stringResource(R.string.access_acl),
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
                    stringResource(R.string.access_acl_add),
                    onClick = viewModel::startAdd,
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy && state.error == null && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { FormHint(stringResource(R.string.access_acl_hint)) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            when {
                state.loading && state.entries.isEmpty() && state.error == null -> item { Loading() }
                !state.loading && state.error == null && state.entries.isEmpty() -> item {
                    EmptyState(Icons.Outlined.AdminPanelSettings, stringResource(R.string.access_acl_empty), stringResource(R.string.access_acl_hint))
                }
                else -> items(state.byPath, key = { it.first }) { (path, entries) ->
                    GlassCard(contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
                        Text(path, style = MaterialTheme.typography.titleMedium, color = AnProxTheme.colors.textPrimary)
                        entries.forEach { entry ->
                            AclRow(entry, subjectLabels.getValue(entry.subject), enabled = !state.busy) { viewModel.requestRemove(entry) }
                        }
                    }
                }
            }
        }
    }

    state.adding?.let { form -> AclDialog(form, state, subjectLabels, viewModel) }
    state.confirmRemove?.let { entry ->
        ConfirmActionDialog(
            title = stringResource(R.string.access_acl_remove_title),
            message = stringResource(R.string.access_acl_remove_body, entry.role, entry.ugid, entry.path),
            confirmLabel = stringResource(R.string.access_acl_remove),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::remove,
            onDismiss = viewModel::dismissRemove,
        )
    }
}

@Composable
private fun subjectLabel(subject: AclSubject): String = stringResource(
    when (subject) {
        AclSubject.USER -> R.string.access_subject_user
        AclSubject.GROUP -> R.string.access_subject_group
        AclSubject.TOKEN -> R.string.access_subject_token
    },
)

@Composable
private fun AclRow(entry: AclEntry, subject: String, enabled: Boolean, onRemove: () -> Unit) {
    val c = AnProxTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.ugid, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(subject, entry.role, if (entry.propagate) null else stringResource(R.string.access_acl_no_propagate))
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.access_acl_remove), tint = c.textSecondary)
        }
    }
}

@Composable
private fun AclDialog(form: AclForm, state: AclUiState, subjectLabels: Map<AclSubject, String>, viewModel: AclViewModel) {
    val candidates = state.candidates(form.subject)
    val problem = form.problem()
    AlertDialog(
        onDismissRequest = viewModel::closeAdd,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.access_acl_add)) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = form.path,
                    onValueChange = { v -> viewModel.edit { copy(path = v) } },
                    label = stringResource(R.string.access_acl_path),
                    supportingText = stringResource(R.string.access_acl_path_hint),
                    isError = problem == AclForm.Problem.PATH,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                ChoiceChips(AclForm.SUGGESTED_PATHS, form.path.trim(), { it }) { p -> viewModel.edit { copy(path = p) } }
                FormLabel(stringResource(R.string.access_acl_who))
                SegmentedPicker(AclSubject.entries, form.subject, { subjectLabels.getValue(it) }, viewModel::setSubject)
                if (candidates.isEmpty()) {
                    FormHint(stringResource(R.string.access_acl_no_candidates))
                } else {
                    ChoiceChips(candidates, form.ugid, { it }) { id -> viewModel.edit { copy(ugid = id) } }
                }
                FormLabel(stringResource(R.string.access_acl_role))
                ChoiceChips(state.roles.map { it.id }, form.role, { it }) { r -> viewModel.edit { copy(role = r) } }
                SwitchRow(
                    stringResource(R.string.access_acl_propagate),
                    form.propagate,
                    { on -> viewModel.edit { copy(propagate = on) } },
                    description = stringResource(R.string.access_acl_propagate_hint),
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::add, enabled = problem == null) { Text(stringResource(R.string.access_acl_add_short)) } },
        dismissButton = { TextButton(onClick = viewModel::closeAdd) { Text(stringResource(R.string.action_cancel)) } },
    )
}
