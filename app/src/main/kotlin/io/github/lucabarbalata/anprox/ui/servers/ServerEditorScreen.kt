package io.github.lucabarbalata.anprox.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.EndpointUrl
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Add or edit a server (questionnaire Q4, Q10, Q13, Q14). */
@Composable
fun ServerEditorScreen(
    serverId: String,
    onBack: () -> Unit,
    onSaved: (serverId: String, isNew: Boolean) -> Unit,
    viewModel: ServerEditorViewModel = koinViewModel(key = "editor-$serverId") { parametersOf(serverId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedId) { state.savedId?.let { onSaved(it, state.isNew) } }

    AnProxScaffold(
        title = stringResource(if (state.isNew) R.string.editor_title_add else R.string.editor_title_edit),
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) { inner ->
        LazyColumn(
            contentPadding = contentPadding(inner),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.editor_section_server))
                SegmentedPicker(
                    options = ProxmoxProduct.entries,
                    selected = state.product,
                    label = { it.displayName },
                    onSelect = viewModel::setProduct,
                )
                Spacer(Modifier.height(12.dp))
                GlassTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = stringResource(R.string.editor_name),
                    placeholder = stringResource(R.string.editor_name_placeholder),
                )
            }

            item { SectionHeader(stringResource(R.string.editor_section_addresses)) }
            item {
                Text(
                    stringResource(R.string.editor_addresses_help, state.product.defaultPort),
                    style = MaterialTheme.typography.bodySmall,
                    color = AnProxTheme.colors.textSecondary,
                )
            }
            itemsIndexed(state.endpoints) { index, endpoint ->
                EndpointCard(
                    index = index,
                    draft = endpoint,
                    canRemove = state.endpoints.size > 1,
                    onUrl = { v -> viewModel.updateEndpoint(index) { it.copy(url = v) } },
                    onLabel = { v -> viewModel.updateEndpoint(index) { it.copy(label = v) } },
                    onHeaders = { v -> viewModel.updateEndpoint(index) { it.copy(headers = v) } },
                    onMoveUp = { viewModel.moveEndpointUp(index) },
                    onRemove = { viewModel.removeEndpoint(index) },
                )
            }
            item {
                TextButton(onClick = viewModel::addEndpoint) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.editor_add_address))
                }
            }

            item {
                SectionHeader(stringResource(R.string.editor_section_account))
                GlassTextField(
                    value = state.user,
                    onValueChange = viewModel::setUser,
                    label = stringResource(R.string.editor_user),
                    supportingText = stringResource(if (state.userError) R.string.editor_user_error else R.string.editor_user_help),
                    isError = state.userError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
            }

            item {
                SectionHeader(stringResource(R.string.editor_section_security))
                GlassCard {
                    SwitchRow(
                        title = stringResource(R.string.editor_insecure_title),
                        description = stringResource(R.string.editor_insecure_description),
                        checked = state.allowInsecure,
                        onCheckedChange = viewModel::setAllowInsecure,
                        tone = Tone.WARNING,
                    )
                    if (state.allowInsecure) {
                        ErrorBanner(stringResource(R.string.editor_insecure_warning), tone = Tone.WARNING)
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                AnProxButton(
                    text = stringResource(if (state.isNew) R.string.editor_save_and_sign_in else R.string.action_save),
                    onClick = viewModel::save,
                    loading = state.saving,
                    enabled = state.loaded,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EndpointCard(
    index: Int,
    draft: EndpointDraft,
    canRemove: Boolean,
    onUrl: (String) -> Unit,
    onLabel: (String) -> Unit,
    onHeaders: (String) -> Unit,
    onMoveUp: () -> Unit,
    onRemove: () -> Unit,
) {
    var showHeaders by rememberSaveable { mutableStateOf(draft.headers.isNotBlank()) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (index == 0) R.string.editor_address_primary else R.string.editor_address_fallback, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    color = AnProxTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (index > 0) {
                    IconButton(onClick = onMoveUp) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.editor_move_up))
                    }
                }
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.editor_remove_address))
                    }
                }
            }
            GlassTextField(
                value = draft.url,
                onValueChange = onUrl,
                label = stringResource(R.string.editor_url),
                placeholder = "192.168.1.10  ·  https://pve.example.com",
                isError = draft.error != null,
                supportingText = draft.error?.let { stringResource(it.messageRes()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            GlassTextField(
                value = draft.label,
                onValueChange = onLabel,
                label = stringResource(R.string.editor_label),
                placeholder = stringResource(R.string.editor_label_placeholder),
            )
            if (showHeaders) {
                GlassTextField(
                    value = draft.headers,
                    onValueChange = onHeaders,
                    label = stringResource(R.string.editor_headers),
                    supportingText = stringResource(R.string.editor_headers_help),
                    singleLine = false,
                    minLines = 2,
                )
            } else {
                TextButton(onClick = { showHeaders = true }) { Text(stringResource(R.string.editor_add_headers)) }
            }
        }
    }
}

private fun EndpointUrl.Reason.messageRes(): Int = when (this) {
    EndpointUrl.Reason.EMPTY -> R.string.editor_url_error_empty
    EndpointUrl.Reason.BAD_SCHEME -> R.string.editor_url_error_scheme
    EndpointUrl.Reason.BAD_HOST -> R.string.editor_url_error_host
    EndpointUrl.Reason.BAD_PORT -> R.string.editor_url_error_port
    EndpointUrl.Reason.MALFORMED -> R.string.editor_url_error_malformed
}

