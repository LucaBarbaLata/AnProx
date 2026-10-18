package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.json.objects
import io.github.lucabarbalata.anprox.model.pve.IpamEntry
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.SdnController
import io.github.lucabarbalata.anprox.model.pve.SdnControllerForm
import io.github.lucabarbalata.anprox.model.pve.SdnDns
import io.github.lucabarbalata.anprox.model.pve.SdnIpam
import io.github.lucabarbalata.anprox.model.pve.SdnPluginForm
import io.github.lucabarbalata.anprox.model.pve.SdnSubnet
import io.github.lucabarbalata.anprox.model.pve.SdnSubnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnVnet
import io.github.lucabarbalata.anprox.model.pve.SdnVnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnZone
import io.github.lucabarbalata.anprox.model.pve.SdnZoneForm

/** A node of the cluster, for the SDN forms (zone nodes, exit nodes, a BGP controller's node). */
data class ClusterNode(val name: String, val online: Boolean)

/**
 * Datacenter → SDN (questionnaire Q21, milestone M6). Zones, VNets, subnets and controllers are
 * read with their pending changes, which reach the nodes only on [apply]. IPAMs and DNS servers
 * take effect at once.
 */
class SdnRepository(private val hub: ServerHub) {

    suspend fun nodes(serverId: String): List<ClusterNode> =
        hub.use(serverId) { it.pve { clusterResources("node") } }
            .filter { it.resourceType == ResourceType.NODE }
            .mapNotNull { r -> r.node?.let { ClusterNode(it, r.status == "online") } }
            .sortedBy { it.name }

    /** Bridges of the first online node, to pick for VLAN and QinQ zones. */
    suspend fun bridges(serverId: String, node: String): List<String> =
        hub.use(serverId) { it.pve { nodeNetworks(node, "any_bridge") } }.filter { it.isBridge }.map { it.iface }.sorted()

    // ---- zones

    suspend fun zones(serverId: String): List<SdnZone> =
        hub.use(serverId) { it.pve { sdnList("zones") } }.objects().map(SdnZone::fromJson).sortedBy { it.id }

    suspend fun createZone(serverId: String, form: SdnZoneForm) {
        hub.use(serverId) { it.pveNoData { createSdn("zones", form.createParams()) } }
    }

    suspend fun updateZone(serverId: String, zone: SdnZone, form: SdnZoneForm) {
        hub.use(serverId) { it.pveNoData { updateSdn("zones", zone.id, form.updateParams(zone)) } }
    }

    suspend fun deleteZone(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteSdn("zones", id) } }
    }

    // ---- VNets and subnets

    suspend fun vnets(serverId: String): List<SdnVnet> =
        hub.use(serverId) { it.pve { sdnList("vnets") } }.objects().map(SdnVnet::fromJson).sortedWith(compareBy({ it.zone }, { it.id }))

    suspend fun createVnet(serverId: String, form: SdnVnetForm) {
        hub.use(serverId) { it.pveNoData { createSdn("vnets", form.createParams()) } }
    }

    suspend fun updateVnet(serverId: String, vnet: SdnVnet, form: SdnVnetForm) {
        hub.use(serverId) { it.pveNoData { updateSdn("vnets", vnet.id, form.updateParams(vnet)) } }
    }

    suspend fun deleteVnet(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteSdn("vnets", id) } }
    }

    suspend fun subnets(serverId: String, vnet: String): List<SdnSubnet> =
        hub.use(serverId) { it.pve { sdnSubnets(vnet) } }.objects().map(SdnSubnet::fromJson).sortedBy { it.cidr }

    suspend fun createSubnet(serverId: String, vnet: String, form: SdnSubnetForm) {
        hub.use(serverId) { it.pveNoData { createSdnSubnet(vnet, form.createParams()) } }
    }

    suspend fun updateSubnet(serverId: String, subnet: SdnSubnet, form: SdnSubnetForm) {
        hub.use(serverId) { it.pveNoData { updateSdnSubnet(subnet.vnet, subnet.id, form.updateParams(subnet)) } }
    }

    suspend fun deleteSubnet(serverId: String, subnet: SdnSubnet) {
        hub.use(serverId) { it.pveNoData { deleteSdnSubnet(subnet.vnet, subnet.id) } }
    }

    // ---- controllers, IPAMs, DNS

    suspend fun controllers(serverId: String): List<SdnController> =
        hub.use(serverId) { it.pve { sdnList("controllers") } }.objects().map(SdnController::fromJson).sortedBy { it.id }

    suspend fun createController(serverId: String, form: SdnControllerForm) {
        hub.use(serverId) { it.pveNoData { createSdn("controllers", form.createParams()) } }
    }

    suspend fun deleteController(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteSdn("controllers", id) } }
    }

    /** IPAMs and DNS servers have no pending state: asking for it is refused. */
    suspend fun ipams(serverId: String): List<SdnIpam> =
        hub.use(serverId) { it.pve { sdnList("ipams", pending = null) } }.objects().map(SdnIpam::fromJson).sortedWith(compareBy({ !it.builtIn }, { it.id }))

    suspend fun dnsServers(serverId: String): List<SdnDns> =
        hub.use(serverId) { it.pve { sdnList("dns", pending = null) } }.objects().map(SdnDns::fromJson).sortedBy { it.id }

    /** Adds a NetBox or phpIPAM IPAM, or a PowerDNS server. */
    suspend fun createPlugin(serverId: String, form: SdnPluginForm) {
        val kind = if (form.type == "powerdns") "dns" else "ipams"
        hub.use(serverId) { it.pveNoData { createSdn(kind, form.createParams()) } }
    }

    suspend fun deleteIpam(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteSdn("ipams", id) } }
    }

    suspend fun deleteDns(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteSdn("dns", id) } }
    }

    /** The addresses [ipam] handed out, by VNet then address. */
    suspend fun ipamEntries(serverId: String, ipam: String): List<IpamEntry> =
        hub.use(serverId) { it.pve { ipamStatus(ipam) } }.objects().map(IpamEntry::fromJson).sortedWith(compareBy({ it.vnet }, { it.ip }))

    // ---- pending changes

    /** Applies the pending configuration on all nodes; returns the task's UPID. */
    suspend fun apply(serverId: String): String = hub.use(serverId) { it.pve { applySdn() } }

    suspend fun discard(serverId: String) {
        hub.use(serverId) { it.pveNoData { rollbackSdn() } }
    }
}
