package io.github.lucabarbalata.anprox.data.guest

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.pve.CloneRequest
import io.github.lucabarbalata.anprox.model.pve.GuestNic
import io.github.lucabarbalata.anprox.model.pve.GuestOptions
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.MigrationCheck
import io.github.lucabarbalata.anprox.model.pve.MigrationPlan
import io.github.lucabarbalata.anprox.model.pve.NewContainer
import io.github.lucabarbalata.anprox.model.pve.NewVm
import io.github.lucabarbalata.anprox.model.pve.NodePowerAction
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.ResourceType
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

/**
 * Hears about every task the app starts waiting for, so :app can follow it in a notification
 * that outlives the screen (questionnaire Q28, live task notifications).
 */
fun interface TaskObserver {
    fun started(serverId: String, node: String, upid: String)
}

/** Guest-level operations (questionnaire Q18/Q19 grow this over the milestones). */
class GuestRepository(private val hub: ServerHub, private val taskObserver: TaskObserver? = null) {

    fun observeStatus(ref: GuestRef, intervalMillis: Long = 3_000): Flow<Polled<GuestStatus>> = poll(intervalMillis) { status(ref) }

    suspend fun status(ref: GuestRef): GuestStatus =
        hub.use(ref.serverId) { it.pve { guestStatus(ref.node, ref.type.apiName, ref.vmid) } }

    /** Asks [node] to reboot or shut down; Proxmox answers at once and does the rest in the background. */
    suspend fun nodePower(serverId: String, node: String, action: NodePowerAction) {
        hub.use(serverId) { it.pveNoData { nodePower(node, action.apiName) } }
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

    // ------------------------------------------------------------------ options (Q19)

    suspend fun options(ref: GuestRef): GuestOptions =
        GuestOptions.fromConfig(ref.type, hub.use(ref.serverId) { it.pve { guestConfig(ref.node, ref.type.apiName, ref.vmid) } })

    /**
     * Saves what changed from [old] to [new]. [old] carries the config digest, so Proxmox refuses
     * the change if someone else edited the guest since it was loaded. Does nothing if nothing changed.
     */
    suspend fun saveOptions(ref: GuestRef, old: GuestOptions, new: GuestOptions) {
        val params = GuestOptions.changes(ref.type, old, new)
        if (params.isEmpty()) return
        hub.use(ref.serverId) { it.pveNoData { updateGuestConfig(ref.node, ref.type.apiName, ref.vmid, params) } }
    }

    // ------------------------------------------------------------------ clone, template, migrate (Q18)

    /** Every node of the server's cluster and whether it is online, for picking a target. */
    suspend fun clusterNodes(serverId: String): Map<String, Boolean> =
        hub.use(serverId) { it.pve { clusterResources("node") } }
            .filter { it.resourceType == ResourceType.NODE && it.node != null }
            .associate { it.node!! to (it.status == "online") }

    /** Enabled storages on [node] that can hold the disks of a [type] guest. */
    suspend fun diskStorages(serverId: String, node: String, type: GuestType): List<NodeStorage> {
        val content = if (type == GuestType.QEMU) "images" else "rootdir"
        return hub.use(serverId) { it.pve { nodeStorages(node, content, 1) } }
            .filter { it.enabled && it.active && content in it.content.split(',') }
            .sortedBy { it.storage }
    }

    /** The lowest free VMID. */
    suspend fun nextVmid(serverId: String): Int = hub.use(serverId) { it.pve { nextId() } }

    /** Every VMID in use in the cluster, to check a chosen ID before creating a guest. */
    suspend fun usedVmids(serverId: String): Set<Int> =
        hub.use(serverId) { it.pve { clusterResources("vm") } }.mapNotNull { it.vmid }.toSet()

    /** Creates a VM on [node]; returns the task UPID. */
    suspend fun createVm(serverId: String, node: String, vm: NewVm): String =
        hub.use(serverId) { it.pve { createQemu(node, vm.params()) } }

    /** Creates a container on [node]; returns the task UPID. */
    suspend fun createContainer(serverId: String, node: String, ct: NewContainer): String =
        hub.use(serverId) { it.pve { createLxc(node, ct.params()) } }

    /** Starts the clone and returns the task UPID (the task runs on the source node). */
    suspend fun clone(ref: GuestRef, request: CloneRequest): String =
        hub.use(ref.serverId) { it.pve { cloneGuest(ref.node, ref.type.apiName, ref.vmid, request.params(ref.type, ref.node)) } }

    /** Turns a stopped guest into a template. A task UPID for VMs; null for containers, which convert at once. */
    suspend fun convertToTemplate(ref: GuestRef): String? =
        hub.use(ref.serverId) { it.pveOrNull { convertToTemplate(ref.node, ref.type.apiName, ref.vmid, emptyMap()) } }

    /** Proxmox's pre-flight check for VMs; containers have none (null). */
    suspend fun migrationCheck(ref: GuestRef): MigrationCheck? =
        if (ref.type == GuestType.QEMU) hub.use(ref.serverId) { it.pve { migrationCheck(ref.node, ref.vmid) } } else null

    /** Moves the guest to [target] (live for running VMs, with a restart for running containers). Returns the UPID. */
    suspend fun migrate(ref: GuestRef, target: String, running: Boolean, check: MigrationCheck?): String =
        hub.use(ref.serverId) {
            it.pve { migrateGuest(ref.node, ref.type.apiName, ref.vmid, MigrationPlan.params(ref.type, target, running, check)) }
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

    // These work for Backup Server tasks too (its node is always `localhost`).

    suspend fun awaitTask(serverId: String, node: String, upid: String): TaskStatus {
        taskObserver?.started(serverId, node, upid)
        return hub.use(serverId) { it.awaitTask(node, upid) }
    }

    suspend fun taskStatus(serverId: String, node: String, upid: String): TaskStatus =
        hub.use(serverId) { if (it.product == ProxmoxProduct.PBS) it.pbs { taskStatus(upid) } else it.pve { taskStatus(node, upid) } }

    suspend fun taskLog(serverId: String, node: String, upid: String, start: Int = 0, limit: Int = 500): List<TaskLogLine> =
        hub.use(serverId) { if (it.product == ProxmoxProduct.PBS) it.pbs { taskLog(upid, start, limit) } else it.pve { taskLog(node, upid, start, limit) } }

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
