package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the HA manager should do with a resource (questionnaire Q21). */
enum class HaState(val apiName: String) {
    /** Keep it running, restart or move it on failure. */
    STARTED("started"),

    /** Keep it stopped (it is still watched). */
    STOPPED("stopped"),

    /** Stop it and stop managing it until set to started again. */
    DISABLED("disabled"),

    /** Leave it alone: neither started, stopped nor moved. */
    IGNORED("ignored"),
    ;

    companion object {
        fun fromApi(name: String?): HaState? = entries.firstOrNull { it.apiName == name }
    }
}

/** One row of `GET /cluster/ha/resources`. */
@Serializable
data class HaResource(
    /** `vm:100` or `ct:200`. */
    val sid: String,
    val type: String = "",
    val state: String? = null,
    val group: String? = null,
    @SerialName("max_restart") val maxRestart: Int? = null,
    @SerialName("max_relocate") val maxRelocate: Int? = null,
    val comment: String? = null,
    val digest: String? = null,
) {
    val vmid: Int? get() = sid.substringAfter(':').toIntOrNull()
    val haState: HaState get() = HaState.fromApi(state) ?: HaState.STARTED

    companion object {
        fun sid(type: GuestType, vmid: Int) = "${if (type == GuestType.QEMU) "vm" else "ct"}:$vmid"
    }
}

/** One row of `GET /cluster/ha/groups` (Proxmox VE 8; version 9 uses rules instead). */
@Serializable
data class HaGroup(
    val group: String,
    /** `pve1:2,pve2` (node with optional priority). */
    val nodes: String? = null,
    val comment: String? = null,
    /** Guests may only run on the group's nodes. */
    @Serializable(with = io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer::class)
    val restricted: Boolean = false,
    /** Don't move guests back when a higher-priority node returns. */
    @Serializable(with = io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer::class)
    val nofailback: Boolean = false,
    val digest: String? = null,
)

/** Nodes with optional priorities, as Proxmox writes them: `pve1:2,pve2`. Higher runs first. */
object NodePriorities {
    fun parse(text: String?): Map<String, Int?> = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .associate { entry -> entry.substringBefore(':') to entry.substringAfter(':', "").toIntOrNull() }

    fun format(nodes: Map<String, Int?>): String = nodes.entries.sortedBy { it.key }.joinToString(",") { (n, p) -> if (p != null) "$n:$p" else n }
}

/** Adding or editing an HA group (Proxmox VE 8). */
data class HaGroupForm(
    val name: String = "",
    val nodes: Map<String, Int?> = emptyMap(),
    val restricted: Boolean = false,
    val nofailback: Boolean = false,
    val comment: String = "",
) {
    val valid: Boolean get() = NAME.matches(name.trim()) && nodes.isNotEmpty() && nodes.values.all { it == null || it in 0..1000 }

    fun createParams(): Map<String, String> = buildMap {
        put("group", name.trim())
        put("type", "group")
        putAll(values())
    }

    fun updateParams(original: HaGroup): Map<String, String> = buildMap {
        putAll(values())
        if (comment.isBlank() && !original.comment.isNullOrBlank()) put("delete", "comment")
        original.digest?.let { put("digest", it) }
    }

    private fun values(): Map<String, String> = buildMap {
        put("nodes", NodePriorities.format(nodes))
        put("restricted", if (restricted) "1" else "0")
        put("nofailback", if (nofailback) "1" else "0")
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
    }

    companion object {
        private val NAME = Regex("^[A-Za-z][A-Za-z0-9\\-_.]*[A-Za-z0-9]$")

        fun from(g: HaGroup) = HaGroupForm(g.group, NodePriorities.parse(g.nodes), g.restricted, g.nofailback, g.comment.orEmpty())
    }
}

/**
 * One row of `GET /cluster/ha/rules` (Proxmox VE 9): a `node-affinity` rule keeps guests on some
 * nodes (like a group did), a `resource-affinity` rule keeps guests together or apart.
 */
@Serializable
data class HaRule(
    val rule: String,
    val type: String = "node-affinity",
    /** `vm:100,ct:101`. */
    val resources: String? = null,
    /** Node affinity: `pve1:2,pve2`. */
    val nodes: String? = null,
    @Serializable(with = io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer::class)
    val strict: Boolean = false,
    /** Resource affinity: `positive` (together) or `negative` (apart). */
    val affinity: String? = null,
    val comment: String? = null,
    @Serializable(with = io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer::class)
    val disable: Boolean = false,
    val digest: String? = null,
) {
    val resourceList: List<String> get() = resources.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Adding or editing an HA rule (Proxmox VE 9). */
data class HaRuleForm(
    val id: String = "",
    val type: String = NODE_AFFINITY,
    val resources: Set<String> = emptySet(),
    val nodes: Map<String, Int?> = emptyMap(),
    val strict: Boolean = false,
    val together: Boolean = true,
    val comment: String = "",
    val enabled: Boolean = true,
) {
    val valid: Boolean get() = NAME.matches(id.trim()) && resources.isNotEmpty() &&
        (if (type == NODE_AFFINITY) nodes.isNotEmpty() else resources.size >= 2)

    fun createParams(): Map<String, String> = buildMap {
        put("rule", id.trim())
        put("type", type)
        putAll(values())
    }

    fun updateParams(original: HaRule): Map<String, String> = buildMap {
        put("type", type)
        putAll(values())
        if (comment.isBlank() && !original.comment.isNullOrBlank()) put("delete", "comment")
        original.digest?.let { put("digest", it) }
    }

    private fun values(): Map<String, String> = buildMap {
        put("resources", resources.sorted().joinToString(","))
        if (type == NODE_AFFINITY) {
            put("nodes", NodePriorities.format(nodes))
            put("strict", if (strict) "1" else "0")
        } else {
            put("affinity", if (together) "positive" else "negative")
        }
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        put("disable", if (enabled) "0" else "1")
    }

    companion object {
        const val NODE_AFFINITY = "node-affinity"
        const val RESOURCE_AFFINITY = "resource-affinity"
        private val NAME = Regex("^[A-Za-z][A-Za-z0-9\\-_.]*[A-Za-z0-9]$")

        fun from(r: HaRule) = HaRuleForm(
            id = r.rule,
            type = r.type,
            resources = r.resourceList.toSet(),
            nodes = NodePriorities.parse(r.nodes),
            strict = r.strict,
            together = r.affinity != "negative",
            comment = r.comment.orEmpty(),
            enabled = !r.disable,
        )
    }
}

/** One row of `GET /cluster/ha/status/current`: quorum, the master, each node's LRM, each service. */
@Serializable
data class HaStatusEntry(
    val id: String,
    val type: String = "",
    val status: String? = null,
    val node: String? = null,
    val sid: String? = null,
    val state: String? = null,
    @SerialName("crm_state") val crmState: String? = null,
    @SerialName("request_state") val requestState: String? = null,
)

/** Adding a guest to HA or changing its settings. */
data class HaResourceForm(
    val state: HaState = HaState.STARTED,
    val group: String = "",
    val maxRestart: Int = 1,
    val maxRelocate: Int = 1,
    val comment: String = "",
) {
    val valid: Boolean get() = maxRestart in 0..10 && maxRelocate in 0..10

    fun createParams(sid: String): Map<String, String> = buildMap {
        put("sid", sid)
        putAll(values())
    }

    fun updateParams(original: HaResource): Map<String, String> = buildMap {
        putAll(values())
        val delete = buildList {
            if (group.isBlank() && !original.group.isNullOrBlank()) add("group")
            if (comment.isBlank() && !original.comment.isNullOrBlank()) add("comment")
        }
        if (delete.isNotEmpty()) put("delete", delete.joinToString(","))
        original.digest?.let { put("digest", it) }
    }

    private fun values(): Map<String, String> = buildMap {
        put("state", state.apiName)
        put("max_restart", maxRestart.toString())
        put("max_relocate", maxRelocate.toString())
        if (group.isNotBlank()) put("group", group)
        if (comment.isNotBlank()) put("comment", comment.trim())
    }

    companion object {
        fun from(r: HaResource) = HaResourceForm(
            state = r.haState,
            group = r.group.orEmpty(),
            maxRestart = r.maxRestart ?: 1,
            maxRelocate = r.maxRelocate ?: 1,
            comment = r.comment.orEmpty(),
        )
    }
}
