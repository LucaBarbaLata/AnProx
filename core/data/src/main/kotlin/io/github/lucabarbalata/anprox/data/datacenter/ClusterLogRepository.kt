package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ClusterLogEntry

/** Datacenter → Cluster log (`GET /cluster/log`). */
class ClusterLogRepository(private val hub: ServerHub) {

    /** The newest [max] entries, oldest first so the screen reads like a log. */
    suspend fun log(serverId: String, max: Int = 500): List<ClusterLogEntry> =
        hub.use(serverId) { it.pve { clusterLog(max) } }.sortedBy { it.time }
}
