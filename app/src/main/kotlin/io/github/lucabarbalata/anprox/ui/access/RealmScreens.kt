package io.github.lucabarbalata.anprox.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.RealmForm
import io.github.lucabarbalata.anprox.model.pve.RealmSync
import io.github.lucabarbalata.anprox.model.pve.RealmType
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
private fun realmTypeLabel(type: String): String = when (type) {
    "pam" -> stringResource(R.string.access_realm_pam)
    "pve" -> stringResource(R.string.access_realm_pve)
    "ldap" -> stringResource(R.string.access_realm_ldap)
    "ad" -> stringResource(R.string.access_realm_ad)
    "openid" -> stringResource(R.string.access_realm_openid)
    else -> type
}

/** Datacenter → Permissions → Realms: where users sign in from. */
@Composable
fun RealmsScreen(
    serverId: String,
    onBack: () -> Unit,
    onAdd: (RealmType) -> Unit,
    onEdit: (realm: String) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: RealmsViewModel = koinViewModel(key = "realms-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)
    var choosingType by remember { mutableStateOf(false) }
    AnProxScaffold(
        title = stringResource(R.string.access_realms),
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
                    stringResource(R.string.access_realm_add),
                    onClick = { choosingType = true },
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy && state.error == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.realms.isEmpty() && state.error == null) item { Loading() }
            items(state.realms, key = { it.realm }) { realm ->
                RealmCard(
                    realm,
                    enabled = !state.busy,
                    onEdit = { onEdit(realm.realm) },
                    onSync = { viewModel.startSync(realm) },
                    onDelete = { viewModel.requestDelete(realm) },
                )
            }
        }
    }

    if (choosingType) {
        var type by remember { mutableStateOf(RealmType.LDAP) }
        AlertDialog(
            onDismissRequest = { choosingType = false },
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.access_realm_add)) },
            text = {
                Column {
                    RealmType.entries.forEach { t ->
                        RadioRow(type == t, realmTypeLabel(t.apiName), null) { type = t }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    choosingType = false
                    onAdd(type)
                }) { Text(stringResource(R.string.access_next)) }
            },
            dismissButton = { TextButton(onClick = { choosingType = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.syncing?.let { draft -> SyncDialog(draft, viewModel) }
    state.confirmDelete?.let { realm ->
        ConfirmActionDialog(
            title = stringResource(R.string.access_realm_delete_title, realm.realm),
            message = stringResource(R.string.access_realm_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
            confirmText = realm.realm,
        )
    }
}

@Composable
private fun RealmCard(realm: Realm, enabled: Boolean, onEdit: () -> Unit, onSync: () -> Unit, onDelete: () -> Unit) {
    val c = AnProxTheme.colors
    val type = RealmType.fromApi(realm.type)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(realm.realm, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
            if (realm.default) {
                Spacer(Modifier.width(8.dp))
                StatusPill(stringResource(R.string.access_realm_default), Tone.ACCENT)
            }
        }
        Text(
            listOfNotNull(realmTypeLabel(realm.type), realm.comment).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        if (type == null) {
            FormHint(stringResource(R.string.access_realm_builtin))
        } else {
            Row {
                TextButton(onClick = onEdit, enabled = enabled) { Text(stringResource(R.string.action_edit)) }
                if (type != RealmType.OPENID) TextButton(onClick = onSync, enabled = enabled) { Text(stringResource(R.string.access_realm_sync)) }
                TextButton(onClick = onDelete, enabled = enabled) { Text(stringResource(R.string.action_delete), color = c.danger) }
            }
        }
    }
}

@Composable
private fun SyncDialog(draft: SyncDraft, viewModel: RealmsViewModel) {
    val sync = draft.sync
    val scopeLabels = mapOf(
        "users" to stringResource(R.string.access_sync_scope_users),
        "groups" to stringResource(R.string.access_sync_scope_groups),
        "both" to stringResource(R.string.access_sync_scope_both),
    )
    val vanishedLabels = mapOf(
        "acl" to stringResource(R.string.access_sync_vanished_acl),
        "entry" to stringResource(R.string.access_sync_vanished_entry),
        "properties" to stringResource(R.string.access_sync_vanished_properties),
    )
    AlertDialog(
        onDismissRequest = viewModel::closeSync,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.access_sync_title, draft.realm.realm)) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FormLabel(stringResource(R.string.access_sync_scope))
                RealmSync.SCOPES.forEach { scope ->
                    RadioRow(sync.scope == scope, scopeLabels.getValue(scope), null) { viewModel.editSync { copy(scope = scope) } }
                }
                SwitchRow(stringResource(R.string.access_sync_enable_new), sync.enableNew, { on -> viewModel.editSync { copy(enableNew = on) } })
                FormLabel(stringResource(R.string.access_sync_vanished))
                FormHint(stringResource(R.string.access_sync_vanished_hint))
                RealmSync.VANISHED.forEach { what ->
                    CheckRow(vanishedLabels.getValue(what), what in sync.removeVanished) {
                        viewModel.editSync { copy(removeVanished = if (what in removeVanished) removeVanished - what else removeVanished + what) }
                    }
                }
                SwitchRow(
                    stringResource(R.string.access_sync_dry_run),
                    sync.dryRun,
                    { on -> viewModel.editSync { copy(dryRun = on) } },
                    description = stringResource(R.string.access_sync_dry_run_hint),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::sync) {
                Text(stringResource(if (sync.dryRun) R.string.access_sync_preview else R.string.access_realm_sync))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeSync) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Datacenter → Permissions → Realms → add a directory or OpenID realm ([realm] null) or edit one. */
@Composable
fun RealmEditorScreen(serverId: String, realm: String?, type: RealmType, onDone: () -> Unit) {
    val viewModel: RealmEditorViewModel = koinViewModel(key = "realm-editor-$serverId-${realm.orEmpty()}-${type.apiName}") {
        parametersOf(serverId, realm.orEmpty(), type)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val form = state.form
    val problem = state.problem
    AnProxScaffold(
        title = if (state.creating) stringResource(R.string.access_realm_add) else realm.orEmpty(),
        subtitle = realmTypeLabel(form.type.apiName),
        onBack = onDone,
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading) {
                item { Loading() }
                return@LazyColumn
            }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.creating) {
                            GlassTextField(
                                value = form.id,
                                onValueChange = { v -> viewModel.edit { copy(id = v) } },
                                label = stringResource(R.string.access_realm_id),
                                supportingText = stringResource(R.string.access_realm_id_hint),
                                isError = form.id.isNotEmpty() && problem == RealmForm.Problem.ID,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            )
                        }
                        GlassTextField(value = form.comment, onValueChange = { v -> viewModel.edit { copy(comment = v) } }, label = stringResource(R.string.access_comment))
                        SwitchRow(
                            stringResource(R.string.access_realm_default_switch),
                            form.isDefault,
                            { on -> viewModel.edit { copy(isDefault = on) } },
                            description = stringResource(R.string.access_realm_default_hint),
                        )
                    }
                }
            }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (form.type == RealmType.OPENID) OpenIdFields(state, viewModel) else DirectoryFields(state, viewModel)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    problem?.let { FormHint(realmProblemText(it), error = true) }
                    state.saveError?.let { FormHint(it.asString(), error = true) }
                    AnProxButton(
                        stringResource(if (state.creating) R.string.access_realm_create else R.string.action_save),
                        onClick = viewModel::save,
                        icon = Icons.Outlined.Save,
                        enabled = state.canSave,
                        loading = state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryFields(state: RealmEditorState, viewModel: RealmEditorViewModel) {
    val form = state.form
    val problem = state.problem
    if (form.type == RealmType.AD) {
        GlassTextField(
            value = form.domain,
            onValueChange = { v -> viewModel.edit { copy(domain = v) } },
            label = stringResource(R.string.access_realm_domain),
            isError = problem == RealmForm.Problem.DOMAIN,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
    GlassTextField(
        value = form.server1,
        onValueChange = { v -> viewModel.edit { copy(server1 = v) } },
        label = stringResource(R.string.access_realm_server1),
        isError = problem == RealmForm.Problem.SERVER,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    GlassTextField(
        value = form.server2,
        onValueChange = { v -> viewModel.edit { copy(server2 = v) } },
        label = stringResource(R.string.access_realm_server2),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    GlassTextField(
        value = form.port,
        onValueChange = { v -> viewModel.edit { copy(port = v.filter(Char::isDigit).take(5)) } },
        label = stringResource(R.string.access_realm_port),
        placeholder = stringResource(R.string.access_realm_port_hint),
        isError = problem == RealmForm.Problem.PORT,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    FormLabel(stringResource(R.string.access_realm_mode))
    ChoiceChips(RealmForm.MODES, form.mode, { it.uppercase().replace("+", " + ") }) { m -> viewModel.edit { copy(mode = m) } }
    if (form.mode != "ldap") {
        SwitchRow(stringResource(R.string.access_realm_verify), form.verify, { on -> viewModel.edit { copy(verify = on) } })
    }
    if (form.type == RealmType.LDAP) {
        GlassTextField(
            value = form.baseDn,
            onValueChange = { v -> viewModel.edit { copy(baseDn = v) } },
            label = stringResource(R.string.access_realm_base_dn),
            placeholder = "dc=example,dc=com",
            isError = problem == RealmForm.Problem.BASE_DN,
        )
        GlassTextField(value = form.userAttr, onValueChange = { v -> viewModel.edit { copy(userAttr = v) } }, label = stringResource(R.string.access_realm_user_attr))
        GlassTextField(value = form.bindDn, onValueChange = { v -> viewModel.edit { copy(bindDn = v) } }, label = stringResource(R.string.access_realm_bind_dn))
    }
    PasswordField(
        form.bindPassword,
        { v -> viewModel.edit { copy(bindPassword = v) } },
        stringResource(R.string.access_realm_bind_password),
        supportingText = if (state.creating) null else stringResource(R.string.access_keep_secret_hint),
    )
}

@Composable
private fun OpenIdFields(state: RealmEditorState, viewModel: RealmEditorViewModel) {
    val form = state.form
    val problem = state.problem
    GlassTextField(
        value = form.issuerUrl,
        onValueChange = { v -> viewModel.edit { copy(issuerUrl = v) } },
        label = stringResource(R.string.access_realm_issuer),
        placeholder = "https://login.example.com/realms/main",
        isError = problem == RealmForm.Problem.ISSUER,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    GlassTextField(
        value = form.clientId,
        onValueChange = { v -> viewModel.edit { copy(clientId = v) } },
        label = stringResource(R.string.access_realm_client_id),
        isError = problem == RealmForm.Problem.CLIENT,
    )
    PasswordField(
        form.clientKey,
        { v -> viewModel.edit { copy(clientKey = v) } },
        stringResource(R.string.access_realm_client_key),
        supportingText = if (state.creating) null else stringResource(R.string.access_keep_secret_hint),
    )
    GlassTextField(
        value = form.usernameClaim,
        onValueChange = { v -> viewModel.edit { copy(usernameClaim = v) } },
        label = stringResource(R.string.access_realm_username_claim),
        supportingText = stringResource(R.string.access_realm_username_claim_hint),
        enabled = state.creating,
    )
    GlassTextField(
        value = form.scopes,
        onValueChange = { v -> viewModel.edit { copy(scopes = v) } },
        label = stringResource(R.string.access_realm_scopes),
        placeholder = "email profile",
    )
    SwitchRow(stringResource(R.string.access_realm_autocreate), form.autocreate, { on -> viewModel.edit { copy(autocreate = on) } })
}

@Composable
private fun realmProblemText(problem: RealmForm.Problem): String = stringResource(
    when (problem) {
        RealmForm.Problem.ID -> R.string.access_realm_id_hint
        RealmForm.Problem.SERVER -> R.string.access_problem_server
        RealmForm.Problem.PORT -> R.string.access_problem_port
        RealmForm.Problem.BASE_DN -> R.string.access_problem_base_dn
        RealmForm.Problem.DOMAIN -> R.string.access_problem_domain
        RealmForm.Problem.ISSUER -> R.string.access_problem_issuer
        RealmForm.Problem.CLIENT -> R.string.access_problem_client
    },
)
