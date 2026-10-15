package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.datacenter.StorageSetup
import io.github.lucabarbalata.anprox.data.datacenter.StorageSetupRepository
import io.github.lucabarbalata.anprox.model.pve.StorageKind
import io.github.lucabarbalata.anprox.model.pve.StorageSetupForm
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which field a scan fills in. */
enum class ScanField { EXPORT, SHARE, DATASTORE, VOLUME_GROUP, THIN_POOL, POOL }

/** What a scan found: values with a short description each. */
data class ScanResult(val field: ScanField, val options: List<Pair<String, String?>>)

/** Why a scan failed, shown under its field. */
data class ScanFailure(val field: ScanField, val message: UiText)

data class StorageEditorState(
    val creating: Boolean,
    val loading: Boolean = true,
    val error: UiText? = null,
    /** The storage being edited; null while creating. */
    val existing: StorageSetup? = null,
    val form: StorageSetupForm = StorageSetupForm(),
    val onlineNodes: List<String> = emptyList(),
    val scanning: ScanField? = null,
    val scan: ScanResult? = null,
    val scanError: ScanFailure? = null,
    val busy: Boolean = false,
    val saveError: UiText? = null,
    val confirmRemove: Boolean = false,
    /** Saved or removed: the screen closes. */
    val done: Boolean = false,
) {
    /** The type can't be edited by the app: only removing is offered. */
    val unsupported: Boolean get() = !creating && existing != null && existing.form == null
    val problem: StorageSetupForm.Problem? get() = form.problem(creating)
    val canSave: Boolean get() = !loading && !busy && !unsupported && error == null && problem == null

    /** Where scans run: a node the storage is limited to, or any online node. */
    val scanNode: String? get() = onlineNodes.firstOrNull { form.nodes.isEmpty() || it in form.nodes } ?: onlineNodes.firstOrNull()
}

/** Datacenter → Storage → add or edit a storage. */
class StorageEditorViewModel(
    private val serverId: String,
    private val storageId: String?,
    private val repo: StorageSetupRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(StorageEditorState(creating = storageId == null))
    val state: StateFlow<StorageEditorState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val nodesCall = async { repo.onlineNodes(serverId) }
                    val existingCall = async { storageId?.let { repo.load(serverId, it) } }
                    val nodes = nodesCall.await()
                    val existing = existingCall.await()
                    ui.update {
                        it.copy(
                            loading = false,
                            onlineNodes = nodes,
                            existing = existing,
                            form = existing?.form ?: it.form,
                        )
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun edit(change: StorageSetupForm.() -> StorageSetupForm) {
        ui.update { it.copy(form = it.form.change(), saveError = null) }
    }

    fun setKind(kind: StorageKind) {
        if (!ui.value.creating) return
        ui.update { it.copy(form = it.form.withKind(kind), scan = null, scanError = null) }
    }

    fun toggleContent(type: String) = edit { copy(content = if (type in content) content - type else content + type) }

    fun toggleNode(node: String) = edit { copy(nodes = if (node in nodes) nodes - node else nodes + node) }

    fun allNodes(all: Boolean) = edit { copy(nodes = if (all) emptySet() else ui.value.onlineNodes.toSet()) }

    fun scan(field: ScanField) {
        val s = ui.value
        val node = s.scanNode ?: return
        val f = s.form
        ui.update { it.copy(scanning = field, scan = null, scanError = null) }
        viewModelScope.launch {
            try {
                val options: List<Pair<String, String?>> = when (field) {
                    ScanField.EXPORT -> repo.scanNfs(serverId, node, f.server).map { it.path to it.options }
                    ScanField.SHARE -> repo.scanCifs(serverId, node, f.server, f.username, f.password, f.domain).map { it.share to it.description }
                    ScanField.DATASTORE -> repo.scanPbs(serverId, node, f.server, f.username, f.password, f.fingerprint).map { it.store to it.comment }
                    ScanField.VOLUME_GROUP -> repo.scanLvm(serverId, node).map { it.vg to null }
                    ScanField.THIN_POOL -> repo.scanLvmThin(serverId, node, f.vgname).map { it.lv to null }
                    ScanField.POOL -> repo.scanZfs(serverId, node).map { it.pool to null }
                }
                ui.update { it.copy(scanning = null, scan = ScanResult(field, options)) }
            } catch (e: ApiException) {
                ui.update { it.copy(scanning = null, scanError = ScanFailure(field, e.toUiText())) }
            }
        }
    }

    fun pick(field: ScanField, value: String) {
        edit {
            when (field) {
                ScanField.EXPORT -> copy(export = value)
                ScanField.SHARE -> copy(share = value)
                ScanField.DATASTORE -> copy(datastore = value)
                ScanField.VOLUME_GROUP -> copy(vgname = value, thinpool = "")
                ScanField.THIN_POOL -> copy(thinpool = value)
                ScanField.POOL -> copy(pool = value)
            }
        }
        ui.update { it.copy(scan = null) }
    }

    fun save() {
        val s = ui.value
        if (!s.canSave) return
        ui.update { it.copy(busy = true, saveError = null) }
        viewModelScope.launch {
            try {
                val existing = s.existing
                if (existing == null) repo.create(serverId, s.form) else repo.update(serverId, existing, s.form)
                ui.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, saveError = e.toUiText()) }
            }
        }
    }

    fun requestRemove() {
        ui.update { it.copy(confirmRemove = true) }
    }

    fun dismissRemove() {
        ui.update { it.copy(confirmRemove = false) }
    }

    fun remove() {
        val id = storageId ?: return
        ui.update { it.copy(confirmRemove = false, busy = true, saveError = null) }
        viewModelScope.launch {
            try {
                repo.remove(serverId, id)
                ui.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, saveError = e.toUiText()) }
            }
        }
    }
}
