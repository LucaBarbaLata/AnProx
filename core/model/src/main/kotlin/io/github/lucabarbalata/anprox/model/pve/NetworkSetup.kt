package io.github.lucabarbalata.anprox.model.pve

/** Interfaces the app can create (questionnaire Q22); physical ports can only be edited. */
enum class NetworkKind(val apiName: String) {
    BRIDGE("bridge"),
    BOND("bond"),
    VLAN("vlan"),
    ;

    companion object {
        fun fromApi(type: String?): NetworkKind? = entries.firstOrNull { it.apiName == type }
    }
}

/**
 * Adding or editing an interface (`POST /nodes/{node}/network`, `PUT …/network/{iface}`).
 * Changes go to `/etc/network/interfaces.new` until they are applied.
 */
data class NetworkInterfaceForm(
    /** The API type: `bridge`, `bond`, `vlan`, or `eth` and others when editing. */
    val type: String = NetworkKind.BRIDGE.apiName,
    val name: String = "",
    val cidr: String = "",
    val gateway: String = "",
    val cidr6: String = "",
    val gateway6: String = "",
    val autostart: Boolean = true,
    val comment: String = "",
    val mtu: String = "",
    val bridgePorts: String = "",
    val vlanAware: Boolean = false,
    val slaves: String = "",
    val bondMode: String = BOND_MODES.first(),
    val hashPolicy: String = "",
    val vlanRawDevice: String = "",
    val vlanId: String = "",
) {
    enum class Problem { NAME, NAME_TAKEN, ADDRESS, GATEWAY, ADDRESS6, GATEWAY6, MTU, SLAVES, VLAN }

    val kind: NetworkKind? get() = NetworkKind.fromApi(type)

    /** VLAN names can carry the parent and tag themselves (`eno1.20`). */
    private val vlanFromName: Pair<String, Int>? get() = name.trim().split('.').takeIf { it.size == 2 }?.let { (dev, tag) -> tag.toIntOrNull()?.let { dev to it } }

    fun problem(creating: Boolean, existing: Collection<String>): Problem? = when {
        creating && !NAME_PATTERN.matches(name.trim()) -> Problem.NAME
        creating && name.trim() in existing -> Problem.NAME_TAKEN
        cidr.isNotBlank() && !IpAddress.isIpv4Cidr(cidr.trim()) -> Problem.ADDRESS
        gateway.isNotBlank() && (!IpAddress.isIpv4(gateway.trim()) || cidr.isBlank()) -> Problem.GATEWAY
        cidr6.isNotBlank() && !IpAddress.isIpv6Cidr(cidr6.trim()) -> Problem.ADDRESS6
        gateway6.isNotBlank() && (!IpAddress.isIpv6(gateway6.trim()) || cidr6.isBlank()) -> Problem.GATEWAY6
        mtu.isNotBlank() && (mtu.trim().toIntOrNull() ?: 0) !in 1280..65520 -> Problem.MTU
        kind == NetworkKind.BOND && slaves.isBlank() -> Problem.SLAVES
        kind == NetworkKind.VLAN && vlanFromName == null && (vlanRawDevice.isBlank() || (vlanId.trim().toIntOrNull() ?: 0) !in 1..4094) -> Problem.VLAN
        else -> null
    }

    private fun values(): Map<String, String> = buildMap {
        put("type", type)
        cidr.trim().takeIf { it.isNotEmpty() }?.let { put("cidr", it) }
        gateway.trim().takeIf { it.isNotEmpty() }?.let { put("gateway", it) }
        cidr6.trim().takeIf { it.isNotEmpty() }?.let { put("cidr6", it) }
        gateway6.trim().takeIf { it.isNotEmpty() }?.let { put("gateway6", it) }
        put("autostart", if (autostart) "1" else "0")
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comments", it) }
        mtu.trim().takeIf { it.isNotEmpty() }?.let { put("mtu", it) }
        when (kind) {
            NetworkKind.BRIDGE -> {
                put("bridge_ports", bridgePorts.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.joinToString(" "))
                put("bridge_vlan_aware", if (vlanAware) "1" else "0")
            }
            NetworkKind.BOND -> {
                put("slaves", slaves.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.joinToString(" "))
                put("bond_mode", bondMode)
                if (bondMode in HASH_MODES && hashPolicy.isNotBlank()) put("bond_xmit_hash_policy", hashPolicy)
            }
            NetworkKind.VLAN -> if (vlanFromName == null) {
                put("vlan-raw-device", vlanRawDevice.trim())
                put("vlan-id", vlanId.trim())
            }
            null -> Unit
        }
    }

    fun createParams(): Map<String, String> = values() + ("iface" to name.trim())

    /** Every value, deleting the cleared ones. */
    fun updateParams(original: NodeNetwork): Map<String, String> {
        val set = LinkedHashMap(values())
        val delete = buildList {
            if (cidr.isBlank() && original.cidr != null) add("cidr")
            if (gateway.isBlank() && original.gateway != null) add("gateway")
            if (cidr6.isBlank() && original.cidr6 != null) add("cidr6")
            if (gateway6.isBlank() && original.gateway6 != null) add("gateway6")
            if (comment.isBlank() && original.comments != null) add("comments")
            if (mtu.isBlank() && original.mtu != null) add("mtu")
            if (kind == NetworkKind.BOND && (bondMode !in HASH_MODES || hashPolicy.isBlank()) && original.hashPolicy != null) add("bond_xmit_hash_policy")
        }
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        return set
    }

    companion object {
        private val NAME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_.\\-]{0,14}$")
        val BOND_MODES = listOf("balance-rr", "active-backup", "balance-xor", "broadcast", "802.3ad", "balance-tlb", "balance-alb")
        val HASH_MODES = setOf("balance-xor", "802.3ad")
        val HASH_POLICIES = listOf("layer2", "layer2+3", "layer3+4")

        /** The next free `vmbrN`, `bondN` or `vlanN` name. */
        fun suggestedName(kind: NetworkKind, existing: Collection<String>): String {
            val prefix = when (kind) {
                NetworkKind.BRIDGE -> "vmbr"
                NetworkKind.BOND -> "bond"
                NetworkKind.VLAN -> "vlan"
            }
            return generateSequence(0) { it + 1 }.map { "$prefix$it" }.first { it !in existing }
        }

        fun from(n: NodeNetwork) = NetworkInterfaceForm(
            type = n.type ?: "eth",
            name = n.iface,
            cidr = n.cidr.orEmpty(),
            gateway = n.gateway.orEmpty(),
            cidr6 = n.cidr6.orEmpty(),
            gateway6 = n.gateway6.orEmpty(),
            autostart = n.autostart,
            comment = n.comments.orEmpty(),
            mtu = n.mtu?.toString().orEmpty(),
            bridgePorts = n.bridgePorts.orEmpty(),
            vlanAware = n.vlanAware,
            slaves = n.slaves.orEmpty(),
            bondMode = n.bondMode ?: BOND_MODES.first(),
            hashPolicy = n.hashPolicy.orEmpty(),
            vlanRawDevice = n.vlanRawDevice.orEmpty(),
            vlanId = n.vlanId?.toString().orEmpty(),
        )
    }
}

/** The interfaces as they will be after applying, and the pending diff (null when nothing is pending). */
data class NetworkConfig(val interfaces: List<NodeNetwork>, val changes: String?) {
    val pending: Boolean get() = !changes.isNullOrBlank()
}
