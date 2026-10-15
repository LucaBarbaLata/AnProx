package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.FirewallMacro
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup

/**
 * Firewall rules and options per scope (`cluster/firewall`, `nodes/pve1/firewall`,
 * `nodes/pve1/qemu/100/firewall`), kept as Proxmox's key/value pairs. Rules are applied top
 * down, so a new rule goes first unless a position is given.
 */
internal class MockFirewall {
    private val rules = HashMap<String, MutableList<LinkedHashMap<String, String>>>()
    private val options = HashMap<String, LinkedHashMap<String, String>>()
    private var version = 1

    val macros = listOf(
        FirewallMacro("DNS", "Domain Name System traffic (UDP and TCP)"),
        FirewallMacro("HTTP", "Hypertext Transfer Protocol (WWW)"),
        FirewallMacro("HTTPS", "Hypertext Transfer Protocol (WWW) over SSL"),
        FirewallMacro("Ping", "ICMP echo request"),
        FirewallMacro("SMB", "Microsoft SMB traffic"),
        FirewallMacro("SSH", "Secure shell traffic"),
    )
    val groups = listOf(SecurityGroup("webserver", "HTTP and HTTPS from anywhere"))

    init {
        options["cluster/firewall"] = linkedMapOf("enable" to "0", "policy_in" to "DROP", "policy_out" to "ACCEPT")
        rules["cluster/firewall"] = mutableListOf(
            linkedMapOf("type" to "in", "action" to "ACCEPT", "macro" to "SSH", "source" to "10.0.0.0/24", "enable" to "1", "comment" to "Admin network"),
            linkedMapOf("type" to "in", "action" to "ACCEPT", "proto" to "tcp", "dport" to "8006", "source" to "10.0.0.0/24", "enable" to "1", "comment" to "Web interface"),
        )
        options["nodes/pve1/qemu/100/firewall"] = linkedMapOf("enable" to "1", "policy_in" to "DROP", "dhcp" to "1", "macfilter" to "1")
        rules["nodes/pve1/qemu/100/firewall"] = mutableListOf(
            linkedMapOf("type" to "group", "action" to "webserver", "enable" to "1"),
            linkedMapOf("type" to "in", "action" to "ACCEPT", "macro" to "SSH", "source" to "10.0.0.0/24", "enable" to "1"),
        )
    }

    private val digest get() = "fw%04d".format(version)

    fun rules(scope: String): List<FirewallRule> = rules[scope].orEmpty().mapIndexed { pos, r ->
        FirewallRule(
            pos = pos, type = r["type"] ?: "in", action = r["action"].orEmpty(), enable = r["enable"] == "1",
            macro = r["macro"], proto = r["proto"], source = r["source"], dest = r["dest"], dport = r["dport"],
            sport = r["sport"], iface = r["iface"], log = r["log"], comment = r["comment"], digest = digest,
        )
    }

    private fun validate(r: Map<String, String>): String? {
        val type = r["type"]
        if (type !in listOf("in", "out", "group", "forward")) return "type: value '$type' does not have a value in the enumeration 'in, out, group, forward'"
        val action = r["action"].orEmpty()
        if (type == "group") {
            if (groups.none { it.group == action }) return "no such security group '$action'"
        } else if (action !in listOf("ACCEPT", "DROP", "REJECT")) {
            return "action: value '$action' does not have a value in the enumeration 'ACCEPT, DROP, REJECT'"
        }
        val ports = !r["dport"].isNullOrEmpty() || !r["sport"].isNullOrEmpty()
        if (ports && r["macro"].isNullOrEmpty() && r["proto"] !in listOf("tcp", "udp", "udplite", "dccp", "sctp")) {
            return "you need to specify a protocol (tcp, udp, ...) to use port numbers"
        }
        r["macro"]?.let { m -> if (macros.none { it.macro == m }) return "unknown macro '$m'" }
        return null
    }

    private fun checkDigest(params: Map<String, String>): String? =
        params["digest"]?.takeIf { it != digest }?.let { "detected modified configuration - file changed by other user? Try again." }

    fun create(scope: String, params: Map<String, String>): String? {
        val r = LinkedHashMap(params.filterKeys { it != "pos" && it != "digest" })
        r.putIfAbsent("enable", "0")
        validate(r)?.let { return it }
        val list = rules.getOrPut(scope) { mutableListOf() }
        val pos = params["pos"]?.toIntOrNull()?.coerceIn(0, list.size) ?: 0
        list.add(pos, r)
        version++
        return null
    }

    fun update(scope: String, pos: Int, params: Map<String, String>): String? {
        val list = rules[scope] ?: return "no rule at position $pos"
        val current = list.getOrNull(pos) ?: return "no rule at position $pos"
        checkDigest(params)?.let { return it }
        val moveTo = params["moveto"]?.toIntOrNull()
        if (moveTo != null) {
            list.removeAt(pos)
            list.add(moveTo.coerceIn(0, list.size), current)
            version++
            return null
        }
        val next = LinkedHashMap(current)
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> next[k] = v }
        params["delete"].orEmpty().split(',').filter { it.isNotBlank() }.forEach { next.remove(it) }
        validate(next)?.let { return it }
        list[pos] = next
        version++
        return null
    }

    fun delete(scope: String, pos: Int, digestParam: String?): String? {
        val list = rules[scope]
        if (list == null || pos !in list.indices) return "no rule at position $pos"
        if (digestParam != null && digestParam != digest) return "detected modified configuration - file changed by other user? Try again."
        list.removeAt(pos)
        version++
        return null
    }

    fun options(scope: String): Map<String, String> = options[scope].orEmpty() + ("digest" to digest)

    fun updateOptions(scope: String, params: Map<String, String>): String? {
        checkDigest(params)?.let { return it }
        val o = options.getOrPut(scope) { linkedMapOf() }
        params["policy_in"]?.let { if (it !in listOf("ACCEPT", "DROP", "REJECT")) return "policy_in: value '$it' does not have a value in the enumeration" }
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> o[k] = v }
        params["delete"].orEmpty().split(',').filter { it.isNotBlank() }.forEach { o.remove(it) }
        version++
        return null
    }
}
