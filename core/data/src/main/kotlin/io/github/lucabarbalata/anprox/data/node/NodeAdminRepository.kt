package io.github.lucabarbalata.anprox.data.node

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.pve.AptRepositories
import io.github.lucabarbalata.anprox.model.pve.AptUpdate
import io.github.lucabarbalata.anprox.model.pve.DiskStorageForm
import io.github.lucabarbalata.anprox.model.pve.Journal
import io.github.lucabarbalata.anprox.model.pve.NetworkConfig
import io.github.lucabarbalata.anprox.model.pve.NetworkInterfaceForm
import io.github.lucabarbalata.anprox.model.pve.NodeDisk
import io.github.lucabarbalata.anprox.model.pve.NodeDns
import io.github.lucabarbalata.anprox.model.pve.NodeDnsForm
import io.github.lucabarbalata.anprox.model.pve.NodeHosts
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import io.github.lucabarbalata.anprox.model.pve.NodeTime
import io.github.lucabarbalata.anprox.model.pve.PackageVersion
import io.github.lucabarbalata.anprox.model.pve.ServiceAction
import io.github.lucabarbalata.anprox.model.pve.SmartData
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.api.PbsApi
import io.github.lucabarbalata.anprox.network.api.PveApi

/**
 * Node administration (questionnaire Q22, milestones M5 and M8): updates and APT repositories, the
 * subscription, services and the system journal, of a Proxmox VE node or a Backup Server.
 */
class NodeAdminRepository(private val hub: ServerHub) {

    /**
     * Updates, repositories, the subscription, services and the journal have the same API on a
     * Backup Server (node `localhost`), so the same screens serve both products.
     */
    private suspend fun <T> either(serverId: String, pve: suspend PveApi.() -> ApiEnvelope<T>, pbs: suspend PbsApi.() -> ApiEnvelope<T>): T =
        hub.use(serverId) { if (it.product == ProxmoxProduct.PBS) it.pbs(pbs) else it.pve(pve) }

    private suspend fun eitherNoData(serverId: String, pve: suspend PveApi.() -> ApiEnvelope<*>, pbs: suspend PbsApi.() -> ApiEnvelope<*>) {
        hub.use(serverId) { if (it.product == ProxmoxProduct.PBS) it.pbsNoData(pbs) else it.pveNoData(pve) }
    }

    suspend fun updates(serverId: String, node: String): List<AptUpdate> =
        either(serverId, { aptUpdates(node) }, { aptUpdates(node) }).sortedBy { it.name }

    /** Starts `apt-get update`; returns the task's UPID. */
    suspend fun refresh(serverId: String, node: String): String = either(serverId, { refreshAptIndex(node) }, { refreshAptIndex(node) })

    suspend fun changelog(serverId: String, node: String, update: AptUpdate): String =
        either(serverId, { aptChangelog(node, update.name, update.version) }, { aptChangelog(node, update.name, update.version) })

    suspend fun versions(serverId: String, node: String): List<PackageVersion> = either(serverId, { packageVersions(node) }, { packageVersions(node) })

    /** Null when the user may not read it (it needs Sys.Audit). */
    suspend fun subscription(serverId: String, node: String): NodeSubscription? = try {
        either(serverId, { subscription(node) }, { subscription(node) })
    } catch (_: ApiException.Forbidden) {
        null
    }

    suspend fun setSubscriptionKey(serverId: String, node: String, key: String) {
        eitherNoData(serverId, { setSubscriptionKey(node, key.trim()) }, { setSubscriptionKey(node, key.trim()) })
    }

    suspend fun removeSubscriptionKey(serverId: String, node: String) {
        eitherNoData(serverId, { removeSubscriptionKey(node) }, { removeSubscriptionKey(node) })
    }

    suspend fun repositories(serverId: String, node: String): AptRepositories = either(serverId, { aptRepositories(node) }, { aptRepositories(node) })

    suspend fun setRepositoryEnabled(serverId: String, node: String, repos: AptRepositories, path: String, index: Int, enabled: Boolean) {
        val on = if (enabled) 1 else 0
        val digest = repos.digest.ifEmpty { null }
        eitherNoData(serverId, { setAptRepositoryEnabled(node, path, index, on, digest) }, { setAptRepositoryEnabled(node, path, index, on, digest) })
    }

    suspend fun addStandardRepository(serverId: String, node: String, repos: AptRepositories, handle: String) {
        val digest = repos.digest.ifEmpty { null }
        eitherNoData(serverId, { addStandardAptRepository(node, handle, digest) }, { addStandardAptRepository(node, handle, digest) })
    }

    suspend fun services(serverId: String, node: String): List<NodeService> =
        either(serverId, { services(node) }, { services(node) }).sortedBy { it.id }

    /** Returns the task's UPID. */
    suspend fun serviceAction(serverId: String, node: String, service: NodeService, action: ServiceAction): String =
        either(serverId, { serviceAction(node, service.id, action.apiName) }, { serviceAction(node, service.id, action.apiName) })

    /** Interfaces as they will be after applying, and the pending diff. */
    suspend fun network(serverId: String, node: String): NetworkConfig {
        val envelope = hub.use(serverId) { it.pveEnvelope { nodeNetworks(node, null) } }
        return NetworkConfig(envelope.data.orEmpty().sortedWith(compareBy({ networkOrder(it.type) }, { it.iface })), envelope.changes)
    }

    private fun networkOrder(type: String?) = when (type) {
        "bridge", "OVSBridge" -> 0
        "bond", "OVSBond" -> 1
        "vlan", "OVSIntPort" -> 2
        "eth" -> 3
        else -> 4
    }

    suspend fun createInterface(serverId: String, node: String, form: NetworkInterfaceForm) {
        hub.use(serverId) { it.pveNoData { createNetwork(node, form.createParams()) } }
    }

    suspend fun updateInterface(serverId: String, node: String, original: NodeNetwork, form: NetworkInterfaceForm) {
        hub.use(serverId) { it.pveNoData { updateNetwork(node, original.iface, form.updateParams(original)) } }
    }

    suspend fun deleteInterface(serverId: String, node: String, iface: String) {
        hub.use(serverId) { it.pveNoData { deleteNetwork(node, iface) } }
    }

    /** Applies the pending changes; returns the task's UPID. */
    suspend fun applyNetwork(serverId: String, node: String): String = hub.use(serverId) { it.pve { applyNetwork(node) } }

    suspend fun revertNetwork(serverId: String, node: String) {
        hub.use(serverId) { it.pveNoData { revertNetwork(node) } }
    }

    suspend fun disks(serverId: String, node: String): List<NodeDisk> =
        hub.use(serverId) { it.pve { disks(node) } }.sortedBy { it.devpath }

    suspend fun smart(serverId: String, node: String, disk: NodeDisk): SmartData = hub.use(serverId) { it.pve { smart(node, disk.devpath) } }

    /** These return the task's UPID. */
    suspend fun wipe(serverId: String, node: String, disk: NodeDisk): String = hub.use(serverId) { it.pve { wipeDisk(node, disk.devpath) } }

    suspend fun initGpt(serverId: String, node: String, disk: NodeDisk): String = hub.use(serverId) { it.pve { initGpt(node, disk.devpath) } }

    suspend fun createStorage(serverId: String, node: String, form: DiskStorageForm): String =
        hub.use(serverId) { it.pve { createDiskStorage(node, form.kind.apiName, form.params()) } }

    suspend fun dns(serverId: String, node: String): NodeDns = hub.use(serverId) { it.pve { nodeDns(node) } }

    suspend fun setDns(serverId: String, node: String, form: NodeDnsForm) {
        hub.use(serverId) { it.pveNoData { setNodeDns(node, form.params()) } }
    }

    suspend fun hosts(serverId: String, node: String): NodeHosts = hub.use(serverId) { it.pve { nodeHosts(node) } }

    /** Writes `/etc/hosts`; the digest refuses it if the file changed meanwhile. */
    suspend fun setHosts(serverId: String, node: String, original: NodeHosts, text: String) {
        hub.use(serverId) { it.pveNoData { setNodeHosts(node, text, original.digest) } }
    }

    suspend fun time(serverId: String, node: String): NodeTime = hub.use(serverId) { it.pve { nodeTime(node) } }

    suspend fun setTimezone(serverId: String, node: String, timezone: String) {
        hub.use(serverId) { it.pveNoData { setNodeTimezone(node, timezone) } }
    }

    /** The newest [lines] lines of the node's journal, oldest first. */
    suspend fun journal(serverId: String, node: String, lines: Int = 500): List<String> =
        Journal.lines(either(serverId, { journal(node, lines) }, { journal(node, lines) }))
}
