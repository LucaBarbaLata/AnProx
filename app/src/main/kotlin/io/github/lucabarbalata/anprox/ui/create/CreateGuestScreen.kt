package io.github.lucabarbalata.anprox.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.DiskBus
import io.github.lucabarbalata.anprox.model.pve.GuestOs
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NewContainer
import io.github.lucabarbalata.anprox.model.pve.NewVm
import io.github.lucabarbalata.anprox.model.pve.VmCpuMemory
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import io.github.lucabarbalata.anprox.model.pve.VmNet
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BridgePicker
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.StoragePicker
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.hardware.NET_MODEL_NAMES
import io.github.lucabarbalata.anprox.ui.hardware.busName
import io.github.lucabarbalata.anprox.ui.hardware.mib
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Create a VM or container on [node] (questionnaire Q17): the choices most people need, and an
 * **Advanced** switch for the rest. When the task has finished, **Open it** goes to the new guest.
 */
@Composable
fun CreateGuestScreen(
    serverId: String,
    node: String,
    type: GuestType,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onOpenGuest: (GuestRef) -> Unit,
) {
    val key = "create-$serverId-$node-${type.apiName}"
    val viewModel: CreateGuestViewModel = koinViewModel(key = key) { parametersOf(serverId, node, type) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    val isVm = type == GuestType.QEMU

    AnProxScaffold(
        title = stringResource(if (isVm) R.string.create_vm_title else R.string.create_ct_title),
        subtitle = stringResource(R.string.create_on_node, node),
        onBack = onBack,
        snackbarHostState = snackbar,
    ) { inner ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(contentPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { ErrorBanner(it.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent)
                }
                state.error == null -> {
                    if (isVm) VmForm(state, viewModel) else CtForm(state, viewModel)
                    SwitchRow(
                        title = stringResource(R.string.create_advanced),
                        description = stringResource(R.string.create_advanced_body),
                        checked = state.advanced,
                        onCheckedChange = viewModel::setAdvanced,
                    )
                    if (state.advanced) {
                        if (isVm) VmAdvanced(state, viewModel) else CtAdvanced(state, viewModel)
                    }
                    Footer(state, isVm, viewModel, onOpenGuest)
                }
            }
        }
    }
}

@Composable
private fun Footer(state: CreateGuestState, isVm: Boolean, viewModel: CreateGuestViewModel, onOpenGuest: (GuestRef) -> Unit) {
    val c = AnProxTheme.colors
    SwitchRow(
        title = stringResource(R.string.create_start),
        checked = if (isVm) state.vm.start else state.ct.start,
        onCheckedChange = { on -> if (isVm) viewModel.editVm { copy(start = on) } else viewModel.editCt { copy(start = on) } },
    )
    val problem = if (isVm) vmProblemText(state) else ctProblemText(state)
    if (problem != null && state.created == null) FormHint(problem, error = true)
    if (state.busy) BusyCard(stringResource(if (isVm) R.string.create_busy_vm else R.string.create_busy_ct))
    val created = state.created
    if (created != null) {
        GlassCard {
            Text(
                stringResource(R.string.create_done, stringResource(if (isVm) R.string.guest_type_vm else R.string.guest_type_ct), created.vmid, created.node),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
            )
            AnProxButton(
                text = stringResource(R.string.clone_open),
                onClick = { onOpenGuest(created) },
                style = ButtonStyle.SECONDARY,
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    } else {
        AnProxButton(
            text = stringResource(if (isVm) R.string.create_vm_action else R.string.create_ct_action),
            onClick = viewModel::create,
            icon = Icons.Outlined.Add,
            enabled = state.canCreate,
            loading = state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------------------------ VMs

@Composable
private fun VmForm(state: CreateGuestState, viewModel: CreateGuestViewModel) {
    val c = AnProxTheme.colors
    val vm = state.vm
    val problem = state.vmProblem
    val osLabels = mapOf(
        GuestOs.LINUX to stringResource(R.string.create_os_linux),
        GuestOs.WINDOWS to stringResource(R.string.create_os_windows),
        GuestOs.OTHER to stringResource(R.string.create_os_other),
    )
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IdField(state, viewModel, idProblem = problem == NewVm.Problem.ID || problem == NewVm.Problem.ID_TAKEN)
            GlassTextField(
                value = vm.name,
                onValueChange = { v -> viewModel.editVm { copy(name = v.trim()) } },
                label = stringResource(R.string.create_vm_name),
                supportingText = stringResource(if (problem == NewVm.Problem.NAME) R.string.options_name_error else R.string.create_name_help),
                isError = problem == NewVm.Problem.NAME,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
            )
        }
    }
    SectionHeader(stringResource(R.string.create_os))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedPicker(GuestOs.entries, vm.os, { osLabels.getValue(it) }, { os -> viewModel.editVm { copy(os = os) } })
            if (vm.os == GuestOs.WINDOWS) FormHint(stringResource(R.string.create_os_windows_body))
            FormLabel(stringResource(R.string.create_iso))
            RadioRow(selected = vm.iso == null, title = stringResource(R.string.create_iso_none), body = null) { viewModel.editVm { copy(iso = null) } }
            if (state.files.isEmpty()) FormHint(stringResource(R.string.hardware_no_isos))
            state.files.forEach { iso ->
                RadioRow(
                    selected = vm.iso == iso.volid,
                    title = iso.volid.substringAfterLast('/'),
                    body = "${iso.volid.substringBefore(':')} · ${Formatters.bytes(iso.size)}",
                ) { viewModel.editVm { copy(iso = iso.volid) } }
            }
        }
    }
    SectionHeader(stringResource(R.string.create_disk))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StoragePicker(state.storages, vm.storage, { s -> viewModel.editVm { copy(storage = s) } })
            NumberField(
                state.numbers[NumberField.DISK].orEmpty(),
                { viewModel.setNumber(NumberField.DISK, it) },
                stringResource(R.string.hardware_size_gib),
                isError = problem == NewVm.Problem.SIZE,
                supportingText = if (problem == NewVm.Problem.SIZE) stringResource(R.string.create_size_error) else null,
            )
        }
    }
    SectionHeader(stringResource(R.string.hardware_cpu_memory))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    NumberField(
                        state.numbers[NumberField.CORES].orEmpty(),
                        { viewModel.setNumber(NumberField.CORES, it) },
                        stringResource(R.string.hardware_cores),
                        isError = problem == NewVm.Problem.CORES || problem == NewVm.Problem.TOO_MANY_CPUS,
                    )
                }
                Column(Modifier.weight(1f)) {
                    NumberField(
                        state.numbers[NumberField.MEMORY].orEmpty(),
                        { viewModel.setNumber(NumberField.MEMORY, it) },
                        stringResource(R.string.hardware_memory_mib),
                        supportingText = state.numbers[NumberField.MEMORY]?.toIntOrNull()?.let { mib(it) },
                        isError = problem == NewVm.Problem.MEMORY,
                    )
                }
            }
            state.hostCpus?.let { FormHint(stringResource(R.string.create_host_cpus, it), error = problem == NewVm.Problem.TOO_MANY_CPUS) }
        }
    }
    SectionHeader(stringResource(R.string.hardware_network))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BridgePicker(state.bridges, vm.bridge) { b -> viewModel.editVm { copy(bridge = b) } }
            Text(
                stringResource(R.string.create_net_model, NET_MODEL_NAMES[vm.nic] ?: vm.nic),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    }
}

@Composable
private fun VmAdvanced(state: CreateGuestState, viewModel: CreateGuestViewModel) {
    val vm = state.vm
    val problem = state.vmProblem
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                state.numbers[NumberField.SOCKETS].orEmpty(),
                { viewModel.setNumber(NumberField.SOCKETS, it) },
                stringResource(R.string.hardware_sockets),
                isError = problem == NewVm.Problem.SOCKETS,
                supportingText = stringResource(R.string.hardware_sockets_error, VmCpuMemory.MAX_SOCKETS),
            )
            FormLabel(stringResource(R.string.hardware_cpu_type))
            ChoiceChips(VmHardware.CPU_TYPES, vm.cpuType, { it }, { t -> viewModel.editVm { copy(cpuType = t) } })
            FormHint(stringResource(R.string.hardware_cpu_type_help))
            FormLabel(stringResource(R.string.hardware_bus))
            SegmentedPicker(DiskBus.entries, vm.diskBus, ::busName, { b -> viewModel.editVm { copy(bus = b) } })
            SwitchRow(stringResource(R.string.hardware_discard), vm.discard, { on -> viewModel.editVm { copy(discard = on) } }, description = stringResource(R.string.hardware_discard_body))
            SwitchRow(stringResource(R.string.hardware_ballooning), vm.ballooning, { on -> viewModel.editVm { copy(ballooning = on) } }, description = stringResource(R.string.hardware_ballooning_body))
            FormLabel(stringResource(R.string.hardware_model))
            ChoiceChips(VmNet.MODELS, vm.nic, { NET_MODEL_NAMES[it] ?: it }, { m -> viewModel.editVm { copy(netModel = m) } })
            NumberField(
                state.numbers[NumberField.VLAN].orEmpty(),
                { viewModel.setNumber(NumberField.VLAN, it) },
                stringResource(R.string.hardware_vlan),
                supportingText = stringResource(if (problem == NewVm.Problem.VLAN) R.string.hardware_vlan_error else R.string.hardware_vlan_help),
                isError = problem == NewVm.Problem.VLAN,
            )
            SwitchRow(stringResource(R.string.hardware_firewall), vm.firewall, { on -> viewModel.editVm { copy(firewall = on) } }, description = stringResource(R.string.hardware_firewall_body))
            SwitchRow(stringResource(R.string.create_uefi), vm.usesUefi, { on -> viewModel.editVm { copy(uefi = on) } }, description = stringResource(R.string.create_uefi_body))
            SwitchRow(stringResource(R.string.options_agent), vm.agent, { on -> viewModel.editVm { copy(agent = on) } }, description = stringResource(R.string.options_agent_body))
            SwitchRow(stringResource(R.string.options_onboot), vm.onBoot, { on -> viewModel.editVm { copy(onBoot = on) } }, description = stringResource(R.string.options_onboot_body))
        }
    }
}

@Composable
private fun vmProblemText(state: CreateGuestState): String? = when (state.vmProblem) {
    null -> null
    NewVm.Problem.ID -> stringResource(R.string.clone_id_error)
    NewVm.Problem.ID_TAKEN -> stringResource(R.string.create_id_taken)
    NewVm.Problem.NAME -> stringResource(R.string.options_name_error)
    NewVm.Problem.STORAGE -> stringResource(R.string.hardware_no_storage)
    NewVm.Problem.SIZE -> stringResource(R.string.create_size_error)
    NewVm.Problem.SOCKETS -> stringResource(R.string.hardware_sockets_error, VmCpuMemory.MAX_SOCKETS)
    NewVm.Problem.CORES -> stringResource(R.string.create_cores_error)
    NewVm.Problem.TOO_MANY_CPUS -> stringResource(R.string.hardware_too_many_cpus, state.hostCpus ?: 0)
    NewVm.Problem.MEMORY -> stringResource(R.string.hardware_memory_error, VmCpuMemory.MIN_MEMORY_MIB)
    NewVm.Problem.BRIDGE -> stringResource(R.string.create_bridge_error)
    NewVm.Problem.VLAN -> stringResource(R.string.hardware_vlan_error)
}

// ------------------------------------------------------------------------------------ containers

@Composable
private fun CtForm(state: CreateGuestState, viewModel: CreateGuestViewModel) {
    val ct = state.ct
    val problem = state.ctProblem
    val v4Labels = mapOf(
        CtNetSettings.Ipv4Mode.DHCP to stringResource(R.string.hardware_ipv4_dhcp),
        CtNetSettings.Ipv4Mode.STATIC to stringResource(R.string.hardware_ip_static),
        CtNetSettings.Ipv4Mode.NONE to stringResource(R.string.hardware_ip_none),
    )
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IdField(state, viewModel, idProblem = problem == NewContainer.Problem.ID || problem == NewContainer.Problem.ID_TAKEN)
            GlassTextField(
                value = ct.hostname,
                onValueChange = { v -> viewModel.editCt { copy(hostname = v.trim()) } },
                label = stringResource(R.string.options_name_ct),
                supportingText = stringResource(if (problem == NewContainer.Problem.HOSTNAME) R.string.options_name_error else R.string.create_name_help),
                isError = problem == NewContainer.Problem.HOSTNAME,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri),
            )
        }
    }
    SectionHeader(stringResource(R.string.create_template))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.files.isEmpty()) FormHint(stringResource(R.string.create_no_templates), error = true)
            state.files.forEach { t ->
                RadioRow(
                    selected = ct.template == t.volid,
                    title = NewContainer.templateName(t.volid),
                    body = "${t.volid.substringBefore(':')} · ${Formatters.bytes(t.size)}",
                ) { viewModel.editCt { copy(template = t.volid) } }
            }
        }
    }
    SectionHeader(stringResource(R.string.create_login))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = ct.password,
                onValueChange = { v -> viewModel.editCt { copy(password = v) } },
                label = stringResource(R.string.create_password),
                supportingText = stringResource(
                    if (problem == NewContainer.Problem.PASSWORD) R.string.create_password_error else R.string.create_password_help,
                    NewContainer.MIN_PASSWORD,
                ),
                isError = problem == NewContainer.Problem.PASSWORD,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (ct.password.isNotEmpty()) {
                GlassTextField(
                    value = state.passwordAgain,
                    onValueChange = viewModel::setPasswordAgain,
                    label = stringResource(R.string.create_password_again),
                    supportingText = if (state.passwordsDiffer) stringResource(R.string.create_password_differ) else null,
                    isError = state.passwordsDiffer,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            GlassTextField(
                value = ct.sshKeys,
                onValueChange = { v -> viewModel.editCt { copy(sshKeys = v) } },
                label = stringResource(R.string.create_ssh_keys),
                supportingText = stringResource(if (problem == NewContainer.Problem.SSH_KEY) R.string.create_ssh_keys_error else R.string.create_ssh_keys_help),
                isError = problem == NewContainer.Problem.SSH_KEY,
                singleLine = false,
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
            )
        }
    }
    SectionHeader(stringResource(R.string.create_disk))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StoragePicker(state.storages, ct.storage, { s -> viewModel.editCt { copy(storage = s) } })
            NumberField(
                state.numbers[NumberField.DISK].orEmpty(),
                { viewModel.setNumber(NumberField.DISK, it) },
                stringResource(R.string.hardware_size_gib),
                isError = problem == NewContainer.Problem.SIZE,
                supportingText = if (problem == NewContainer.Problem.SIZE) stringResource(R.string.create_size_error) else null,
            )
        }
    }
    SectionHeader(stringResource(R.string.hardware_resources))
    GlassCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                NumberField(
                    state.numbers[NumberField.CORES].orEmpty(),
                    { viewModel.setNumber(NumberField.CORES, it) },
                    stringResource(R.string.hardware_cores),
                    isError = problem == NewContainer.Problem.CORES,
                )
            }
            Column(Modifier.weight(1f)) {
                NumberField(
                    state.numbers[NumberField.MEMORY].orEmpty(),
                    { viewModel.setNumber(NumberField.MEMORY, it) },
                    stringResource(R.string.hardware_memory_mib),
                    supportingText = state.numbers[NumberField.MEMORY]?.toIntOrNull()?.let { mib(it) },
                    isError = problem == NewContainer.Problem.MEMORY,
                )
            }
        }
    }
    SectionHeader(stringResource(R.string.hardware_network))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BridgePicker(state.bridges, ct.network.bridge) { b -> viewModel.editCtNetwork { copy(bridge = b) } }
            FormLabel(stringResource(R.string.hardware_ipv4))
            SegmentedPicker(CtNetSettings.Ipv4Mode.entries, ct.network.ipv4, { v4Labels.getValue(it) }, { m -> viewModel.editCtNetwork { copy(ipv4 = m) } })
            if (ct.network.ipv4 == CtNetSettings.Ipv4Mode.STATIC) {
                GlassTextField(
                    value = ct.network.ip,
                    onValueChange = { v -> viewModel.editCtNetwork { copy(ip = v.trim()) } },
                    label = stringResource(R.string.hardware_ipv4_address),
                    supportingText = stringResource(R.string.hardware_ipv4_help),
                    isError = problem == NewContainer.Problem.NETWORK,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                GlassTextField(
                    value = ct.network.gateway,
                    onValueChange = { v -> viewModel.editCtNetwork { copy(gateway = v.trim()) } },
                    label = stringResource(R.string.hardware_gateway),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }
    }
}

@Composable
private fun CtAdvanced(state: CreateGuestState, viewModel: CreateGuestViewModel) {
    val ct = state.ct
    val problem = state.ctProblem
    val v6Labels = mapOf(
        CtNetSettings.Ipv6Mode.NONE to stringResource(R.string.hardware_ip_none),
        CtNetSettings.Ipv6Mode.DHCP to stringResource(R.string.hardware_ipv6_dhcp),
        CtNetSettings.Ipv6Mode.SLAAC to stringResource(R.string.hardware_ipv6_slaac),
        CtNetSettings.Ipv6Mode.STATIC to stringResource(R.string.hardware_ip_static),
    )
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                state.numbers[NumberField.SWAP].orEmpty(),
                { viewModel.setNumber(NumberField.SWAP, it) },
                stringResource(R.string.hardware_swap_mib),
                supportingText = state.numbers[NumberField.SWAP]?.toIntOrNull()?.let { mib(it) },
            )
            FormLabel(stringResource(R.string.hardware_ipv6))
            SegmentedPicker(CtNetSettings.Ipv6Mode.entries, ct.network.ipv6, { v6Labels.getValue(it) }, { m -> viewModel.editCtNetwork { copy(ipv6 = m) } })
            if (ct.network.ipv6 == CtNetSettings.Ipv6Mode.STATIC) {
                GlassTextField(
                    value = ct.network.ip6,
                    onValueChange = { v -> viewModel.editCtNetwork { copy(ip6 = v.trim()) } },
                    label = stringResource(R.string.hardware_ipv6_address),
                    supportingText = stringResource(R.string.hardware_ipv6_help),
                    isError = problem == NewContainer.Problem.NETWORK,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                GlassTextField(
                    value = ct.network.gateway6,
                    onValueChange = { v -> viewModel.editCtNetwork { copy(gateway6 = v.trim()) } },
                    label = stringResource(R.string.hardware_gateway6),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            NumberField(
                state.numbers[NumberField.VLAN].orEmpty(),
                { viewModel.setNumber(NumberField.VLAN, it) },
                stringResource(R.string.hardware_vlan),
                supportingText = stringResource(R.string.hardware_vlan_help),
                isError = problem == NewContainer.Problem.NETWORK && state.numbers[NumberField.VLAN]?.isNotBlank() == true,
            )
            SwitchRow(stringResource(R.string.hardware_firewall), ct.network.firewall, { on -> viewModel.editCtNetwork { copy(firewall = on) } }, description = stringResource(R.string.hardware_firewall_body))
            SwitchRow(stringResource(R.string.create_unprivileged), ct.unprivileged, { on -> viewModel.editCt { copy(unprivileged = on) } }, description = stringResource(R.string.create_unprivileged_body))
            SwitchRow(stringResource(R.string.create_nesting), ct.nesting, { on -> viewModel.editCt { copy(nesting = on) } }, description = stringResource(R.string.create_nesting_body))
            SwitchRow(stringResource(R.string.options_onboot), ct.onBoot, { on -> viewModel.editCt { copy(onBoot = on) } }, description = stringResource(R.string.options_onboot_body))
        }
    }
}

@Composable
private fun ctProblemText(state: CreateGuestState): String? {
    if (state.passwordsDiffer && state.ctProblem == null) return stringResource(R.string.create_password_differ)
    return when (state.ctProblem) {
        null -> null
        NewContainer.Problem.ID -> stringResource(R.string.clone_id_error)
        NewContainer.Problem.ID_TAKEN -> stringResource(R.string.create_id_taken)
        NewContainer.Problem.HOSTNAME -> stringResource(R.string.options_name_error)
        NewContainer.Problem.TEMPLATE -> stringResource(R.string.create_no_templates)
        NewContainer.Problem.PASSWORD -> stringResource(R.string.create_password_error, NewContainer.MIN_PASSWORD)
        NewContainer.Problem.SSH_KEY -> stringResource(R.string.create_ssh_keys_error)
        NewContainer.Problem.LOGIN -> stringResource(R.string.create_login_needed)
        NewContainer.Problem.STORAGE -> stringResource(R.string.hardware_no_storage)
        NewContainer.Problem.SIZE -> stringResource(R.string.create_size_error)
        NewContainer.Problem.CORES -> stringResource(R.string.create_cores_error)
        NewContainer.Problem.MEMORY -> stringResource(R.string.hardware_memory_error, VmCpuMemory.MIN_MEMORY_MIB)
        NewContainer.Problem.SWAP -> stringResource(R.string.create_swap_error)
        NewContainer.Problem.NETWORK -> stringResource(R.string.create_network_error)
    }
}

// ------------------------------------------------------------------------------------ shared

@Composable
private fun IdField(state: CreateGuestState, viewModel: CreateGuestViewModel, idProblem: Boolean) {
    NumberField(
        state.numbers[NumberField.ID].orEmpty(),
        { viewModel.setNumber(NumberField.ID, it) },
        stringResource(R.string.create_id),
        supportingText = if (idProblem) stringResource(R.string.create_id_taken) else stringResource(R.string.create_id_help),
        isError = idProblem,
    )
}
