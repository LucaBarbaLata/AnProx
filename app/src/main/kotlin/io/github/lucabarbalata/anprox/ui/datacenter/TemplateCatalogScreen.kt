package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.ApplianceTemplate
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.model.pve.fileName
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.taskFailedText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class CatalogState(
    val loading: Boolean = true,
    val items: List<ApplianceTemplate> = emptyList(),
    /** Template file names already on the storage. */
    val stored: Set<String> = emptySet(),
    val error: UiText? = null,
    val filter: String = "",
    /** The template being downloaded (one at a time). */
    val downloading: String? = null,
    val result: TaskResult? = null,
) {
    val shown: List<ApplianceTemplate> get() = items.filter { it.matches(filter) }
}

/** Proxmox's container template index for one storage (`aplinfo`). */
class TemplateCatalogViewModel(
    private val serverId: String,
    private val node: String,
    private val storage: String,
    private val repo: StorageRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // Both requests in one scope, so a failing one can't cancel the ViewModel's scope.
                val (items, stored) = coroutineScope {
                    val items = async { repo.appliances(serverId, node) }
                    val stored = async { storedNames() }
                    items.await() to stored.await()
                }
                ui.update { it.copy(loading = false, items = items, stored = stored) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setFilter(text: String) {
        ui.update { it.copy(filter = text) }
    }

    fun download(template: ApplianceTemplate) {
        if (ui.value.downloading != null) return
        ui.update { it.copy(downloading = template.template) }
        viewModelScope.launch {
            val result = try {
                val upid = repo.downloadAppliance(serverId, node, storage, template.template)
                val task = guests.awaitTask(serverId, node, upid)
                val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
                if (task.succeeded) {
                    TaskResult(UiText.res(R.string.catalog_downloaded, template.headline ?: template.packageName), upid, ok = true)
                } else {
                    TaskResult(taskFailedText(label, task.exitstatus), upid, ok = false)
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            val stored = try {
                storedNames()
            } catch (_: ApiException) {
                ui.value.stored
            }
            ui.update { it.copy(downloading = null, result = result, stored = stored) }
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private suspend fun storedNames(): Set<String> =
        repo.content(serverId, node, storage, ContentType.TEMPLATE).map { it.fileName }.toSet()
}

/** Storage → Container templates → Catalogue: download a distribution or appliance to the storage. */
@Composable
fun TemplateCatalogScreen(
    serverId: String,
    node: String,
    storage: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: TemplateCatalogViewModel = koinViewModel(key = "catalog-$serverId-$node-$storage") { parametersOf(serverId, node, storage) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    val c = AnProxTheme.colors
    AnProxScaffold(
        title = stringResource(R.string.catalog_title),
        subtitle = "$storage · $node",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { GlassTextField(value = state.filter, onValueChange = viewModel::setFilter, label = stringResource(R.string.catalog_search)) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            if (state.loading && state.items.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            }
            items(state.shown, key = { it.template }) { t ->
                CatalogItem(
                    t,
                    stored = t.template in state.stored,
                    busy = state.downloading == t.template,
                    enabled = state.downloading == null,
                    onDownload = { viewModel.download(t) },
                )
            }
        }
    }
}

@Composable
private fun CatalogItem(t: ApplianceTemplate, stored: Boolean, busy: Boolean, enabled: Boolean, onDownload: () -> Unit) {
    val c = AnProxTheme.colors
    val section = when (t.section) {
        "system" -> stringResource(R.string.catalog_section_system)
        "turnkeylinux" -> stringResource(R.string.catalog_section_turnkey)
        else -> t.section.orEmpty()
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.headline ?: t.packageName, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text(
                    listOfNotNull(t.version, t.os, section.ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
            if (stored) {
                Spacer(Modifier.width(8.dp))
                TagChip(stringResource(R.string.catalog_stored))
            }
        }
        t.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (!stored) {
            Spacer(Modifier.height(10.dp))
            AnProxButton(
                stringResource(R.string.catalog_download),
                onClick = onDownload,
                style = ButtonStyle.SECONDARY,
                icon = Icons.Outlined.CloudDownload,
                enabled = enabled,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
