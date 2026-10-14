package io.github.lucabarbalata.anprox.ui.resources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class ResourceFilter { ALL, VMS, CONTAINERS, NODES, STORAGE }

/** A resource plus the saved server it belongs to. */
data class ResourceItem(val serverId: String, val serverName: String, val resource: ClusterResource) {
    val key: String get() = "$serverId/${resource.id}"

    val ref: GuestRef?
        get() {
            val type = GuestType.fromApi(resource.type) ?: return null
            return GuestRef(serverId, resource.node ?: return null, type, resource.vmid ?: return null)
        }

    /** Guests can be selected for bulk actions; templates can't be started or stopped. */
    val selectable: Boolean get() = ref != null && !resource.template
}

/** Several guests selected on the list, and a power action for all of them. */
data class BulkState(
    val selected: Map<String, ResourceItem> = emptyMap(),
    val confirm: PowerAction? = null,
    val busy: PowerAction? = null,
    /** One-shot summary shown as a snackbar. */
    val result: UiText? = null,
) {
    val active: Boolean get() = selected.isNotEmpty()

    /** The selected guests [action] makes sense for right now. */
    fun targets(action: PowerAction): List<ResourceItem> = selected.values.filter { item ->
        val status = item.resource.status
        when (action) {
            PowerAction.START -> status == "stopped"
            PowerAction.SHUTDOWN -> status == "running"
            PowerAction.STOP -> status == "running" || status == "paused"
            else -> false
        }
    }
}

data class ResourcesState(
    val loaded: Boolean = false,
    val items: List<ResourceItem> = emptyList(),
    val counts: Map<ResourceFilter, Int> = emptyMap(),
    val filter: ResourceFilter = ResourceFilter.ALL,
    val query: String = "",
    val runningOnly: Boolean = false,
    val showServerNames: Boolean = false,
)

/** Every VM, container, node and storage of every logged-in PVE server, searchable. */
class ResourcesViewModel(
    hub: ServerHub,
    snapshots: SnapshotRepository,
    settings: SettingsRepository,
    private val guests: GuestRepository,
) : ViewModel() {

    private val bulkUi = MutableStateFlow(BulkState())
    val bulk: StateFlow<BulkState> = bulkUi.asStateFlow()

    private val filter = MutableStateFlow(ResourceFilter.ALL)
    private val query = MutableStateFlow("")
    private val runningOnly = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val all = settings.settings
        .map { it.refreshIntervalMillis }
        .distinctUntilChanged()
        .flatMapLatest { snapshots.snapshots(it) }
        .map { states ->
            val items = states.flatMap { s ->
                val snap = s.snapshot as? PveSnapshot ?: return@flatMap emptyList()
                (snap.guests + snap.nodes + snap.storages).map { ResourceItem(s.server.id, s.server.profile.name, it) }
            }
            items to states.none { it.isLoading }
        }

    val state: StateFlow<ResourcesState> = combine(all, filter, query, runningOnly, hub.servers) { (items, loaded), f, q, running, servers ->
        val counts = ResourceFilter.entries.associateWith { fl -> items.count { matches(it, fl) } }
        val visible = items
            .filter { matches(it, f) }
            .filter { !running || it.resource.isRunning }
            .filter { q.isBlank() || search(it, q) }
            .sortedWith(compareBy({ order(it.resource) }, { it.resource.vmid ?: 0 }, { it.resource.displayName.lowercase() }))
        ResourcesState(
            loaded = loaded,
            items = visible,
            counts = counts,
            filter = f,
            query = q,
            runningOnly = running,
            showServerNames = servers.size > 1,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResourcesState())

    fun setFilter(f: ResourceFilter) {
        filter.value = f
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setRunningOnly(v: Boolean) {
        runningOnly.value = v
    }

    // ------------------------------------------------------------------ bulk actions

    fun toggleSelected(item: ResourceItem) {
        if (!item.selectable || bulkUi.value.busy != null) return
        bulkUi.update { b -> b.copy(selected = if (item.key in b.selected) b.selected - item.key else b.selected + (item.key to item)) }
    }

    fun clearSelection() {
        if (bulkUi.value.busy == null) bulkUi.update { it.copy(selected = emptyMap(), confirm = null) }
    }

    fun requestBulk(action: PowerAction) {
        bulkUi.update { it.copy(selected = refreshed(it.selected), confirm = action) }
    }

    fun dismissBulk() {
        bulkUi.update { it.copy(confirm = null) }
    }

    /** Runs the action on every applicable selected guest, four at a time, and waits for the tasks. */
    fun runBulk() {
        val action = bulkUi.value.confirm ?: return
        val targets = bulkUi.value.targets(action).mapNotNull { it.ref }
        bulkUi.update { it.copy(confirm = null, busy = action) }
        viewModelScope.launch {
            val gate = Semaphore(BULK_PARALLELISM)
            val outcomes = coroutineScope {
                targets.map { ref ->
                    async {
                        gate.withPermit {
                            try {
                                guests.awaitTask(ref.serverId, ref.node, guests.power(ref, action)).succeeded
                            } catch (_: ApiException) {
                                false
                            }
                        }
                    }
                }.awaitAll()
            }
            val done = when (action) {
                PowerAction.START -> R.string.bulk_done_start
                PowerAction.SHUTDOWN -> R.string.bulk_done_shutdown
                else -> R.string.bulk_done_stop
            }
            bulkUi.update { BulkState(result = UiText.res(done, outcomes.count { it }, outcomes.size)) }
        }
    }

    fun consumeBulkResult() {
        bulkUi.update { it.copy(result = null) }
    }

    /** The selection with each guest's latest status, where the list still shows it. */
    private fun refreshed(selected: Map<String, ResourceItem>): Map<String, ResourceItem> {
        val latest = state.value.items.associateBy { it.key }
        return selected.mapValues { (key, item) -> latest[key] ?: item }
    }

    private fun matches(item: ResourceItem, f: ResourceFilter): Boolean = when (f) {
        ResourceFilter.ALL -> true
        ResourceFilter.VMS -> item.resource.resourceType == ResourceType.QEMU
        ResourceFilter.CONTAINERS -> item.resource.resourceType == ResourceType.LXC
        ResourceFilter.NODES -> item.resource.resourceType == ResourceType.NODE
        ResourceFilter.STORAGE -> item.resource.resourceType == ResourceType.STORAGE
    }

    private fun order(r: ClusterResource): Int = when (r.resourceType) {
        ResourceType.QEMU, ResourceType.LXC -> 0
        ResourceType.NODE -> 1
        ResourceType.STORAGE -> 2
        else -> 3
    }

    companion object {
        private const val BULK_PARALLELISM = 4
    }

    private fun search(item: ResourceItem, q: String): Boolean {
        val r = item.resource
        val needle = q.trim().lowercase()
        return listOfNotNull(r.name, r.vmid?.toString(), r.node, r.storage, r.tags, r.pool, r.type, item.serverName)
            .any { it.lowercase().contains(needle) }
    }
}
