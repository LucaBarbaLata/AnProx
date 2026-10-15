package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A VM's cloud-init settings (questionnaire Q18): `ciuser`, `cipassword`, `sshkeys`, `ipconfigN`,
 * `nameserver`, `searchdomain` and `ciupgrade`, read from `GET …/qemu/{vmid}/config`.
 *
 * Proxmox never returns the password (it answers `**********` when one is set), and keeps
 * `sshkeys` URL-encoded in the config.
 *
 * @property drive the cloud-init drive's key (`ide2`, `scsi1`…), or null if the VM has none.
 * @property ipconfigs IP settings per network device index (`ipconfig0` belongs to `net0`).
 * @property upgrade null when the server doesn't report `ciupgrade` (before Proxmox VE 8.1).
 */
data class CloudInit(
    val drive: String? = null,
    val user: String = "",
    val passwordSet: Boolean = false,
    val sshKeys: String = "",
    val ipconfigs: Map<Int, CloudInitIp> = emptyMap(),
    val nameserver: String = "",
    val searchDomain: String = "",
    val upgrade: Boolean? = null,
    val digest: String? = null,
) {
    companion object {
        private val IPCONFIG = Regex("^ipconfig(\\d+)$")

        fun fromConfig(config: JsonObject): CloudInit {
            fun string(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull
            val hw = VmHardware.fromConfig(config)
            return CloudInit(
                drive = hw.disks.firstOrNull { it.kind == VmDisk.Kind.CLOUD_INIT }?.key,
                user = string("ciuser").orEmpty(),
                passwordSet = !string("cipassword").isNullOrEmpty(),
                sshKeys = string("sshkeys")?.let(::decodeKeys).orEmpty(),
                ipconfigs = config.keys.mapNotNull { k -> IPCONFIG.matchEntire(k)?.groupValues?.get(1)?.toIntOrNull()?.let { it to CloudInitIp.parse(string(k)) } }.toMap(),
                nameserver = string("nameserver").orEmpty(),
                searchDomain = string("searchdomain").orEmpty(),
                upgrade = string("ciupgrade")?.let { it.isOn() },
                digest = string("digest"),
            )
        }

        /** Proxmox stores `sshkeys` URL-encoded (spaces as `%20`, newlines as `%0A`). */
        fun decodeKeys(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8").trim()

        /** The inverse of [decodeKeys], as the Proxmox web UI sends it (`%20`, not `+`). */
        fun encodeKeys(keys: String): String =
            URLEncoder.encode(keys.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"), "UTF-8").replace("+", "%20")
    }
}

/** One `ipconfigN`: `ip=dhcp` or `ip=10.0.0.5/24,gw=10.0.0.1`, plus the IPv6 counterparts. */
data class CloudInitIp(
    val ipv4: CtNetSettings.Ipv4Mode = CtNetSettings.Ipv4Mode.DHCP,
    val ip: String = "",
    val gateway: String = "",
    val ipv6: CtNetSettings.Ipv6Mode = CtNetSettings.Ipv6Mode.NONE,
    val ip6: String = "",
    val gateway6: String = "",
) {
    enum class Problem { IP, GATEWAY, IP6, GATEWAY6 }

    fun problem(): Problem? = when {
        ipv4 == CtNetSettings.Ipv4Mode.STATIC && !IpAddress.isIpv4Cidr(ip.trim()) -> Problem.IP
        ipv4 == CtNetSettings.Ipv4Mode.STATIC && gateway.isNotBlank() && !IpAddress.isIpv4(gateway.trim()) -> Problem.GATEWAY
        ipv6 == CtNetSettings.Ipv6Mode.STATIC && !IpAddress.isIpv6Cidr(ip6.trim()) -> Problem.IP6
        ipv6 == CtNetSettings.Ipv6Mode.STATIC && gateway6.isNotBlank() && !IpAddress.isIpv6(gateway6.trim()) -> Problem.GATEWAY6
        else -> null
    }

    /** The `ipconfigN` value, or null when there is nothing to configure (Proxmox's default). */
    fun value(): String? = PropertyString.EMPTY.with(
        "ip" to when (ipv4) {
            CtNetSettings.Ipv4Mode.DHCP -> "dhcp"
            CtNetSettings.Ipv4Mode.STATIC -> ip.trim()
            CtNetSettings.Ipv4Mode.NONE -> null
        },
        "gw" to gateway.trim().takeIf { ipv4 == CtNetSettings.Ipv4Mode.STATIC && it.isNotEmpty() },
        "ip6" to when (ipv6) {
            CtNetSettings.Ipv6Mode.NONE -> null
            CtNetSettings.Ipv6Mode.DHCP -> "dhcp"
            CtNetSettings.Ipv6Mode.SLAAC -> "auto"
            CtNetSettings.Ipv6Mode.STATIC -> ip6.trim()
        },
        "gw6" to gateway6.trim().takeIf { ipv6 == CtNetSettings.Ipv6Mode.STATIC && it.isNotEmpty() },
    ).toString().ifEmpty { null }

    companion object {
        /** No `ipconfigN` at all means no IPv4 configuration from cloud-init. */
        fun parse(value: String?): CloudInitIp {
            if (value.isNullOrBlank()) return CloudInitIp(ipv4 = CtNetSettings.Ipv4Mode.NONE)
            val p = PropertyString.parse(value)
            val ip = p["ip"]
            val ip6 = p["ip6"]
            return CloudInitIp(
                ipv4 = when (ip) {
                    null -> CtNetSettings.Ipv4Mode.NONE
                    "dhcp" -> CtNetSettings.Ipv4Mode.DHCP
                    else -> CtNetSettings.Ipv4Mode.STATIC
                },
                ip = ip?.takeUnless { it == "dhcp" }.orEmpty(),
                gateway = p["gw"].orEmpty(),
                ipv6 = when (ip6) {
                    null -> CtNetSettings.Ipv6Mode.NONE
                    "dhcp" -> CtNetSettings.Ipv6Mode.DHCP
                    "auto" -> CtNetSettings.Ipv6Mode.SLAAC
                    else -> CtNetSettings.Ipv6Mode.STATIC
                },
                ip6 = ip6?.takeUnless { it == "dhcp" || it == "auto" }.orEmpty(),
                gateway6 = p["gw6"].orEmpty(),
            )
        }
    }
}

/**
 * The cloud-init form. An empty [password] keeps the current one; [clearPassword] removes it.
 * Only changed keys are sent, with the config digest.
 */
data class CloudInitForm(
    val user: String,
    val password: String = "",
    val clearPassword: Boolean = false,
    val sshKeys: String,
    val ipconfigs: Map<Int, CloudInitIp>,
    val nameserver: String,
    val searchDomain: String,
    val upgrade: Boolean?,
) {
    enum class Problem { USER, SSH_KEY, IP, DNS }

    /** The index of the first network device whose settings are wrong, with [problem] IP. */
    val badIp: Int? get() = ipconfigs.entries.sortedBy { it.key }.firstOrNull { it.value.problem() != null }?.key

    fun problem(): Problem? = when {
        user.isNotBlank() && !USER.matches(user.trim()) -> Problem.USER
        sshKeys.lines().map { it.trim() }.filter { it.isNotEmpty() }.any { !NewContainer.isSshKey(it) } -> Problem.SSH_KEY
        badIp != null -> Problem.IP
        nameserver.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }.any { !IpAddress.isIpv4(it) && !IpAddress.isIpv6(it) } -> Problem.DNS
        else -> null
    }

    fun changes(old: CloudInit): Map<String, String> {
        val set = LinkedHashMap<String, String>()
        val delete = ArrayList<String>()
        fun text(key: String, before: String, after: String, encode: (String) -> String = { it }) {
            if (before == after) return
            if (after.isEmpty()) delete += key else set[key] = encode(after)
        }
        text("ciuser", old.user, user.trim())
        when {
            clearPassword && old.passwordSet -> delete += "cipassword"
            password.isNotEmpty() -> set["cipassword"] = password
        }
        val keys = sshKeys.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        text("sshkeys", old.sshKeys, keys, CloudInit::encodeKeys)
        (old.ipconfigs.keys + ipconfigs.keys).toSortedSet().forEach { i ->
            val before = old.ipconfigs[i]?.value()
            val after = ipconfigs[i]?.value()
            if (before != after) {
                if (after == null) delete += "ipconfig$i" else set["ipconfig$i"] = after
            }
        }
        text("nameserver", old.nameserver, nameserver.trim())
        text("searchdomain", old.searchDomain, searchDomain.trim())
        if (upgrade != null && upgrade != old.upgrade) set["ciupgrade"] = if (upgrade) "1" else "0"
        return finish(set, delete, old.digest)
    }

    companion object {
        /** A Linux user name. */
        private val USER = Regex("^[a-z_][a-z0-9_.-]{0,31}$")

        fun from(ci: CloudInit, nets: List<VmNet>): CloudInitForm = CloudInitForm(
            user = ci.user,
            sshKeys = ci.sshKeys,
            // Every network device gets a row; one without ipconfig starts as "none".
            ipconfigs = nets.mapNotNull { n -> n.key.removePrefix("net").toIntOrNull() }
                .associateWith { ci.ipconfigs[it] ?: CloudInitIp(ipv4 = CtNetSettings.Ipv4Mode.NONE) } + ci.ipconfigs,
            nameserver = ci.nameserver,
            searchDomain = ci.searchDomain,
            upgrade = ci.upgrade,
        )

        /** The config form that adds a cloud-init drive on [storage], on a free IDE slot (SCSI if none). */
        fun addDrive(hw: VmHardware, storage: String): Map<String, String> {
            val key = hw.freeSlot(DiskBus.IDE) ?: hw.freeSlot(DiskBus.SCSI) ?: return emptyMap()
            return finish(linkedMapOf(key to "$storage:cloudinit"), emptyList(), hw.digest)
        }
    }
}
