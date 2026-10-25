package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.component.color
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.FirewallOptions
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Firewall rules and options of the datacenter, a node or a guest (questionnaire Q24). Rules
 * apply top to bottom: the arrows move one up or down.
 */
@Composable
fun FirewallScreen(
    serverId: String,
    scope: FirewallScope,
    subtitle: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onExtra: (FirewallExtra) -> Unit = {},
) {
    val viewModel: FirewallViewModel = koinViewModel(key = "firewall-$serverId-${scope.path}") { parametersOf(serverId, scope) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)
    val tabLabels = mapOf(
        FirewallTab.RULES to stringResource(R.string.firewall_rules),
        FirewallTab.OPTIONS to stringResource(R.string.firewall_options),
    )

    AnProxScaffold(
        title = if (scope is FirewallScope.Group) stringResource(R.string.firewall_group_title, scope.group) else stringResource(R.string.firewall_title),
        subtitle = subtitle,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scope.hasOptions) item { SegmentedPicker(FirewallTab.entries, state.tab, { tabLabels.getValue(it) }, viewModel::selectTab) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.rules.isEmpty() && state.options == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
            } else if (state.error == null) {
                when (state.tab) {
                    FirewallTab.RULES -> {
                        // The datacenter switch turns the whole firewall on or off.
                        if (state.options?.flag("enable") == false) {
                            item { ErrorBanner(stringResource(R.string.firewall_disabled_hint), tone = Tone.WARNING) }
                        }
                        item {
                            AnProxButton(
                                stringResource(R.string.firewall_add_rule),
                                onClick = viewModel::add,
                                icon = Icons.Outlined.Add,
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (state.rules.isEmpty()) {
                            item { EmptyState(Icons.Outlined.Shield, stringResource(R.string.firewall_empty_title), stringResource(R.string.firewall_empty_body)) }
                        }
                        items(state.rules, key = { "${it.pos}-${it.digest}" }) { rule ->
                            RuleCard(
                                rule = rule,
                                first = rule.pos == 0,
                                last = rule.pos == state.rules.lastIndex,
                                enabled = !state.busy,
                                onEdit = { viewModel.edit(rule) },
                                onToggle = { viewModel.setEnabled(rule, it) },
                                onMove = { by -> viewModel.move(rule, by) },
                            )
                        }
                        item { FirewallExtrasCard(scope, onExtra) }
                    }
                    FirewallTab.OPTIONS -> item { OptionsCard(state, viewModel) }
                }
            }
        }
    }

    state.editing?.let { edit -> RuleDialog(edit, state, viewModel) }
    state.confirmDelete?.let {
        ConfirmActionDialog(
            title = stringResource(R.string.firewall_delete_title),
            message = stringResource(R.string.firewall_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun RuleCard(
    rule: FirewallRule,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    val c = AnProxTheme.colors
    val actionTone = when (rule.action) {
        "ACCEPT" -> Tone.SUCCESS
        "DROP" -> Tone.DANGER
        "REJECT" -> Tone.WARNING
        else -> Tone.ACCENT
    }
    val what = when {
        rule.type == "group" -> stringResource(R.string.firewall_group_rule, rule.action)
        !rule.macro.isNullOrBlank() -> rule.macro.orEmpty()
        else -> listOfNotNull(rule.proto, rule.dport?.let { stringResource(R.string.firewall_port, it) }).joinToString(" ").ifEmpty { stringResource(R.string.firewall_any_traffic) }
    }
    val from = rule.source?.takeIf { it.isNotBlank() } ?: stringResource(R.string.firewall_anywhere)
    val to = rule.dest?.takeIf { it.isNotBlank() } ?: stringResource(R.string.firewall_anywhere)
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = stringResource(R.string.action_edit), onClick = onEdit)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagChip(rule.type.uppercase())
                    Spacer(Modifier.width(8.dp))
                    if (rule.type != "group") {
                        Text(rule.action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = actionTone.color())
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(what, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (rule.type != "group") {
                    Text(stringResource(R.string.firewall_from_to, from, to), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2)
                }
                rule.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1) }
            }
            Column {
                IconButton(onClick = { onMove(-1) }, enabled = enabled && !first) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.firewall_move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = enabled && !last) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.firewall_move_down))
                }
            }
            Switch(checked = rule.enable, onCheckedChange = onToggle, enabled = enabled, modifier = Modifier.semantics { contentDescription = what })
        }
    }
}

@Composable
private fun OptionsCard(state: FirewallState, viewModel: FirewallViewModel) {
    val scope = state.scope
    val hasPolicies = scope !is FirewallScope.Node
    val hasLogLevels = scope !is FirewallScope.Datacenter
    GlassCard {
        SwitchRow(
            title = stringResource(R.string.firewall_enabled),
            description = stringResource(
                when (scope) {
                    FirewallScope.Datacenter -> R.string.firewall_enabled_dc
                    is FirewallScope.Node -> R.string.firewall_enabled_node
                    is FirewallScope.Guest -> R.string.firewall_enabled_guest
                    // A security group has no options; this card isn't shown for one.
                    is FirewallScope.Group -> R.string.firewall_enabled_dc
                },
            ),
            checked = state.option("enable") == "1",
            onCheckedChange = { on -> viewModel.setOption("enable", if (on) "1" else "0") },
        )
        if (hasPolicies) {
            Spacer(Modifier.height(8.dp))
            FormLabel(stringResource(R.string.firewall_policy_in))
            Spacer(Modifier.height(6.dp))
            SegmentedPicker(FirewallOptions.POLICIES, state.option("policy_in"), { it }, { p -> viewModel.setOption("policy_in", p) })
            Spacer(Modifier.height(8.dp))
            FormLabel(stringResource(R.string.firewall_policy_out))
            Spacer(Modifier.height(6.dp))
            SegmentedPicker(FirewallOptions.POLICIES, state.option("policy_out"), { it }, { p -> viewModel.setOption("policy_out", p) })
        }
        if (hasLogLevels) {
            Spacer(Modifier.height(8.dp))
            FormLabel(stringResource(R.string.firewall_log_in))
            ChoiceChips(FirewallRuleForm.LOG_LEVELS, state.option("log_level_in"), { it }) { l -> viewModel.setOption("log_level_in", l) }
            Spacer(Modifier.height(8.dp))
            FormLabel(stringResource(R.string.firewall_log_out))
            ChoiceChips(FirewallRuleForm.LOG_LEVELS, state.option("log_level_out"), { it }) { l -> viewModel.setOption("log_level_out", l) }
        }
        if (scope is FirewallScope.Guest) {
            val labels = mapOf(
                "dhcp" to R.string.firewall_dhcp,
                "ndp" to R.string.firewall_ndp,
                "radv" to R.string.firewall_radv,
                "macfilter" to R.string.firewall_macfilter,
                "ipfilter" to R.string.firewall_ipfilter,
            )
            Spacer(Modifier.height(8.dp))
            FirewallOptions.GUEST_FLAGS.forEach { key ->
                SwitchRow(
                    title = stringResource(labels.getValue(key)),
                    checked = state.option(key) == "1",
                    onCheckedChange = { on -> viewModel.setOption(key, if (on) "1" else "0") },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AnProxButton(
            stringResource(R.string.action_save),
            onClick = viewModel::saveOptions,
            icon = Icons.Outlined.Save,
            enabled = state.optionsDirty && !state.busy,
            loading = state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RuleDialog(edit: RuleEdit, state: FirewallState, viewModel: FirewallViewModel) {
    val form = edit.form
    val problem = form.problem()
    val none = stringResource(R.string.firewall_none)
    val any = stringResource(R.string.firewall_any)
    // A security group can't insert another one.
    val types = if (state.scope is FirewallScope.Group) listOf("in", "out") else listOf("in", "out", "group")
    val typeLabels = mapOf(
        "in" to stringResource(R.string.firewall_in),
        "out" to stringResource(R.string.firewall_out),
        "group" to stringResource(R.string.firewall_group),
    )
    AlertDialog(
        onDismissRequest = viewModel::closeEditor,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(if (edit.rule == null) R.string.firewall_add_rule else R.string.firewall_edit_rule)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedPicker(types, form.type, { typeLabels.getValue(it) }, { t ->
                    viewModel.editForm { copy(type = t, action = if (t == "group") state.groups.firstOrNull()?.group.orEmpty() else "ACCEPT") }
                })
                if (form.isGroup) {
                    FormLabel(stringResource(R.string.firewall_security_group))
                    if (state.groups.isEmpty()) {
                        FormHint(stringResource(R.string.firewall_no_groups), error = true)
                    } else {
                        ChoiceChips(state.groups.map { it.group }, form.action, { it }) { g -> viewModel.editForm { copy(action = g) } }
                    }
                } else {
                    SegmentedPicker(FirewallRuleForm.ACTIONS, form.action, { it }, { a -> viewModel.editForm { copy(action = a) } })
                    FormLabel(stringResource(R.string.firewall_macro))
                    ChoiceChips(listOf("") + state.macros.map { it.macro }, form.macro, { it.ifEmpty { none } }) { m -> viewModel.editForm { copy(macro = m) } }
                    state.macros.firstOrNull { it.macro == form.macro }?.descr?.let { FormHint(it) }
                    FormLabel(stringResource(R.string.firewall_protocol))
                    ChoiceChips(listOf("") + FirewallRuleForm.PROTOCOLS, form.proto, { it.ifEmpty { any } }) { p -> viewModel.editForm { copy(proto = p) } }
                    GlassTextField(
                        value = form.source,
                        onValueChange = { v -> viewModel.editForm { copy(source = v) } },
                        label = stringResource(R.string.firewall_source),
                        supportingText = stringResource(R.string.firewall_address_hint),
                    )
                    GlassTextField(value = form.dest, onValueChange = { v -> viewModel.editForm { copy(dest = v) } }, label = stringResource(R.string.firewall_dest))
                    GlassTextField(
                        value = form.dport,
                        onValueChange = { v -> viewModel.editForm { copy(dport = v) } },
                        label = stringResource(R.string.firewall_dport),
                        supportingText = stringResource(R.string.firewall_port_hint),
                    )
                    GlassTextField(value = form.sport, onValueChange = { v -> viewModel.editForm { copy(sport = v) } }, label = stringResource(R.string.firewall_sport))
                    FormLabel(stringResource(R.string.firewall_log))
                    ChoiceChips(FirewallRuleForm.LOG_LEVELS, form.log, { it }) { l -> viewModel.editForm { copy(log = l) } }
                }
                GlassTextField(
                    value = form.iface,
                    onValueChange = { v -> viewModel.editForm { copy(iface = v) } },
                    label = stringResource(R.string.firewall_iface),
                    supportingText = stringResource(R.string.firewall_iface_hint),
                )
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editForm { copy(comment = v) } }, label = stringResource(R.string.firewall_comment))
                SwitchRow(
                    title = stringResource(R.string.firewall_rule_enabled),
                    checked = form.enabled,
                    onCheckedChange = { on -> viewModel.editForm { copy(enabled = on) } },
                )
                problem?.let { FormHint(ruleProblem(it), error = true) }
                if (edit.rule != null) {
                    TextButton(onClick = { viewModel.requestDelete(edit.rule) }) {
                        Text(stringResource(R.string.firewall_delete_rule), color = AnProxTheme.colors.danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::saveRule, enabled = problem == null) {
                Text(stringResource(if (edit.rule == null) R.string.firewall_add else R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeEditor) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ruleProblem(problem: FirewallRuleForm.Problem): String = stringResource(
    when (problem) {
        FirewallRuleForm.Problem.GROUP -> R.string.firewall_problem_group
        FirewallRuleForm.Problem.PORTS_NEED_PROTOCOL -> R.string.firewall_problem_protocol
        FirewallRuleForm.Problem.PORT -> R.string.firewall_problem_port
        FirewallRuleForm.Problem.ADDRESS -> R.string.firewall_problem_address
    },
)
