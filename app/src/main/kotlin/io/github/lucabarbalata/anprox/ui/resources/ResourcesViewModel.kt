package io.github.lucabarbalata.anprox.ui.resources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ResourceFilter { ALL, VMS, CONTAINERS, NODES, STORAGE }

/** A resource plus the saved server it belongs to. */
data class ResourceItem(val serverId: String, val serverName: String, val resource: ClusterResource) {
    val key: String get() = "$serverId/${resource.id}"
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
) : ViewModel() {

    private val filter = MutableStateFlow(ResourceFilter.ALL)
    private val query = MutableStateFlow("")
    private val runningOnly = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val all = settings.settings
        .map { it.refreshIntervalMillis }
        .distinctUntilChanged()
        .flatMapLatest { snapshots.snapshots(it) }
        .map { states ->
            states.flatMap { s ->
                val snap = s.snapshot as? PveSnapshot ?: return@flatMap emptyList()
                (snap.guests + snap.nodes + snap.storages).map { ResourceItem(s.server.id, s.server.profile.name, it) }
            }
        }

    val state: StateFlow<ResourcesState> = combine(all, filter, query, runningOnly, hub.servers) { items, f, q, running, servers ->
        val counts = ResourceFilter.entries.associateWith { fl -> items.count { matches(it, fl) } }
        val visible = items
            .filter { matches(it, f) }
            .filter { !running || it.resource.isRunning }
            .filter { q.isBlank() || search(it, q) }
            .sortedWith(compareBy({ order(it.resource) }, { it.resource.vmid ?: 0 }, { it.resource.displayName.lowercase() }))
        ResourcesState(
            loaded = true,
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

    private fun search(item: ResourceItem, q: String): Boolean {
        val r = item.resource
        val needle = q.trim().lowercase()
        return listOfNotNull(r.name, r.vmid?.toString(), r.node, r.storage, r.tags, r.pool, r.type, item.serverName)
            .any { it.lowercase().contains(needle) }
    }
}
