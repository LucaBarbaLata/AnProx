package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ClusterLogEntry
import io.github.lucabarbalata.anprox.model.pve.DatacenterOptions

/** Datacenter-wide settings and the cluster log. */
class ClusterRepository(private val hub: ServerHub) {

    /** The newest [max] entries, oldest first so the screen reads like a log. */
    suspend fun log(serverId: String, max: Int = 500): List<ClusterLogEntry> =
        hub.use(serverId) { it.pve { clusterLog(max) } }.sortedBy { it.time }

    suspend fun options(serverId: String): DatacenterOptions =
        DatacenterOptions.fromJson(hub.use(serverId) { it.pve { clusterOptions() } })

    /** Saves what changed; [old] carries the digest, so a concurrent edit is refused. */
    suspend fun saveOptions(serverId: String, old: DatacenterOptions, new: DatacenterOptions) {
        val params = DatacenterOptions.changes(old, new)
        if (params.isEmpty()) return
        hub.use(serverId) { it.pveNoData { updateClusterOptions(params) } }
    }
}
