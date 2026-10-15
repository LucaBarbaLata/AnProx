package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A node network interface (`GET /nodes/{node}/network`); the app uses it to offer bridges. */
@Serializable
data class NodeNetwork(
    val iface: String,
    val type: String? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val active: Boolean = false,
    val comments: String? = null,
    @SerialName("bridge_vlan_aware")
    @Serializable(with = PveBooleanSerializer::class)
    val vlanAware: Boolean = false,
) {
    val isBridge: Boolean get() = type == "bridge" || type == "OVSBridge"
}

/**
 * One key of `GET …/{qemu|lxc}/{vmid}/pending`: the current [value], the [pending] one that takes
 * effect at the next start, and [delete] when the key will be removed.
 */
@Serializable
data class PendingEntry(
    val key: String,
    val value: JsonElement? = null,
    val pending: JsonElement? = null,
    val delete: Int? = null,
) {
    val isPending: Boolean get() = (pending != null && pending != JsonNull) || (delete ?: 0) > 0

    companion object {
        fun keys(entries: List<PendingEntry>): Set<String> = entries.filter { it.isPending }.map { it.key }.toSet()
    }
}
