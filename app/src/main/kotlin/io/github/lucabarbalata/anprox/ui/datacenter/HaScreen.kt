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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaRuleForm
import io.github.lucabarbalata.anprox.model.pve.HaState
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
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

/** Datacenter → High availability (questionnaire Q21): the HA manager's view and the guests it manages. */
@Composable
fun HaScreen(serverId: String, onBack: () -> Unit, onTask: (serverId: String, node: String, upid: String) -> Unit) {
    val viewModel: HaViewModel = koinViewModel(key = "ha-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)

    AnProxScaffold(
        title = stringResource(R.string.ha_title),
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
            if (state.loading && state.status.isEmpty() && state.error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
            }
            if (state.status.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ha_status)) }
                item { StatusCard(state) }
            }
            if (!state.loading || state.resources.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ha_resources)) }
                item {
                    AnProxButton(
                        stringResource(R.string.ha_add),
                        onClick = viewModel::add,
                        icon = Icons.Outlined.Add,
                        enabled = !state.busy && state.candidates.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.resources.isEmpty() && state.error == null) {
                    item { EmptyState(Icons.Outlined.Sync, stringResource(R.string.ha_empty_title), stringResource(R.string.ha_empty_body)) }
                }
                items(state.resources, key = { it.sid }) { r -> ResourceCard(r, state, enabled = !state.busy) { viewModel.edit(r) } }
            }
            if (state.groupsSupported) {
                item { SectionHeader(stringResource(R.string.ha_groups)) }
                item { FormHint(stringResource(R.string.ha_groups_body)) }
                items(state.groups, key = { "g-${it.group}" }) { g ->
                    SimpleRow(
                        title = g.group,
                        subtitle = listOfNotNull(
                            g.nodes,
                            stringResource(R.string.ha_group_restricted).takeIf { g.restricted },
                            stringResource(R.string.ha_group_nofailback).takeIf { g.nofailback },
                            g.comment,
                        ).joinToString(" · "),
                        enabled = !state.busy,
                    ) { viewModel.editGroup(g) }
                }
                item {
                    AnProxButton(stringResource(R.string.ha_group_add), onClick = viewModel::addGroup, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Add, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                }
            }
            state.rules?.let { rules ->
                item { SectionHeader(stringResource(R.string.ha_rules)) }
                item { FormHint(stringResource(R.string.ha_rules_body)) }
                items(rules, key = { "r-${it.rule}" }) { r ->
                    val what = if (r.type == HaRuleForm.NODE_AFFINITY) {
                        stringResource(R.string.ha_rule_on_nodes, r.nodes.orEmpty())
                    } else {
                        stringResource(if (r.affinity == "negative") R.string.ha_rule_apart else R.string.ha_rule_together)
                    }
                    SimpleRow(
                        title = r.rule,
                        subtitle = listOfNotNull(r.resourceList.joinToString(", ") { guestLabel(state, it) }, what, stringResource(R.string.ha_rule_off).takeIf { r.disable }).joinToString(" · "),
                        enabled = !state.busy,
                    ) { viewModel.editRule(r) }
                }
                item {
                    AnProxButton(stringResource(R.string.ha_rule_add), onClick = viewModel::addRule, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Add, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    state.editing?.let { edit -> HaDialog(edit, state, viewModel) }
    state.editingGroup?.let { edit -> GroupDialog(edit, state, viewModel) }
    state.editingRule?.let { edit -> RuleDialog(edit, state, viewModel) }
    state.confirmRemove?.let { r ->
        ConfirmActionDialog(
            title = stringResource(R.string.ha_remove_title),
            message = stringResource(R.string.ha_remove_body, guestLabel(state, r.sid)),
            confirmLabel = stringResource(R.string.ha_remove),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::remove,
            onDismiss = viewModel::dismissRemove,
        )
    }
}

@Composable
private fun StatusCard(state: HaUiState) {
    val c = AnProxTheme.colors
    val quorum = state.status.firstOrNull { it.type == "quorum" }
    val master = state.status.firstOrNull { it.type == "master" }
    val lrms = state.status.filter { it.type == "lrm" }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ha_quorum), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            StatusPill(quorum?.status ?: "—", if (quorum?.status == "OK") Tone.SUCCESS else Tone.DANGER)
        }
        master?.let {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.ha_master, it.node ?: it.status.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        lrms.forEach { lrm ->
            val active = lrm.status?.contains("active") == true
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (active) Tone.SUCCESS else if (lrm.status?.contains("idle") == true) Tone.NEUTRAL else Tone.WARNING)
                Spacer(Modifier.width(8.dp))
                Text(lrm.node ?: lrm.id, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.width(88.dp))
                Text(lrm.status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ResourceCard(r: HaResource, state: HaUiState, enabled: Boolean, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    val node = state.status.firstOrNull { it.sid == r.sid }?.node ?: state.guestFor(r.sid)?.node
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(enabled = enabled, onClickLabel = stringResource(R.string.action_edit), onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(guestLabel(state, r.sid), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    listOfNotNull(node, r.group?.let { stringResource(R.string.ha_in_group, it) }, r.comment).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
            StatusPill(haStateLabel(r.haState), haStateTone(r.haState))
        }
    }
}

@Composable
private fun HaDialog(edit: HaEdit, state: HaUiState, viewModel: HaViewModel) {
    val form = edit.form
    val none = stringResource(R.string.ha_no_group)
    val stateLabels = HaState.entries.associateWith { haStateLabel(it) }
    AlertDialog(
        onDismissRequest = viewModel::close,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(if (edit.resource == null) stringResource(R.string.ha_add) else guestLabel(state, edit.resource.sid)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (edit.resource == null) {
                    FormLabel(stringResource(R.string.ha_guest))
                    val sids = state.candidates.map { HaResource.sid(if (it.type == "lxc") GuestType.LXC else GuestType.QEMU, it.vmid ?: -1) }
                    val labels = sids.associateWith { guestLabel(state, it) }
                    ChoiceChips(sids, edit.sid.orEmpty(), { labels.getValue(it) }, viewModel::pickGuest)
                }
                FormLabel(stringResource(R.string.ha_requested_state))
                ChoiceChips(HaState.entries, form.state, { stateLabels.getValue(it) }) { s -> viewModel.editForm { copy(state = s) } }
                FormHint(stringResource(haStateHelp(form.state)))
                if (state.groups.isNotEmpty() || form.group.isNotEmpty()) {
                    FormLabel(stringResource(R.string.ha_group))
                    ChoiceChips(listOf("") + state.groups.map { it.group }, form.group, { it.ifEmpty { none } }) { g -> viewModel.editForm { copy(group = g) } }
                }
                NumberField(form.maxRestart.toString(), { v -> viewModel.editForm { copy(maxRestart = v.toIntOrNull() ?: 0) } }, stringResource(R.string.ha_max_restart))
                NumberField(form.maxRelocate.toString(), { v -> viewModel.editForm { copy(maxRelocate = v.toIntOrNull() ?: 0) } }, stringResource(R.string.ha_max_relocate))
                if (!form.valid) FormHint(stringResource(R.string.ha_problem_limits), error = true)
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editForm { copy(comment = v) } }, label = stringResource(R.string.ha_comment))
                val resource = edit.resource
                if (resource != null) {
                    val current = state.status.firstOrNull { it.sid == resource.sid }?.node ?: state.guestFor(resource.sid)?.node
                    val targets = state.onlineNodes.filter { it != current }
                    if (targets.isNotEmpty()) {
                        FormLabel(stringResource(R.string.ha_migrate))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            targets.forEach { node ->
                                TextButton(onClick = { viewModel.migrate(resource, node) }) { Text(stringResource(R.string.ha_migrate_to, node)) }
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.requestRemove(resource) }) {
                        Text(stringResource(R.string.ha_remove), color = AnProxTheme.colors.danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = form.valid && edit.sid != null) {
                Text(stringResource(if (edit.resource == null) R.string.ha_add_short else R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::close) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "100 · web" for a known guest, else Proxmox's id (`vm:100`). */
private fun guestLabel(state: HaUiState, sid: String): String =
    state.guestFor(sid)?.let { g -> "${g.vmid} · ${g.name.orEmpty()}" } ?: sid

@Composable
private fun haStateLabel(s: HaState): String = stringResource(
    when (s) {
        HaState.STARTED -> R.string.ha_state_started
        HaState.STOPPED -> R.string.ha_state_stopped
        HaState.DISABLED -> R.string.ha_state_disabled
        HaState.IGNORED -> R.string.ha_state_ignored
    },
)

private fun haStateHelp(s: HaState): Int = when (s) {
    HaState.STARTED -> R.string.ha_state_started_body
    HaState.STOPPED -> R.string.ha_state_stopped_body
    HaState.DISABLED -> R.string.ha_state_disabled_body
    HaState.IGNORED -> R.string.ha_state_ignored_body
}

private fun haStateTone(s: HaState): Tone = when (s) {
    HaState.STARTED -> Tone.SUCCESS
    HaState.STOPPED -> Tone.NEUTRAL
    HaState.DISABLED -> Tone.WARNING
    HaState.IGNORED -> Tone.PAUSED
}

@Composable
private fun SimpleRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    GlassCard(contentPadding = PaddingValues(0.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable(enabled = enabled, onClickLabel = stringResource(R.string.action_edit), onClick = onClick)
                .padding(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

/** Nodes with a tick and an optional priority each (higher runs first). */
@Composable
private fun NodePriorityPicker(all: List<String>, nodes: Map<String, Int?>, onChange: (Map<String, Int?>) -> Unit) {
    val c = AnProxTheme.colors
    (all + nodes.keys).distinct().sorted().forEach { node ->
        val checked = node in nodes
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { on -> onChange(if (on) nodes + (node to null) else nodes - node) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(node, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            }
            if (checked) {
                Box(Modifier.width(120.dp)) {
                    NumberField(nodes[node]?.toString().orEmpty(), { v -> onChange(nodes + (node to v.toIntOrNull())) }, stringResource(R.string.ha_priority))
                }
            }
        }
    }
}

@Composable
private fun GroupDialog(edit: HaGroupEdit, state: HaUiState, viewModel: HaViewModel) {
    val form = edit.form
    AlertDialog(
        onDismissRequest = viewModel::closeGroup,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(edit.group?.group ?: stringResource(R.string.ha_group_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edit.group == null) {
                    GlassTextField(value = form.name, onValueChange = { v -> viewModel.editGroupForm { copy(name = v) } }, label = stringResource(R.string.ha_name))
                }
                FormLabel(stringResource(R.string.ha_nodes))
                NodePriorityPicker(state.allNodes, form.nodes) { n -> viewModel.editGroupForm { copy(nodes = n) } }
                SwitchRow(stringResource(R.string.ha_group_restricted), form.restricted, { on -> viewModel.editGroupForm { copy(restricted = on) } }, description = stringResource(R.string.ha_group_restricted_body))
                SwitchRow(stringResource(R.string.ha_group_nofailback), form.nofailback, { on -> viewModel.editGroupForm { copy(nofailback = on) } }, description = stringResource(R.string.ha_group_nofailback_body))
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editGroupForm { copy(comment = v) } }, label = stringResource(R.string.ha_comment))
                if (edit.group != null) {
                    TextButton(onClick = viewModel::deleteGroup) { Text(stringResource(R.string.ha_group_delete), color = AnProxTheme.colors.danger) }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveGroup, enabled = form.valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::closeGroup) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RuleDialog(edit: HaRuleEdit, state: HaUiState, viewModel: HaViewModel) {
    val form = edit.form
    val c = AnProxTheme.colors
    val typeLabels = mapOf(
        HaRuleForm.NODE_AFFINITY to stringResource(R.string.ha_rule_type_nodes),
        HaRuleForm.RESOURCE_AFFINITY to stringResource(R.string.ha_rule_type_guests),
    )
    val sids = state.resources.map { it.sid }
    AlertDialog(
        onDismissRequest = viewModel::closeRule,
        containerColor = c.surfaceSolid,
        title = { Text(edit.rule?.rule ?: stringResource(R.string.ha_rule_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (edit.rule == null) {
                    GlassTextField(value = form.id, onValueChange = { v -> viewModel.editRuleForm { copy(id = v) } }, label = stringResource(R.string.ha_name))
                    ChoiceChips(typeLabels.keys.toList(), form.type, { typeLabels.getValue(it) }) { t -> viewModel.editRuleForm { copy(type = t) } }
                }
                FormLabel(stringResource(R.string.ha_rule_guests))
                if (sids.isEmpty()) FormHint(stringResource(R.string.ha_rule_no_guests))
                sids.forEach { sid ->
                    val checked = sid in form.resources
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { on -> viewModel.editRuleForm { copy(resources = if (on) resources + sid else resources - sid) } })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(guestLabel(state, sid), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
                if (form.type == HaRuleForm.NODE_AFFINITY) {
                    FormLabel(stringResource(R.string.ha_nodes))
                    NodePriorityPicker(state.allNodes, form.nodes) { n -> viewModel.editRuleForm { copy(nodes = n) } }
                    SwitchRow(stringResource(R.string.ha_rule_strict), form.strict, { on -> viewModel.editRuleForm { copy(strict = on) } }, description = stringResource(R.string.ha_rule_strict_body))
                } else {
                    SwitchRow(stringResource(R.string.ha_rule_together), form.together, { on -> viewModel.editRuleForm { copy(together = on) } }, description = stringResource(R.string.ha_rule_together_body))
                }
                GlassTextField(value = form.comment, onValueChange = { v -> viewModel.editRuleForm { copy(comment = v) } }, label = stringResource(R.string.ha_comment))
                SwitchRow(stringResource(R.string.ha_rule_enabled), form.enabled, { on -> viewModel.editRuleForm { copy(enabled = on) } })
                if (edit.rule != null) {
                    TextButton(onClick = viewModel::deleteRule) { Text(stringResource(R.string.ha_rule_delete), color = c.danger) }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveRule, enabled = form.valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::closeRule) { Text(stringResource(R.string.action_cancel)) } },
    )
}

