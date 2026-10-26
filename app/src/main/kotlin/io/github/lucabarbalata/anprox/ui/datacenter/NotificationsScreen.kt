package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcherForm
import io.github.lucabarbalata.anprox.model.pve.NotifySeverities
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetType
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.CheckRow
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Datacenter → Notifications: where Proxmox VE sends what happens (backups, replication, fencing,
 * updates), and which notifications go where.
 */
@Composable
fun NotificationsScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: NotificationsViewModel = koinViewModel(key = "notify-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", { _, _, _ -> }, viewModel::consumeResult)
    val typeLabels = NotifyTargetType.entries.associateWith { targetTypeLabel(it) }
    AnProxScaffold(
        title = stringResource(R.string.notify_title),
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
            if (state.busy || state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent) }
            item { SectionHeader(stringResource(R.string.notify_targets)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    state.targets.forEachIndexed { i, target ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
                        val subtitle = listOfNotNull(
                            target.kind?.let { typeLabels[it] } ?: target.type,
                            target.comment,
                            stringResource(R.string.notify_disabled).takeIf { target.disable },
                        ).joinToString(" · ")
                        EntryRow(
                            title = target.name,
                            subtitle = subtitle,
                            enabled = !state.busy,
                            onClick = { viewModel.startEditTarget(target) },
                            actions = buildList<Pair<String, () -> Unit>> {
                                add(stringResource(R.string.notify_send_test) to { viewModel.test(target) })
                                if (!target.builtin) add(stringResource(R.string.action_delete) to { viewModel.requestDeleteTarget(target) })
                            },
                        )
                    }
                    AnProxButton(
                        stringResource(R.string.notify_add_target),
                        onClick = viewModel::startAddTarget,
                        style = ButtonStyle.SECONDARY,
                        enabled = !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.notify_matchers)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    state.matchers.forEachIndexed { i, matcher ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
                        val subtitle = listOfNotNull(
                            stringResource(R.string.notify_matcher_to, matcher.targets.joinToString(", ")),
                            matcher.severities.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            stringResource(R.string.notify_disabled).takeIf { matcher.disabled },
                        ).joinToString(" · ")
                        EntryRow(
                            title = matcher.name,
                            subtitle = subtitle,
                            enabled = !state.busy,
                            onClick = { viewModel.startEditMatcher(matcher) },
                            actions = if (matcher.builtin) emptyList() else listOf(stringResource(R.string.action_delete) to { viewModel.requestDeleteMatcher(matcher) }),
                        )
                    }
                    AnProxButton(
                        stringResource(R.string.notify_add_matcher),
                        onClick = viewModel::startAddMatcher,
                        style = ButtonStyle.SECONDARY,
                        enabled = !state.busy && !state.loading && state.targets.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    state.targetEdit?.let { edit -> TargetDialog(edit, typeLabels, viewModel) }
    state.matcherEdit?.let { edit -> MatcherDialog(edit, state, viewModel) }
    state.deleteTarget?.let { target ->
        ConfirmActionDialog(
            title = stringResource(R.string.notify_delete_named, target.name),
            message = stringResource(R.string.notify_delete_target_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::deleteTarget,
            onDismiss = viewModel::dismissDeleteTarget,
        )
    }
    state.deleteMatcher?.let { matcher ->
        ConfirmActionDialog(
            title = stringResource(R.string.notify_delete_named, matcher.name),
            message = stringResource(R.string.notify_delete_matcher_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::deleteMatcher,
            onDismiss = viewModel::dismissDeleteMatcher,
        )
    }
}

@Composable
private fun targetTypeLabel(type: NotifyTargetType): String = stringResource(
    when (type) {
        NotifyTargetType.SENDMAIL -> R.string.notify_type_sendmail
        NotifyTargetType.SMTP -> R.string.notify_type_smtp
        NotifyTargetType.GOTIFY -> R.string.notify_type_gotify
        NotifyTargetType.WEBHOOK -> R.string.notify_type_webhook
    },
)

/** A row that opens its editor, with its other actions in a ⋮ menu. */
@Composable
internal fun EntryRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    val c = AnProxTheme.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (actions.isNotEmpty()) {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.notify_actions_named, title), tint = c.textSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    actions.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menu = false
                            action()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetDialog(edit: TargetEdit, typeLabels: Map<NotifyTargetType, String>, viewModel: NotificationsViewModel) {
    val c = AnProxTheme.colors
    val form = edit.form
    val problem = form.problem(edit.create)
    val keep = stringResource(R.string.notify_keep_secret)
    AlertDialog(
        onDismissRequest = viewModel::cancelTarget,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(if (edit.create) R.string.notify_add_target else R.string.notify_target)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (edit.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                    return@Column
                }
                if (edit.create) ChoiceChips(NotifyTargetType.entries, form.type, { typeLabels.getValue(it) }, viewModel::setTargetType)
                GlassTextField(
                    value = form.name,
                    onValueChange = { v -> viewModel.editTarget { copy(name = v) } },
                    label = stringResource(R.string.notify_name),
                    enabled = edit.create,
                    isError = problem == NotifyTargetForm.Problem.NAME && form.name.isNotEmpty(),
                )
                when (form.type) {
                    NotifyTargetType.SENDMAIL, NotifyTargetType.SMTP -> {
                        if (form.type == NotifyTargetType.SMTP) {
                            GlassTextField(value = form.server, onValueChange = { v -> viewModel.editTarget { copy(server = v) } }, label = stringResource(R.string.notify_smtp_server), isError = problem == NotifyTargetForm.Problem.SERVER && form.server.isNotEmpty())
                            NumberField(value = form.port, onValueChange = { v -> viewModel.editTarget { copy(port = v) } }, label = stringResource(R.string.notify_port), supportingText = stringResource(R.string.notify_port_hint), isError = problem == NotifyTargetForm.Problem.PORT)
                            FormLabel(stringResource(R.string.notify_encryption))
                            val modes = mapOf("tls" to stringResource(R.string.notify_mode_tls), "starttls" to stringResource(R.string.notify_mode_starttls), "insecure" to stringResource(R.string.notify_mode_insecure))
                            ChoiceChips(modes.keys.toList(), form.mode, { modes.getValue(it) }) { m -> viewModel.editTarget { copy(mode = m) } }
                            GlassTextField(value = form.username, onValueChange = { v -> viewModel.editTarget { copy(username = v) } }, label = stringResource(R.string.notify_username))
                            GlassTextField(
                                value = form.password,
                                onValueChange = { v -> viewModel.editTarget { copy(password = v) } },
                                label = stringResource(R.string.notify_password),
                                supportingText = keep.takeIf { !edit.create },
                            )
                        }
                        GlassTextField(
                            value = form.mailto,
                            onValueChange = { v -> viewModel.editTarget { copy(mailto = v) } },
                            label = stringResource(R.string.notify_mailto),
                            supportingText = stringResource(R.string.notify_mailto_hint),
                            isError = problem == NotifyTargetForm.Problem.RECIPIENT && form.mailto.isNotEmpty(),
                        )
                        GlassTextField(value = form.mailtoUser, onValueChange = { v -> viewModel.editTarget { copy(mailtoUser = v) } }, label = stringResource(R.string.notify_mailto_user), supportingText = stringResource(R.string.notify_mailto_user_hint))
                        GlassTextField(
                            value = form.fromAddress,
                            onValueChange = { v -> viewModel.editTarget { copy(fromAddress = v) } },
                            label = stringResource(if (form.type == NotifyTargetType.SMTP) R.string.notify_from else R.string.notify_from_optional),
                            isError = problem == NotifyTargetForm.Problem.FROM && form.fromAddress.isNotEmpty(),
                        )
                        GlassTextField(value = form.author, onValueChange = { v -> viewModel.editTarget { copy(author = v) } }, label = stringResource(R.string.notify_author))
                    }
                    NotifyTargetType.GOTIFY -> {
                        GlassTextField(value = form.server, onValueChange = { v -> viewModel.editTarget { copy(server = v) } }, label = stringResource(R.string.notify_gotify_server), isError = problem == NotifyTargetForm.Problem.SERVER && form.server.isNotEmpty())
                        GlassTextField(value = form.token, onValueChange = { v -> viewModel.editTarget { copy(token = v) } }, label = stringResource(R.string.notify_token), supportingText = keep.takeIf { !edit.create })
                    }
                    NotifyTargetType.WEBHOOK -> {
                        GlassTextField(value = form.url, onValueChange = { v -> viewModel.editTarget { copy(url = v) } }, label = stringResource(R.string.notify_url), isError = problem == NotifyTargetForm.Problem.URL && form.url.isNotEmpty())
                        FormLabel(stringResource(R.string.notify_method))
                        ChoiceChips(listOf("post", "put", "get"), form.method, { it.uppercase() }) { m -> viewModel.editTarget { copy(method = m) } }
                        GlassTextField(value = form.headers, onValueChange = { v -> viewModel.editTarget { copy(headers = v) } }, label = stringResource(R.string.notify_headers), supportingText = stringResource(R.string.notify_headers_hint), singleLine = false, minLines = 2, isError = problem == NotifyTargetForm.Problem.HEADER)
                        GlassTextField(value = form.body, onValueChange = { v -> viewModel.editTarget { copy(body = v) } }, label = stringResource(R.string.notify_body), supportingText = stringResource(R.string.notify_body_hint), singleLine = false, minLines = 3)
                        GlassTextField(
                            value = form.secrets,
                            onValueChange = { v -> viewModel.editTarget { copy(secrets = v) } },
                            label = stringResource(R.string.notify_secrets),
                            supportingText = if (edit.create) stringResource(R.string.notify_secrets_hint) else keep,
                            singleLine = false,
                            minLines = 2,
                            isError = problem == NotifyTargetForm.Problem.SECRET,
                        )
                    }
                }
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editTarget { copy(comment = v) } }, label = stringResource(R.string.notify_comment))
                CheckRow(stringResource(R.string.notify_disable), form.disabled) { viewModel.editTarget { copy(disabled = !disabled) } }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveTarget, enabled = !edit.loading && problem == null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::cancelTarget) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun MatcherDialog(edit: MatcherEdit, state: NotificationsState, viewModel: NotificationsViewModel) {
    val c = AnProxTheme.colors
    val form = edit.form
    val problem = form.problem()
    val known = state.fieldValues.filter { it.first == "type" }.joinToString(", ") { it.second }
    AlertDialog(
        onDismissRequest = viewModel::cancelMatcher,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(if (edit.existing == null) R.string.notify_add_matcher else R.string.notify_matcher)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTextField(
                    value = form.name,
                    onValueChange = { v -> viewModel.editMatcher { copy(name = v) } },
                    label = stringResource(R.string.notify_name),
                    enabled = edit.existing == null,
                    isError = problem == NotifyMatcherForm.Problem.NAME && form.name.isNotEmpty(),
                )
                FormLabel(stringResource(R.string.notify_send_to))
                state.targets.forEach { target ->
                    CheckRow(target.name, target.name in form.targets) {
                        viewModel.editMatcher { copy(targets = if (target.name in targets) targets - target.name else targets + target.name) }
                    }
                }
                if (problem == NotifyMatcherForm.Problem.TARGET) FormHint(stringResource(R.string.notify_need_target), error = true)
                FormLabel(stringResource(R.string.notify_severities))
                FormHint(stringResource(R.string.notify_severities_hint))
                NotifySeverities.forEach { severity ->
                    CheckRow(severity, severity in form.severities) {
                        viewModel.editMatcher { copy(severities = if (severity in severities) severities - severity else severities + severity) }
                    }
                }
                GlassTextField(
                    value = form.fields,
                    onValueChange = { v -> viewModel.editMatcher { copy(fields = v) } },
                    label = stringResource(R.string.notify_fields),
                    supportingText = if (known.isNotEmpty()) stringResource(R.string.notify_fields_known, known) else stringResource(R.string.notify_fields_hint),
                    singleLine = false,
                    minLines = 2,
                    isError = problem == NotifyMatcherForm.Problem.FIELD,
                )
                GlassTextField(
                    value = form.calendar,
                    onValueChange = { v -> viewModel.editMatcher { copy(calendar = v) } },
                    label = stringResource(R.string.notify_calendar),
                    supportingText = stringResource(R.string.notify_calendar_hint),
                    singleLine = false,
                    minLines = 1,
                )
                FormLabel(stringResource(R.string.notify_mode))
                val modes = mapOf("all" to stringResource(R.string.notify_mode_all), "any" to stringResource(R.string.notify_mode_any))
                ChoiceChips(modes.keys.toList(), form.mode, { modes.getValue(it) }) { m -> viewModel.editMatcher { copy(mode = m) } }
                CheckRow(stringResource(R.string.notify_invert), form.invert) { viewModel.editMatcher { copy(invert = !invert) } }
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editMatcher { copy(comment = v) } }, label = stringResource(R.string.notify_comment))
                CheckRow(stringResource(R.string.notify_disable), form.disabled) { viewModel.editMatcher { copy(disabled = !disabled) } }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveMatcher, enabled = problem == null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::cancelMatcher) { Text(stringResource(R.string.action_cancel)) } },
    )
}
