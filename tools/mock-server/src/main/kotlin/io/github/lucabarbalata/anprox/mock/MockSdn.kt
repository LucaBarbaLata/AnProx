package io.github.lucabarbalata.anprox.mock

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

private typealias Section = LinkedHashMap<String, LinkedHashMap<String, String>>

/**
 * SDN as Proxmox keeps it: the configuration being edited and the configuration the nodes run.
 * Zones, VNets, subnets and controllers change only the edited one, and lists with `pending=1`
 * show the difference the way `PVE::Network::SDN::pending_config` does. Applying copies it over.
 * IPAMs and DNS servers have no pending state.
 *
 * The cluster runs a simple zone `lan` (DHCP, VNet `lab` with 10.10.0.0/24) and a VLAN zone
 * `vlans` on vmbr0 (VNet `dmz`, tag 30, 192.168.30.0/24), and has one change waiting: a new
 * VNet `iot` (tag 40).
 */
internal class MockSdn {
    private class Config(
        val zones: Section = Section(),
        val vnets: Section = Section(),
        val subnets: Section = Section(),
        val controllers: Section = Section(),
    ) {
        fun copy() = Config(zones.deep(), vnets.deep(), subnets.deep(), controllers.deep())

        fun section(kind: String): Section? = when (kind) {
            "zones" -> zones
            "vnets" -> vnets
            "subnets" -> subnets
            "controllers" -> controllers
            else -> null
        }

        private fun Section.deep() = Section().also { s -> forEach { (k, v) -> s[k] = LinkedHashMap(v) } }
    }

    private var running = Config()
    private var config = Config()
    private val ipams = linkedMapOf("pve" to linkedMapOf("type" to "pve"))
    private val dns = linkedMapOf<String, LinkedHashMap<String, String>>()

    init {
        running.zones["lan"] = linkedMapOf("type" to "simple", "ipam" to "pve", "dhcp" to "dnsmasq")
        running.zones["vlans"] = linkedMapOf("type" to "vlan", "bridge" to "vmbr0", "ipam" to "pve")
        running.vnets["lab"] = linkedMapOf("type" to "vnet", "zone" to "lan", "alias" to "Lab network")
        running.vnets["dmz"] = linkedMapOf("type" to "vnet", "zone" to "vlans", "tag" to "30", "alias" to "DMZ")
        running.subnets["lan-10.10.0.0-24"] = subnet("lab", "lan", "10.10.0.0/24", "gateway" to "10.10.0.1", "snat" to "1", "dhcp-range" to "start-address=10.10.0.100,end-address=10.10.0.200")
        running.subnets["vlans-192.168.30.0-24"] = subnet("dmz", "vlans", "192.168.30.0/24", "gateway" to "192.168.30.1")
        running.controllers["evpn1"] = linkedMapOf("type" to "evpn", "asn" to "65000", "peers" to "10.0.0.11,10.0.0.12,10.0.0.13")
        config = running.copy()
        config.vnets["iot"] = linkedMapOf("type" to "vnet", "zone" to "vlans", "tag" to "40", "alias" to "IoT")
    }

    private fun subnet(vnet: String, zone: String, cidr: String, vararg extra: Pair<String, String>) =
        linkedMapOf("type" to "subnet", "vnet" to vnet, "zone" to zone, "cidr" to cidr, *extra)

    // ---- reads

    fun hasPending(): Boolean = synchronized(this) { listOf("zones", "vnets", "subnets", "controllers").any { pending(it).any { e -> e.value.containsKey("state") } } }

    /** `GET /cluster/sdn/{kind}`; [vnet] filters subnets. */
    fun list(kind: String, pending: Boolean, vnet: String? = null): JsonArray? = synchronized(this) {
        val idKey = idKey(kind) ?: return null
        val entries: Map<String, Map<String, Any>> = when (kind) {
            "ipams" -> ipams
            "dns" -> dns
            else -> if (pending) pending(kind) else config.section(kind)!!
        }
        buildJsonArray {
            entries.filter { vnet == null || (it.value["vnet"] ?: it.value["pending"]?.let { p -> (p as Map<*, *>)["vnet"] }) == vnet }
                .forEach { (id, values) -> add(JsonObject(mapOf(idKey to JsonPrimitive(id)) + values.mapValues { (k, v) -> json(k, v) })) }
        }
    }

    private fun idKey(kind: String) = when (kind) {
        "zones" -> "zone"
        "vnets" -> "vnet"
        "subnets" -> "subnet"
        "controllers" -> "controller"
        "ipams" -> "ipam"
        "dns" -> "dns"
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(key: String, value: Any): JsonElement = when {
        value is Map<*, *> -> JsonObject((value as Map<String, Any>).mapValues { (k, v) -> json(k, v) })
        key == "dhcp-range" -> JsonArray(listOf(JsonPrimitive(value.toString())))
        key in NUMERIC -> value.toString().toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value.toString())
        else -> JsonPrimitive(value.toString())
    }

    /** Like `PVE::Network::SDN::pending_config`. */
    private fun pending(kind: String): Map<String, Map<String, Any>> {
        val run = running.section(kind)!!
        val cfg = config.section(kind)!!
        val out = linkedMapOf<String, LinkedHashMap<String, Any>>()
        for ((id, r) in run) {
            val c = cfg[id]
            val e = LinkedHashMap<String, Any>(r)
            if (c == null) {
                e["state"] = "deleted"
            } else {
                val changes = linkedMapOf<String, Any>()
                r.keys.filter { it !in c }.forEach { changes[it] = "deleted" }
                c.forEach { (k, v) -> if (k != "type" && k != "vnet" && r[k] != v) changes[k] = v }
                if (changes.isNotEmpty()) {
                    e["state"] = "changed"
                    e["pending"] = changes
                }
            }
            out[id] = e
        }
        for ((id, c) in cfg) {
            if (id in run) continue
            val e = LinkedHashMap<String, Any>()
            c["type"]?.let { e["type"] = it }
            c["vnet"]?.let { e["vnet"] = it }
            e["state"] = "new"
            e["pending"] = LinkedHashMap<String, Any>(c.filterKeys { it != "type" && it != "vnet" })
            out[id] = e
        }
        return out
    }

    fun ipamStatus(ipam: String): JsonArray? = synchronized(this) {
        if (ipam !in ipams) return null
        buildJsonArray {
            addJsonObject {
                put("vnet", "lab")
                put("zone", "lan")
                put("subnet", "10.10.0.0/24")
                put("ip", "10.10.0.1")
                put("gateway", 1)
            }
            addJsonObject {
                put("vnet", "lab")
                put("zone", "lan")
                put("subnet", "10.10.0.0/24")
                put("ip", "10.10.0.101")
                put("mac", "BC:24:11:3A:5E:01")
                put("hostname", "pihole")
                put("vmid", 200)
            }
            addJsonObject {
                put("vnet", "dmz")
                put("zone", "vlans")
                put("subnet", "192.168.30.0/24")
                put("ip", "192.168.30.1")
                put("gateway", 1)
            }
        }
    }

    // ---- changes (to the edited configuration; IPAM and DNS at once)

    fun create(kind: String, p: Map<String, String>): String? = synchronized(this) {
        when (kind) {
            "ipams", "dns" -> {
                val target = if (kind == "ipams") ipams else dns
                val id = p[idKey(kind)].orEmpty()
                if (!ID.matches(id)) return "$kind: invalid format"
                if (id in target) return "$kind '$id' already defined"
                if (p["url"].isNullOrBlank()) return "url: property is missing and it is not optional"
                target[id] = LinkedHashMap(p.filterKeys { it != idKey(kind) && it != "token" && it != "key" })
                return null
            }
        }
        val section = config.section(kind) ?: return "unknown kind '$kind'"
        val id = p[idKey(kind)].orEmpty()
        val values = LinkedHashMap(p.filterKeys { it != idKey(kind) && it != "digest" })
        when (kind) {
            "zones" -> {
                if (!ID.matches(id)) return "zone: invalid format - value does not match the regex pattern"
                if (id in section) return "zone '$id' already exists"
                val type = values["type"]
                if (type !in ZONE_TYPES) return "type: value '$type' does not have a value in the enumeration '${ZONE_TYPES.joinToString(", ")}'"
                zoneProblem(values)?.let { return it }
            }
            "vnets" -> {
                if (!ID.matches(id)) return "vnet: invalid format - value does not match the regex pattern"
                if (id in section) return "vnet '$id' already exists"
                values["type"] = "vnet"
                vnetProblem(values)?.let { return it }
            }
            "controllers" -> {
                if (!ID.matches(id)) return "controller: invalid format"
                if (id in section) return "controller '$id' already exists"
                if (values["asn"]?.toLongOrNull() == null) return "asn: property is missing and it is not optional"
            }
        }
        section[id] = values
        null
    }

    fun update(kind: String, id: String, p: Map<String, String>): String? = synchronized(this) {
        val section = config.section(kind) ?: return "unknown kind '$kind'"
        val current = section[id] ?: return "$kind '$id' does not exist"
        val next = LinkedHashMap(current)
        p["delete"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { next.remove(it) }
        p.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> next[k] = v }
        when (kind) {
            "zones" -> zoneProblem(next)?.let { return it }
            "vnets" -> vnetProblem(next)?.let { return it }
        }
        section[id] = next
        null
    }

    fun delete(kind: String, id: String): String? = synchronized(this) {
        when (kind) {
            "ipams" -> {
                if (config.zones.values.any { it["ipam"] == id }) return "ipam '$id' is used by a zone"
                return if (ipams.remove(id) == null) "ipam '$id' does not exist" else null
            }
            "dns" -> return if (dns.remove(id) == null) "dns '$id' does not exist" else null
        }
        val section = config.section(kind) ?: return "unknown kind '$kind'"
        if (id !in section) return "$kind '$id' does not exist"
        when (kind) {
            "zones" -> config.vnets.entries.firstOrNull { it.value["zone"] == id }?.let { return "Can't delete zone $id, it is used by vnet ${it.key}" }
            "vnets" -> if (config.subnets.values.any { it["vnet"] == id }) return "Can't delete vnet $id: it still has subnets"
            "controllers" -> config.zones.entries.firstOrNull { it.value["controller"] == id }?.let { return "controller $id is used by zone ${it.key}" }
        }
        section.remove(id)
        null
    }

    fun createSubnet(vnet: String, p: Map<String, String>): String? = synchronized(this) {
        val v = config.vnets[vnet] ?: return "vnet '$vnet' does not exist"
        val cidr = p["subnet"].orEmpty()
        val (net, prefix) = cidr.split('/').let { it.getOrNull(0).orEmpty() to it.getOrNull(1)?.toIntOrNull() }
        if (net.isEmpty() || prefix == null) return "subnet: invalid format - value does not look like a valid CIDR network"
        val zone = v["zone"].orEmpty()
        val id = "$zone-$net-$prefix"
        if (id in config.subnets) return "subnet '$cidr' already exists"
        config.subnets[id] = subnet(vnet, zone, cidr, *p.filterKeys { it != "subnet" && it != "type" }.toList().toTypedArray())
        null
    }

    fun updateSubnet(vnet: String, id: String, p: Map<String, String>): String? = synchronized(this) {
        val current = config.subnets[id]?.takeIf { it["vnet"] == vnet } ?: return "subnet '$id' does not exist"
        val next = LinkedHashMap(current)
        p["delete"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { next.remove(it) }
        p.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> next[k] = v }
        config.subnets[id] = next
        null
    }

    fun deleteSubnet(vnet: String, id: String): String? = synchronized(this) {
        if (config.subnets[id]?.get("vnet") != vnet) return "subnet '$id' does not exist"
        config.subnets.remove(id)
        null
    }

    /** What applying does when its task ends. */
    fun apply() = synchronized(this) { running = config.copy() }

    fun rollback() = synchronized(this) { config = running.copy() }

    private fun zoneProblem(z: Map<String, String>): String? = when (z["type"]) {
        "vlan" -> if (z["bridge"].isNullOrBlank()) "bridge: property is missing and it is not optional" else null
        "qinq" -> when {
            z["bridge"].isNullOrBlank() -> "bridge: property is missing and it is not optional"
            (z["tag"]?.toIntOrNull() ?: 0) !in 1..4094 -> "tag: property is missing and it is not optional"
            else -> null
        }
        "vxlan" -> if (z["peers"].isNullOrBlank()) "peers: property is missing and it is not optional" else null
        "evpn" -> when {
            z["controller"] !in config.controllers -> "controller: '${z["controller"]}' does not exist"
            z["vrf-vxlan"]?.toIntOrNull() == null -> "vrf-vxlan: property is missing and it is not optional"
            else -> null
        }
        else -> null
    }

    private fun vnetProblem(v: Map<String, String>): String? {
        val zone = config.zones[v["zone"].orEmpty()] ?: return "zone '${v["zone"]}' does not exist"
        val tag = v["tag"]
        return when (zone["type"]) {
            "simple" -> if (tag != null) "tag: simple zones don't support tags" else null
            "vlan", "qinq" -> if ((tag?.toIntOrNull() ?: 0) !in 1..4094) "tag: missing or invalid vlan tag" else null
            else -> if ((tag?.toIntOrNull() ?: 0) !in 1..16_777_215) "tag: missing or invalid vxlan id" else null
        }
    }

    companion object {
        val ID = Regex("^[A-Za-z][A-Za-z0-9]{0,6}[A-Za-z0-9]$")
        val ZONE_TYPES = listOf("simple", "vlan", "qinq", "vxlan", "evpn")
        private val NUMERIC = setOf("tag", "mtu", "vrf-vxlan", "snat", "vlanaware", "isolate-ports", "section", "ttl")
    }
}
