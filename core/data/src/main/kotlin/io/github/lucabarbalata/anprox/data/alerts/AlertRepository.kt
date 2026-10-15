package io.github.lucabarbalata.anprox.data.alerts

import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.hub.ServerState
import io.github.lucabarbalata.anprox.data.snapshot.PbsSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.alerts.Alert
import io.github.lucabarbalata.anprox.model.alerts.AlertEngine
import io.github.lucabarbalata.anprox.model.alerts.AlertMemory
import io.github.lucabarbalata.anprox.model.alerts.AlertSettings
import io.github.lucabarbalata.anprox.model.alerts.FinishedTask
import io.github.lucabarbalata.anprox.model.alerts.GuestSeen
import io.github.lucabarbalata.anprox.model.alerts.Observation
import io.github.lucabarbalata.anprox.model.alerts.ServerSummary
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Persistence for [AlertMemory], one per server. :app keeps it in DataStore. */
interface AlertMemoryStore {
    suspend fun load(serverId: String): AlertMemory?
    suspend fun save(serverId: String, memory: AlertMemory)
}

/** An alert and the server it is about. */
data class ServerAlert(
    val serverId: String,
    val serverName: String,
    val alert: Alert,
    val product: ProxmoxProduct = ProxmoxProduct.PVE,
)

/** The outcome of checking every server once. */
data class AlertCheck(
    val alerts: List<ServerAlert>,
    /** How many servers answered. */
    val checked: Int,
    /** Servers that couldn't be reached, by name. */
    val unreachable: List<String>,
    /** Servers whose session ran out during this check; alerts for them stop until the user signs in. */
    val signInNeeded: List<String>,
    /** Every server checked, in list order, answered or not. */
    val servers: List<ServerSummary> = emptyList(),
)

/**
 * Background alerts (questionnaire Q26/Q27): reads what each signed-in server looks like now,
 * compares it with what the previous check saw ([AlertEngine]) and remembers the result.
 * The worker in :app calls [check] every few minutes and turns the alerts into notifications.
 */
class AlertRepository(
    private val hub: ServerHub,
    private val snapshots: SnapshotRepository,
    private val memory: AlertMemoryStore,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun check(settings: AlertSettings): AlertCheck {
        hub.awaitLoaded()
        // A server the user logged out of is skipped; an expired session may still renew silently.
        val servers = hub.serversNow().filter { it.auth != AuthState.LOGGED_OUT }
        val outcomes = coroutineScope {
            servers.map { server ->
                async {
                    try {
                        server to Result.success(observe(server))
                    } catch (e: ApiException) {
                        server to Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        val alerts = ArrayList<ServerAlert>()
        val unreachable = ArrayList<String>()
        val signIn = ArrayList<String>()
        val summaries = ArrayList<ServerSummary>()
        outcomes.forEach { (server, outcome) ->
            val observation = outcome.getOrNull()
            if (observation != null) {
                // After a long gap (alerts off, phone off) old tasks would all look new: start over.
                val previous = memory.load(server.id)?.takeIf { observation.time - it.time in 0..MAX_GAP_SECONDS }
                val (found, remembered) = AlertEngine.evaluate(previous, observation, settings)
                memory.save(server.id, remembered)
                found.forEach { alerts += ServerAlert(server.id, server.profile.name, it, server.profile.product) }
                summaries += summary(server, observation, remembered)
            } else {
                if (outcome.exceptionOrNull() is ApiException.Unauthorized) {
                    // Reported once: when a working session stops working.
                    if (server.auth == AuthState.LOGGED_IN) signIn += server.profile.name
                } else {
                    unreachable += server.profile.name
                }
                summaries += ServerSummary(server.profile.name, reachable = false, backupServer = server.profile.product == ProxmoxProduct.PBS)
            }
        }
        return AlertCheck(alerts, outcomes.count { it.second.isSuccess }, unreachable, signIn, summaries)
    }

    private fun summary(server: ServerState, o: Observation, m: AlertMemory) = ServerSummary(
        name = server.profile.name,
        backupServer = server.profile.product == ProxmoxProduct.PBS,
        nodesOnline = o.nodes.count { it.value },
        nodes = o.nodes.size,
        guestsRunning = o.runningGuests.size,
        guests = o.guestIds.size,
        storages = o.storages.size,
        problems = m.offlineNodes.size + m.hot.size + (if (o.quorate == false) 1 else 0) +
            (if (o.ceph == "HEALTH_WARN" || o.ceph == "HEALTH_ERR") 1 else 0),
    )

    /** What [server] looks like right now. */
    suspend fun observe(server: ServerState): Observation {
        val now = clockSeconds()
        return when (val snapshot = snapshots.fetch(server)) {
            is PveSnapshot -> coroutineScope {
                val tasks = async { hub.use(server.id) { it.pve { clusterTasks() } } }
                val ceph = async {
                    try {
                        hub.use(server.id) { it.pve { cephStatus() } }.health.status
                    } catch (_: ApiException) {
                        null // no Ceph on this cluster, or not allowed to read it
                    }
                }
                pveObservation(now, snapshot, tasks.await(), ceph.await())
            }
            is PbsSnapshot -> Observation(
                time = now,
                storages = snapshot.datastores.mapNotNull { d -> d.fraction?.let { d.store to it } }.toMap(),
                tasks = finished(snapshot.recentTasks),
            )
        }
    }

    private fun pveObservation(now: Long, s: PveSnapshot, tasks: List<TaskSummary>, ceph: String?): Observation {
        // Offline only when both the cluster status and the resource list say so: a missing
        // field must never page someone at night.
        val onlineRows = s.nodeStatus.filter { it.online }.map { it.name }.toSet()
        val nodes = s.nodes.mapNotNull { n -> n.node?.let { it to (it in onlineRows || n.status == "online") } }.toMap()
        val guests = s.guests.filter { !it.template && it.vmid != null }
        val running = tasks.filter { it.endtime == null }
        // A backup of several guests (no single id) may stop and restart any of them.
        val busy = if (running.any { it.type == "vzdump" && it.id.isNullOrBlank() }) {
            guests.mapNotNull { it.vmid }.toSet()
        } else {
            running.mapNotNull { it.id?.toIntOrNull() }.toSet()
        }
        val online = s.nodes.filter { it.status == "online" }
        return Observation(
            time = now,
            nodes = nodes,
            runningGuests = guests.filter { it.status == "running" }.associate { g ->
                val vmid = g.vmid!!
                vmid to GuestSeen(vmid, g.name ?: vmid.toString(), g.node.orEmpty(), g.type)
            },
            guestIds = guests.mapNotNull { it.vmid }.toSet(),
            // A standalone node has no cluster row and so no quorum to lose.
            quorate = if (s.clusterName != null) s.quorate else null,
            nodeCpu = online.mapNotNull { n -> n.cpu?.let { n.node.orEmpty() to it } }.toMap(),
            nodeMemory = online.mapNotNull { n ->
                val mem = n.mem
                val max = n.maxmem
                if (mem != null && max != null && max > 0) n.node.orEmpty() to mem.toDouble() / max else null
            }.toMap(),
            storages = s.storages.filter { it.resourceType == ResourceType.STORAGE && it.status == "available" }
                .mapNotNull { st -> st.diskFraction?.let { storageName(st.shared, st.node, st.storage) to it } }
                .toMap(),
            tasks = finished(tasks),
            busyGuests = busy,
            ceph = ceph,
        )
    }

    private fun storageName(shared: Boolean, node: String?, storage: String?): String =
        if (shared || node == null) storage.orEmpty() else "$node/${storage.orEmpty()}"

    private fun finished(tasks: List<TaskSummary>): List<FinishedTask> = tasks.mapNotNull { t ->
        val end = t.endtime ?: return@mapNotNull null
        val status = t.status ?: return@mapNotNull null
        FinishedTask(t.upid, t.type, t.id?.takeIf { it.isNotBlank() }, t.node, end, status)
    }.sortedByDescending { it.endtime }

    companion object {
        /** Older memory than this (in seconds) is ignored: 12 hours. */
        const val MAX_GAP_SECONDS = 12 * 3600L
    }
}
