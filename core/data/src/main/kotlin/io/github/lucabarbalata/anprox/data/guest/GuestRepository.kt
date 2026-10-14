package io.github.lucabarbalata.anprox.data.guest

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.GuestNic
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Identifies a VM or container on a specific saved server. */
data class GuestRef(val serverId: String, val node: String, val type: GuestType, val vmid: Int)

/** Result of polling something: the latest value and/or the latest error. */
data class Polled<T>(val value: T?, val error: ApiException?)

/**
 * What a running guest reports about itself: OS and IP addresses. VMs need the QEMU guest agent
 * ([agentProblem] says why it can't answer); containers report their interfaces directly.
 */
data class GuestNetworkInfo(
    val os: String? = null,
    val nics: List<GuestNic> = emptyList(),
    val agentProblem: String? = null,
)

/** Guest-level operations (questionnaire Q18/Q19 grow this over the milestones). */
class GuestRepository(private val hub: ServerHub) {

    fun observeStatus(ref: GuestRef, intervalMillis: Long = 3_000): Flow<Polled<GuestStatus>> = poll(intervalMillis) {
        hub.use(ref.serverId) { it.pve { guestStatus(ref.node, ref.type.apiName, ref.vmid) } }
    }

    fun observeNode(serverId: String, node: String, intervalMillis: Long = 3_000): Flow<Polled<NodeStatus>> =
        poll(intervalMillis) { hub.use(serverId) { it.pve { nodeStatus(node) } } }

    /**
     * OS and IP addresses of a running guest, refreshed every [intervalMillis]. Emits `null` first
     * (loading) so the guest screen never waits for a slow guest agent.
     */
    fun observeNetwork(ref: GuestRef, intervalMillis: Long = 30_000): Flow<GuestNetworkInfo?> = flow {
        emit(null)
        while (true) {
            emit(
                try {
                    networkInfo(ref)
                } catch (e: ApiException) {
                    GuestNetworkInfo(agentProblem = e.message)
                },
            )
            delay(intervalMillis)
        }
    }

    /** One-shot [observeNetwork]. Agent failures become [GuestNetworkInfo.agentProblem]; others throw. */
    suspend fun networkInfo(ref: GuestRef): GuestNetworkInfo = when (ref.type) {
        GuestType.QEMU -> try {
            coroutineScope {
                val nics = async { hub.use(ref.serverId) { it.pve { agentInterfaces(ref.node, ref.vmid) } }.result.orEmpty() }
                // Some agents (old Windows builds) can't report the OS; that shouldn't hide the IPs.
                val os = async {
                    try {
                        hub.use(ref.serverId) { it.pve { agentOsInfo(ref.node, ref.vmid) } }.result?.displayName
                    } catch (e: ApiException) {
                        null
                    }
                }
                GuestNetworkInfo(os = os.await(), nics = GuestNic.fromAgent(nics.await()))
            }
        } catch (e: ApiException) {
            if (e is ApiException.Unauthorized || e is ApiException.Unreachable) throw e
            GuestNetworkInfo(agentProblem = e.message)
        }
        GuestType.LXC -> try {
            GuestNetworkInfo(nics = GuestNic.fromLxc(hub.use(ref.serverId) { it.pve { lxcInterfaces(ref.node, ref.vmid) } }))
        } catch (e: ApiException) {
            if (e is ApiException.Unauthorized || e is ApiException.Unreachable) throw e
            GuestNetworkInfo(agentProblem = e.message)
        }
    }

    /** Starts [action] and returns the task UPID. */
    suspend fun power(ref: GuestRef, action: PowerAction): String {
        require(action.appliesTo(ref.type)) { "$action is not available for ${ref.type}" }
        return hub.use(ref.serverId) {
            it.pve { guestPower(ref.node, ref.type.apiName, ref.vmid, action.apiPath, emptyMap()) }
        }
    }

    // ------------------------------------------------------------------ snapshots (Q18)

    /** All snapshots, including the `current` pseudo-entry. */
    suspend fun snapshots(ref: GuestRef): List<GuestSnapshot> =
        hub.use(ref.serverId) { it.pve { snapshots(ref.node, ref.type.apiName, ref.vmid) } }

    /**
     * Takes a snapshot and returns the task UPID. [includeRam] only applies to VMs (containers
     * can't save their memory) and only matters while the VM is running.
     */
    suspend fun createSnapshot(ref: GuestRef, name: String, description: String, includeRam: Boolean): String {
        val params = buildMap {
            put("snapname", name)
            if (description.isNotBlank()) put("description", description.trim())
            if (includeRam && ref.type == GuestType.QEMU) put("vmstate", "1")
        }
        return hub.use(ref.serverId) { it.pve { createSnapshot(ref.node, ref.type.apiName, ref.vmid, params) } }
    }

    /** Rolls back to [name] and returns the task UPID. Everything since that snapshot is lost. */
    suspend fun rollbackSnapshot(ref: GuestRef, name: String): String =
        hub.use(ref.serverId) { it.pve { rollbackSnapshot(ref.node, ref.type.apiName, ref.vmid, name, emptyMap()) } }

    /** Deletes [name] (the guest's current state is unaffected) and returns the task UPID. */
    suspend fun deleteSnapshot(ref: GuestRef, name: String): String =
        hub.use(ref.serverId) { it.pve { deleteSnapshot(ref.node, ref.type.apiName, ref.vmid, name) } }

    // ------------------------------------------------------------------ tasks

    suspend fun awaitTask(serverId: String, node: String, upid: String): TaskStatus =
        hub.use(serverId) { it.awaitPveTask(node, upid) }

    suspend fun taskStatus(serverId: String, node: String, upid: String): TaskStatus =
        hub.use(serverId) { it.pve { taskStatus(node, upid) } }

    suspend fun taskLog(serverId: String, node: String, upid: String, start: Int = 0, limit: Int = 500): List<TaskLogLine> =
        hub.use(serverId) { it.pve { taskLog(node, upid, start, limit) } }

    private fun <T> poll(intervalMillis: Long, fetch: suspend () -> T): Flow<Polled<T>> = flow {
        var last: T? = null
        while (true) {
            try {
                last = fetch()
                emit(Polled(last, null))
            } catch (e: ApiException) {
                emit(Polled(last, e))
            }
            delay(intervalMillis)
        }
    }
}
