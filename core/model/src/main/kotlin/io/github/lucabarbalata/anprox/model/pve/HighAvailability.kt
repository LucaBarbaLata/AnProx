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
)

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
