package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * A node network interface (`GET /nodes/{node}/network`): bridges for guests' network devices,
 * and the Network screen (questionnaire Q22). Values come from `/etc/network/interfaces` (or the
 * pending `interfaces.new`).
 */
@Serializable
data class NodeNetwork(
    val iface: String,
    /** `eth`, `bridge`, `bond`, `vlan`, `alias`, `OVSBridge`, … */
    val type: String? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val active: Boolean = false,
    val comments: String? = null,
    @SerialName("bridge_vlan_aware")
    @Serializable(with = PveBooleanSerializer::class)
    val vlanAware: Boolean = false,
    @Serializable(with = PveBooleanSerializer::class)
    val autostart: Boolean = false,
    /** Whether the device exists on the node (a physical port, or an applied virtual one). */
    @Serializable(with = PveBooleanSerializer::class)
    val exists: Boolean = false,
    val cidr: String? = null,
    val gateway: String? = null,
    val cidr6: String? = null,
    val gateway6: String? = null,
    @SerialName("bridge_ports") val bridgePorts: String? = null,
    val slaves: String? = null,
    @SerialName("bond_mode") val bondMode: String? = null,
    @SerialName("bond_xmit_hash_policy") val hashPolicy: String? = null,
    @SerialName("vlan-raw-device") val vlanRawDevice: String? = null,
    @SerialName("vlan-id") val vlanId: Int? = null,
    val mtu: Int? = null,
) {
    val isBridge: Boolean get() = type == "bridge" || type == "OVSBridge"

    /** Ports, bond members or the parent device, for one line of the list. */
    val members: String? get() = (bridgePorts ?: slaves ?: vlanRawDevice)?.takeIf { it.isNotBlank() }
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
