package io.github.lucabarbalata.anprox.ui.node

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import io.github.lucabarbalata.anprox.core.designsystem.component.InfoRow
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.model.pve.AcmeAccountForm
import io.github.lucabarbalata.anprox.model.pve.AcmeDomain
import io.github.lucabarbalata.anprox.model.pve.AcmePluginForm
import io.github.lucabarbalata.anprox.model.pve.CertificateInfo
import io.github.lucabarbalata.anprox.model.pve.CustomCertificateForm
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.CheckRow
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Node → Certificates: what the web interface serves, ACME (Let's Encrypt) with the node's
 * domains, and a certificate of your own. Every change restarts the node's web proxy.
 */
@Composable
fun CertificatesScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onAcme: () -> Unit,
) {
    val viewModel: CertificatesViewModel = koinViewModel(key = "certs-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.cert_title),
        subtitle = node,
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
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent) }
            if (state.loading && state.certificates.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                }
            }
            state.certificates.forEach { cert -> item(key = cert.filename) { CertificateCard(cert) } }

            state.acme?.let { acme ->
                item { SectionHeader(stringResource(R.string.cert_acme)) }
                item {
                    GlassCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                        AccountRow(acme.accountOrDefault, state.accounts, enabled = !state.busy, onSelect = viewModel::setAccount)
                        acme.domains.forEach { domain ->
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
                            DomainRow(domain, enabled = !state.busy) { viewModel.removeDomain(domain) }
                        }
                        if (acme.domains.isEmpty()) {
                            Text(
                                stringResource(R.string.cert_no_domains),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AnProxButton(
                                stringResource(R.string.cert_add_domain),
                                onClick = viewModel::startAddDomain,
                                style = ButtonStyle.SECONDARY,
                                enabled = !state.busy && acme.freeKey != null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AnProxButton(
                                    stringResource(R.string.cert_order),
                                    onClick = viewModel::order,
                                    enabled = !state.busy && acme.domains.isNotEmpty(),
                                    modifier = Modifier.weight(1f),
                                )
                                if (state.inUse != null) {
                                    AnProxButton(
                                        stringResource(R.string.cert_renew),
                                        onClick = viewModel::renew,
                                        style = ButtonStyle.SECONDARY,
                                        enabled = !state.busy && acme.domains.isNotEmpty(),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
                item { MenuRowCard(stringResource(R.string.acme_title), onAcme) }
            }

            item { SectionHeader(stringResource(R.string.cert_custom)) }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormHint(stringResource(R.string.cert_restart_hint))
                        AnProxButton(stringResource(R.string.cert_upload), onClick = viewModel::startUpload, style = ButtonStyle.SECONDARY, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                        if (state.inUse != null) {
                            AnProxButton(stringResource(R.string.cert_remove_custom), onClick = viewModel::requestRemoveCustom, style = ButtonStyle.DANGER, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    state.addingDomain?.let { draft ->
        DomainDialog(draft, state.plugins.filter { it.isDns }.map { it.id }, viewModel)
    }
    state.upload?.let { form -> UploadDialog(form, viewModel) }
    state.confirm?.let { confirm ->
        val inUse = state.inUse
        val days = inUse?.daysLeft(System.currentTimeMillis() / 1000) ?: 0
        ConfirmActionDialog(
            title = stringResource(
                when (confirm) {
                    CertificateConfirm.ORDER_REPLACES_CUSTOM -> R.string.cert_order
                    CertificateConfirm.RENEW_EARLY -> R.string.cert_renew
                    CertificateConfirm.REMOVE_CUSTOM -> R.string.cert_remove_custom
                },
            ),
            message = when (confirm) {
                CertificateConfirm.ORDER_REPLACES_CUSTOM -> stringResource(R.string.cert_order_replaces, inUse?.commonName.orEmpty())
                CertificateConfirm.RENEW_EARLY -> stringResource(R.string.cert_renew_early, days)
                CertificateConfirm.REMOVE_CUSTOM -> stringResource(R.string.cert_remove_custom_body)
            },
            confirmLabel = stringResource(
                when (confirm) {
                    CertificateConfirm.ORDER_REPLACES_CUSTOM -> R.string.cert_order
                    CertificateConfirm.RENEW_EARLY -> R.string.cert_renew
                    CertificateConfirm.REMOVE_CUSTOM -> R.string.action_remove
                },
            ),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

@Composable
private fun MenuRowCard(title: String, onClick: () -> Unit) {
    GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        MenuRow(Icons.Outlined.Key, title, null, onClick)
    }
}

/** One certificate: what it is for, its name, who issued it and until when it is valid. */
@Composable
private fun CertificateCard(cert: CertificateInfo) {
    val c = AnProxTheme.colors
    val now = System.currentTimeMillis() / 1000
    val days = cert.daysLeft(now)
    GlassCard {
        Text(
            stringResource(
                when (cert.role) {
                    CertificateInfo.Role.IN_USE -> R.string.cert_role_in_use
                    CertificateInfo.Role.NODE -> R.string.cert_role_node
                    CertificateInfo.Role.CLUSTER_CA -> R.string.cert_role_ca
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        Text(
            cert.commonName ?: cert.subject.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = c.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        val until = cert.notafter?.let { dateText(it) } ?: "—"
        Text(
            when {
                days == null -> stringResource(R.string.cert_valid_until, until)
                days < 0 -> stringResource(R.string.cert_expired, until)
                else -> stringResource(R.string.cert_valid_days, until, days)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                days != null && days < 0 -> c.danger
                cert.expiresWithin(30, now) && cert.role != CertificateInfo.Role.CLUSTER_CA -> c.warning
                else -> c.textPrimary
            },
        )
        Spacer(Modifier.height(6.dp))
        cert.issuerName?.let { InfoRow(stringResource(R.string.cert_issuer), it) }
        if (cert.san.isNotEmpty()) InfoRow(stringResource(R.string.cert_names), cert.san.joinToString(", "))
        cert.publicKeyType?.let { type ->
            val bits = cert.publicKeyBits
            InfoRow(stringResource(R.string.cert_key), if (bits != null) stringResource(R.string.cert_key_value, keyType(type), bits) else keyType(type))
        }
        cert.fingerprint?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Text(it, style = MonoStyle, color = c.textTertiary)
        }
    }
}

private fun keyType(type: String): String = when (type) {
    "rsaEncryption" -> "RSA"
    "id-ecPublicKey" -> "ECDSA"
    else -> type
}

private fun dateText(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epochSeconds))

/** The ACME account the node orders with, changed through a menu of the cluster's accounts. */
@Composable
private fun AccountRow(account: String, accounts: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    val c = AnProxTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && accounts.size > 1, onClickLabel = stringResource(R.string.cert_choose_account)) { menu = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cert_account), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text(account, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            accounts.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    menu = false
                    onSelect(name)
                })
            }
        }
    }
}

@Composable
private fun DomainRow(domain: AcmeDomain, enabled: Boolean, onRemove: () -> Unit) {
    val c = AnProxTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(domain.domain, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(
                listOfNotNull(
                    if (domain.isStandalone) stringResource(R.string.cert_plugin_http) else stringResource(R.string.cert_plugin_dns, domain.plugin),
                    domain.alias?.let { stringResource(R.string.cert_alias_value, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cert_remove_domain, domain.domain), tint = c.textSecondary)
        }
    }
}

@Composable
private fun DomainDialog(draft: DomainDraft, dnsPlugins: List<String>, viewModel: CertificatesViewModel) {
    val c = AnProxTheme.colors
    val plugins = listOf(AcmeDomain.STANDALONE) + dnsPlugins
    val httpLabel = stringResource(R.string.cert_plugin_http)
    AlertDialog(
        onDismissRequest = viewModel::cancelDomain,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.cert_add_domain)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = draft.domain,
                    onValueChange = { v -> viewModel.editDomain { copy(domain = v) } },
                    label = stringResource(R.string.cert_domain),
                    isError = draft.domain.isNotBlank() && !draft.valid,
                )
                FormLabel(stringResource(R.string.cert_validation))
                ChoiceChips(plugins, draft.plugin, { if (it == AcmeDomain.STANDALONE) httpLabel else it }) { p -> viewModel.editDomain { copy(plugin = p) } }
                if (draft.plugin == AcmeDomain.STANDALONE) {
                    FormHint(stringResource(R.string.cert_http_hint))
                } else {
                    GlassTextField(
                        value = draft.alias,
                        onValueChange = { v -> viewModel.editDomain { copy(alias = v) } },
                        label = stringResource(R.string.cert_alias),
                        supportingText = stringResource(R.string.cert_alias_hint),
                    )
                }
                if (dnsPlugins.isEmpty()) FormHint(stringResource(R.string.cert_no_dns_plugins))
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveDomain, enabled = draft.valid) { Text(stringResource(R.string.action_add)) } },
        dismissButton = { TextButton(onClick = viewModel::cancelDomain) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun UploadDialog(form: CustomCertificateForm, viewModel: CertificatesViewModel) {
    val c = AnProxTheme.colors
    val problem = form.problem()
    AlertDialog(
        onDismissRequest = viewModel::cancelUpload,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.cert_upload)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = form.certificates,
                    onValueChange = { v -> viewModel.editUpload { copy(certificates = v) } },
                    label = stringResource(R.string.cert_pem_chain),
                    singleLine = false,
                    minLines = 4,
                    isError = form.certificates.isNotBlank() && problem == CustomCertificateForm.Problem.CERTIFICATE,
                )
                GlassTextField(
                    value = form.key,
                    onValueChange = { v -> viewModel.editUpload { copy(key = v) } },
                    label = stringResource(R.string.cert_pem_key),
                    supportingText = stringResource(R.string.cert_pem_key_hint),
                    singleLine = false,
                    minLines = 4,
                    isError = problem == CustomCertificateForm.Problem.KEY,
                )
                FormHint(stringResource(R.string.cert_restart_hint))
            }
        },
        confirmButton = { TextButton(onClick = viewModel::saveUpload, enabled = problem == null) { Text(stringResource(R.string.cert_upload_action)) } },
        dismissButton = { TextButton(onClick = viewModel::cancelUpload) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ------------------------------------------------------------------------------ cluster ACME

/** Datacenter → Certificates (ACME): accounts at a CA and the plugins that prove domain ownership. */
@Composable
fun AcmeScreen(serverId: String, onBack: () -> Unit, onTask: (serverId: String, node: String, upid: String) -> Unit) {
    val viewModel: AcmeViewModel = koinViewModel(key = "acme-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", onTask, viewModel::consumeResult)
    AnProxScaffold(
        title = stringResource(R.string.acme_title),
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
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent) }
            item { SectionHeader(stringResource(R.string.acme_accounts)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    state.accounts.forEachIndexed { i, account ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(account.name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                                Text(
                                    listOfNotNull(account.contacts.joinToString(", ").ifEmpty { null }, account.status, directoryName(account.directory, state)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textTertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { viewModel.requestDeactivate(account) }, enabled = !state.busy) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.acme_deactivate_named, account.name), tint = c.textSecondary)
                            }
                        }
                    }
                    if (state.accounts.isEmpty() && !state.loading) {
                        Text(stringResource(R.string.acme_no_accounts), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    AnProxButton(
                        stringResource(R.string.acme_register),
                        onClick = viewModel::startRegister,
                        style = ButtonStyle.SECONDARY,
                        enabled = !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.acme_plugins)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    state.plugins.forEachIndexed { i, plugin ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = plugin.isDns && !state.busy) { viewModel.startEditPlugin(plugin) }
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.id, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                                Text(
                                    if (plugin.isDns) {
                                        stringResource(R.string.cert_plugin_dns, state.dnsApis.firstOrNull { it.id == plugin.api }?.name ?: plugin.api.orEmpty())
                                    } else {
                                        stringResource(R.string.cert_plugin_http)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textTertiary,
                                )
                            }
                            if (plugin.isDns) {
                                IconButton(onClick = { viewModel.requestDeletePlugin(plugin) }, enabled = !state.busy) {
                                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.acme_delete_plugin_named, plugin.id), tint = c.textSecondary)
                                }
                            } else {
                                Spacer(Modifier.width(48.dp))
                            }
                        }
                    }
                    AnProxButton(
                        stringResource(R.string.acme_add_plugin),
                        onClick = viewModel::startAddPlugin,
                        style = ButtonStyle.SECONDARY,
                        enabled = !state.busy && state.dnsApis.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    state.register?.let { form -> RegisterDialog(form, state, viewModel) }
    state.pluginEdit?.let { edit -> PluginDialog(edit, state, viewModel) }
    state.deactivate?.let { account ->
        ConfirmActionDialog(
            title = stringResource(R.string.acme_deactivate_named, account.name),
            message = stringResource(R.string.acme_deactivate_body),
            confirmLabel = stringResource(R.string.acme_deactivate),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            confirmText = account.name,
            onConfirm = viewModel::deactivate,
            onDismiss = viewModel::dismissDeactivate,
        )
    }
    state.deletePlugin?.let { plugin ->
        ConfirmActionDialog(
            title = stringResource(R.string.acme_delete_plugin_named, plugin.id),
            message = stringResource(R.string.acme_delete_plugin_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::deletePlugin,
            onDismiss = viewModel::dismissDeletePlugin,
        )
    }
}

private fun directoryName(url: String?, state: AcmeState): String? =
    url?.let { u -> state.directories.firstOrNull { it.url == u }?.name ?: u.substringAfter("://").substringBefore('/') }

@Composable
private fun RegisterDialog(form: AcmeAccountForm, state: AcmeState, viewModel: AcmeViewModel) {
    val c = AnProxTheme.colors
    val problem = form.problem(state.meta)
    AlertDialog(
        onDismissRequest = viewModel::cancelRegister,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(R.string.acme_register)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = form.name,
                    onValueChange = { v -> viewModel.editRegister { copy(name = v) } },
                    label = stringResource(R.string.acme_account_name),
                    isError = problem == AcmeAccountForm.Problem.NAME && form.name.isNotEmpty(),
                )
                GlassTextField(
                    value = form.email,
                    onValueChange = { v -> viewModel.editRegister { copy(email = v) } },
                    label = stringResource(R.string.acme_email),
                    supportingText = stringResource(R.string.acme_email_hint),
                    isError = problem == AcmeAccountForm.Problem.EMAIL && form.email.isNotEmpty(),
                )
                if (state.directories.isNotEmpty()) {
                    FormLabel(stringResource(R.string.acme_directory))
                    ChoiceChips(state.directories.map { it.url }, form.directory, { url -> state.directories.firstOrNull { it.url == url }?.name ?: url }) { url ->
                        viewModel.editRegister { copy(directory = url) }
                    }
                }
                val meta = state.meta
                if (meta == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                } else {
                    meta.termsOfService?.let { tos ->
                        Text(stringResource(R.string.acme_tos, tos), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        CheckRow(stringResource(R.string.acme_accept_tos), form.acceptTos) { viewModel.editRegister { copy(acceptTos = !acceptTos) } }
                    }
                    if (meta.externalAccountRequired) {
                        FormHint(stringResource(R.string.acme_eab_hint))
                        GlassTextField(value = form.eabKid, onValueChange = { v -> viewModel.editRegister { copy(eabKid = v) } }, label = stringResource(R.string.acme_eab_kid))
                        GlassTextField(value = form.eabHmacKey, onValueChange = { v -> viewModel.editRegister { copy(eabHmacKey = v) } }, label = stringResource(R.string.acme_eab_hmac))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::saveRegister, enabled = problem == null && state.meta != null) { Text(stringResource(R.string.acme_register_action)) }
        },
        dismissButton = { TextButton(onClick = viewModel::cancelRegister) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PluginDialog(edit: PluginEdit, state: AcmeState, viewModel: AcmeViewModel) {
    val c = AnProxTheme.colors
    val form = edit.form
    val problem = form.problem()
    val api = state.dnsApis.firstOrNull { it.id == form.api }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::cancelPlugin,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(if (edit.existing == null) R.string.acme_add_plugin else R.string.acme_edit_plugin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = form.id,
                    onValueChange = { v -> viewModel.editPlugin { copy(id = v) } },
                    label = stringResource(R.string.acme_plugin_id),
                    enabled = edit.existing == null,
                    isError = problem == AcmePluginForm.Problem.ID && form.id.isNotEmpty(),
                )
                FormLabel(stringResource(R.string.acme_provider, api?.name ?: form.api))
                GlassTextField(value = query, onValueChange = { query = it }, label = stringResource(R.string.acme_provider_search))
                val matches = if (query.isBlank()) emptyList() else state.dnsApis.filter { it.name.contains(query.trim(), ignoreCase = true) || it.id.contains(query.trim(), ignoreCase = true) }.take(6)
                matches.forEach { candidate ->
                    Text(
                        "${candidate.name} (${candidate.id})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (candidate.id == form.api) c.accent else c.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.editPlugin { copy(api = candidate.id) }
                                query = ""
                            }
                            .padding(vertical = 14.dp), // 48 dp to tap
                    )
                }
                GlassTextField(
                    value = form.data,
                    onValueChange = { v -> viewModel.editPlugin { copy(data = v) } },
                    label = stringResource(R.string.acme_plugin_data),
                    supportingText = api?.fields?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.acme_plugin_fields, it.joinToString(", ")) }
                        ?: stringResource(R.string.acme_plugin_data_hint),
                    singleLine = false,
                    minLines = 3,
                    isError = problem == AcmePluginForm.Problem.DATA,
                )
                NumberField(
                    value = form.validationDelay,
                    onValueChange = { v -> viewModel.editPlugin { copy(validationDelay = v) } },
                    label = stringResource(R.string.acme_validation_delay),
                    supportingText = stringResource(R.string.acme_validation_delay_hint),
                    isError = problem == AcmePluginForm.Problem.DELAY,
                )
            }
        },
        confirmButton = { TextButton(onClick = viewModel::savePlugin, enabled = problem == null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::cancelPlugin) { Text(stringResource(R.string.action_cancel)) } },
    )
}
