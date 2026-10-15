package io.github.lucabarbalata.anprox.data.hardware

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.CtHardware
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.PendingEntry
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * Hardware of VMs and containers (questionnaire Q19): reading the config with its pending
 * changes, and the calls that change it. The forms come from the model (`VmCpuMemory`,
 * `NewVmDisk`, `VmDiskOps`, `CtResources`…), so this class only talks to Proxmox.
 */
class HardwareRepository(private val hub: ServerHub) {

    suspend fun vm(ref: GuestRef): VmHardware {
        require(ref.type == GuestType.QEMU) { "not a VM" }
        val (config, pending) = configAndPending(ref)
        return VmHardware.fromConfig(config, pending)
    }

    suspend fun container(ref: GuestRef): CtHardware {
        require(ref.type == GuestType.LXC) { "not a container" }
        val (config, pending) = configAndPending(ref)
        return CtHardware.fromConfig(config, pending)
    }

    private suspend fun configAndPending(ref: GuestRef): Pair<JsonObject, Set<String>> = coroutineScope {
        val config = async { hub.use(ref.serverId) { it.pve { guestConfig(ref.node, ref.type.apiName, ref.vmid) } } }
        // Only used for the "after restart" badges, so a failure here must not hide the hardware.
        val pending = async {
            try {
                PendingEntry.keys(hub.use(ref.serverId) { it.pve { guestPending(ref.node, ref.type.apiName, ref.vmid) } })
            } catch (_: ApiException) {
                emptySet()
            }
        }
        config.await() to pending.await()
    }

    /**
     * Applies a config form from the model. With [asTask] (VMs, when a disk is allocated)
     * Proxmox runs it as a task and the UPID is returned; otherwise the change is done when this
     * returns (null). Does nothing for an empty form.
     */
    suspend fun apply(ref: GuestRef, params: Map<String, String>, asTask: Boolean = false): String? {
        if (params.isEmpty()) return null
        return hub.use(ref.serverId) {
            if (asTask && ref.type == GuestType.QEMU) {
                it.pveOrNull { updateVmConfigAsync(ref.node, ref.vmid, params) }
            } else {
                it.pveNoData { updateGuestConfig(ref.node, ref.type.apiName, ref.vmid, params) }
                null
            }
        }
    }

    /** Grows a disk or volume. A task UPID, or null when Proxmox did it at once (older VMs). */
    suspend fun resize(ref: GuestRef, params: Map<String, String>): String? =
        hub.use(ref.serverId) { it.pveOrNull { resizeDisk(ref.node, ref.type.apiName, ref.vmid, params) } }

    /** Moves a disk (VM) or volume (container) to another storage. Returns the task UPID. */
    suspend fun move(ref: GuestRef, params: Map<String, String>): String = hub.use(ref.serverId) {
        if (ref.type == GuestType.QEMU) it.pve { moveVmDisk(ref.node, ref.vmid, params) } else it.pve { moveCtVolume(ref.node, ref.vmid, params) }
    }

    /** The bridges of [node], for network devices. */
    suspend fun bridges(serverId: String, node: String): List<NodeNetwork> =
        hub.use(serverId) { it.pve { nodeNetworks(node, "any_bridge") } }.filter { it.isBridge }.sortedBy { it.iface }

    /** ISO images on every storage of [node] that holds them, by file name. */
    suspend fun isoImages(serverId: String, node: String): List<StorageContentItem> = files(serverId, node, ISO)

    /** Container templates on every storage of [node] that holds them, by file name. */
    suspend fun containerTemplates(serverId: String, node: String): List<StorageContentItem> = files(serverId, node, TEMPLATE)

    /** Files of one [content] type across the node's storages. A storage that can't be listed (offline NFS…) is skipped. */
    private suspend fun files(serverId: String, node: String, content: String): List<StorageContentItem> {
        val storages = hub.use(serverId) { it.pve { nodeStorages(node, content, 1) } }
            .filter { it.enabled && it.active && content in it.content.split(',') }
        return coroutineScope {
            storages.map { s ->
                async {
                    try {
                        hub.use(serverId) { it.pve { storageContent(node, s.storage, content, null) } }
                    } catch (_: ApiException) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }.flatten().filter { it.content == content }.sortedBy { it.volid.substringAfterLast('/').lowercase() }
    }

    /** The node's CPU threads (the limit for a guest's vCPUs), or null if unknown. */
    suspend fun hostCpus(serverId: String, node: String): Int? = try {
        hub.use(serverId) { it.pve { nodeStatus(node) } }.cpuinfo?.cpus
    } catch (_: ApiException) {
        null
    }

    private companion object {
        const val ISO = "iso"
        const val TEMPLATE = "vztmpl"
    }
}
