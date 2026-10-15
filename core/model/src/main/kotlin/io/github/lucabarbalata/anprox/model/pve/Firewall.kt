package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where a firewall's rules and options live (questionnaire Q24). */
sealed interface FirewallScope {
    /** API path of the firewall, e.g. `nodes/pve1/qemu/100/firewall`. */
    val path: String

    /** Where its rules are listed; rule `n` is `rulesPath/n`. */
    val rulesPath: String get() = "$path/rules"

    /** Firewalls with options (a security group has none). */
    val hasOptions: Boolean get() = this !is Group

    /** The datacenter and guests have their own aliases and IP sets. */
    val hasAliases: Boolean get() = this is Datacenter || this is Guest

    /** Nodes and guests keep a log of what the rules logged. */
    val hasLog: Boolean get() = this is Node || this is Guest

    data object Datacenter : FirewallScope {
        override val path: String get() = "cluster/firewall"
    }

    data class Node(val node: String) : FirewallScope {
        override val path: String get() = "nodes/$node/firewall"
    }

    data class Guest(val node: String, val type: GuestType, val vmid: Int) : FirewallScope {
        override val path: String get() = "nodes/$node/${type.apiName}/$vmid/firewall"
    }

    /** A security group: rules only, inserted into other firewalls with a `group` rule. */
    data class Group(val group: String) : FirewallScope {
        override val path: String get() = "cluster/firewall/groups/$group"
        override val rulesPath: String get() = path
    }
}

/** One rule of `GET …/firewall/rules`, in the order Proxmox applies them ([pos]). */
@Serializable
data class FirewallRule(
    val pos: Int,
    /** `in`, `out`, `group` (insert a security group) or `forward`. */
    val type: String = "in",
    /** `ACCEPT`, `DROP`, `REJECT`, or the security group's name for `group`. */
    val action: String = "",
    @Serializable(with = PveBooleanSerializer::class)
    val enable: Boolean = false,
    val macro: String? = null,
    val proto: String? = null,
    val source: String? = null,
    val dest: String? = null,
    val dport: String? = null,
    val sport: String? = null,
    val iface: String? = null,
    val log: String? = null,
    @SerialName("icmp-type") val icmpType: String? = null,
    val comment: String? = null,
    val digest: String? = null,
)

/** One row of `GET /cluster/firewall/macros`: a named set of ports (SSH, HTTP, …). */
@Serializable
data class FirewallMacro(val macro: String, val descr: String? = null)

/** One row of `GET /cluster/firewall/groups`. */
@Serializable
data class SecurityGroup(val group: String, val comment: String? = null)

/** The rule editor. [createParams] and [updateParams] build the calls; [problem] explains a refusal. */
data class FirewallRuleForm(
    val type: String = "in",
    val action: String = "ACCEPT",
    val enabled: Boolean = true,
    val macro: String = "",
    val proto: String = "",
    val source: String = "",
    val dest: String = "",
    val dport: String = "",
    val sport: String = "",
    val iface: String = "",
    val log: String = "nolog",
    val comment: String = "",
) {
    enum class Problem { GROUP, PORTS_NEED_PROTOCOL, PORT, ADDRESS }

    val isGroup: Boolean get() = type == "group"

    fun problem(): Problem? = when {
        isGroup && action.isBlank() -> Problem.GROUP
        !isGroup && (dport.isNotBlank() || sport.isNotBlank()) && macro.isBlank() && proto !in PORT_PROTOCOLS -> Problem.PORTS_NEED_PROTOCOL
        listOf(dport, sport).any { it.isNotBlank() && !PORTS.matches(it.trim()) } -> Problem.PORT
        listOf(source, dest).any { ' ' in it.trim() } -> Problem.ADDRESS
        else -> null
    }

    private fun values(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["type"] = type
        m["action"] = action
        m["enable"] = if (enabled) "1" else "0"
        if (!isGroup) {
            if (macro.isNotBlank()) m["macro"] = macro
            if (proto.isNotBlank()) m["proto"] = proto
            if (source.isNotBlank()) m["source"] = source.trim()
            if (dest.isNotBlank()) m["dest"] = dest.trim()
            if (dport.isNotBlank()) m["dport"] = dport.trim()
            if (sport.isNotBlank()) m["sport"] = sport.trim()
            if (log != "nolog") m["log"] = log
        }
        if (iface.isNotBlank()) m["iface"] = iface.trim()
        if (comment.isNotBlank()) m["comment"] = comment.trim()
        return m
    }

    fun createParams(): Map<String, String> = values()

    /** Everything the form shows, with `delete` for what was cleared and the rule's digest. */
    fun updateParams(original: FirewallRule): Map<String, String> {
        val set = values()
        val delete = OPTIONAL.filter { it !in set && original.has(it) }
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        original.digest?.let { set["digest"] = it }
        return set
    }

    private fun FirewallRule.has(key: String): Boolean = when (key) {
        "macro" -> !macro.isNullOrBlank()
        "proto" -> !proto.isNullOrBlank()
        "source" -> !source.isNullOrBlank()
        "dest" -> !dest.isNullOrBlank()
        "dport" -> !dport.isNullOrBlank()
        "sport" -> !sport.isNullOrBlank()
        "iface" -> !iface.isNullOrBlank()
        "log" -> !log.isNullOrBlank() && log != "nolog"
        "comment" -> !comment.isNullOrBlank()
        "icmp-type" -> !icmpType.isNullOrBlank()
        else -> false
    }

    companion object {
        val ACTIONS = listOf("ACCEPT", "DROP", "REJECT")
        val PROTOCOLS = listOf("tcp", "udp", "icmp", "ipv6-icmp")
        val LOG_LEVELS = listOf("nolog", "emerg", "alert", "crit", "err", "warning", "notice", "info", "debug")
        private val PORT_PROTOCOLS = setOf("tcp", "udp", "udplite", "dccp", "sctp")
        private val OPTIONAL = listOf("macro", "proto", "source", "dest", "dport", "sport", "iface", "log", "comment", "icmp-type")

        /** Ports or service names, lists with `,` and ranges with `:` (`22`, `80,443`, `6000:6010`, `ssh`). */
        private val PORTS = Regex("[A-Za-z0-9-]+(:[A-Za-z0-9-]+)?(,[A-Za-z0-9-]+(:[A-Za-z0-9-]+)?)*")

        fun from(rule: FirewallRule) = FirewallRuleForm(
            type = rule.type,
            action = rule.action,
            enabled = rule.enable,
            macro = rule.macro.orEmpty(),
            proto = rule.proto.orEmpty(),
            source = rule.source.orEmpty(),
            dest = rule.dest.orEmpty(),
            dport = rule.dport.orEmpty(),
            sport = rule.sport.orEmpty(),
            iface = rule.iface.orEmpty(),
            log = rule.log ?: "nolog",
            comment = rule.comment.orEmpty(),
        )
    }
}

/**
 * A firewall's options (`GET …/firewall/options`). Which ones exist depends on the scope:
 * policies on the datacenter and guests, log levels on nodes and guests, address filters on
 * guests. Kept as Proxmox's key/value pairs so unknown options survive an edit.
 */
data class FirewallOptions(val values: Map<String, String>, val digest: String?) {
    fun flag(key: String, default: Boolean = false): Boolean = values[key]?.isOn() ?: default
    operator fun get(key: String): String? = values[key]

    /** The PUT for [edited]: changed values, and `delete` for options set back to their default (empty). */
    fun changes(edited: Map<String, String>): Map<String, String> {
        val set = LinkedHashMap<String, String>()
        val delete = ArrayList<String>()
        edited.forEach { (k, v) ->
            when {
                v == values[k] -> Unit
                v.isEmpty() -> if (values.containsKey(k)) delete += k
                else -> set[k] = v
            }
        }
        if (set.isEmpty() && delete.isEmpty()) return emptyMap()
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        digest?.let { set["digest"] = it }
        return set
    }

    companion object {
        fun fromJson(o: JsonObject): FirewallOptions {
            val values = o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            return FirewallOptions(values - "digest", values["digest"])
        }

        /** Options shown for each scope, besides `enable`. */
        val GUEST_FLAGS = listOf("dhcp", "ndp", "radv", "macfilter", "ipfilter")
        val POLICIES = listOf("ACCEPT", "DROP", "REJECT")
    }
}
