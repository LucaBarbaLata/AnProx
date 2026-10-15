package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.NodeNetwork

/**
 * `/etc/network/interfaces` per node: two ports, `vmbr0` (the LAN, with the node's address) and
 * `vmbr1`. Changes go to a pending copy (`interfaces.new`) until applied or reverted, as in Proxmox.
 */
internal class MockNetwork(nodes: List<String>) {
    private val current = HashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>()
    private val pending = HashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>()

    init {
        nodes.forEachIndexed { i, node ->
            current[node] = linkedMapOf(
                "eno1" to linkedMapOf("type" to "eth", "autostart" to "1"),
                "eno2" to linkedMapOf("type" to "eth", "autostart" to "1"),
                "vmbr0" to linkedMapOf(
                    "type" to "bridge", "autostart" to "1", "cidr" to "10.0.0.${11 + i}/24", "gateway" to "10.0.0.1",
                    "bridge_ports" to "eno1", "bridge_vlan_aware" to "1", "comments" to "LAN",
                ),
                "vmbr1" to linkedMapOf("type" to "bridge", "autostart" to "1", "bridge_ports" to "eno2", "comments" to "Storage and lab"),
            )
        }
    }

    private fun view(node: String) = pending[node] ?: current[node]

    private fun editable(node: String): LinkedHashMap<String, LinkedHashMap<String, String>>? {
        val base = current[node] ?: return null
        return pending.getOrPut(node) { LinkedHashMap(base.mapValues { LinkedHashMap(it.value) }) }
    }

    fun list(node: String, type: String?): List<NodeNetwork>? {
        val ifaces = view(node) ?: return null
        val applied = current.getValue(node)
        return ifaces.map { (name, o) ->
            NodeNetwork(
                iface = name, type = o["type"], active = name in applied && o["autostart"] == "1", comments = o["comments"],
                vlanAware = o["bridge_vlan_aware"] == "1", autostart = o["autostart"] == "1", exists = name in applied,
                cidr = o["cidr"], gateway = o["gateway"], cidr6 = o["cidr6"], gateway6 = o["gateway6"],
                bridgePorts = o["bridge_ports"], slaves = o["slaves"], bondMode = o["bond_mode"], hashPolicy = o["bond_xmit_hash_policy"],
                vlanRawDevice = o["vlan-raw-device"], vlanId = o["vlan-id"]?.toIntOrNull(), mtu = o["mtu"]?.toIntOrNull(),
            )
        }.filter { type == null || (type in setOf("any_bridge", "bridge") && it.isBridge) || it.type == type }
    }

    /** A unified-diff-like summary of what applying would change, or null. */
    fun changes(node: String): String? {
        val next = pending[node] ?: return null
        val now = current.getValue(node)
        if (next == now) return null
        return buildString {
            append("--- /etc/network/interfaces\n+++ /etc/network/interfaces.new\n")
            (now.keys + next.keys).distinct().forEach { name ->
                val before = now[name]
                val after = next[name]
                if (before == after) return@forEach
                before?.let { o -> append(stanza(name, o, "-")) }
                after?.let { o -> append(stanza(name, o, "+")) }
            }
        }
    }

    private fun stanza(name: String, o: Map<String, String>, sign: String) = buildString {
        if (o["autostart"] == "1") append("${sign}auto $name\n")
        append("${sign}iface $name inet ${if (o["cidr"] != null) "static" else "manual"}\n")
        o.filterKeys { it !in setOf("type", "autostart") }.forEach { (k, v) -> append("$sign\t${k.replace('_', '-')} $v\n") }
    }

    fun create(node: String, params: Map<String, String>): String? {
        val ifaces = editable(node) ?: return "no such node"
        val name = params["iface"].orEmpty()
        if (!Regex("^[a-zA-Z][a-zA-Z0-9_.\\-]{0,14}$").matches(name)) return "iface: invalid format - interface name '$name'"
        if (name in ifaces) return "interface '$name' already exists"
        val type = params["type"].orEmpty()
        if (type !in setOf("bridge", "bond", "vlan")) return "type: value '$type' does not have a value in the enumeration"
        validate(ifaces, name, params)?.let { return it }
        ifaces[name] = LinkedHashMap(params.filterKeys { it != "iface" && it != "delete" })
        return null
    }

    fun update(node: String, name: String, params: Map<String, String>): String? {
        val ifaces = editable(node) ?: return "no such node"
        val o = ifaces[name] ?: return "interface '$name' does not exist"
        if (params["type"] != null && params["type"] != o["type"]) return "interface type '${o["type"]}' can't be changed"
        validate(ifaces, name, params)?.let { return it }
        params.filterKeys { it != "delete" }.forEach { (k, v) -> o[k] = v }
        params["delete"].orEmpty().split(',').filter { it.isNotBlank() }.forEach { o.remove(it) }
        return null
    }

    private fun validate(ifaces: Map<String, Map<String, String>>, name: String, p: Map<String, String>): String? {
        p["cidr"]?.let { if (!Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+$").matches(it)) return "cidr: invalid format - value does not look like a valid IPv4 network configuration" }
        (p["bridge_ports"].orEmpty().split(' ') + p["slaves"].orEmpty().split(' ')).filter { it.isNotBlank() }.forEach { port ->
            if (port == name || port !in ifaces) return "interface '$port' does not exist"
        }
        return null
    }

    fun delete(node: String, name: String): String? {
        val ifaces = editable(node) ?: return "no such node"
        ifaces.remove(name) ?: return "interface '$name' does not exist"
        return null
    }

    fun apply(node: String) {
        pending.remove(node)?.let { current[node] = it }
    }

    fun revert(node: String) {
        pending.remove(node)
    }
}
