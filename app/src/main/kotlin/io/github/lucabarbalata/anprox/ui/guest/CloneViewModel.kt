package io.github.lucabarbalata.anprox.ui.guest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.CloneRequest
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CloneBusy { CLONE, TEMPLATE }

data class CloneState(
    val ref: GuestRef,
    val guestName: String,
    val loading: Boolean = true,
    val error: UiText? = null,
    val isTemplate: Boolean = false,
    val running: Boolean = false,
    val idText: String = "",
    val name: String = "",
    /** Node name → online. */
    val nodes: Map<String, Boolean> = emptyMap(),
    val targetNode: String = ref.node,
    val full: Boolean = true,
    /** Storages on [targetNode] for the disks; null while loading. */
    val storages: List<NodeStorage>? = null,
    /** null: the same storage as the source. */
    val storage: String? = null,
    val busy: CloneBusy? = null,
    val confirmTemplate: Boolean = false,
    /** One-shot result shown as a snackbar. */
    val result: TaskResult? = null,
    /** The guest the last clone created, to open it. */
    val created: GuestRef? = null,
) {
    val request: CloneRequest
        get() = CloneRequest(idText.toIntOrNull() ?: -1, name, targetNode, full, storage.takeIf { full })
    val problem: CloneRequest.Problem? get() = request.problem(isTemplate)
    val canClone: Boolean get() = !loading && error == null && busy == null && problem == null
}

/** Clone a guest, or turn it into a template (questionnaire Q18). */
class CloneViewModel(
    private val ref: GuestRef,
    guestName: String,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(CloneState(ref, guestName, name = CloneRequest.suggestName(guestName)))
    val state: StateFlow<CloneState> = ui.asStateFlow()
    private var storageJob: Job? = null

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // coroutineScope: a failed request is thrown here instead of cancelling viewModelScope's job.
                val (s, nextId, nodes) = coroutineScope {
                    val status = async { guests.status(ref) }
                    val nextId = async { guests.nextVmid(ref.serverId) }
                    val nodes = async { guests.clusterNodes(ref.serverId) }
                    Triple(status.await(), nextId.await(), nodes.await())
                }
                ui.update {
                    it.copy(
                        loading = false,
                        isTemplate = s.template,
                        running = s.isRunning,
                        // Templates are cloned linked by default, like in the web UI.
                        full = if (it.idText.isEmpty() || s.template != it.isTemplate) !s.template else it.full,
                        idText = it.idText.ifEmpty { nextId.toString() },
                        nodes = nodes,
                    )
                }
                loadStorages()
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setId(text: String) {
        ui.update { it.copy(idText = text.filter(Char::isDigit).take(9)) }
    }

    fun setName(name: String) {
        ui.update { it.copy(name = name) }
    }

    fun setTarget(node: String) {
        ui.update { it.copy(targetNode = node, storage = null) }
        loadStorages()
    }

    fun setFull(full: Boolean) {
        ui.update { it.copy(full = full) }
    }

    fun setStorage(storage: String?) {
        ui.update { it.copy(storage = storage) }
    }

    private fun loadStorages() {
        val node = ui.value.targetNode
        storageJob?.cancel()
        ui.update { it.copy(storages = null) }
        storageJob = viewModelScope.launch {
            val list = try {
                guests.diskStorages(ref.serverId, node, ref.type)
            } catch (_: ApiException) {
                emptyList()
            }
            ui.update { if (it.targetNode == node) it.copy(storages = list) else it }
        }
    }

    fun clone() {
        val s = ui.value
        if (!s.canClone) return
        val request = s.request
        ui.update { it.copy(busy = CloneBusy.CLONE, created = null) }
        viewModelScope.launch {
            var created: GuestRef? = null
            val result = runTask {
                val upid = guests.clone(ref, request)
                upid to { created = GuestRef(ref.serverId, request.targetNode ?: ref.node, ref.type, request.newId) }
            }
            ui.update { it.copy(busy = null, result = result, created = created ?: it.created) }
            // The next free ID changed; suggest it for another clone.
            if (created != null) {
                try {
                    val next = guests.nextVmid(ref.serverId)
                    ui.update { it.copy(idText = next.toString()) }
                } catch (_: ApiException) {
                    // Keep the ID shown; Proxmox will say if it's taken.
                }
            }
        }
    }

    fun requestTemplate() {
        ui.update { it.copy(confirmTemplate = true) }
    }

    fun dismissTemplate() {
        ui.update { it.copy(confirmTemplate = false) }
    }

    fun convertToTemplate() {
        if (ui.value.busy != null) return
        ui.update { it.copy(confirmTemplate = false, busy = CloneBusy.TEMPLATE) }
        viewModelScope.launch {
            val result = try {
                val upid = guests.convertToTemplate(ref)
                if (upid == null) {
                    TaskResult(UiText.res(R.string.clone_template_done), null, ok = true)
                } else {
                    finished(upid)
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = null, result = result) }
            if (result.ok) load()
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    /** Starts a task, waits for it and runs the returned success action if it finished OK. */
    private suspend fun runTask(start: suspend () -> Pair<String, () -> Unit>): TaskResult = try {
        val (upid, onSuccess) = start()
        finished(upid).also { if (it.ok) onSuccess() }
    } catch (e: ApiException) {
        TaskResult(e.toUiText(), null, ok = false)
    }

    private suspend fun finished(upid: String): TaskResult {
        val task = guests.awaitTask(ref.serverId, ref.node, upid)
        val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
        return if (task.succeeded) {
            TaskResult(UiText.res(R.string.guest_task_ok, label), upid, ok = true)
        } else {
            TaskResult(UiText.res(R.string.guest_task_failed, label, task.exitstatus.orEmpty()), upid, ok = false)
        }
    }
}
