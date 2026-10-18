package io.github.lucabarbalata.anprox.ui.access

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.VpnKey
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.lucabarbalata.anprox.core.designsystem.component.InfoRow
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.ApiToken
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.ExpiryDate
import io.github.lucabarbalata.anprox.model.pve.PveUser
import io.github.lucabarbalata.anprox.model.pve.TfaEntry
import io.github.lucabarbalata.anprox.model.pve.UserForm
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.CheckRow
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.PasswordField
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.util.Date

/** Datacenter → Permissions → Users. */
@Composable
fun UsersScreen(serverId: String, onBack: () -> Unit, onUser: (userId: String) -> Unit, onAdd: () -> Unit) {
    val viewModel: UsersViewModel = koinViewModel(key = "users-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Also after coming back from a user or the editor.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    AnProxScaffold(
        title = stringResource(R.string.access_users),
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AnProxButton(
                    stringResource(R.string.access_user_add),
                    onClick = onAdd,
                    icon = Icons.Outlined.Add,
                    enabled = state.error == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            when {
                state.loading && state.users.isEmpty() && state.error == null -> item { Loading() }
                !state.loading && state.error == null && state.users.isEmpty() -> item {
                    EmptyState(Icons.Outlined.Person, stringResource(R.string.access_users_empty), stringResource(R.string.access_users_empty_body))
                }
                else -> items(state.users, key = { it.id }) { user -> UserCard(user) { onUser(user.id) } }
            }
        }
    }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AnProxTheme.colors.accent)
    }
}

@Composable
private fun UserCard(user: PveUser, onClick: () -> Unit) {
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(user.id, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(user.fullName, user.email).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                UserPills(user)
                Text(
                    stringResource(R.string.access_user_counts, user.groups.size, user.tokens.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textTertiary)
        }
    }
}

@Composable
private fun UserPills(user: PveUser) {
    val now = System.currentTimeMillis() / 1000
    val pills = buildList {
        if (!user.enabled) add(stringResource(R.string.access_user_disabled) to Tone.WARNING)
        if (user.expired(now)) add(stringResource(R.string.access_user_expired) to Tone.DANGER)
        if (user.tfaLocked(now)) add(stringResource(R.string.access_user_tfa_locked) to Tone.DANGER)
    }
    if (pills.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            pills.forEach { (text, tone) -> StatusPill(text, tone) }
        }
    }
}

/** One user: details, password, API tokens, second factors and what they may do. */
@Composable
fun UserScreen(
    serverId: String,
    userId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val viewModel: UserViewModel = koinViewModel(key = "user-$serverId-$userId") { parametersOf(serverId, userId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.access_copied)
    val onCopied: () -> Unit = { scope.launch { snackbar.showSnackbar(copied) } }
    TaskResultSnackbar(state.result, snackbar, serverId, "", { _, _, _ -> }, viewModel::consumeResult)
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    val user = state.user
    AnProxScaffold(
        title = userId,
        subtitle = user?.fullName,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = onEdit, enabled = user != null && !state.busy) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
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
            if (user == null) {
                if (state.loading) item { Loading() }
                return@LazyColumn
            }
            item { DetailsCard(user, state, viewModel) }
            item { SectionHeader(stringResource(R.string.access_tokens, state.tokens.size)) }
            item {
                AnProxButton(
                    stringResource(R.string.access_token_add),
                    onClick = viewModel::startToken,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.Add,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.tokens.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.tokens.forEach { token -> TokenRow(token, enabled = !state.busy) { viewModel.requestDeleteToken(token) } }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.access_tfa_section)) }
            state.tfaError?.let { item { FormHint(it.asString(), error = true) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnProxButton(
                        stringResource(R.string.access_totp_add),
                        onClick = viewModel::startTotp,
                        style = ButtonStyle.SECONDARY,
                        icon = Icons.Outlined.PhonelinkLock,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                    AnProxButton(
                        stringResource(R.string.access_recovery_add),
                        onClick = { viewModel.requestTfa(TfaAction.Recovery) },
                        style = ButtonStyle.SECONDARY,
                        icon = Icons.Outlined.VpnKey,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (state.tfa.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.tfa.forEach { entry ->
                            TfaRow(
                                entry,
                                enabled = !state.busy,
                                onToggle = { viewModel.requestTfa(TfaAction.Toggle(entry)) },
                                onDelete = { viewModel.requestTfa(TfaAction.Delete(entry)) },
                            )
                        }
                    }
                }
            } else if (state.tfaError == null) {
                item { FormHint(stringResource(R.string.access_tfa_none)) }
            }
            item { SectionHeader(stringResource(R.string.access_effective, userId)) }
            state.permissionsError?.let { item { FormHint(it.asString(), error = true) } }
            state.permissions?.let { permissions ->
                if (permissions.paths.isEmpty()) {
                    item { FormHint(stringResource(R.string.access_effective_none)) }
                } else {
                    item { PermissionsCard(permissions.paths) }
                }
            }
            if (state.canDelete) {
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    AnProxButton(
                        stringResource(R.string.access_user_delete),
                        onClick = viewModel::requestDelete,
                        style = ButtonStyle.DANGER,
                        icon = Icons.Outlined.Delete,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    state.password?.let { draft -> PasswordDialog(draft, state.needsYourPassword, viewModel) }
    state.token?.let { draft -> TokenDialog(draft, viewModel) }
    state.createdToken?.let { token ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.access_token_created)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormHint(stringResource(R.string.access_token_created_body))
                    FormLabel(stringResource(R.string.access_token_id))
                    SecretBox(token.fullId, onCopied)
                    FormLabel(stringResource(R.string.access_token_secret))
                    SecretBox(token.secret, onCopied)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeCreatedToken) { Text(stringResource(R.string.access_secret_saved)) } },
        )
    }
    state.confirmDeleteToken?.let { token ->
        ConfirmActionDialog(
            title = stringResource(R.string.access_token_delete_title, token.id),
            message = stringResource(R.string.access_token_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::deleteToken,
            onDismiss = viewModel::dismissDeleteToken,
        )
    }
    state.totp?.let { draft -> TotpDialog(draft, state.needsYourPassword, state.busy, viewModel, onCopied) }
    state.tfaConfirm?.let { confirm -> TfaConfirmDialog(confirm, state.needsYourPassword, viewModel) }
    state.recoveryKeys?.let { keys ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.access_recovery_title)) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormHint(stringResource(R.string.access_recovery_body))
                    SecretBox(keys.joinToString("\n"), onCopied)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeRecoveryKeys) { Text(stringResource(R.string.access_secret_saved)) } },
        )
    }
    if (state.confirmDelete) {
        ConfirmActionDialog(
            title = stringResource(R.string.access_user_delete_title, userId),
            message = stringResource(R.string.access_user_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
            confirmText = userId,
        )
    }
}

@Composable
private fun DetailsCard(user: PveUser, state: UserUiState, viewModel: UserViewModel) {
    val now = System.currentTimeMillis() / 1000
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UserPills(user)
            InfoRow(stringResource(R.string.access_realm), user.realmType?.let { "${user.realm} ($it)" } ?: user.realm)
            user.email?.let { InfoRow(stringResource(R.string.access_email), it) }
            InfoRow(
                stringResource(R.string.access_expires),
                user.expire?.takeIf { it > 0 }?.let { ExpiryDate.format(it) } ?: stringResource(R.string.access_never),
            )
            user.comment?.let { InfoRow(stringResource(R.string.access_comment), it) }
            if (user.groups.isNotEmpty()) {
                FormLabel(stringResource(R.string.access_groups))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { user.groups.forEach { TagChip(it) } }
            }
            if (state.canChangePassword || user.tfaLocked(now)) Spacer(Modifier.height(4.dp))
            if (state.canChangePassword) {
                AnProxButton(
                    stringResource(R.string.access_password_change),
                    onClick = viewModel::startPassword,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.Password,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (user.tfaLocked(now)) {
                AnProxButton(
                    stringResource(R.string.access_tfa_unlock),
                    onClick = viewModel::unlockTfa,
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.LockOpen,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TokenRow(token: ApiToken, enabled: Boolean, onDelete: () -> Unit) {
    val c = AnProxTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(token.id, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            val details = listOfNotNull(
                token.comment,
                stringResource(if (token.privsep) R.string.access_token_privsep_on else R.string.access_token_privsep_off),
                token.expire?.takeIf { it > 0 }?.let { stringResource(R.string.access_token_expires, ExpiryDate.format(it)) },
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = c.textSecondary)
        }
    }
}

@Composable
private fun tfaTypeLabel(type: String): String = when (type) {
    "totp" -> stringResource(R.string.access_tfa_totp)
    "recovery" -> stringResource(R.string.access_tfa_recovery)
    "webauthn" -> stringResource(R.string.access_tfa_webauthn)
    "yubico" -> stringResource(R.string.access_tfa_yubico)
    "u2f" -> stringResource(R.string.access_tfa_u2f)
    else -> type
}

@Composable
private fun TfaRow(entry: TfaEntry, enabled: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val c = AnProxTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.description ?: tfaTypeLabel(entry.type), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            val created = entry.created?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) }
            Text(listOfNotNull(tfaTypeLabel(entry.type), created).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Switch(checked = entry.enabled, onCheckedChange = { onToggle() }, enabled = enabled)
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = c.textSecondary)
        }
    }
}

/** Path → privileges; a path opens to list them. */
@Composable
private fun PermissionsCard(paths: Map<String, Map<String, Boolean>>) {
    val c = AnProxTheme.colors
    var open by remember { mutableStateOf(setOf<String>()) }
    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        paths.toSortedMap().forEach { (path, privileges) ->
            val expanded = path in open
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = if (expanded) open - path else open + path }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(path, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.access_effective_count, privileges.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = c.textTertiary)
                }
                if (expanded) {
                    Text(privileges.keys.sorted().joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(draft: PasswordDraft, needsYourPassword: Boolean, viewModel: UserViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closePassword,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.access_password_change)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordField(
                    draft.password,
                    { v -> viewModel.editPassword { copy(password = v) } },
                    stringResource(R.string.access_password_new),
                    supportingText = stringResource(R.string.access_password_hint, UserForm.MIN_PASSWORD),
                    isError = draft.password.isNotEmpty() && draft.tooShort,
                )
                PasswordField(
                    draft.confirm,
                    { v -> viewModel.editPassword { copy(confirm = v) } },
                    stringResource(R.string.access_password_confirm),
                    isError = draft.mismatch,
                )
                if (draft.mismatch) FormHint(stringResource(R.string.access_problem_password_mismatch), error = true)
                if (needsYourPassword) YourPasswordField(draft.yourPassword) { v -> viewModel.editPassword { copy(yourPassword = v) } }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::savePassword, enabled = draft.valid(needsYourPassword)) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = viewModel::closePassword) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TokenDialog(draft: TokenDraft, viewModel: UserViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeToken,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.access_token_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = draft.form.id,
                    onValueChange = { v -> viewModel.editToken { copy(form = form.copy(id = v)) } },
                    label = stringResource(R.string.access_token_name),
                    supportingText = stringResource(R.string.access_token_name_hint),
                    isError = draft.form.id.isNotEmpty() && !draft.form.valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                GlassTextField(
                    value = draft.form.comment,
                    onValueChange = { v -> viewModel.editToken { copy(form = form.copy(comment = v)) } },
                    label = stringResource(R.string.access_comment),
                )
                GlassTextField(
                    value = draft.expire,
                    onValueChange = { v -> viewModel.editToken { copy(expire = v) } },
                    label = stringResource(R.string.access_expires),
                    supportingText = stringResource(R.string.access_date_hint),
                    isError = draft.badExpiry,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                SwitchRow(
                    stringResource(R.string.access_token_privsep),
                    draft.form.privsep,
                    { on -> viewModel.editToken { copy(form = form.copy(privsep = on)) } },
                    description = stringResource(R.string.access_token_privsep_hint),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::createToken, enabled = draft.valid) { Text(stringResource(R.string.access_create)) }
        },
        dismissButton = { TextButton(onClick = viewModel::closeToken) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TotpDialog(draft: TotpDraft, needsYourPassword: Boolean, busy: Boolean, viewModel: UserViewModel, onCopied: () -> Unit) {
    val context = LocalContext.current
    var noApp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = viewModel::closeTotp,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.access_totp_add)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormHint(stringResource(R.string.access_totp_body))
                AnProxButton(
                    stringResource(R.string.access_totp_open),
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(draft.setup.uri)))
                        } catch (_: ActivityNotFoundException) {
                            noApp = true
                        }
                    },
                    style = ButtonStyle.SECONDARY,
                    icon = Icons.Outlined.PhonelinkLock,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (noApp) FormHint(stringResource(R.string.access_totp_no_app), error = true)
                FormLabel(stringResource(R.string.access_totp_secret))
                SecretBox(draft.groupedSecret, onCopied, copyText = draft.setup.secret)
                GlassTextField(
                    value = draft.description,
                    onValueChange = { v -> viewModel.editTotp { copy(description = v) } },
                    label = stringResource(R.string.access_totp_description),
                    placeholder = stringResource(R.string.access_totp_description_hint),
                )
                GlassTextField(
                    value = draft.code,
                    onValueChange = { v -> viewModel.editTotp { copy(code = v.filter(Char::isDigit).take(6)) } },
                    label = stringResource(R.string.access_totp_code),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (needsYourPassword) YourPasswordField(draft.yourPassword) { v -> viewModel.editTotp { copy(yourPassword = v) } }
                draft.error?.let { FormHint(it.asString(), error = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::addTotp, enabled = draft.valid(needsYourPassword) && !busy) { Text(stringResource(R.string.access_totp_confirm)) }
        },
        dismissButton = { TextButton(onClick = viewModel::closeTotp) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TfaConfirmDialog(confirm: TfaConfirm, needsYourPassword: Boolean, viewModel: UserViewModel) {
    val c = AnProxTheme.colors
    val (title, body, label) = when (val action = confirm.action) {
        is TfaAction.Toggle -> Triple(
            stringResource(if (action.entry.enabled) R.string.access_tfa_disable_title else R.string.access_tfa_enable_title),
            stringResource(if (action.entry.enabled) R.string.access_tfa_disable_body else R.string.access_tfa_enable_body),
            stringResource(if (action.entry.enabled) R.string.access_tfa_disable else R.string.access_tfa_enable),
        )
        is TfaAction.Delete -> Triple(
            stringResource(R.string.access_tfa_delete_title),
            stringResource(R.string.access_tfa_delete_body),
            stringResource(R.string.action_delete),
        )
        TfaAction.Recovery -> Triple(
            stringResource(R.string.access_recovery_add),
            stringResource(R.string.access_recovery_confirm_body),
            stringResource(R.string.access_create),
        )
    }
    val ready = !needsYourPassword || confirm.yourPassword.isNotEmpty()
    AlertDialog(
        onDismissRequest = viewModel::dismissTfa,
        containerColor = c.surfaceSolid,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                if (needsYourPassword) YourPasswordField(confirm.yourPassword, viewModel::editTfaPassword)
            }
        },
        confirmButton = { TextButton(onClick = viewModel::confirmTfa, enabled = ready) { Text(label) } },
        dismissButton = { TextButton(onClick = viewModel::dismissTfa) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Datacenter → Permissions → Users → add a user ([userId] null) or edit one. */
@Composable
fun UserEditorScreen(serverId: String, userId: String?, onDone: () -> Unit) {
    val viewModel: UserEditorViewModel = koinViewModel(key = "user-editor-$serverId-${userId.orEmpty()}") { parametersOf(serverId, userId.orEmpty()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val form = state.form
    AnProxScaffold(
        title = if (state.creating) stringResource(R.string.access_user_add) else userId.orEmpty(),
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
            if (state.creating) {
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassTextField(
                                value = form.name,
                                onValueChange = { v -> viewModel.edit { copy(name = v) } },
                                label = stringResource(R.string.access_user_name),
                                isError = form.name.isNotEmpty() && state.problem == UserForm.Problem.NAME,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            )
                            FormLabel(stringResource(R.string.access_realm))
                            ChoiceChips(state.realms.map { it.realm }, form.realm, { it }) { r -> viewModel.edit { copy(realm = r) } }
                            if (form.realm != "pve") FormHint(stringResource(R.string.access_user_realm_hint, form.realm))
                            if (form.hasPassword) {
                                PasswordField(
                                    form.password,
                                    { v -> viewModel.edit { copy(password = v) } },
                                    stringResource(R.string.access_password),
                                    supportingText = stringResource(R.string.access_password_hint, UserForm.MIN_PASSWORD),
                                    isError = form.password.isNotEmpty() && form.password.length < UserForm.MIN_PASSWORD,
                                )
                                PasswordField(
                                    form.confirmPassword,
                                    { v -> viewModel.edit { copy(confirmPassword = v) } },
                                    stringResource(R.string.access_password_confirm),
                                    isError = form.confirmPassword.isNotEmpty() && form.confirmPassword != form.password,
                                )
                            }
                        }
                    }
                }
            }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassTextField(value = form.firstName, onValueChange = { v -> viewModel.edit { copy(firstName = v) } }, label = stringResource(R.string.access_first_name))
                        GlassTextField(value = form.lastName, onValueChange = { v -> viewModel.edit { copy(lastName = v) } }, label = stringResource(R.string.access_last_name))
                        GlassTextField(
                            value = form.email,
                            onValueChange = { v -> viewModel.edit { copy(email = v) } },
                            label = stringResource(R.string.access_email),
                            isError = state.problem == UserForm.Problem.EMAIL,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        GlassTextField(value = form.comment, onValueChange = { v -> viewModel.edit { copy(comment = v) } }, label = stringResource(R.string.access_comment))
                        GlassTextField(
                            value = state.expire,
                            onValueChange = viewModel::setExpire,
                            label = stringResource(R.string.access_expires),
                            supportingText = stringResource(R.string.access_date_hint),
                            isError = state.badExpiry,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        SwitchRow(stringResource(R.string.access_user_enabled), form.enabled, { on -> viewModel.edit { copy(enabled = on) } })
                    }
                }
            }
            item {
                GlassCard {
                    FormLabel(stringResource(R.string.access_groups))
                    if (state.groups.isEmpty()) FormHint(stringResource(R.string.access_groups_none))
                    state.groups.forEach { g -> CheckRow(g.id, g.id in form.groups) { viewModel.toggleGroup(g.id) } }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.problem?.let { FormHint(userProblemText(it), error = true) }
                    if (state.badExpiry) FormHint(stringResource(R.string.access_problem_date), error = true)
                    state.saveError?.let { FormHint(it.asString(), error = true) }
                    AnProxButton(
                        stringResource(if (state.creating) R.string.access_user_create else R.string.action_save),
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
private fun userProblemText(problem: UserForm.Problem): String = when (problem) {
    UserForm.Problem.NAME -> stringResource(R.string.access_problem_user_name)
    UserForm.Problem.PASSWORD -> stringResource(R.string.access_password_hint, UserForm.MIN_PASSWORD)
    UserForm.Problem.PASSWORD_MISMATCH -> stringResource(R.string.access_problem_password_mismatch)
    UserForm.Problem.EMAIL -> stringResource(R.string.access_problem_email)
}
