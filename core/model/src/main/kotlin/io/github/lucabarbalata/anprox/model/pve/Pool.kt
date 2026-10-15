package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One member of a resource pool: a guest, or a storage on one node. */
@Serializable
data class PoolMember(
    /** `qemu/100`, `lxc/201` or `storage/pve1/local`. */
    val id: String,
    val type: String = "",
    val vmid: Int? = null,
    val node: String? = null,
    val name: String? = null,
    val storage: String? = null,
    val status: String? = null,
) {
    val isGuest: Boolean get() = type == "qemu" || type == "lxc"
    val isStorage: Boolean get() = type == "storage"
}

/** A pool with its members: `GET /pools?poolid=…` (PVE 8.1+) or `GET /pools/{poolid}`. */
@Serializable
data class PoolDetail(
    @SerialName("poolid") val id: String = "",
    val comment: String? = null,
    val members: List<PoolMember> = emptyList(),
) {
    val guests: List<PoolMember> get() = members.filter { it.isGuest }.sortedBy { it.vmid }

    /** Storage names; Proxmox lists a storage once per node. */
    val storages: List<String> get() = members.filter { it.isStorage }.mapNotNull { it.storage }.distinct().sorted()

    val isEmpty: Boolean get() = members.isEmpty()
}

/** Adding members to a pool or, with [remove], taking them out (`PUT /pools`). */
data class PoolChange(
    val vmids: Set<Int> = emptySet(),
    val storages: Set<String> = emptySet(),
    val remove: Boolean = false,
) {
    val isEmpty: Boolean get() = vmids.isEmpty() && storages.isEmpty()

    fun params(): Map<String, String> = buildMap {
        if (vmids.isNotEmpty()) put("vms", vmids.sorted().joinToString(","))
        if (storages.isNotEmpty()) put("storage", storages.sorted().joinToString(","))
        if (remove) put("delete", "1")
    }
}

object PoolIds {
    private val VALID = Regex("^[A-Za-z0-9.\\-_]+$")

    /** What Proxmox accepts as a (top-level) pool name. */
    fun isValid(id: String): Boolean = VALID.matches(id)
}
