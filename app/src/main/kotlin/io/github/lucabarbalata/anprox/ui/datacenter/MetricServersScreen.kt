package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.MetricServerForm
import io.github.lucabarbalata.anprox.model.pve.MetricServerType
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

/** Datacenter → Metric servers: InfluxDB, Graphite and OpenTelemetry servers that get Proxmox VE's metrics. */
@Composable
fun MetricServersScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: MetricServersViewModel = koinViewModel(key = "metrics-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, "", { _, _, _ -> }, viewModel::consumeResult)
    val typeLabels = MetricServerType.entries.associateWith { metricTypeLabel(it) }
    val off = stringResource(R.string.metrics_off)
    AnProxScaffold(
        title = stringResource(R.string.metrics_title),
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
            item { FormHint(stringResource(R.string.metrics_hint)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (!state.loading && state.servers.isEmpty() && state.error == null) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { FormHint(stringResource(R.string.metrics_empty)) }
                    }
                    state.servers.forEachIndexed { i, server ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = c.hairline)
                        val address = listOfNotNull(server.server, server.port?.toString()).joinToString(":")
                        val subtitle = listOfNotNull(server.kind?.let { typeLabels[it] } ?: server.type, address.ifEmpty { null }, off.takeIf { server.disable }).joinToString(" · ")
                        EntryRow(
                            title = server.id,
                            subtitle = subtitle,
                            enabled = !state.busy,
                            onClick = { viewModel.startEdit(server) },
                            actions = listOf(stringResource(R.string.action_delete) to { viewModel.requestDelete(server) }),
                        )
                    }
                    AnProxButton(
                        stringResource(R.string.metrics_add),
                        onClick = viewModel::startAdd,
                        style = ButtonStyle.SECONDARY,
                        enabled = !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    state.edit?.let { edit -> MetricServerDialog(edit, state, typeLabels, viewModel) }
    state.delete?.let { server ->
        ConfirmActionDialog(
            title = stringResource(R.string.metrics_delete_title, server.id),
            message = stringResource(R.string.metrics_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun metricTypeLabel(type: MetricServerType): String = stringResource(
    when (type) {
        MetricServerType.INFLUXDB -> R.string.metrics_type_influxdb
        MetricServerType.GRAPHITE -> R.string.metrics_type_graphite
        MetricServerType.OPENTELEMETRY -> R.string.metrics_type_opentelemetry
    },
)

@Composable
private fun MetricServerDialog(edit: MetricServerEdit, state: MetricServersState, typeLabels: Map<MetricServerType, String>, viewModel: MetricServersViewModel) {
    val c = AnProxTheme.colors
    val form = edit.form
    val problem = form.problem()
    AlertDialog(
        onDismissRequest = viewModel::cancel,
        containerColor = c.surfaceSolid,
        title = { Text(stringResource(if (edit.create) R.string.metrics_add else R.string.metrics_server_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (edit.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                    return@Column
                }
                if (edit.create) ChoiceChips(MetricServerType.entries, form.type, { typeLabels.getValue(it) }, viewModel::setType)
                GlassTextField(
                    value = form.id,
                    onValueChange = { v -> viewModel.edit { copy(id = v) } },
                    label = stringResource(R.string.metrics_id),
                    supportingText = stringResource(R.string.metrics_id_hint).takeIf { edit.create },
                    enabled = edit.create,
                    isError = problem == MetricServerForm.Problem.ID && form.id.isNotEmpty(),
                )
                GlassTextField(
                    value = form.server,
                    onValueChange = { v -> viewModel.edit { copy(server = v) } },
                    label = stringResource(R.string.metrics_server),
                    isError = problem == MetricServerForm.Problem.SERVER && form.server.isNotEmpty(),
                )
                NumberField(value = form.port, onValueChange = { v -> viewModel.edit { copy(port = v) } }, label = stringResource(R.string.metrics_port), isError = problem == MetricServerForm.Problem.PORT)
                FormLabel(stringResource(R.string.metrics_protocol))
                ChoiceChips(form.protocols, form.protocol, { it.uppercase() }, viewModel::setProtocol)
                when (form.type) {
                    MetricServerType.INFLUXDB -> if (form.protocol != "udp") {
                        GlassTextField(value = form.organization, onValueChange = { v -> viewModel.edit { copy(organization = v) } }, label = stringResource(R.string.metrics_organization))
                        GlassTextField(value = form.bucket, onValueChange = { v -> viewModel.edit { copy(bucket = v) } }, label = stringResource(R.string.metrics_bucket))
                        GlassTextField(
                            value = form.token,
                            onValueChange = { v -> viewModel.edit { copy(token = v) } },
                            label = stringResource(R.string.metrics_token),
                            supportingText = stringResource(if (edit.create) R.string.metrics_token_hint else R.string.metrics_keep_token),
                        )
                        GlassTextField(value = form.apiPathPrefix, onValueChange = { v -> viewModel.edit { copy(apiPathPrefix = v) } }, label = stringResource(R.string.metrics_api_prefix))
                    }
                    MetricServerType.GRAPHITE -> GlassTextField(
                        value = form.path,
                        onValueChange = { v -> viewModel.edit { copy(path = v) } },
                        label = stringResource(R.string.metrics_graphite_path),
                        supportingText = stringResource(R.string.metrics_graphite_path_hint),
                        isError = problem == MetricServerForm.Problem.PATH,
                    )
                    MetricServerType.OPENTELEMETRY -> {
                        GlassTextField(value = form.otelPath, onValueChange = { v -> viewModel.edit { copy(otelPath = v) } }, label = stringResource(R.string.metrics_otel_path))
                        FormLabel(stringResource(R.string.metrics_compression))
                        ChoiceChips(listOf("gzip", "none"), form.compression, { it }) { v -> viewModel.edit { copy(compression = v) } }
                        GlassTextField(
                            value = form.headers,
                            onValueChange = { v -> viewModel.edit { copy(headers = v) } },
                            label = stringResource(R.string.metrics_headers),
                            supportingText = stringResource(R.string.metrics_headers_hint),
                            singleLine = false,
                            minLines = 2,
                            isError = problem == MetricServerForm.Problem.HEADER,
                        )
                        GlassTextField(
                            value = form.resourceAttributes,
                            onValueChange = { v -> viewModel.edit { copy(resourceAttributes = v) } },
                            label = stringResource(R.string.metrics_attributes),
                            supportingText = stringResource(R.string.metrics_attributes_hint),
                            singleLine = false,
                            minLines = 2,
                            isError = problem == MetricServerForm.Problem.ATTRIBUTE,
                        )
                    }
                }
                if (form.protocol == "https") {
                    CheckRow(stringResource(R.string.metrics_verify), form.verifyCertificate) { viewModel.edit { copy(verifyCertificate = !verifyCertificate) } }
                }
                if (form.usesUdp) {
                    NumberField(value = form.mtu, onValueChange = { v -> viewModel.edit { copy(mtu = v) } }, label = stringResource(R.string.metrics_mtu), isError = problem == MetricServerForm.Problem.MTU)
                }
                NumberField(value = form.timeout, onValueChange = { v -> viewModel.edit { copy(timeout = v) } }, label = stringResource(R.string.metrics_timeout), isError = problem == MetricServerForm.Problem.TIMEOUT)
                if (form.type != MetricServerType.GRAPHITE) {
                    NumberField(value = form.maxBodySize, onValueChange = { v -> viewModel.edit { copy(maxBodySize = v) } }, label = stringResource(R.string.metrics_max_body), isError = problem == MetricServerForm.Problem.MAX_BODY)
                }
                CheckRow(stringResource(R.string.metrics_disable), form.disabled) { viewModel.edit { copy(disabled = !disabled) } }
                state.saveError?.let { FormHint(it.asString(), error = true) }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::save, enabled = !edit.loading && problem == null && !state.busy) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
