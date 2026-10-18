package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.bool
import io.github.lucabarbalata.anprox.model.json.int
import io.github.lucabarbalata.anprox.model.json.obj
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.json.strings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Software-defined networking (questionnaire Q21, milestone M6): zones, VNets, subnets,
 * controllers, IPAMs and DNS servers under `/cluster/sdn`. Changes are saved as pending and only
 * reach the nodes when they are applied (`PUT /cluster/sdn`), so every list is read with
 * `pending=1`: an entry then has its running values, a `pending` object with what changes, and a
 * `state` (new, changed or deleted).
 */

/** Where an SDN object stands compared to what the nodes run. */
enum class SdnState(val apiName: String) {
    NEW("new"),
    CHANGED("changed"),
    DELETED("deleted"),
    ;

    companion object {
        fun fromApi(value: String?): SdnState? = entries.firstOrNull { it.apiName == value }
    }
}

/** The object as it will be once applied: running values overlaid with the pending ones. */
fun JsonObject.sdnEffective(): JsonObject {
    val pending = obj("pending") ?: return this
    val merged = LinkedHashMap(this)
    merged.remove("pending")
    pending.forEach { (k, v) ->
        if ((v as? JsonPrimitive)?.content == "deleted" && k != "state") merged.remove(k) else merged[k] = v
    }
    return JsonObject(merged)
}

private fun JsonObject.sdnState(): SdnState? = SdnState.fromApi(str("state"))

/** Pending changes anywhere: the reason to show **Apply**. */
fun sdnHasPending(states: List<SdnState?>): Boolean = states.any { it != null }

// ------------------------------------------------------------------------------ zones

enum class SdnZoneType(val apiName: String) {
    SIMPLE("simple"),
    VLAN("vlan"),
    QINQ("qinq"),
    VXLAN("vxlan"),
    EVPN("evpn"),
    ;

    /** What a VNet's `tag` means in this zone; null when VNets have none. */
    val tagRange: IntRange?
        get() = when (this) {
            SIMPLE -> null
            VLAN, QINQ -> 1..4094
            VXLAN, EVPN -> 1..16_777_215
        }

    companion object {
        fun fromApi(value: String?): SdnZoneType? = entries.firstOrNull { it.apiName == value }
    }
}

data class SdnZone(
    val id: String,
    val type: String,
    val state: SdnState? = null,
    val bridge: String? = null,
    /** The service VLAN of a QinQ zone. */
    val tag: Int? = null,
    val vlanProtocol: String? = null,
    val peers: List<String> = emptyList(),
    val controller: String? = null,
    val vrfVxlan: Int? = null,
    val exitNodes: List<String> = emptyList(),
    val mtu: Int? = null,
    val nodes: List<String> = emptyList(),
    val ipam: String? = null,
    val dns: String? = null,
    val reverseDns: String? = null,
    val dnsZone: String? = null,
    /** `dnsmasq` when the zone hands out addresses (simple zones). */
    val dhcp: String? = null,
) {
    val zoneType: SdnZoneType? get() = SdnZoneType.fromApi(type)

    companion object {
        fun fromJson(raw: JsonObject): SdnZone {
            val o = raw.sdnEffective()
            return SdnZone(
                id = o.str("zone").orEmpty(),
                type = o.str("type").orEmpty(),
                state = raw.sdnState(),
                bridge = o.str("bridge"),
                tag = o.int("tag"),
                vlanProtocol = o.str("vlan-protocol"),
                peers = o.strings("peers"),
                controller = o.str("controller"),
                vrfVxlan = o.int("vrf-vxlan"),
                exitNodes = o.strings("exitnodes"),
                mtu = o.int("mtu"),
                nodes = o.strings("nodes"),
                ipam = o.str("ipam"),
                dns = o.str("dns"),
                reverseDns = o.str("reversedns"),
                dnsZone = o.str("dnszone"),
                dhcp = o.str("dhcp"),
            )
        }
    }
}

/** Creating or editing a zone. Lists are comma-separated as typed. */
data class SdnZoneForm(
    val type: SdnZoneType = SdnZoneType.SIMPLE,
    val id: String = "",
    val bridge: String = "",
    val tag: String = "",
    val vlanProtocol: String = "802.1q",
    val peers: String = "",
    val controller: String = "",
    val vrfVxlan: String = "",
    val exitNodes: Set<String> = emptySet(),
    val mtu: String = "",
    /** Empty for all nodes. */
    val nodes: Set<String> = emptySet(),
    val ipam: String = "pve",
    val dhcp: Boolean = false,
    val dns: String = "",
    val dnsZone: String = "",
) {
    enum class Problem { ID, BRIDGE, TAG, PEERS, CONTROLLER, VRF_VXLAN, MTU }

    fun problem(creating: Boolean): Problem? = when {
        creating && !SDN_ID.matches(id.trim()) -> Problem.ID
        (type == SdnZoneType.VLAN || type == SdnZoneType.QINQ) && bridge.isBlank() -> Problem.BRIDGE
        type == SdnZoneType.QINQ && (tag.toIntOrNull() ?: 0) !in 1..4094 -> Problem.TAG
        type == SdnZoneType.VXLAN && peerList().isEmpty() -> Problem.PEERS
        type == SdnZoneType.VXLAN && peerList().any { !IpAddress.isIpv4(it) && !IpAddress.isIpv6(it) } -> Problem.PEERS
        type == SdnZoneType.EVPN && controller.isBlank() -> Problem.CONTROLLER
        type == SdnZoneType.EVPN && (vrfVxlan.toIntOrNull() ?: 0) !in 1..16_777_215 -> Problem.VRF_VXLAN
        mtu.isNotBlank() && (mtu.toIntOrNull() ?: 0) !in 576..65520 -> Problem.MTU
        else -> null
    }

    fun peerList(): List<String> = peers.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }

    private fun values(): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        when (type) {
            SdnZoneType.SIMPLE -> if (dhcp) put("dhcp", "dnsmasq")
            SdnZoneType.VLAN -> put("bridge", bridge.trim())
            SdnZoneType.QINQ -> {
                put("bridge", bridge.trim())
                put("tag", tag.trim())
                put("vlan-protocol", vlanProtocol)
            }
            SdnZoneType.VXLAN -> put("peers", peerList().joinToString(","))
            SdnZoneType.EVPN -> {
                put("controller", controller)
                put("vrf-vxlan", vrfVxlan.trim())
                if (exitNodes.isNotEmpty()) put("exitnodes", exitNodes.sorted().joinToString(","))
            }
        }
        mtu.trim().takeIf { it.isNotEmpty() }?.let { put("mtu", it) }
        if (nodes.isNotEmpty()) put("nodes", nodes.sorted().joinToString(","))
        ipam.takeIf { it.isNotBlank() }?.let { put("ipam", it) }
        dns.takeIf { it.isNotBlank() }?.let { put("dns", it) }
        dnsZone.trim().takeIf { it.isNotEmpty() }?.let { put("dnszone", it) }
    }

    fun createParams(): Map<String, String> = values() + mapOf("zone" to id.trim(), "type" to type.apiName)

    /** Changed values, and `delete` for options that were set and are now empty. */
    fun updateParams(original: SdnZone): Map<String, String> {
        val set = values()
        val delete = buildList {
            if (original.mtu != null && mtu.isBlank()) add("mtu")
            if (original.nodes.isNotEmpty() && nodes.isEmpty()) add("nodes")
            if (original.dns != null && dns.isBlank()) add("dns")
            if (original.dnsZone != null && dnsZone.isBlank()) add("dnszone")
            if (original.dhcp != null && !dhcp && type == SdnZoneType.SIMPLE) add("dhcp")
            if (original.exitNodes.isNotEmpty() && exitNodes.isEmpty() && type == SdnZoneType.EVPN) add("exitnodes")
        }
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        return set
    }

    companion object {
        /** Zone and VNet IDs: a letter, then letters or digits, at most 8 characters. */
        val SDN_ID = Regex("^[A-Za-z][A-Za-z0-9]{0,6}[A-Za-z0-9]$")

        fun from(z: SdnZone) = SdnZoneForm(
            type = z.zoneType ?: SdnZoneType.SIMPLE,
            id = z.id,
            bridge = z.bridge.orEmpty(),
            tag = z.tag?.toString().orEmpty(),
            vlanProtocol = z.vlanProtocol ?: "802.1q",
            peers = z.peers.joinToString(", "),
            controller = z.controller.orEmpty(),
            vrfVxlan = z.vrfVxlan?.toString().orEmpty(),
            exitNodes = z.exitNodes.toSet(),
            mtu = z.mtu?.toString().orEmpty(),
            nodes = z.nodes.toSet(),
            ipam = z.ipam.orEmpty(),
            dhcp = z.dhcp != null,
            dns = z.dns.orEmpty(),
            dnsZone = z.dnsZone.orEmpty(),
        )
    }
}

// ------------------------------------------------------------------------------ VNets

data class SdnVnet(
    val id: String,
    val zone: String,
    val state: SdnState? = null,
    val tag: Int? = null,
    val alias: String? = null,
    val vlanAware: Boolean = false,
    val isolatePorts: Boolean = false,
) {
    companion object {
        fun fromJson(raw: JsonObject): SdnVnet {
            val o = raw.sdnEffective()
            return SdnVnet(
                id = o.str("vnet").orEmpty(),
                zone = o.str("zone").orEmpty(),
                state = raw.sdnState(),
                tag = o.int("tag"),
                alias = o.str("alias"),
                vlanAware = o.bool("vlanaware"),
                isolatePorts = o.bool("isolate-ports"),
            )
        }
    }
}

data class SdnVnetForm(
    val id: String = "",
    val zone: String = "",
    val tag: String = "",
    val alias: String = "",
    val vlanAware: Boolean = false,
    val isolatePorts: Boolean = false,
) {
    enum class Problem { ID, ZONE, TAG, TAG_NOT_ALLOWED, ALIAS }

    /** [zoneType] is the type of the chosen zone, which decides whether a tag is needed. */
    fun problem(creating: Boolean, zoneType: SdnZoneType?): Problem? {
        val range = zoneType?.tagRange
        return when {
            creating && !SdnZoneForm.SDN_ID.matches(id.trim()) -> Problem.ID
            zone.isBlank() -> Problem.ZONE
            range != null && (tag.toIntOrNull() ?: 0) !in range -> Problem.TAG
            range == null && zoneType != null && tag.isNotBlank() -> Problem.TAG_NOT_ALLOWED
            alias.length > 256 -> Problem.ALIAS
            else -> null
        }
    }

    private fun values(): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        put("zone", zone)
        tag.trim().takeIf { it.isNotEmpty() }?.let { put("tag", it) }
        alias.trim().takeIf { it.isNotEmpty() }?.let { put("alias", it) }
        if (vlanAware) put("vlanaware", "1")
        if (isolatePorts) put("isolate-ports", "1")
    }

    fun createParams(): Map<String, String> = values() + ("vnet" to id.trim())

    fun updateParams(original: SdnVnet): Map<String, String> {
        val set = values()
        val delete = buildList {
            if (original.tag != null && tag.isBlank()) add("tag")
            if (original.alias != null && alias.isBlank()) add("alias")
            if (original.vlanAware && !vlanAware) add("vlanaware")
            if (original.isolatePorts && !isolatePorts) add("isolate-ports")
        }
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        return set
    }

    companion object {
        fun from(v: SdnVnet) = SdnVnetForm(v.id, v.zone, v.tag?.toString().orEmpty(), v.alias.orEmpty(), v.vlanAware, v.isolatePorts)
    }
}

// ------------------------------------------------------------------------------ subnets

data class SdnSubnet(
    /** `zone-10.0.0.0-24`: the ID the API wants in paths. */
    val id: String,
    val cidr: String,
    val vnet: String,
    val state: SdnState? = null,
    val gateway: String? = null,
    val snat: Boolean = false,
    /** `start-address=…,end-address=…`, as Proxmox stores them. */
    val dhcpRanges: List<String> = emptyList(),
    val dnsZonePrefix: String? = null,
) {
    companion object {
        fun fromJson(raw: JsonObject): SdnSubnet {
            val o = raw.sdnEffective()
            val ranges = when (val r = o["dhcp-range"]) {
                is kotlinx.serialization.json.JsonArray -> r.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(r.content)
                else -> emptyList()
            }
            val id = o.str("subnet").orEmpty()
            return SdnSubnet(
                id = id,
                cidr = o.str("cidr") ?: cidrOf(id),
                vnet = o.str("vnet").orEmpty(),
                state = raw.sdnState(),
                gateway = o.str("gateway"),
                snat = o.bool("snat"),
                dhcpRanges = ranges,
                dnsZonePrefix = o.str("dnszoneprefix"),
            )
        }

        /** `zone1-10.0.0.0-24` → `10.0.0.0/24`. */
        fun cidrOf(id: String): String {
            val rest = id.substringAfter('-', id)
            val prefix = rest.substringAfterLast('-', "")
            return if (prefix.isEmpty()) rest else rest.substringBeforeLast('-') + "/" + prefix
        }
    }
}

/** Creating or editing a subnet (IPv4 or IPv6), with one DHCP range. */
data class SdnSubnetForm(
    val cidr: String = "",
    val gateway: String = "",
    val snat: Boolean = false,
    val dhcpStart: String = "",
    val dhcpEnd: String = "",
    val dnsZonePrefix: String = "",
) {
    enum class Problem { CIDR, GATEWAY, DHCP_RANGE }

    fun problem(creating: Boolean): Problem? {
        val c = cidr.trim()
        val v4 = IpAddress.isIpv4Cidr(c)
        return when {
            creating && !v4 && !IpAddress.isIpv6Cidr(c) -> Problem.CIDR
            gateway.isNotBlank() && !(if (v4) Ipv4.inside(gateway.trim(), c) else IpAddress.isIpv6(gateway.trim())) -> Problem.GATEWAY
            (dhcpStart.isBlank()) != (dhcpEnd.isBlank()) -> Problem.DHCP_RANGE
            dhcpStart.isNotBlank() && v4 && !rangeInside(c) -> Problem.DHCP_RANGE
            else -> null
        }
    }

    private fun rangeInside(cidr: String): Boolean {
        val start = Ipv4.toLong(dhcpStart.trim())
        val end = Ipv4.toLong(dhcpEnd.trim())
        return start != null && end != null && start <= end && Ipv4.inside(dhcpStart.trim(), cidr) && Ipv4.inside(dhcpEnd.trim(), cidr)
    }

    private fun values(): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        gateway.trim().takeIf { it.isNotEmpty() }?.let { put("gateway", it) }
        if (snat) put("snat", "1")
        if (dhcpStart.isNotBlank()) put("dhcp-range", "start-address=${dhcpStart.trim()},end-address=${dhcpEnd.trim()}")
        dnsZonePrefix.trim().takeIf { it.isNotEmpty() }?.let { put("dnszoneprefix", it) }
    }

    fun createParams(): Map<String, String> = values() + mapOf("subnet" to cidr.trim(), "type" to "subnet")

    fun updateParams(original: SdnSubnet): Map<String, String> {
        val set = values()
        val delete = buildList {
            if (original.gateway != null && gateway.isBlank()) add("gateway")
            if (original.snat && !snat) add("snat")
            if (original.dhcpRanges.isNotEmpty() && dhcpStart.isBlank()) add("dhcp-range")
            if (original.dnsZonePrefix != null && dnsZonePrefix.isBlank()) add("dnszoneprefix")
        }
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        return set
    }

    companion object {
        fun from(s: SdnSubnet): SdnSubnetForm {
            val range = s.dhcpRanges.firstOrNull().orEmpty().split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            return SdnSubnetForm(s.cidr, s.gateway.orEmpty(), s.snat, range["start-address"].orEmpty(), range["end-address"].orEmpty(), s.dnsZonePrefix.orEmpty())
        }
    }
}

/** IPv4 arithmetic for subnet checks. */
object Ipv4 {
    fun toLong(address: String): Long? {
        if (!IpAddress.isIpv4(address)) return null
        return address.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    }

    /** True if [address] lies in [cidr] (e.g. `10.0.0.1` in `10.0.0.0/24`). */
    fun inside(address: String, cidr: String): Boolean {
        val a = toLong(address) ?: return false
        val net = toLong(cidr.substringBefore('/')) ?: return false
        val bits = cidr.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 0..32 } ?: return false
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (a and mask) == (net and mask)
    }
}

// ------------------------------------------------------------------------------ controllers, IPAMs, DNS

/** An EVPN, BGP or IS-IS controller (`/cluster/sdn/controllers`). */
data class SdnController(
    val id: String,
    val type: String,
    val state: SdnState? = null,
    val asn: Long? = null,
    val peers: List<String> = emptyList(),
    val node: String? = null,
) {
    companion object {
        fun fromJson(raw: JsonObject): SdnController {
            val o = raw.sdnEffective()
            return SdnController(
                id = o.str("controller").orEmpty(),
                type = o.str("type").orEmpty(),
                state = raw.sdnState(),
                asn = o.str("asn")?.toLongOrNull(),
                peers = o.strings("peers"),
                node = o.str("node"),
            )
        }
    }
}

/** An EVPN controller (the peers are the nodes' addresses) or a BGP controller for one node. */
data class SdnControllerForm(
    val type: String = "evpn",
    val id: String = "",
    val asn: String = "65000",
    val peers: String = "",
    val node: String = "",
) {
    enum class Problem { ID, ASN, PEERS, NODE }

    fun peerList(): List<String> = peers.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }

    fun problem(): Problem? = when {
        !CONTROLLER_ID.matches(id.trim()) -> Problem.ID
        (asn.toLongOrNull() ?: 0) !in 1..4_294_967_295 -> Problem.ASN
        type == "evpn" && (peerList().isEmpty() || peerList().any { !IpAddress.isIpv4(it) && !IpAddress.isIpv6(it) }) -> Problem.PEERS
        type == "bgp" && node.isBlank() -> Problem.NODE
        else -> null
    }

    fun createParams(): Map<String, String> = buildMap {
        put("controller", id.trim())
        put("type", type)
        put("asn", asn.trim())
        if (peerList().isNotEmpty()) put("peers", peerList().joinToString(","))
        if (type == "bgp") put("node", node)
    }

    companion object {
        val TYPES = listOf("evpn", "bgp")
        val CONTROLLER_ID = Regex("^[A-Za-z][A-Za-z0-9]{0,6}[A-Za-z0-9]$")
    }
}

/** An IPAM (`/cluster/sdn/ipams`): Proxmox's own (`pve`), NetBox or phpIPAM. */
data class SdnIpam(val id: String, val type: String, val url: String? = null, val section: Int? = null, val state: SdnState? = null) {
    val builtIn: Boolean get() = type == "pve"

    companion object {
        fun fromJson(raw: JsonObject): SdnIpam {
            val o = raw.sdnEffective()
            return SdnIpam(o.str("ipam").orEmpty(), o.str("type").orEmpty(), o.str("url"), o.int("section"), raw.sdnState())
        }
    }
}

/** A DNS server for SDN (`/cluster/sdn/dns`): PowerDNS. */
data class SdnDns(val id: String, val type: String, val url: String? = null, val ttl: Int? = null, val state: SdnState? = null) {
    companion object {
        fun fromJson(raw: JsonObject): SdnDns {
            val o = raw.sdnEffective()
            return SdnDns(o.str("dns").orEmpty(), o.str("type").orEmpty(), o.str("url"), o.int("ttl"), raw.sdnState())
        }
    }
}

/** Adding NetBox or phpIPAM ([type] `netbox` or `phpipam`), or a PowerDNS server ([type] `powerdns`). */
data class SdnPluginForm(
    val type: String,
    val id: String = "",
    val url: String = "",
    /** The API token (IPAM) or key (PowerDNS). */
    val token: String = "",
    /** phpIPAM section. */
    val section: String = "",
) {
    val valid: Boolean
        get() = SdnControllerForm.CONTROLLER_ID.matches(id.trim()) &&
            (url.trim().startsWith("https://") || url.trim().startsWith("http://")) && token.isNotBlank() &&
            (type != "phpipam" || section.toIntOrNull() != null)

    fun createParams(): Map<String, String> = buildMap {
        put(if (type == "powerdns") "dns" else "ipam", id.trim())
        put("type", type)
        put("url", url.trim())
        put(if (type == "powerdns") "key" else "token", token.trim())
        if (type == "phpipam") put("section", section.trim())
    }

    companion object {
        val IPAM_TYPES = listOf("netbox", "phpipam")
    }
}

/** An address the IPAM handed out (`GET /cluster/sdn/ipams/{ipam}/status`). */
data class IpamEntry(
    val ip: String,
    val vnet: String? = null,
    val subnet: String? = null,
    val mac: String? = null,
    val hostname: String? = null,
    val vmid: Int? = null,
    val gateway: Boolean = false,
) {
    companion object {
        fun fromJson(o: JsonObject) = IpamEntry(
            ip = o.str("ip").orEmpty(),
            vnet = o.str("vnet"),
            subnet = o.str("subnet"),
            mac = o.str("mac"),
            hostname = o.str("hostname"),
            vmid = o.int("vmid"),
            gateway = o.bool("gateway"),
        )
    }
}
