package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A container volume: the root disk (`rootfs`), a mount point (`mpN`) or an unused volume. */
data class CtVolume(val key: String, val props: PropertyString) {
    val volume: String get() = props["volume"].orEmpty()

    /** Where it is mounted inside the container: `/` for the root disk. */
    val mountPath: String? get() = if (key == "rootfs") "/" else props["mp"]

    /** Null for a bind mount of a host directory (`/mnt/data`), which has no storage. */
    val storage: String? get() = volume.substringBefore(':', "").ifEmpty { null }

    val sizeBytes: Long? get() = DiskSize.parse(props["size"])
    val isUnused: Boolean get() = key.startsWith("unused")
    val isRoot: Boolean get() = key == "rootfs"

    /** Only storage volumes can be resized or moved; bind mounts and devices can't. */
    val isResizable: Boolean get() = storage != null && !isUnused
    val backup: Boolean get() = if (isRoot) true else props["backup"].isOn()
}

/** A container network device (`netN: name=eth0,bridge=vmbr0,ip=dhcp,…`). */
data class CtNet(val key: String, val props: PropertyString) {
    val name: String get() = props["name"].orEmpty()
    val bridge: String? get() = props["bridge"]
    val hwaddr: String? get() = props["hwaddr"]
    val ip: String? get() = props["ip"]
    val gateway: String? get() = props["gw"]
    val ip6: String? get() = props["ip6"]
    val gateway6: String? get() = props["gw6"]
    val tag: Int? get() = props["tag"]?.toIntOrNull()
    val firewall: Boolean get() = props["firewall"].isOn()
}

/**
 * A container's resources (questionnaire Q19), from `GET …/lxc/{vmid}/config` (pending values
 * included; [pending] names those keys).
 *
 * @property cores null: no limit, the container may use every core of the node.
 */
data class CtHardware(
    val cores: Int? = null,
    val memoryMiB: Int = 512,
    val swapMiB: Int = 512,
    val rootfs: CtVolume? = null,
    val mountPoints: List<CtVolume> = emptyList(),
    val unused: List<CtVolume> = emptyList(),
    val nets: List<CtNet> = emptyList(),
    val pending: Set<String> = emptySet(),
    val digest: String? = null,
) {
    val volumes: List<CtVolume> get() = listOfNotNull(rootfs) + mountPoints

    fun volume(key: String): CtVolume? = (volumes + unused).firstOrNull { it.key == key }

    fun freeMountPoint(): String? = (0 until MAX_MOUNT_POINTS).map { "mp$it" }.firstOrNull { k -> mountPoints.none { it.key == k } }

    fun freeNetKey(): String? = (0 until MAX_NETS).map { "net$it" }.firstOrNull { k -> nets.none { it.key == k } }

    /** `eth0`, `eth1`… the first name no device uses yet. */
    fun suggestNetName(): String = generateSequence(0) { it + 1 }.map { "eth$it" }.first { n -> nets.none { it.name == n } }

    companion object {
        const val MAX_MOUNT_POINTS = 256
        const val MAX_NETS = 32

        private val MP_KEY = Regex("^mp\\d+$")
        private val UNUSED_KEY = Regex("^unused\\d+$")
        private val NET_KEY = Regex("^net\\d+$")

        fun fromConfig(config: JsonObject, pending: Set<String> = emptySet()): CtHardware {
            fun string(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull
            fun index(key: String) = key.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0
            fun volumes(rule: Regex) = config.keys.filter { rule.matches(it) }.sortedBy(::index).map { CtVolume(it, PropertyString.parse(string(it), "volume")) }
            return CtHardware(
                cores = string("cores")?.toIntOrNull(),
                memoryMiB = string("memory")?.toDoubleOrNull()?.toInt() ?: 512,
                swapMiB = string("swap")?.toDoubleOrNull()?.toInt() ?: 512,
                rootfs = string("rootfs")?.let { CtVolume("rootfs", PropertyString.parse(it, "volume")) },
                mountPoints = volumes(MP_KEY),
                unused = volumes(UNUSED_KEY),
                nets = config.keys.filter { NET_KEY.matches(it) }.sortedBy(::index).map { CtNet(it, PropertyString.parse(string(it))) },
                pending = pending,
                digest = string("digest"),
            )
        }
    }
}

/** The Resources form of a container. */
data class CtResources(val cores: Int?, val memoryMiB: Int, val swapMiB: Int) {
    enum class Problem { CORES, MEMORY, SWAP }

    fun problem(hostCpus: Int?): Problem? = when {
        cores != null && (cores < 1 || (hostCpus != null && hostCpus > 0 && cores > hostCpus)) -> Problem.CORES
        memoryMiB < VmCpuMemory.MIN_MEMORY_MIB -> Problem.MEMORY
        swapMiB < 0 -> Problem.SWAP
        else -> null
    }

    fun changes(old: CtHardware): Map<String, String> {
        val set = LinkedHashMap<String, String>()
        val delete = ArrayList<String>()
        if (cores != old.cores) {
            if (cores == null) delete += "cores" else set["cores"] = cores.toString()
        }
        if (memoryMiB != old.memoryMiB) set["memory"] = memoryMiB.toString()
        if (swapMiB != old.swapMiB) set["swap"] = swapMiB.toString()
        return finish(set, delete, old.digest)
    }

    companion object {
        fun from(hw: CtHardware) = CtResources(hw.cores, hw.memoryMiB, hw.swapMiB)
    }
}

/** A new mount point, allocated on [storage] and mounted at [path]. */
data class NewMountPoint(
    val storage: String = "",
    val sizeGiB: Int = 8,
    val path: String = "/mnt/data",
    val backup: Boolean = true,
) {
    enum class Problem { STORAGE, SIZE, PATH, PATH_IN_USE, NO_SLOT }

    /** The path as it will be sent: trimmed, without a trailing `/`. */
    val cleanPath: String get() = path.trim().trimEnd('/')

    fun problem(hw: CtHardware, freeBytes: Long?): Problem? = when {
        storage.isBlank() -> Problem.STORAGE
        sizeGiB < 1 || (freeBytes != null && sizeGiB.toLong() * DiskSize.GIB > freeBytes) -> Problem.SIZE
        !isValidPath(cleanPath) -> Problem.PATH
        hw.volumes.any { it.mountPath?.trimEnd('/') == cleanPath } -> Problem.PATH_IN_USE
        hw.freeMountPoint() == null -> Problem.NO_SLOT
        else -> null
    }

    fun params(hw: CtHardware): Map<String, String> {
        val key = hw.freeMountPoint() ?: return emptyMap()
        val value = PropertyString.EMPTY.withFirst("volume", "$storage:$sizeGiB", implicit = true)
            .with("mp" to cleanPath, "backup" to "1".takeIf { backup })
        return finish(linkedMapOf(key to value.toString()), emptyList(), hw.digest)
    }

    companion object {
        private val PATH = Regex("^/[^,=\\s]*[^/,=\\s]$")

        /** An absolute path other than `/`, without commas, `=` or spaces. */
        fun isValidPath(path: String): Boolean = PATH.matches(path) && !path.split('/').any { it == ".." }
    }
}

/** Container volume operations. */
object CtVolumeOps {
    /** `PUT …/lxc/{vmid}/resize`: grows the volume and its file system. */
    fun resize(volume: CtVolume, addGiB: Int, digest: String?): Map<String, String> =
        finish(linkedMapOf("disk" to volume.key, "size" to "+${addGiB}G"), emptyList(), digest)

    /** `POST …/lxc/{vmid}/move_volume`. Proxmox requires the container to be stopped. */
    fun move(volume: CtVolume, storage: String, deleteSource: Boolean, digest: String?): Map<String, String> =
        finish(linkedMapOf("volume" to volume.key, "storage" to storage, "delete" to if (deleteSource) "1" else "0"), emptyList(), digest)

    /** Detaching a mount point keeps the volume as `unusedN`; removing an unused volume destroys it. */
    fun remove(volume: CtVolume, digest: String?): Map<String, String> = finish(LinkedHashMap(), listOf(volume.key), digest)
}

/** The form of a container network device. */
data class CtNetSettings(
    val name: String = "eth0",
    val bridge: String = "vmbr0",
    val ipv4: Ipv4Mode = Ipv4Mode.DHCP,
    val ip: String = "",
    val gateway: String = "",
    val ipv6: Ipv6Mode = Ipv6Mode.NONE,
    val ip6: String = "",
    val gateway6: String = "",
    val tag: Int? = null,
    val firewall: Boolean = true,
) {
    enum class Ipv4Mode { DHCP, STATIC, NONE }
    enum class Ipv6Mode { NONE, DHCP, SLAAC, STATIC }
    enum class Problem { NAME, NAME_IN_USE, BRIDGE, IP, GATEWAY, IP6, GATEWAY6, TAG }

    fun problem(hw: CtHardware, old: CtNet?): Problem? = when {
        !NAME.matches(name.trim()) -> Problem.NAME
        hw.nets.any { it.key != old?.key && it.name == name.trim() } -> Problem.NAME_IN_USE
        bridge.isBlank() -> Problem.BRIDGE
        ipv4 == Ipv4Mode.STATIC && !IpAddress.isIpv4Cidr(ip.trim()) -> Problem.IP
        ipv4 == Ipv4Mode.STATIC && gateway.isNotBlank() && !IpAddress.isIpv4(gateway.trim()) -> Problem.GATEWAY
        ipv6 == Ipv6Mode.STATIC && !IpAddress.isIpv6Cidr(ip6.trim()) -> Problem.IP6
        ipv6 == Ipv6Mode.STATIC && gateway6.isNotBlank() && !IpAddress.isIpv6(gateway6.trim()) -> Problem.GATEWAY6
        tag != null && tag !in VmNetSettings.VLAN_TAGS -> Problem.TAG
        else -> null
    }

    /** The `netN` value; the MAC address, type and settings the app doesn't edit are kept. */
    fun value(old: CtNet?): String {
        val base = old?.props ?: PropertyString.EMPTY
        val ipValue = when (ipv4) {
            Ipv4Mode.DHCP -> "dhcp"
            Ipv4Mode.STATIC -> ip.trim()
            Ipv4Mode.NONE -> null
        }
        val ip6Value = when (ipv6) {
            Ipv6Mode.NONE -> null
            Ipv6Mode.DHCP -> "dhcp"
            Ipv6Mode.SLAAC -> "auto"
            Ipv6Mode.STATIC -> ip6.trim()
        }
        return base.withFirst("name", name.trim()).with(
            "bridge" to bridge.trim(),
            "firewall" to "1".takeIf { firewall },
            "ip" to ipValue,
            "gw" to gateway.trim().takeIf { ipv4 == Ipv4Mode.STATIC && it.isNotEmpty() },
            "ip6" to ip6Value,
            "gw6" to gateway6.trim().takeIf { ipv6 == Ipv6Mode.STATIC && it.isNotEmpty() },
            "tag" to tag?.toString(),
            "type" to (base["type"] ?: "veth"),
        ).toString()
    }

    fun params(hw: CtHardware, old: CtNet?): Map<String, String> {
        val key = old?.key ?: hw.freeNetKey() ?: return emptyMap()
        val value = value(old)
        if (old != null && value == old.props.toString()) return emptyMap()
        return finish(linkedMapOf(key to value), emptyList(), hw.digest)
    }

    companion object {
        /** Linux interface names: at most 15 characters. */
        private val NAME = Regex("^[A-Za-z0-9_.-]{1,15}$")

        fun from(net: CtNet) = CtNetSettings(
            name = net.name,
            bridge = net.bridge.orEmpty(),
            ipv4 = when (net.ip) {
                null -> Ipv4Mode.NONE
                "dhcp" -> Ipv4Mode.DHCP
                else -> Ipv4Mode.STATIC
            },
            ip = net.ip?.takeUnless { it == "dhcp" }.orEmpty(),
            gateway = net.gateway.orEmpty(),
            ipv6 = when (net.ip6) {
                null, "manual" -> Ipv6Mode.NONE
                "dhcp" -> Ipv6Mode.DHCP
                "auto" -> Ipv6Mode.SLAAC
                else -> Ipv6Mode.STATIC
            },
            ip6 = net.ip6?.takeUnless { it == "dhcp" || it == "auto" || it == "manual" }.orEmpty(),
            gateway6 = net.gateway6.orEmpty(),
            tag = net.tag,
            firewall = net.firewall,
        )
    }
}

/** IP address checks without `InetAddress` (which may resolve host names). */
object IpAddress {
    fun isIpv4(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 && (p.length == 1 || p[0] != '0') }
    }

    fun isIpv4Cidr(text: String): Boolean = cidr(text, 32, ::isIpv4)

    fun isIpv6(text: String): Boolean {
        if (text.isEmpty() || text.count { it == ':' } < 2) return false
        if (text.indexOf("::") != text.lastIndexOf("::")) return false
        var groups = text.split(':')
        // An embedded IPv4 address counts as two groups (::ffff:192.0.2.1).
        val ipv4Tail = groups.last().contains('.')
        if (ipv4Tail && !isIpv4(groups.last())) return false
        if (ipv4Tail) groups = groups.dropLast(1) + listOf("0", "0")
        val compressed = "::" in text
        val nonEmpty = groups.filter { it.isNotEmpty() }
        if (nonEmpty.any { g -> g.length > 4 || !g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }) return false
        if (!compressed && (groups.size != 8 || groups.any { it.isEmpty() })) return false
        if (compressed && nonEmpty.size > 7) return false
        // A single leading or trailing ':' is only allowed as part of '::'.
        if (text.startsWith(':') && !text.startsWith("::")) return false
        if (text.endsWith(':') && !text.endsWith("::")) return false
        return true
    }

    fun isIpv6Cidr(text: String): Boolean = cidr(text, 128, ::isIpv6)

    private fun cidr(text: String, max: Int, address: (String) -> Boolean): Boolean {
        val slash = text.lastIndexOf('/')
        if (slash <= 0) return false
        val prefix = text.substring(slash + 1)
        return prefix.isNotEmpty() && prefix.length <= 3 && prefix.all(Char::isDigit) && prefix.toInt() in 0..max && address(text.substring(0, slash))
    }
}
