package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.datacenter.ClusterRepository
import io.github.lucabarbalata.anprox.model.pve.DatacenterOptions
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class DatacenterOptionsState(
    val loading: Boolean = true,
    /** As loaded, with its digest; null until the first load succeeds. */
    val original: DatacenterOptions? = null,
    val draft: DatacenterOptions = DatacenterOptions(),
    val saving: Boolean = false,
    val error: UiText? = null,
    val saveError: UiText? = null,
    /** One-shot "Options saved" snackbar. */
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = original != null && DatacenterOptions.changes(original, draft).isNotEmpty()
    val canSave: Boolean get() = dirty && draft.problem() == null && !saving
}

/** Datacenter → Options: console, keyboard, proxy, e-mail sender, migration, HA and ID defaults. */
class DatacenterOptionsViewModel(private val serverId: String, private val repo: ClusterRepository) : ViewModel() {
    private val ui = MutableStateFlow(DatacenterOptionsState())
    val state: StateFlow<DatacenterOptionsState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val options = repo.options(serverId)
                ui.update { it.copy(loading = false, original = options, draft = options, saveError = null) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun edit(change: DatacenterOptions.() -> DatacenterOptions) {
        ui.update { it.copy(draft = it.draft.change(), saveError = null) }
    }

    fun save() {
        val current = ui.value
        val original = current.original ?: return
        if (!current.canSave) return
        ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                repo.saveOptions(serverId, original, current.draft)
                // Reload for the new digest.
                val fresh = repo.options(serverId)
                ui.update { it.copy(saving = false, original = fresh, draft = fresh, saved = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(saving = false, saveError = e.toUiText()) }
            }
        }
    }

    fun consumeSaved() {
        ui.update { it.copy(saved = false) }
    }
}

@Composable
fun DatacenterOptionsScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: DatacenterOptionsViewModel = koinViewModel(key = "dcoptions-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.dcoptions_saved)
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            snackbar.showSnackbar(savedText)
        }
    }
    // ChoiceChips' labels aren't composable: resolve them first.
    val consoleLabels = mapOf(
        "" to stringResource(R.string.dcoptions_default),
        "html5" to stringResource(R.string.dcoptions_console_novnc),
        "xtermjs" to stringResource(R.string.dcoptions_console_xterm),
        "vv" to stringResource(R.string.dcoptions_console_spice),
    )
    val migrationLabels = mapOf(
        "" to stringResource(R.string.dcoptions_default),
        "secure" to stringResource(R.string.dcoptions_migration_secure),
        "insecure" to stringResource(R.string.dcoptions_migration_insecure),
    )
    val haLabels = mapOf(
        "" to stringResource(R.string.dcoptions_default),
        "conditional" to stringResource(R.string.dcoptions_ha_conditional),
        "freeze" to stringResource(R.string.dcoptions_ha_freeze),
        "failover" to stringResource(R.string.dcoptions_ha_failover),
        "migrate" to stringResource(R.string.dcoptions_ha_migrate),
    )
    val d = state.draft
    val problem = d.problem()
    val noCaps = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)

    AnProxScaffold(
        title = stringResource(R.string.dcoptions_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading && !state.saving) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.original == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
            }
            if (state.original == null) return@LazyColumn
            item { SectionHeader(stringResource(R.string.dcoptions_section_console)) }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormLabel(stringResource(R.string.dcoptions_console))
                        ChoiceChips(
                            DatacenterOptions.CONSOLES.let { if (d.console in it) it else it + d.console },
                            d.console,
                            { consoleLabels[it] ?: it },
                        ) { v -> viewModel.edit { copy(console = v) } }
                        FormHint(stringResource(R.string.dcoptions_console_help))
                        GlassTextField(
                            value = d.keyboard,
                            onValueChange = { v -> viewModel.edit { copy(keyboard = v) } },
                            label = stringResource(R.string.dcoptions_keyboard),
                            supportingText = stringResource(R.string.dcoptions_keyboard_help),
                            keyboardOptions = noCaps,
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.dcoptions_section_network)) }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassTextField(
                            value = d.httpProxy,
                            onValueChange = { v -> viewModel.edit { copy(httpProxy = v) } },
                            label = stringResource(R.string.dcoptions_http_proxy),
                            supportingText = stringResource(R.string.dcoptions_http_proxy_help),
                            isError = problem == DatacenterOptions.Problem.HTTP_PROXY,
                            keyboardOptions = noCaps,
                        )
                        GlassTextField(
                            value = d.emailFrom,
                            onValueChange = { v -> viewModel.edit { copy(emailFrom = v) } },
                            label = stringResource(R.string.dcoptions_email_from),
                            supportingText = stringResource(R.string.dcoptions_email_from_help),
                            isError = problem == DatacenterOptions.Problem.EMAIL,
                            keyboardOptions = noCaps.copy(keyboardType = KeyboardType.Email),
                        )
                        GlassTextField(
                            value = d.macPrefix,
                            onValueChange = { v -> viewModel.edit { copy(macPrefix = v) } },
                            label = stringResource(R.string.dcoptions_mac_prefix),
                            supportingText = stringResource(R.string.dcoptions_mac_prefix_help),
                            isError = problem == DatacenterOptions.Problem.MAC_PREFIX,
                            keyboardOptions = noCaps.copy(keyboardType = KeyboardType.Ascii),
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.dcoptions_section_migration)) }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormLabel(stringResource(R.string.dcoptions_migration_type))
                        ChoiceChips(DatacenterOptions.MIGRATION_TYPES, d.migrationType.takeIf { it in DatacenterOptions.MIGRATION_TYPES } ?: "", { migrationLabels.getValue(it) }) { v ->
                            viewModel.edit { copy(migrationType = v) }
                        }
                        FormHint(stringResource(R.string.dcoptions_migration_help))
                        GlassTextField(
                            value = d.migrationNetwork,
                            onValueChange = { v -> viewModel.edit { copy(migrationNetwork = v) } },
                            label = stringResource(R.string.dcoptions_migration_network),
                            supportingText = stringResource(R.string.dcoptions_migration_network_help),
                            isError = problem == DatacenterOptions.Problem.MIGRATION_NETWORK,
                            keyboardOptions = noCaps,
                        )
                        NumberField(
                            value = d.maxWorkers,
                            onValueChange = { v -> viewModel.edit { copy(maxWorkers = v) } },
                            label = stringResource(R.string.dcoptions_max_workers),
                            supportingText = stringResource(R.string.dcoptions_max_workers_help),
                            isError = problem == DatacenterOptions.Problem.MAX_WORKERS,
                        )
                        FormLabel(stringResource(R.string.dcoptions_ha_policy))
                        ChoiceChips(DatacenterOptions.HA_POLICIES, d.haShutdownPolicy.takeIf { it in DatacenterOptions.HA_POLICIES } ?: "", { haLabels.getValue(it) }) { v ->
                            viewModel.edit { copy(haShutdownPolicy = v) }
                        }
                        FormHint(stringResource(R.string.dcoptions_ha_policy_help))
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.dcoptions_section_ids)) }
            item {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormHint(stringResource(R.string.dcoptions_next_id_help), error = problem == DatacenterOptions.Problem.NEXT_ID)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                NumberField(
                                    value = d.nextIdLower,
                                    onValueChange = { v -> viewModel.edit { copy(nextIdLower = v) } },
                                    label = stringResource(R.string.dcoptions_next_id_lower),
                                    isError = problem == DatacenterOptions.Problem.NEXT_ID,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                NumberField(
                                    value = d.nextIdUpper,
                                    onValueChange = { v -> viewModel.edit { copy(nextIdUpper = v) } },
                                    label = stringResource(R.string.dcoptions_next_id_upper),
                                    isError = problem == DatacenterOptions.Problem.NEXT_ID,
                                )
                            }
                        }
                        GlassTextField(
                            value = d.description,
                            onValueChange = { v -> viewModel.edit { copy(description = v) } },
                            label = stringResource(R.string.dcoptions_notes),
                            singleLine = false,
                            minLines = 3,
                        )
                    }
                }
            }
            state.saveError?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.options_reload), onAction = viewModel::load) }
            }
            item {
                AnProxButton(
                    text = stringResource(R.string.action_save),
                    onClick = viewModel::save,
                    icon = Icons.Outlined.Save,
                    enabled = state.canSave,
                    loading = state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!state.dirty && !state.saving) {
                item { FormHint(stringResource(R.string.options_no_changes)) }
            }
        }
    }
}
