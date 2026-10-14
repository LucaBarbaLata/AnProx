package io.github.lucabarbalata.anprox.data.snapshot

import io.github.lucabarbalata.anprox.data.hub.ServerState
import io.github.lucabarbalata.anprox.model.pbs.DatastoreUsage
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.network.ApiException

/** Point-in-time view of one server, refreshed by [SnapshotRepository]. */
sealed interface ServerSnapshot {
    val version: String?
}

/** A Proxmox VE cluster (or standalone node). */
data class PveSnapshot(
    /** `null` for a standalone node (no `cluster` row in /cluster/status). */
    val clusterName: String?,
    val quorate: Boolean?,
    val nodeStatus: List<ClusterStatusEntry>,
    val resources: List<ClusterResource>,
    override val version: String?,
) : ServerSnapshot {
    val nodes: List<ClusterResource> = resources.filter { it.resourceType == ResourceType.NODE }.sortedBy { it.node }
    val guests: List<ClusterResource> = resources.filter { it.isGuest }.sortedBy { it.vmid }

    /** Shared storage is listed once per node by the API; keep one row per storage id. */
    val storages: List<ClusterResource> = resources
        .filter { it.resourceType == ResourceType.STORAGE }
        .groupBy { if (it.shared) "shared/${it.storage}" else it.id }
        .map { (_, rows) -> rows.firstOrNull { it.status == "available" } ?: rows.first() }
        .sortedBy { it.displayName }

    val nodesOnline: Int get() = nodes.count { it.status == "online" }

    fun guestCounts(type: GuestType): GuestCounts {
        val list = guests.filter { it.type == type.apiName && !it.template }
        return GuestCounts(running = list.count { it.status == "running" }, total = list.size)
    }

    val templateCount: Int get() = guests.count { it.template }

    /** Cluster CPU use, weighted by core count, over online nodes. */
    val cpuFraction: Double?
        get() {
            val online = nodes.filter { it.status == "online" && it.cpu != null && it.maxcpu != null }
            val cores = online.sumOf { it.maxcpu!! }
            return if (cores <= 0) null else online.sumOf { it.cpu!! * it.maxcpu!! } / cores
        }

    val memUsed: Long get() = nodes.filter { it.status == "online" }.sumOf { it.mem ?: 0 }
    val memTotal: Long get() = nodes.filter { it.status == "online" }.sumOf { it.maxmem ?: 0 }
    val memFraction: Double? get() = if (memTotal <= 0) null else memUsed.toDouble() / memTotal

    /** Storage above [threshold] usage — shown as warnings on the dashboard. */
    fun storagesAbove(threshold: Double): List<ClusterResource> =
        storages.filter { (it.diskFraction ?: 0.0) >= threshold }
}

data class GuestCounts(val running: Int, val total: Int)

/** A Proxmox Backup Server. */
data class PbsSnapshot(
    val datastores: List<DatastoreUsage>,
    val recentTasks: List<TaskSummary>,
    override val version: String?,
) : ServerSnapshot

/** Latest known state of one server plus how fresh it is. */
data class SnapshotState(
    val server: ServerState,
    /** Last successful snapshot; kept when a refresh fails so the UI can show stale data. */
    val snapshot: ServerSnapshot?,
    /** Error of the latest refresh, if it failed. */
    val error: ApiException?,
    val updatedAtMillis: Long?,
) {
    val isStale: Boolean get() = error != null && snapshot != null
    val isLoading: Boolean get() = error == null && snapshot == null
}
