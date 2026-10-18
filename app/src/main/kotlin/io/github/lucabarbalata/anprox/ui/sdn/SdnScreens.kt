package io.github.lucabarbalata.anprox.ui.sdn

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.IpamEntry
import io.github.lucabarbalata.anprox.model.pve.SdnControllerForm
import io.github.lucabarbalata.anprox.model.pve.SdnPluginForm
import io.github.lucabarbalata.anprox.model.pve.SdnState
import io.github.lucabarbalata.anprox.model.pve.SdnSubnet
import io.github.lucabarbalata.anprox.model.pve.SdnSubnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnVnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnZone
import io.github.lucabarbalata.anprox.model.pve.SdnZoneForm
import io.github.lucabarbalata.anprox.model.pve.SdnZoneType
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.CheckRow
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.PasswordField
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AnProxTheme.colors.accent)
    }
}

@Composable
private fun zoneTypeLabel(type: String): String = when (type) {
    "simple" -> stringResource(R.string.sdn_zone_simple)
    "vlan" -> stringResource(R.string.sdn_zone_vlan)
    "qinq" -> stringResource(R.string.sdn_zone_qinq)
    "vxlan" -> stringResource(R.string.sdn_zone_vxlan)
    "evpn" -> stringResource(R.string.sdn_zone_evpn)
    else -> type
}

@Composable
private fun StatePill(state: SdnState?) {
    when (state) {
        null -> Unit
        SdnState.NEW -> StatusPill(stringResource(R.string.sdn_state_new), Tone.INFO)
        SdnState.CHANGED -> StatusPill(stringResource(R.string.sdn_state_changed), Tone.WARNING)
        SdnState.DELETED -> StatusPill(stringResource(R.string.sdn_state_deleted), Tone.DANGER)
    }
}

/** A row of an SDN list: title, details, its pending state, and an optional delete button. */
@Composable
private fun SdnRow(title: String, body: String?, state: SdnState?, onClick: (() -> Unit)?, onDelete: (() -> Unit)? = null) {
    val c = AnProxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = if (onDelete != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            body?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        }
        StatePill(state)
        if (onDelete != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = c.textSecondary) }
        }
    }
}

@Composable
private fun AddButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    AnProxButton(text, onClick = onClick, style = ButtonStyle.SECONDARY, icon = Icons.Outlined.Add, enabled = enabled, modifier = Modifier.fillMaxWidth())
}

// ------------------------------------------------------------------------------ overview

/** Datacenter → SDN. */
@Composable
fun SdnScreen(
    serverId: String,
    onBack: () -> Unit,
    onVnet: (vnet: String) -> Unit,
    onIpam: (ipam: String) -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: SdnViewModel = koinViewModel(key = "sdn-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)
    val ready = !state.loading || state.zones.isNotEmpty()
    val enabled = !state.busy && !state.applying && state.error == null
    AnProxScaffold(
        title = stringResource(R.string.sdn_title),
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
            if (state.applying) item { BusyCard(stringResource(R.string.sdn_applying)) }
            if (state.pendingCount > 0 && !state.applying) {
                item {
                    GlassCard {
                        Text(pluralStringResource(R.plurals.sdn_pending, state.pendingCount, state.pendingCount), style = MaterialTheme.typography.titleMedium, color = AnProxTheme.colors.warning)
                        FormHint(stringResource(R.string.sdn_pending_hint))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnProxButton(stringResource(R.string.sdn_apply), onClick = viewModel::requestApply, enabled = enabled, modifier = Modifier.weight(1f))
                            AnProxButton(stringResource(R.string.sdn_discard), onClick = viewModel::requestDiscard, style = ButtonStyle.SECONDARY, enabled = enabled, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (!ready) {
                item { Spinner() }
                return@LazyColumn
            }
            if (state.error == null && state.zones.isEmpty() && state.vnets.isEmpty()) {
                item { EmptyState(Icons.Outlined.Lan, stringResource(R.string.sdn_empty), stringResource(R.string.sdn_empty_body)) }
            }

            item { SectionHeader(stringResource(R.string.sdn_zones, state.zones.size)) }
            if (state.zones.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.zones.forEach { z -> SdnRow(z.id, zoneDetails(z), z.state, onClick = { viewModel.openZone(z) }) }
                    }
                }
            }
            item { AddButton(stringResource(R.string.sdn_zone_add), enabled) { viewModel.startZone() } }

            item { SectionHeader(stringResource(R.string.sdn_vnets, state.vnets.size)) }
            if (state.vnets.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.vnets.forEach { v ->
                            val details = listOfNotNull(
                                v.alias,
                                stringResource(R.string.sdn_vnet_in_zone, v.zone),
                                v.tag?.let { stringResource(R.string.sdn_tag, it) },
                            ).joinToString(" · ")
                            SdnRow(v.id, details, v.state, onClick = { onVnet(v.id) })
                        }
                    }
                }
            }
            item { AddButton(stringResource(R.string.sdn_vnet_add), enabled && state.zones.isNotEmpty()) { viewModel.startVnet() } }

            item { SectionHeader(stringResource(R.string.sdn_controllers, state.controllers.size)) }
            if (state.controllers.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.controllers.forEach { ctl ->
                            val details = listOfNotNull(ctl.type.uppercase(), ctl.asn?.let { "ASN $it" }, ctl.node, ctl.peers.joinToString(", ").ifEmpty { null }).joinToString(" · ")
                            SdnRow(ctl.id, details, ctl.state, onClick = null, onDelete = if (enabled) ({ viewModel.requestDelete(SdnDelete.Controller(ctl.id)) }) else null)
                        }
                    }
                }
            }
            item { AddButton(stringResource(R.string.sdn_controller_add), enabled) { viewModel.startController() } }

            item { SectionHeader(stringResource(R.string.sdn_ipams, state.ipams.size)) }
            if (state.ipams.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.ipams.forEach { ipam ->
                            SdnRow(
                                ipam.id,
                                listOfNotNull(if (ipam.builtIn) stringResource(R.string.sdn_ipam_builtin) else ipam.type, ipam.url).joinToString(" · "),
                                ipam.state,
                                onClick = { onIpam(ipam.id) },
                                onDelete = if (enabled && !ipam.builtIn) ({ viewModel.requestDelete(SdnDelete.Ipam(ipam.id)) }) else null,
                            )
                        }
                    }
                }
            }
            item { AddButton(stringResource(R.string.sdn_ipam_add), enabled) { viewModel.startPlugin("netbox") } }

            item { SectionHeader(stringResource(R.string.sdn_dns, state.dns.size)) }
            if (state.dns.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.dns.forEach { d ->
                            SdnRow(d.id, listOfNotNull(d.type, d.url).joinToString(" · "), d.state, onClick = null, onDelete = if (enabled) ({ viewModel.requestDelete(SdnDelete.Dns(d.id)) }) else null)
                        }
                    }
                }
            }
            item { AddButton(stringResource(R.string.sdn_dns_add), enabled) { viewModel.startPlugin("powerdns") } }
        }
    }

    state.zoneDraft?.let { draft -> ZoneDialog(draft, state, viewModel) }
    state.vnetDraft?.let { draft ->
        VnetDialog(
            form = draft.form,
            creating = draft.existing == null,
            zones = state.zones.filter { it.state != SdnState.DELETED },
            onChange = viewModel::editVnet,
            onSave = viewModel::saveVnet,
            onClose = viewModel::closeVnet,
        )
    }
    state.controllerDraft?.let { form -> ControllerDialog(form, state, viewModel) }
    state.pluginDraft?.let { form -> PluginDialog(form, viewModel) }
    state.confirmDelete?.let { target ->
        ConfirmActionDialog(
            title = stringResource(R.string.sdn_delete_title, target.id),
            message = stringResource(
                when (target) {
                    is SdnDelete.Ipam, is SdnDelete.Dns -> R.string.sdn_delete_now_body
                    else -> R.string.sdn_delete_pending_body
                },
            ),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
    if (state.confirmApply) {
        ConfirmActionDialog(
            title = stringResource(R.string.sdn_apply_title),
            message = stringResource(R.string.sdn_apply_body),
            confirmLabel = stringResource(R.string.sdn_apply),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::apply,
            onDismiss = viewModel::dismissApply,
        )
    }
    if (state.confirmDiscard) {
        ConfirmActionDialog(
            title = stringResource(R.string.sdn_discard_title),
            message = stringResource(R.string.sdn_discard_body),
            confirmLabel = stringResource(R.string.sdn_discard),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::discard,
            onDismiss = viewModel::dismissDiscard,
        )
    }
}

@Composable
private fun zoneDetails(z: SdnZone): String = listOfNotNull(
    zoneTypeLabel(z.type),
    z.bridge,
    z.tag?.let { stringResource(R.string.sdn_tag, it) },
    z.peers.joinToString(", ").ifEmpty { null },
    z.controller,
    z.dhcp?.let { stringResource(R.string.sdn_zone_dhcp_on) },
    z.nodes.joinToString(", ").ifEmpty { null },
).joinToString(" · ")

@Composable
private fun ZoneDialog(draft: ZoneDraft, state: SdnUiState, viewModel: SdnViewModel) {
    val c = AnProxTheme.colors
    val form = draft.form
    val existing = draft.existing
    val creating = existing == null
    val problem = form.problem(creating)
    val typeLabels = SdnZoneType.entries.associateWith { zoneTypeLabel(it.apiName) }
    val none = stringResource(R.string.sdn_none)
    AlertDialog(
        onDismissRequest = viewModel::closeZone,
        containerColor = c.surfaceSolid,
        title = { Text(existing?.id ?: stringResource(R.string.sdn_zone_add)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creating) {
                    FormLabel(stringResource(R.string.sdn_zone_type))
                    ChoiceChips(SdnZoneType.entries, form.type, { typeLabels.getValue(it) }) { t -> viewModel.editZone { copy(type = t) } }
                    FormHint(stringResource(zoneTypeHint(form.type)))
                    GlassTextField(
                        value = form.id,
                        onValueChange = { v -> viewModel.editZone { copy(id = v) } },
                        label = stringResource(R.string.sdn_zone_id),
                        supportingText = stringResource(R.string.sdn_id_hint),
                        isError = form.id.isNotEmpty() && problem == SdnZoneForm.Problem.ID,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                } else {
                    FormHint(typeLabels.getValue(form.type))
                }
                when (form.type) {
                    SdnZoneType.SIMPLE -> SwitchRow(
                        stringResource(R.string.sdn_zone_dhcp),
                        form.dhcp,
                        { on -> viewModel.editZone { copy(dhcp = on) } },
                        description = stringResource(R.string.sdn_zone_dhcp_hint),
                    )
                    SdnZoneType.VLAN, SdnZoneType.QINQ -> {
                        FormLabel(stringResource(R.string.sdn_zone_bridge))
                        if (state.bridges.isEmpty()) {
                            GlassTextField(value = form.bridge, onValueChange = { v -> viewModel.editZone { copy(bridge = v.trim()) } }, label = stringResource(R.string.sdn_zone_bridge), isError = problem == SdnZoneForm.Problem.BRIDGE)
                        } else {
                            ChoiceChips((state.bridges + form.bridge).filter { it.isNotBlank() }.distinct(), form.bridge, { it }) { b -> viewModel.editZone { copy(bridge = b) } }
                        }
                        if (form.type == SdnZoneType.QINQ) {
                            NumberField(form.tag, { v -> viewModel.editZone { copy(tag = v) } }, stringResource(R.string.sdn_zone_service_vlan), stringResource(R.string.sdn_tag_range, 1, 4094), isError = problem == SdnZoneForm.Problem.TAG)
                            FormLabel(stringResource(R.string.sdn_zone_vlan_protocol))
                            ChoiceChips(listOf("802.1q", "802.1ad"), form.vlanProtocol, { it }) { p -> viewModel.editZone { copy(vlanProtocol = p) } }
                        }
                    }
                    SdnZoneType.VXLAN -> GlassTextField(
                        value = form.peers,
                        onValueChange = { v -> viewModel.editZone { copy(peers = v) } },
                        label = stringResource(R.string.sdn_zone_peers),
                        supportingText = stringResource(R.string.sdn_zone_peers_hint),
                        isError = form.peers.isNotEmpty() && problem == SdnZoneForm.Problem.PEERS,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    SdnZoneType.EVPN -> {
                        FormLabel(stringResource(R.string.sdn_zone_controller))
                        val evpn = state.controllers.filter { it.type == "evpn" && it.state != SdnState.DELETED }.map { it.id }
                        if (evpn.isEmpty()) FormHint(stringResource(R.string.sdn_zone_no_controller), error = true)
                        else ChoiceChips(evpn, form.controller, { it }) { ctl -> viewModel.editZone { copy(controller = ctl) } }
                        NumberField(form.vrfVxlan, { v -> viewModel.editZone { copy(vrfVxlan = v) } }, stringResource(R.string.sdn_zone_vrf_vxlan), stringResource(R.string.sdn_tag_range, 1, 16_777_215), isError = problem == SdnZoneForm.Problem.VRF_VXLAN)
                        if (state.nodes.isNotEmpty()) {
                            FormLabel(stringResource(R.string.sdn_zone_exit_nodes))
                            state.nodes.forEach { n ->
                                CheckRow(n.name, n.name in form.exitNodes) {
                                    viewModel.editZone { copy(exitNodes = if (n.name in exitNodes) exitNodes - n.name else exitNodes + n.name) }
                                }
                            }
                        }
                    }
                }
                NumberField(form.mtu, { v -> viewModel.editZone { copy(mtu = v) } }, stringResource(R.string.sdn_zone_mtu), stringResource(R.string.sdn_zone_mtu_hint), isError = problem == SdnZoneForm.Problem.MTU)
                SwitchRow(stringResource(R.string.sdn_zone_all_nodes), form.nodes.isEmpty(), { all -> viewModel.editZone { copy(nodes = if (all) emptySet() else state.nodes.map { it.name }.toSet()) } })
                if (form.nodes.isNotEmpty()) {
                    state.nodes.forEach { n ->
                        CheckRow(n.name, n.name in form.nodes) { viewModel.editZone { copy(nodes = if (n.name in nodes) nodes - n.name else nodes + n.name) } }
                    }
                }
                if (state.ipams.isNotEmpty()) {
                    FormLabel(stringResource(R.string.sdn_zone_ipam))
                    ChoiceChips(state.ipams.map { it.id }, form.ipam, { it }) { i -> viewModel.editZone { copy(ipam = i) } }
                }
                if (state.dns.isNotEmpty()) {
                    FormLabel(stringResource(R.string.sdn_zone_dns))
                    ChoiceChips(listOf("") + state.dns.map { it.id }, form.dns, { it.ifEmpty { none } }) { d -> viewModel.editZone { copy(dns = d) } }
                    if (form.dns.isNotEmpty()) {
                        GlassTextField(value = form.dnsZone, onValueChange = { v -> viewModel.editZone { copy(dnsZone = v) } }, label = stringResource(R.string.sdn_zone_dns_zone))
                    }
                }
                problem?.let { if (it != SdnZoneForm.Problem.ID || form.id.isNotEmpty()) FormHint(zoneProblemText(it), error = true) }
                if (existing != null && existing.state != SdnState.DELETED) {
                    Spacer(Modifier.height(4.dp))
                    AnProxButton(stringResource(R.string.sdn_zone_delete), onClick = { viewModel.requestDelete(SdnDelete.Zone(existing.id)) }, style = ButtonStyle.DANGER, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveZone, enabled = problem == null) { Text(stringResource(if (creating) R.string.sdn_create else R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::closeZone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun zoneTypeHint(type: SdnZoneType): Int = when (type) {
    SdnZoneType.SIMPLE -> R.string.sdn_zone_simple_hint
    SdnZoneType.VLAN -> R.string.sdn_zone_vlan_hint
    SdnZoneType.QINQ -> R.string.sdn_zone_qinq_hint
    SdnZoneType.VXLAN -> R.string.sdn_zone_vxlan_hint
    SdnZoneType.EVPN -> R.string.sdn_zone_evpn_hint
}

@Composable
private fun zoneProblemText(problem: SdnZoneForm.Problem): String = stringResource(
    when (problem) {
        SdnZoneForm.Problem.ID -> R.string.sdn_id_hint
        SdnZoneForm.Problem.BRIDGE -> R.string.sdn_problem_bridge
        SdnZoneForm.Problem.TAG -> R.string.sdn_problem_service_vlan
        SdnZoneForm.Problem.PEERS -> R.string.sdn_zone_peers_hint
        SdnZoneForm.Problem.CONTROLLER -> R.string.sdn_zone_no_controller
        SdnZoneForm.Problem.VRF_VXLAN -> R.string.sdn_problem_vrf_vxlan
        SdnZoneForm.Problem.MTU -> R.string.sdn_zone_mtu_hint
    },
)

@Composable
private fun VnetDialog(
    form: SdnVnetForm,
    creating: Boolean,
    zones: List<SdnZone>,
    onChange: (SdnVnetForm.() -> SdnVnetForm) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val zoneType = zones.firstOrNull { it.id == form.zone }?.zoneType
    val problem = form.problem(creating, zoneType)
    val range = zoneType?.tagRange
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(if (creating) stringResource(R.string.sdn_vnet_add) else form.id) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creating) {
                    GlassTextField(
                        value = form.id,
                        onValueChange = { v -> onChange { copy(id = v) } },
                        label = stringResource(R.string.sdn_vnet_id),
                        supportingText = stringResource(R.string.sdn_vnet_id_hint),
                        isError = form.id.isNotEmpty() && problem == SdnVnetForm.Problem.ID,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                }
                FormLabel(stringResource(R.string.sdn_vnet_zone))
                ChoiceChips(zones.map { it.id }, form.zone, { it }) { z -> onChange { copy(zone = z) } }
                if (range != null) {
                    NumberField(
                        form.tag,
                        { v -> onChange { copy(tag = v) } },
                        stringResource(if (zoneType == SdnZoneType.VLAN || zoneType == SdnZoneType.QINQ) R.string.sdn_vnet_vlan_tag else R.string.sdn_vnet_vxlan_id),
                        stringResource(R.string.sdn_tag_range, range.first, range.last),
                        isError = form.tag.isNotEmpty() && problem == SdnVnetForm.Problem.TAG,
                    )
                } else if (problem == SdnVnetForm.Problem.TAG_NOT_ALLOWED) {
                    FormHint(stringResource(R.string.sdn_vnet_no_tag), error = true)
                }
                GlassTextField(value = form.alias, onValueChange = { v -> onChange { copy(alias = v) } }, label = stringResource(R.string.sdn_vnet_alias))
                SwitchRow(stringResource(R.string.sdn_vnet_vlan_aware), form.vlanAware, { on -> onChange { copy(vlanAware = on) } }, description = stringResource(R.string.sdn_vnet_vlan_aware_hint))
                SwitchRow(stringResource(R.string.sdn_vnet_isolate), form.isolatePorts, { on -> onChange { copy(isolatePorts = on) } }, description = stringResource(R.string.sdn_vnet_isolate_hint))
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = problem == null) { Text(stringResource(if (creating) R.string.sdn_create else R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ControllerDialog(form: SdnControllerForm, state: SdnUiState, viewModel: SdnViewModel) {
    val problem = form.problem()
    AlertDialog(
        onDismissRequest = viewModel::closeController,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.sdn_controller_add)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(SdnControllerForm.TYPES, form.type, { it.uppercase() }) { t -> viewModel.editController { copy(type = t) } }
                FormHint(stringResource(if (form.type == "evpn") R.string.sdn_controller_evpn_hint else R.string.sdn_controller_bgp_hint))
                GlassTextField(
                    value = form.id,
                    onValueChange = { v -> viewModel.editController { copy(id = v) } },
                    label = stringResource(R.string.sdn_controller_id),
                    supportingText = stringResource(R.string.sdn_id_hint),
                    isError = form.id.isNotEmpty() && problem == SdnControllerForm.Problem.ID,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                NumberField(form.asn, { v -> viewModel.editController { copy(asn = v) } }, stringResource(R.string.sdn_controller_asn), isError = problem == SdnControllerForm.Problem.ASN)
                GlassTextField(
                    value = form.peers,
                    onValueChange = { v -> viewModel.editController { copy(peers = v) } },
                    label = stringResource(R.string.sdn_zone_peers),
                    supportingText = stringResource(R.string.sdn_zone_peers_hint),
                    isError = form.peers.isNotEmpty() && problem == SdnControllerForm.Problem.PEERS,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (form.type == "bgp") {
                    FormLabel(stringResource(R.string.sdn_controller_node))
                    ChoiceChips(state.nodes.map { it.name }, form.node, { it }) { n -> viewModel.editController { copy(node = n) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveController, enabled = problem == null) { Text(stringResource(R.string.sdn_create)) } },
        dismissButton = { TextButton(onClick = viewModel::closeController) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PluginDialog(form: SdnPluginForm, viewModel: SdnViewModel) {
    val dns = form.type == "powerdns"
    AlertDialog(
        onDismissRequest = viewModel::closePlugin,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(if (dns) R.string.sdn_dns_add else R.string.sdn_ipam_add)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!dns) {
                    val labels = mapOf("netbox" to "NetBox", "phpipam" to "phpIPAM")
                    ChoiceChips(SdnPluginForm.IPAM_TYPES, form.type, { labels.getValue(it) }) { t -> viewModel.editPlugin { copy(type = t) } }
                }
                GlassTextField(
                    value = form.id,
                    onValueChange = { v -> viewModel.editPlugin { copy(id = v) } },
                    label = stringResource(R.string.sdn_plugin_id),
                    supportingText = stringResource(R.string.sdn_id_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                GlassTextField(
                    value = form.url,
                    onValueChange = { v -> viewModel.editPlugin { copy(url = v) } },
                    label = stringResource(R.string.sdn_plugin_url),
                    supportingText = stringResource(if (dns) R.string.sdn_plugin_url_dns_hint else R.string.sdn_plugin_url_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                PasswordField(form.token, { v -> viewModel.editPlugin { copy(token = v) } }, stringResource(if (dns) R.string.sdn_plugin_key else R.string.sdn_plugin_token))
                if (form.type == "phpipam") {
                    NumberField(form.section, { v -> viewModel.editPlugin { copy(section = v) } }, stringResource(R.string.sdn_plugin_section))
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::savePlugin, enabled = form.valid) { Text(stringResource(R.string.sdn_create)) } },
        dismissButton = { TextButton(onClick = viewModel::closePlugin) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------ one VNet

/** Datacenter → SDN → a VNet: its settings and subnets. */
@Composable
fun SdnVnetScreen(serverId: String, vnet: String, onBack: () -> Unit) {
    val viewModel: SdnVnetViewModel = koinViewModel(key = "sdn-vnet-$serverId-$vnet") { parametersOf(serverId, vnet) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", { _, _, _ -> }, viewModel::consumeResult)
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val v = state.vnet
    AnProxScaffold(
        title = vnet,
        subtitle = v?.alias,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::editVnet, enabled = v != null && !state.busy) {
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
            if (v == null) {
                if (state.loading) item { Spinner() }
                return@LazyColumn
            }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sdn_vnet_in_zone, v.zone), style = MaterialTheme.typography.titleSmall, color = AnProxTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                            StatePill(v.state)
                        }
                        v.tag?.let { InfoRow(stringResource(if (state.zoneType == SdnZoneType.VLAN || state.zoneType == SdnZoneType.QINQ) R.string.sdn_vnet_vlan_tag else R.string.sdn_vnet_vxlan_id), it.toString()) }
                        if (v.vlanAware) FormHint(stringResource(R.string.sdn_vnet_vlan_aware))
                        if (v.isolatePorts) FormHint(stringResource(R.string.sdn_vnet_isolate))
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.sdn_subnets, state.subnets.size)) }
            if (state.subnets.isNotEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.subnets.forEach { s -> SdnRow(s.cidr, subnetDetails(s), s.state, onClick = { viewModel.openSubnet(s) }) }
                    }
                }
            } else {
                item { FormHint(stringResource(R.string.sdn_subnets_none)) }
            }
            item { AddButton(stringResource(R.string.sdn_subnet_add), !state.busy) { viewModel.startSubnet() } }
            item { Spacer(Modifier.height(4.dp)) }
            item {
                AnProxButton(
                    stringResource(R.string.sdn_vnet_delete),
                    onClick = viewModel::requestDeleteVnet,
                    style = ButtonStyle.DANGER,
                    icon = Icons.Outlined.Delete,
                    enabled = !state.busy && state.subnets.isEmpty() && v.state != SdnState.DELETED,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.subnets.isNotEmpty()) item { FormHint(stringResource(R.string.sdn_vnet_delete_hint)) }
        }
    }

    state.vnetDraft?.let { form ->
        VnetDialog(
            form = form,
            creating = false,
            zones = state.zones.filter { it.state != SdnState.DELETED },
            onChange = viewModel::changeVnet,
            onSave = viewModel::saveVnet,
            onClose = viewModel::closeVnet,
        )
    }
    state.subnetDraft?.let { draft -> SubnetDialog(draft, viewModel) }
    state.confirmDeleteSubnet?.let { s ->
        ConfirmActionDialog(
            title = stringResource(R.string.sdn_delete_title, s.cidr),
            message = stringResource(R.string.sdn_delete_pending_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::deleteSubnet,
            onDismiss = viewModel::dismissDeleteSubnet,
        )
    }
    if (state.confirmDeleteVnet) {
        ConfirmActionDialog(
            title = stringResource(R.string.sdn_delete_title, vnet),
            message = stringResource(R.string.sdn_vnet_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::deleteVnet,
            onDismiss = viewModel::dismissDeleteVnet,
        )
    }
}

@Composable
private fun subnetDetails(s: SdnSubnet): String = listOfNotNull(
    s.gateway?.let { stringResource(R.string.sdn_subnet_gateway_value, it) },
    if (s.snat) stringResource(R.string.sdn_subnet_snat) else null,
    s.dhcpRanges.firstOrNull()?.let { r ->
        val parts = r.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        stringResource(R.string.sdn_subnet_dhcp_value, parts["start-address"].orEmpty(), parts["end-address"].orEmpty())
    },
).joinToString(" · ")

@Composable
private fun SubnetDialog(draft: SubnetDraft, viewModel: SdnVnetViewModel) {
    val form = draft.form
    val existing = draft.existing
    val creating = existing == null
    val problem = form.problem(creating)
    AlertDialog(
        onDismissRequest = viewModel::closeSubnet,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(existing?.cidr ?: stringResource(R.string.sdn_subnet_add)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (creating) {
                    GlassTextField(
                        value = form.cidr,
                        onValueChange = { v -> viewModel.editSubnet { copy(cidr = v.trim()) } },
                        label = stringResource(R.string.sdn_subnet_cidr),
                        placeholder = "10.20.0.0/24",
                        isError = form.cidr.isNotEmpty() && problem == SdnSubnetForm.Problem.CIDR,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                GlassTextField(
                    value = form.gateway,
                    onValueChange = { v -> viewModel.editSubnet { copy(gateway = v.trim()) } },
                    label = stringResource(R.string.sdn_subnet_gateway),
                    isError = problem == SdnSubnetForm.Problem.GATEWAY,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                SwitchRow(stringResource(R.string.sdn_subnet_snat), form.snat, { on -> viewModel.editSubnet { copy(snat = on) } }, description = stringResource(R.string.sdn_subnet_snat_hint))
                FormLabel(stringResource(R.string.sdn_subnet_dhcp))
                FormHint(stringResource(R.string.sdn_subnet_dhcp_hint))
                GlassTextField(
                    value = form.dhcpStart,
                    onValueChange = { v -> viewModel.editSubnet { copy(dhcpStart = v.trim()) } },
                    label = stringResource(R.string.sdn_subnet_dhcp_start),
                    isError = problem == SdnSubnetForm.Problem.DHCP_RANGE,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                GlassTextField(
                    value = form.dhcpEnd,
                    onValueChange = { v -> viewModel.editSubnet { copy(dhcpEnd = v.trim()) } },
                    label = stringResource(R.string.sdn_subnet_dhcp_end),
                    isError = problem == SdnSubnetForm.Problem.DHCP_RANGE,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                problem?.let { p ->
                    if (p != SdnSubnetForm.Problem.CIDR || form.cidr.isNotEmpty()) FormHint(subnetProblemText(p), error = true)
                }
                if (existing != null && existing.state != SdnState.DELETED) {
                    Spacer(Modifier.height(4.dp))
                    AnProxButton(stringResource(R.string.sdn_subnet_delete), onClick = { viewModel.requestDeleteSubnet(existing) }, style = ButtonStyle.DANGER, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveSubnet, enabled = problem == null) { Text(stringResource(if (creating) R.string.sdn_create else R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::closeSubnet) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun subnetProblemText(problem: SdnSubnetForm.Problem): String = stringResource(
    when (problem) {
        SdnSubnetForm.Problem.CIDR -> R.string.sdn_problem_cidr
        SdnSubnetForm.Problem.GATEWAY -> R.string.sdn_problem_gateway
        SdnSubnetForm.Problem.DHCP_RANGE -> R.string.sdn_problem_dhcp
    },
)

// ------------------------------------------------------------------------------ IPAM

/** Datacenter → SDN → an IPAM: the addresses it handed out. */
@Composable
fun SdnIpamScreen(serverId: String, ipam: String, onBack: () -> Unit) {
    val viewModel: SdnIpamViewModel = koinViewModel(key = "sdn-ipam-$serverId-$ipam") { parametersOf(serverId, ipam) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnProxScaffold(
        title = stringResource(R.string.sdn_ipam_title, ipam),
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { GlassTextField(value = state.filter, onValueChange = viewModel::setFilter, label = stringResource(R.string.firewall_log_filter)) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.entries.isEmpty()) item { Spinner() }
            if (!state.loading && state.error == null && state.entries.isEmpty()) item { FormHint(stringResource(R.string.sdn_ipam_empty)) }
            items(state.shown, key = { "${it.vnet}-${it.ip}" }) { e -> IpamRow(e) }
        }
    }
}

@Composable
private fun IpamRow(e: IpamEntry) {
    val c = AnProxTheme.colors
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.ip, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                val details = listOfNotNull(
                    if (e.gateway) stringResource(R.string.sdn_ipam_gateway) else null,
                    e.hostname,
                    e.vmid?.let { stringResource(R.string.sdn_ipam_guest, it) },
                    e.mac,
                ).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            e.vnet?.let { StatusPill(it, Tone.INFO) }
        }
    }
}
