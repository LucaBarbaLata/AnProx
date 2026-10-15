package io.github.lucabarbalata.anprox.data.node

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.AptRepositories
import io.github.lucabarbalata.anprox.model.pve.AptUpdate
import io.github.lucabarbalata.anprox.model.pve.Journal
import io.github.lucabarbalata.anprox.model.pve.NetworkConfig
import io.github.lucabarbalata.anprox.model.pve.NetworkInterfaceForm
import io.github.lucabarbalata.anprox.model.pve.NodeDns
import io.github.lucabarbalata.anprox.model.pve.NodeDnsForm
import io.github.lucabarbalata.anprox.model.pve.NodeHosts
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import io.github.lucabarbalata.anprox.model.pve.NodeTime
import io.github.lucabarbalata.anprox.model.pve.PackageVersion
import io.github.lucabarbalata.anprox.model.pve.ServiceAction
import io.github.lucabarbalata.anprox.network.ApiException

/**
 * Node administration (questionnaire Q22, milestone M5): updates and APT repositories, the
 * subscription, services and the system journal.
 */
class NodeAdminRepository(private val hub: ServerHub) {

    suspend fun updates(serverId: String, node: String): List<AptUpdate> =
        hub.use(serverId) { it.pve { aptUpdates(node) } }.sortedBy { it.name }

    /** Starts `apt-get update`; returns the task's UPID. */
    suspend fun refresh(serverId: String, node: String): String = hub.use(serverId) { it.pve { refreshAptIndex(node) } }

    suspend fun changelog(serverId: String, node: String, update: AptUpdate): String =
        hub.use(serverId) { it.pve { aptChangelog(node, update.name, update.version) } }

    suspend fun versions(serverId: String, node: String): List<PackageVersion> = hub.use(serverId) { it.pve { packageVersions(node) } }

    /** Null when the user may not read it (it needs Sys.Audit). */
    suspend fun subscription(serverId: String, node: String): NodeSubscription? = try {
        hub.use(serverId) { it.pve { subscription(node) } }
    } catch (_: ApiException.Forbidden) {
        null
    }

    suspend fun repositories(serverId: String, node: String): AptRepositories = hub.use(serverId) { it.pve { aptRepositories(node) } }

    suspend fun setRepositoryEnabled(serverId: String, node: String, repos: AptRepositories, path: String, index: Int, enabled: Boolean) {
        hub.use(serverId) { it.pveNoData { setAptRepositoryEnabled(node, path, index, if (enabled) 1 else 0, repos.digest.ifEmpty { null }) } }
    }

    suspend fun addStandardRepository(serverId: String, node: String, repos: AptRepositories, handle: String) {
        hub.use(serverId) { it.pveNoData { addStandardAptRepository(node, handle, repos.digest.ifEmpty { null }) } }
    }

    suspend fun services(serverId: String, node: String): List<NodeService> =
        hub.use(serverId) { it.pve { services(node) } }.sortedBy { it.id }

    /** Returns the task's UPID. */
    suspend fun serviceAction(serverId: String, node: String, service: NodeService, action: ServiceAction): String =
        hub.use(serverId) { it.pve { serviceAction(node, service.id, action.apiName) } }

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
        Journal.lines(hub.use(serverId) { it.pve { journal(node, lines) } })
}
