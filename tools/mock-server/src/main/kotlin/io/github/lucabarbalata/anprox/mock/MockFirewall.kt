package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.FirewallAlias
import io.github.lucabarbalata.anprox.model.pve.FirewallIpSet
import io.github.lucabarbalata.anprox.model.pve.FirewallMacro
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.IpSetEntry
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine

/**
 * Firewall rules and options per scope (`cluster/firewall`, `nodes/pve1/firewall`,
 * `nodes/pve1/qemu/100/firewall`), kept as Proxmox's key/value pairs. Rules are applied top
 * down, so a new rule goes first unless a position is given. Security groups keep their rules
 * under `cluster/firewall/groups/{group}`; aliases and IP sets are per scope.
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
    private val groupComments = linkedMapOf<String, String?>("webserver" to "HTTP and HTTPS from anywhere")
    private val aliases = HashMap<String, LinkedHashMap<String, Pair<String, String?>>>()
    private class IpSet(var comment: String?, val entries: LinkedHashMap<String, Pair<String?, Boolean>> = LinkedHashMap())
    private val ipsets = HashMap<String, LinkedHashMap<String, IpSet>>()

    fun groups(): List<SecurityGroup> = groupComments.map { (g, c) -> SecurityGroup(g, c) }

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
        rules[group("webserver")] = mutableListOf(
            linkedMapOf("type" to "in", "action" to "ACCEPT", "macro" to "HTTP", "enable" to "1"),
            linkedMapOf("type" to "in", "action" to "ACCEPT", "macro" to "HTTPS", "enable" to "1"),
        )
        aliases["cluster/firewall"] = linkedMapOf("admin-net" to ("10.0.0.0/24" to "Admin workstations"), "nas" to ("10.0.0.50" to null))
        ipsets["cluster/firewall"] = linkedMapOf(
            "blocklist" to IpSet("Abusive networks", linkedMapOf("203.0.113.0/24" to ("Scanner" to false), "198.51.100.7" to (null to false))),
        )
        ipsets["nodes/pve1/qemu/100/firewall"] = linkedMapOf("ipfilter-net0" to IpSet(null, linkedMapOf("10.0.0.21" to (null to false))))
    }

    private fun group(name: String) = "cluster/firewall/groups/$name"

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
            if (action !in groupComments) return "no such security group '$action'"
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

    // ------------------------------------------------------------------ security groups

    fun createGroup(params: Map<String, String>): String? {
        val name = params["group"].orEmpty()
        if (!NAME.matches(name)) return "group: invalid format - security group name '$name'"
        if (name in groupComments) return "security group '$name' already exists"
        groupComments[name] = params["comment"]?.takeIf { it.isNotBlank() }
        rules[group(name)] = mutableListOf()
        version++
        return null
    }

    fun deleteGroup(name: String): String? {
        if (name !in groupComments) return "no such security group '$name'"
        if (rules[group(name)].orEmpty().isNotEmpty()) return "Security group '$name' is not empty"
        groupComments.remove(name)
        rules.remove(group(name))
        version++
        return null
    }

    /** Whether [scope] is a firewall or group the mock knows (a group's rule routes need it). */
    fun groupExists(name: String) = name in groupComments

    // ------------------------------------------------------------------ aliases

    fun aliases(scope: String): List<FirewallAlias> = aliases[scope].orEmpty().map { (n, v) -> FirewallAlias(n, v.first, v.second, digest) }

    fun createAlias(scope: String, params: Map<String, String>): String? {
        val name = params["name"].orEmpty()
        if (!NAME.matches(name)) return "name: invalid format - alias name '$name'"
        val map = aliases.getOrPut(scope) { linkedMapOf() }
        if (map.keys.any { it.equals(name, ignoreCase = true) }) return "alias '$name' already exists"
        val cidr = params["cidr"].orEmpty()
        if (!ADDRESS.matches(cidr)) return "cidr: value does not look like a valid CIDR network"
        map[name] = cidr to params["comment"]?.takeIf { it.isNotBlank() }
        version++
        return null
    }

    fun updateAlias(scope: String, name: String, params: Map<String, String>): String? {
        val map = aliases[scope] ?: return "no such alias '$name'"
        val current = map[name] ?: return "no such alias '$name'"
        checkDigest(params)?.let { return it }
        val cidr = params["cidr"] ?: current.first
        if (!ADDRESS.matches(cidr)) return "cidr: value does not look like a valid CIDR network"
        val target = params["rename"]?.takeIf { it.isNotBlank() } ?: name
        if (target != name && map.containsKey(target)) return "alias '$target' already exists"
        map.remove(name)
        map[target] = cidr to (params["comment"] ?: current.second)?.takeIf { it.isNotBlank() }
        version++
        return null
    }

    fun deleteAlias(scope: String, name: String, digestParam: String?): String? {
        if (digestParam != null && digestParam != digest) return "detected modified configuration - file changed by other user? Try again."
        aliases[scope]?.remove(name) ?: return "no such alias '$name'"
        version++
        return null
    }

    // ------------------------------------------------------------------ IP sets

    fun ipSets(scope: String): List<FirewallIpSet> = ipsets[scope].orEmpty().map { (n, s) -> FirewallIpSet(n, s.comment, digest) }

    fun ipSetEntries(scope: String, name: String): List<IpSetEntry>? =
        ipsets[scope]?.get(name)?.entries?.map { (cidr, v) -> IpSetEntry(cidr, v.first, v.second, digest) }

    fun createIpSet(scope: String, params: Map<String, String>): String? {
        val name = params["name"].orEmpty()
        if (!NAME.matches(name)) return "name: invalid format - IPSet name '$name'"
        val map = ipsets.getOrPut(scope) { linkedMapOf() }
        if (name in map) return "IPSet '$name' already exists"
        map[name] = IpSet(params["comment"]?.takeIf { it.isNotBlank() })
        version++
        return null
    }

    fun deleteIpSet(scope: String, name: String, force: Boolean): String? {
        val set = ipsets[scope]?.get(name) ?: return "no such IPSet '$name'"
        if (set.entries.isNotEmpty() && !force) return "IPSet '$name' is not empty"
        ipsets[scope]?.remove(name)
        version++
        return null
    }

    fun addIpSetEntry(scope: String, name: String, params: Map<String, String>): String? {
        val set = ipsets[scope]?.get(name) ?: return "no such IPSet '$name'"
        val cidr = params["cidr"].orEmpty()
        if (!ADDRESS.matches(cidr) && !NAME.matches(cidr.removePrefix("dc/").removePrefix("guest/"))) return "cidr: value does not look like a valid CIDR network"
        if (cidr in set.entries) return "entry for '$cidr' already exists"
        set.entries[cidr] = params["comment"]?.takeIf { it.isNotBlank() } to (params["nomatch"] == "1")
        version++
        return null
    }

    fun removeIpSetEntry(scope: String, name: String, cidr: String, digestParam: String?): String? {
        if (digestParam != null && digestParam != digest) return "detected modified configuration - file changed by other user? Try again."
        val set = ipsets[scope]?.get(name) ?: return "no such IPSet '$name'"
        set.entries.remove(cidr) ?: return "no such IP/Network '$cidr'"
        version++
        return null
    }

    // ------------------------------------------------------------------ log

    /** A few hundred logged packets, the newest last; [start]/[limit] page it like Proxmox. */
    fun log(scope: String, start: Int, limit: Int): Pair<List<TaskLogLine>, Int> {
        val vmid = Regex("/(\\d+)/firewall$").find(scope)?.groupValues?.get(1) ?: "0"
        val all = (0 until 640).map { i ->
            val minute = i / 12
            val time = "15/Oct/2026:%02d:%02d:%02d +0200".format(8 + minute / 60, minute % 60, (i * 7) % 60)
            when {
                i % 9 == 0 -> "$vmid 6 - $time policy DROP: IN=vmbr0 SRC=203.0.113.${i % 250} DST=10.0.0.11 PROTO=TCP SPT=${40000 + i} DPT=22"
                i % 5 == 0 -> "$vmid 6 - $time policy REJECT: IN=vmbr0 SRC=198.51.100.7 DST=10.0.0.11 PROTO=UDP SPT=5353 DPT=161"
                else -> "$vmid 6 - $time ACCEPT: IN=vmbr0 SRC=10.0.0.${20 + i % 30} DST=10.0.0.11 PROTO=TCP SPT=${50000 + i} DPT=8006"
            }
        }
        val page = all.drop(start).take(limit).mapIndexed { i, t -> TaskLogLine(start + i + 1, t) }
        return page to all.size
    }

    private companion object {
        val NAME = Regex("^[A-Za-z][A-Za-z0-9\\-_]+$")
        val ADDRESS = Regex("^[0-9a-fA-F:.]+(/\\d{1,3})?$")
    }
}
