package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.PoolRepository
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.PoolChange
import io.github.lucabarbalata.anprox.model.pve.PoolDetail
import io.github.lucabarbalata.anprox.model.pve.PoolIds
import io.github.lucabarbalata.anprox.model.pve.PoolMember
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "new pool" dialog. */
data class NewPool(val id: String = "", val comment: String = "") {
    val valid: Boolean get() = PoolIds.isValid(id.trim())
}

data class PoolsUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val pools: List<PoolDetail> = emptyList(),
    val creating: NewPool? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    fun nameTaken(id: String): Boolean = pools.any { it.id == id.trim() }
}

/** Datacenter → Resource pools: the list, and creating a pool. */
class PoolsViewModel(
    private val serverId: String,
    private val repo: PoolRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(PoolsUiState())
    val state: StateFlow<PoolsUiState> = ui.asStateFlow()

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val pools = repo.pools(serverId)
                ui.update { it.copy(loading = false, pools = pools) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startCreate() {
        ui.update { it.copy(creating = NewPool()) }
    }

    fun editNew(change: NewPool.() -> NewPool) {
        ui.update { s -> s.copy(creating = s.creating?.change()) }
    }

    fun closeCreate() {
        ui.update { it.copy(creating = null) }
    }

    fun create() {
        val s = ui.value
        val pool = s.creating ?: return
        if (!pool.valid || s.nameTaken(pool.id) || s.busy) return
        ui.update { it.copy(creating = null, busy = true) }
        viewModelScope.launch {
            val result = try {
                repo.create(serverId, pool.id, pool.comment)
                TaskResult(UiText.res(R.string.pools_created, pool.id.trim()), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

/** Which kind of member the "add" dialog offers, and what is ticked. */
data class PoolPick(val guests: Boolean, val selected: Set<String> = emptySet())

data class PoolUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val pool: PoolDetail? = null,
    val resources: List<ClusterResource> = emptyList(),
    val picking: PoolPick? = null,
    val editingComment: String? = null,
    val confirmRemove: PoolMember? = null,
    val confirmDelete: Boolean = false,
    val busy: Boolean = false,
    val result: TaskResult? = null,
    /** The pool was deleted: the screen closes. */
    val deleted: Boolean = false,
) {
    /** Guests in no pool (a guest belongs to one pool at most). */
    val freeGuests: List<ClusterResource>
        get() = resources.filter { it.isGuest && it.vmid != null && it.pool == null }.sortedBy { it.vmid }

    /** Storages not in this pool yet. */
    val freeStorages: List<String>
        get() = resources.filter { it.resourceType == ResourceType.STORAGE }.mapNotNull { it.storage }.distinct().sorted() - pool?.storages.orEmpty().toSet()
}

/** One pool: its members, adding and removing them, the comment, deleting the pool. */
class PoolViewModel(
    private val serverId: String,
    private val poolId: String,
    private val repo: PoolRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(PoolUiState())
    val state: StateFlow<PoolUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val poolCall = async { repo.pool(serverId, poolId) }
                    val resourcesCall = async { repo.resources(serverId) }
                    val pool = poolCall.await()
                    val resources = resourcesCall.await()
                    ui.update { it.copy(loading = false, pool = pool, resources = resources) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun pickGuests() {
        ui.update { it.copy(picking = PoolPick(guests = true)) }
    }

    fun pickStorages() {
        ui.update { it.copy(picking = PoolPick(guests = false)) }
    }

    fun toggle(key: String) {
        ui.update { s ->
            val pick = s.picking ?: return@update s
            s.copy(picking = pick.copy(selected = if (key in pick.selected) pick.selected - key else pick.selected + key))
        }
    }

    fun closePick() {
        ui.update { it.copy(picking = null) }
    }

    fun addPicked() {
        val pick = ui.value.picking ?: return
        if (pick.selected.isEmpty()) return
        ui.update { it.copy(picking = null) }
        val change = if (pick.guests) {
            PoolChange(vmids = pick.selected.mapNotNull { it.toIntOrNull() }.toSet())
        } else {
            PoolChange(storages = pick.selected)
        }
        run(UiText.res(R.string.pool_added, pick.selected.size)) { repo.change(serverId, poolId, change) }
    }

    fun requestRemove(member: PoolMember) {
        ui.update { it.copy(confirmRemove = member) }
    }

    fun dismissRemove() {
        ui.update { it.copy(confirmRemove = null) }
    }

    fun remove() {
        val member = ui.value.confirmRemove ?: return
        ui.update { it.copy(confirmRemove = null) }
        val change = member.vmid?.let { PoolChange(vmids = setOf(it), remove = true) }
            ?: PoolChange(storages = setOfNotNull(member.storage), remove = true)
        run(UiText.res(R.string.pool_removed)) { repo.change(serverId, poolId, change) }
    }

    fun editComment() {
        ui.update { it.copy(editingComment = it.pool?.comment.orEmpty()) }
    }

    fun changeComment(text: String) {
        ui.update { it.copy(editingComment = text) }
    }

    fun closeComment() {
        ui.update { it.copy(editingComment = null) }
    }

    fun saveComment() {
        val text = ui.value.editingComment ?: return
        ui.update { it.copy(editingComment = null) }
        run(UiText.res(R.string.pool_comment_saved)) { repo.setComment(serverId, poolId, text) }
    }

    fun requestDelete() {
        ui.update { it.copy(confirmDelete = true) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = false) }
    }

    fun delete() {
        ui.update { it.copy(confirmDelete = false, busy = true) }
        viewModelScope.launch {
            try {
                repo.delete(serverId, poolId)
                ui.update { it.copy(busy = false, deleted = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun run(done: UiText, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}
