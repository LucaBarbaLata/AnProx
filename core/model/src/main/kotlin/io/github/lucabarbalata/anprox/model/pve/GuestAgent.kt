package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** QEMU guest agent replies are wrapped: `{"data": {"result": …}}`. */
@Serializable
data class AgentResult<T>(val result: T? = null)

/** One entry of `…/qemu/{vmid}/agent/network-get-interfaces`. */
@Serializable
data class AgentInterface(
    val name: String,
    @SerialName("hardware-address") val mac: String? = null,
    @SerialName("ip-addresses") val addresses: List<AgentIpAddress> = emptyList(),
)

@Serializable
data class AgentIpAddress(
    @SerialName("ip-address") val address: String,
    /** `ipv4` or `ipv6`. */
    @SerialName("ip-address-type") val type: String = "",
    val prefix: Int? = null,
)

/** `…/qemu/{vmid}/agent/get-osinfo`. Windows and Linux fill different fields. */
@Serializable
data class AgentOsInfo(
    val name: String? = null,
    @SerialName("pretty-name") val prettyName: String? = null,
    val version: String? = null,
    @SerialName("kernel-release") val kernelRelease: String? = null,
    val id: String? = null,
) {
    val displayName: String? get() = prettyName ?: listOfNotNull(name, version).joinToString(" ").ifBlank { null }
}

/** One entry of `GET /nodes/{node}/lxc/{vmid}/interfaces` (PVE 7.2+). */
@Serializable
data class LxcInterface(
    val name: String,
    val hwaddr: String? = null,
    /** `10.0.0.5/24`. */
    val inet: String? = null,
    val inet6: String? = null,
)

/** A guest's network interface with display-ready addresses. */
data class GuestNic(val name: String, val mac: String?, val ipv4: List<String>, val ipv6: List<String>) {
    val isLoopback: Boolean get() = name == "lo" || name.startsWith("Loopback")

    companion object {
        /** Drops loopback and link-local noise; keeps prefixes off (people copy the bare address). */
        fun fromAgent(interfaces: List<AgentInterface>): List<GuestNic> = interfaces.map { nic ->
            GuestNic(
                name = nic.name,
                mac = nic.mac?.takeUnless { it == "00:00:00:00:00:00" },
                ipv4 = nic.addresses.filter { it.type == "ipv4" }.map { it.address }.filterNot { it.startsWith("127.") || it.startsWith("169.254.") },
                ipv6 = nic.addresses.filter { it.type == "ipv6" }.map { it.address }.filterNot { isNoiseV6(it) },
            )
        }.filterNot { it.isLoopback || (it.ipv4.isEmpty() && it.ipv6.isEmpty()) }

        fun fromLxc(interfaces: List<LxcInterface>): List<GuestNic> = interfaces.map { nic ->
            GuestNic(
                name = nic.name,
                mac = nic.hwaddr,
                ipv4 = nic.inet?.split(' ', ',')?.map { it.substringBefore('/') }?.filter { it.isNotBlank() && !it.startsWith("127.") }.orEmpty(),
                ipv6 = nic.inet6?.split(' ', ',')?.map { it.substringBefore('/') }?.filter { it.isNotBlank() && !isNoiseV6(it) }.orEmpty(),
            )
        }.filterNot { it.isLoopback || (it.ipv4.isEmpty() && it.ipv6.isEmpty()) }

        private fun isNoiseV6(address: String) = address == "::1" || address.lowercase().startsWith("fe80:")
    }
}

/** `fsfreeze-status`: whether the guest has paused writes to its filesystems. */
enum class FsFreezeStatus {
    THAWED,
    FROZEN,
    UNKNOWN,
    ;

    companion object {
        fun fromApi(value: String?): FsFreezeStatus = when (value?.lowercase()) {
            "thawed" -> THAWED
            "frozen" -> FROZEN
            else -> UNKNOWN
        }
    }
}

/** One filesystem in the reply of `agent/fstrim`. Linux reports bytes trimmed; Windows only errors. */
@Serializable
data class AgentTrimPath(val path: String, val trimmed: Long? = null, val minimum: Long? = null, val error: String? = null)

@Serializable
data class AgentTrimResult(val paths: List<AgentTrimPath> = emptyList())

/** Checks for `agent/set-user-password`, matching Proxmox (5 to 1024 characters). */
object AgentPassword {
    const val MIN = 5
    const val MAX = 1024

    enum class Problem { NO_USER, TOO_SHORT, TOO_LONG, MISMATCH }

    fun validate(username: String, password: String, repeat: String): Problem? = when {
        username.isBlank() -> Problem.NO_USER
        password.length < MIN -> Problem.TOO_SHORT
        password.length > MAX -> Problem.TOO_LONG
        password != repeat -> Problem.MISMATCH
        else -> null
    }
}
