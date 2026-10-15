package io.github.lucabarbalata.anprox.ui.cloudinit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.pve.CloudInitForm
import io.github.lucabarbalata.anprox.model.pve.CloudInitIp
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.StoragePicker
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A VM's cloud-init settings (questionnaire Q18). Proxmox applies them when the VM starts, from a
 * small image on the cloud-init drive; without a drive the screen offers to add one.
 */
@Composable
fun CloudInitScreen(ref: GuestRef, guestName: String, onBack: () -> Unit, onTask: (String, String, String) -> Unit) {
    val key = "cloudinit-${ref.serverId}-${ref.node}-${ref.vmid}"
    val viewModel: CloudInitViewModel = koinViewModel(key = key) { parametersOf(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask, viewModel::consumeResult)

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    val leave: () -> Unit = {
        if (state.dirty) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }

    AnProxScaffold(
        title = stringResource(R.string.cloudinit_title),
        subtitle = "$guestName · ${stringResource(R.string.guest_type_vm)} ${ref.vmid}",
        onBack = leave,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::reload, enabled = !state.loading && !state.busy) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(contentPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { ErrorBanner(it.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::reload) }
            val original = state.original
            val form = state.form
            when {
                original == null && state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent)
                }
                original != null && original.drive == null -> NoDrive(state, viewModel)
                original != null && form != null -> Form(state, form, viewModel)
            }
            if (state.busy) BusyCard(stringResource(R.string.hardware_saving))
        }
    }

    if (confirmDiscard) {
        ConfirmActionDialog(
            title = stringResource(R.string.options_discard_title),
            message = stringResource(R.string.options_discard_body),
            confirmLabel = stringResource(R.string.options_discard),
            danger = DangerLevel.CONFIRM,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun NoDrive(state: CloudInitState, viewModel: CloudInitViewModel) {
    EmptyState(
        Icons.Outlined.Add,
        stringResource(R.string.cloudinit_no_drive_title),
        stringResource(R.string.cloudinit_no_drive_body),
    )
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormLabel(stringResource(R.string.hardware_storage))
            StoragePicker(state.storages, state.chosenStorage, viewModel::setDriveStorage)
            AnProxButton(
                text = stringResource(R.string.cloudinit_add_drive),
                onClick = viewModel::addDrive,
                icon = Icons.Outlined.Add,
                enabled = !state.busy && state.chosenStorage != null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Form(state: CloudInitState, form: CloudInitForm, viewModel: CloudInitViewModel) {
    val c = AnProxTheme.colors
    val problem = state.problem
    val original = state.original ?: return
    val v4Labels = mapOf(
        CtNetSettings.Ipv4Mode.DHCP to stringResource(R.string.hardware_ipv4_dhcp),
        CtNetSettings.Ipv4Mode.STATIC to stringResource(R.string.hardware_ip_static),
        CtNetSettings.Ipv4Mode.NONE to stringResource(R.string.hardware_ip_none),
    )
    val v6Labels = mapOf(
        CtNetSettings.Ipv6Mode.NONE to stringResource(R.string.hardware_ip_none),
        CtNetSettings.Ipv6Mode.DHCP to stringResource(R.string.hardware_ipv6_dhcp),
        CtNetSettings.Ipv6Mode.SLAAC to stringResource(R.string.hardware_ipv6_slaac),
        CtNetSettings.Ipv6Mode.STATIC to stringResource(R.string.hardware_ip_static),
    )
    if (state.running) ErrorBanner(stringResource(R.string.cloudinit_running_note), tone = Tone.INFO)

    SectionHeader(stringResource(R.string.cloudinit_user_section))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = form.user,
                onValueChange = { v -> viewModel.edit { copy(user = v.trim()) } },
                label = stringResource(R.string.cloudinit_user),
                supportingText = stringResource(if (problem == CloudInitForm.Problem.USER) R.string.cloudinit_user_error else R.string.cloudinit_user_help),
                isError = problem == CloudInitForm.Problem.USER,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
            )
            GlassTextField(
                value = form.password,
                onValueChange = { v -> viewModel.edit { copy(password = v, clearPassword = false) } },
                label = stringResource(R.string.cloudinit_password),
                supportingText = stringResource(if (original.passwordSet) R.string.cloudinit_password_set else R.string.cloudinit_password_unset),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (original.passwordSet) {
                SwitchRow(
                    title = stringResource(R.string.cloudinit_password_clear),
                    checked = form.clearPassword,
                    onCheckedChange = { on -> viewModel.edit { copy(clearPassword = on, password = if (on) "" else password) } },
                )
            }
            GlassTextField(
                value = form.sshKeys,
                onValueChange = { v -> viewModel.edit { copy(sshKeys = v) } },
                label = stringResource(R.string.create_ssh_keys),
                supportingText = stringResource(if (problem == CloudInitForm.Problem.SSH_KEY) R.string.create_ssh_keys_error else R.string.create_ssh_keys_help),
                isError = problem == CloudInitForm.Problem.SSH_KEY,
                singleLine = false,
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
            )
        }
    }

    SectionHeader(stringResource(R.string.hardware_network))
    form.ipconfigs.keys.sorted().forEach { index ->
        val ip = form.ipconfigs.getValue(index)
        val ipProblem = ip.problem()
        val net = state.hardware?.nets?.firstOrNull { it.key == "net$index" }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull("net$index", net?.bridge).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textPrimary,
                )
                FormLabel(stringResource(R.string.hardware_ipv4))
                SegmentedPicker(CtNetSettings.Ipv4Mode.entries, ip.ipv4, { v4Labels.getValue(it) }, { m -> viewModel.editIp(index) { copy(ipv4 = m) } })
                if (ip.ipv4 == CtNetSettings.Ipv4Mode.STATIC) {
                    GlassTextField(
                        value = ip.ip,
                        onValueChange = { v -> viewModel.editIp(index) { copy(ip = v.trim()) } },
                        label = stringResource(R.string.hardware_ipv4_address),
                        supportingText = stringResource(if (ipProblem == CloudInitIp.Problem.IP) R.string.hardware_ipv4_error else R.string.hardware_ipv4_help),
                        isError = ipProblem == CloudInitIp.Problem.IP,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    GlassTextField(
                        value = ip.gateway,
                        onValueChange = { v -> viewModel.editIp(index) { copy(gateway = v.trim()) } },
                        label = stringResource(R.string.hardware_gateway),
                        supportingText = if (ipProblem == CloudInitIp.Problem.GATEWAY) stringResource(R.string.hardware_gateway_error) else null,
                        isError = ipProblem == CloudInitIp.Problem.GATEWAY,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                FormLabel(stringResource(R.string.hardware_ipv6))
                SegmentedPicker(CtNetSettings.Ipv6Mode.entries, ip.ipv6, { v6Labels.getValue(it) }, { m -> viewModel.editIp(index) { copy(ipv6 = m) } })
                if (ip.ipv6 == CtNetSettings.Ipv6Mode.STATIC) {
                    GlassTextField(
                        value = ip.ip6,
                        onValueChange = { v -> viewModel.editIp(index) { copy(ip6 = v.trim()) } },
                        label = stringResource(R.string.hardware_ipv6_address),
                        supportingText = stringResource(if (ipProblem == CloudInitIp.Problem.IP6) R.string.hardware_ipv6_error else R.string.hardware_ipv6_help),
                        isError = ipProblem == CloudInitIp.Problem.IP6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    GlassTextField(
                        value = ip.gateway6,
                        onValueChange = { v -> viewModel.editIp(index) { copy(gateway6 = v.trim()) } },
                        label = stringResource(R.string.hardware_gateway6),
                        supportingText = if (ipProblem == CloudInitIp.Problem.GATEWAY6) stringResource(R.string.hardware_gateway_error) else null,
                        isError = ipProblem == CloudInitIp.Problem.GATEWAY6,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            }
        }
    }

    SectionHeader(stringResource(R.string.cloudinit_dns))
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = form.nameserver,
                onValueChange = { v -> viewModel.edit { copy(nameserver = v) } },
                label = stringResource(R.string.cloudinit_nameserver),
                supportingText = stringResource(if (problem == CloudInitForm.Problem.DNS) R.string.cloudinit_nameserver_error else R.string.cloudinit_nameserver_help),
                isError = problem == CloudInitForm.Problem.DNS,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            GlassTextField(
                value = form.searchDomain,
                onValueChange = { v -> viewModel.edit { copy(searchDomain = v.trim()) } },
                label = stringResource(R.string.cloudinit_search_domain),
                supportingText = stringResource(R.string.cloudinit_dns_help),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            form.upgrade?.let { upgrade ->
                SwitchRow(
                    title = stringResource(R.string.cloudinit_upgrade),
                    description = stringResource(R.string.cloudinit_upgrade_body),
                    checked = upgrade,
                    onCheckedChange = { on -> viewModel.edit { copy(upgrade = on) } },
                )
            }
        }
    }

    AnProxButton(
        text = stringResource(R.string.action_save),
        onClick = viewModel::save,
        icon = Icons.Outlined.Save,
        enabled = state.canSave,
        loading = state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!state.dirty && !state.busy) {
        Text(stringResource(R.string.options_no_changes), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
    FormHint(stringResource(R.string.cloudinit_regenerate_help))
    AnProxButton(
        text = stringResource(R.string.cloudinit_regenerate),
        onClick = viewModel::regenerate,
        style = ButtonStyle.SECONDARY,
        icon = Icons.Outlined.Refresh,
        enabled = !state.busy && !state.dirty,
        modifier = Modifier.fillMaxWidth(),
    )
}
