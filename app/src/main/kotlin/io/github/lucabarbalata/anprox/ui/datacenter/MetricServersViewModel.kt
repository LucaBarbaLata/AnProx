package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.MetricServerRepository
import io.github.lucabarbalata.anprox.model.pve.MetricServer
import io.github.lucabarbalata.anprox.model.pve.MetricServerForm
import io.github.lucabarbalata.anprox.model.pve.MetricServerType
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A metric server being added ([create]) or changed; [loading] while its settings are read. */
data class MetricServerEdit(val form: MetricServerForm, val create: Boolean, val digest: String? = null, val loading: Boolean = false)

data class MetricServersState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val servers: List<MetricServer> = emptyList(),
    val busy: Boolean = false,
    val edit: MetricServerEdit? = null,
    /** Why Proxmox refused the last save (it tries an HTTP or TCP server first); the editor stays open. */
    val saveError: UiText? = null,
    val delete: MetricServer? = null,
    val result: TaskResult? = null,
)

/** Datacenter → Metric servers: where Proxmox VE sends its metrics (InfluxDB, Graphite, OpenTelemetry). */
class MetricServersViewModel(
    private val serverId: String,
    private val repo: MetricServerRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(MetricServersState())
    val state: StateFlow<MetricServersState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val servers = repo.servers(serverId)
                ui.update { it.copy(loading = false, servers = servers) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startAdd() = ui.update { it.copy(edit = MetricServerEdit(MetricServerForm(), create = true), saveError = null) }

    /** Opens [server]'s settings; the InfluxDB token is left empty and kept unless typed again. */
    fun startEdit(server: MetricServer) {
        val type = server.kind ?: return
        ui.update { it.copy(edit = MetricServerEdit(MetricServerForm(type = type, id = server.id), create = false, loading = true), saveError = null) }
        viewModelScope.launch {
            try {
                val settings = repo.settings(serverId, server.id)
                ui.update { s -> if (s.edit?.form?.id == server.id) s.copy(edit = MetricServerEdit(settings.form, create = false, digest = settings.digest)) else s }
            } catch (e: ApiException) {
                ui.update { it.copy(edit = null, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun edit(change: MetricServerForm.() -> MetricServerForm) = ui.update { s -> s.copy(edit = s.edit?.let { it.copy(form = it.form.change()) }, saveError = null) }

    fun setType(type: MetricServerType) = edit { withType(type) }

    fun setProtocol(protocol: String) = edit { withProtocol(protocol) }

    fun cancel() = ui.update { it.copy(edit = null, saveError = null) }

    fun save() {
        val edit = ui.value.edit ?: return
        if (edit.loading || edit.form.problem() != null || ui.value.busy) return
        ui.update { it.copy(busy = true, saveError = null) }
        viewModelScope.launch {
            try {
                if (edit.create) repo.create(serverId, edit.form) else repo.update(serverId, edit.form, edit.digest)
                ui.update { it.copy(busy = false, edit = null, result = TaskResult(UiText.res(if (edit.create) R.string.metrics_added else R.string.metrics_saved), null, ok = true)) }
                load()
            } catch (e: ApiException) {
                // Keep the form: a server that can't be reached is fixed by changing the address.
                ui.update { it.copy(busy = false, saveError = e.toUiText()) }
            }
        }
    }

    fun requestDelete(server: MetricServer) = ui.update { it.copy(delete = server) }

    fun dismissDelete() = ui.update { it.copy(delete = null) }

    fun delete() {
        val server = ui.value.delete ?: return
        ui.update { it.copy(delete = null, busy = true) }
        viewModelScope.launch {
            val result = try {
                repo.delete(serverId, server.id)
                TaskResult(UiText.res(R.string.metrics_deleted), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    fun consumeResult() = ui.update { it.copy(result = null) }
}
