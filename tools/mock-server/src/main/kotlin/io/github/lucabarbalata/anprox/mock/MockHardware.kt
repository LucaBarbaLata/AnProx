package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.mock.MockState.Companion.GIB
import io.github.lucabarbalata.anprox.mock.MockState.Companion.MIB
import io.github.lucabarbalata.anprox.model.pve.DiskSize
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.PendingEntry
import io.github.lucabarbalata.anprox.model.pve.PropertyString
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.VmNet
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hardware part of the mock guests' configs: disks, CD/DVD drives, network devices, CPU and
 * memory, and pending changes. Callers hold [MockState]'s lock.
 *
 * Like Proxmox: `storage:size` allocates a new volume, detaching a disk leaves it as `unusedN`,
 * removing an unused volume destroys it, and on a running VM the cold-plug keys (CPU, memory, boot
 * order…) wait in `pending` until the next start.
 */
internal class MockHardware(
    private val storages: List<MockState.Storage>,
    private val nodes: List<MockState.Node>,
) {
    private val current = HashMap<Int, LinkedHashMap<String, String>>()

    /** Changes waiting for the next start; a null value removes the key. */
    private val pending = HashMap<Int, LinkedHashMap<String, String?>>()

    /** The keys [MockState] handles as options (name, tags…); everything else is hardware. */
    val optionKeys = setOf("name", "hostname", "onboot", "protection", "description", "tags", "agent", "digest", "delete")

    val bridges = listOf(
        NodeNetwork("vmbr0", "bridge", active = true, comments = "LAN", vlanAware = true),
        NodeNetwork("vmbr1", "bridge", active = true, comments = "Storage and lab"),
        NodeNetwork("eno1", "eth", active = true),
        NodeNetwork("eno2", "eth", active = false),
    )

    val isos = listOf(
        StoredFile("local", "iso", "debian-12.7.0-amd64-netinst.iso", 631 * MIB),
        StoredFile("local", "iso", "ubuntu-24.04.1-live-server-amd64.iso", 2_640 * MIB),
        StoredFile("nas-nfs", "iso", "virtio-win-0.1.262.iso", 708 * MIB),
    )

    val templates = listOf(
        StoredFile("local", "vztmpl", "debian-12-standard_12.7-1_amd64.tar.zst", 126 * MIB),
        StoredFile("local", "vztmpl", "ubuntu-24.04-standard_24.04-2_amd64.tar.zst", 141 * MIB),
        StoredFile("local", "vztmpl", "alpine-3.20-default_20240908_amd64.tar.xz", 3 * MIB),
    )

    /** An ISO image or a container template on a storage. */
    data class StoredFile(val storage: String, val content: String, val file: String, val size: Long) {
        val volid get() = "$storage:$content/$file"
        fun item(ctime: Long) = StorageContentItem(volid = volid, content = content, ctime = ctime, size = size, format = file.substringAfterLast('.'))
    }

    /** A new guest's config from `POST /nodes/{node}/{qemu|lxc}`. Returns an error message, or null. */
    fun create(g: MockState.Guest, params: Map<String, String>): String? {
        current[g.vmid] = LinkedHashMap()
        pending.remove(g.vmid)
        val error = update(g, params, emptyList(), creating = true)
        if (error != null) current.remove(g.vmid)
        return error
    }

    fun forget(vmid: Int) {
        current.remove(vmid)
        pending.remove(vmid)
    }

    fun of(g: MockState.Guest): LinkedHashMap<String, String> = current.getOrPut(g.vmid) { seed(g) }

    /** What `GET …/config` shows: the config with pending values applied. */
    fun effective(g: MockState.Guest): Map<String, String> {
        val config = LinkedHashMap(of(g))
        pending[g.vmid]?.forEach { (k, v) -> if (v == null) config.remove(k) else config[k] = v }
        return config
    }

    fun digestPart(g: MockState.Guest): String = of(g).toString() + pending[g.vmid].toString()

    fun pendingEntries(g: MockState.Guest): List<PendingEntry> {
        val now = of(g)
        val waiting = pending[g.vmid].orEmpty()
        return (now.keys + waiting.keys).distinct().map { k ->
            PendingEntry(
                key = k,
                value = now[k]?.let(::JsonPrimitive),
                pending = waiting[k]?.let(::JsonPrimitive),
                delete = if (k in waiting && waiting[k] == null) 1 else null,
            )
        }
    }

    /** Applies pending changes (the guest starts). */
    fun applyPending(g: MockState.Guest) {
        val waiting = pending.remove(g.vmid) ?: return
        val config = of(g)
        waiting.forEach { (k, v) -> if (v == null) config.remove(k) else config[k] = v }
        sync(g)
    }

    // ------------------------------------------------------------------ config changes

    /** Validates and applies the hardware keys of a config change. Returns an error message, or null. */
    fun update(g: MockState.Guest, set: Map<String, String>, delete: List<String>, creating: Boolean = false): String? {
        val config = LinkedHashMap(effective(g))
        val qemu = g.type == "qemu"
        val node = nodes.first { it.name == g.node }
        var nextDisk = nextDiskNumber(g)
        val resolved = LinkedHashMap<String, String>()
        val removed = ArrayList<String>()
        val newUnused = ArrayList<String>()

        for (key in delete) {
            val value = config[key] ?: continue
            if (key == "rootfs") return "unable to delete required option 'rootfs'"
            val volume = PropertyString.parse(value, if (qemu) "file" else "volume")
            val keepsVolume = (DISK.matches(key) && volume["media"] != "cdrom") || (key.startsWith("mp") && ':' in volume[if (qemu) "file" else "volume"].orEmpty())
            if (keepsVolume) newUnused += volume[if (qemu) "file" else "volume"]!!
            removed += key
        }

        for ((key, raw) in set) {
            val value = raw.trim()
            resolved[key] = when {
                DISK.matches(key) && qemu -> disk(g, node, key, value, config) { nextDisk++ } ?: return lastError
                key.startsWith("mp") && !qemu -> mountPoint(g, value) { nextDisk++ } ?: return lastError
                NET.matches(key) -> (if (qemu) vmNet(g, key, value) else ctNet(g, key, value)) ?: return lastError
                key == "rootfs" && creating && !qemu -> mountPoint(g, value, root = true) { nextDisk++ } ?: return lastError
                key == "rootfs" -> return "unable to modify read-only option: 'rootfs'"
                key == "sockets" || key == "cores" -> value.toIntOrNull()?.takeIf { it >= 1 }?.toString() ?: return "$key: value must have a minimum value of 1"
                key == "memory" || key == "swap" || key == "balloon" -> value.toIntOrNull()?.toString() ?: return "$key: type check ('integer') failed - got '$value'"
                key == "boot" -> value
                else -> value
            }
        }

        val after = LinkedHashMap(config).apply {
            removed.forEach { remove(it) }
            putAll(resolved)
        }
        if (qemu) {
            val vcpus = (after["sockets"]?.toIntOrNull() ?: 1) * (after["cores"]?.toIntOrNull() ?: 1)
            if (vcpus > node.cpus) return "MAX ${node.cpus} vcpus allowed per VM on this node"
            val memory = after["memory"]?.toIntOrNull() ?: 512
            if (memory < 16) return "memory: value must have a minimum value of 16"
            val balloon = after["balloon"]?.toIntOrNull()
            if (balloon != null && balloon > memory) return "balloon value too large (must be smaller than assigned memory)"
            after["boot"]?.let { boot ->
                val order = PropertyString.parse(boot, "legacy")["order"]?.split(';')?.filter { it.isNotBlank() }.orEmpty()
                // Removed devices drop out of the boot order, as in Proxmox.
                val kept = order.filter { it !in removed }
                kept.firstOrNull { it !in after }?.let { return "invalid bootorder: device '$it' does not exist'" }
                if (kept != order) resolved["boot"] = "order=" + kept.joinToString(";")
            }
        } else {
            val cores = after["cores"]?.toIntOrNull()
            if (cores != null && cores > node.cpus) return "cores: value must have a maximum value of ${node.cpus}"
            if ((after["memory"]?.toIntOrNull() ?: 512) < 16) return "memory: value must have a minimum value of 16"
        }

        // Valid: apply. Running VMs keep cold-plug keys for the next start.
        val now = of(g)
        val waiting = pending.getOrPut(g.vmid) { LinkedHashMap() }
        val coldPlug = qemu && g.status == "running"
        for (key in removed) {
            if (coldPlug && key in COLD_KEYS) waiting[key] = null else {
                now.remove(key)
                waiting.remove(key)
            }
        }
        newUnused.forEach { volume -> now[freeKey(now, "unused")] = volume }
        for ((key, value) in resolved) {
            if (key.startsWith("unused")) continue
            // Attaching an unused volume takes it out of the unused list.
            now.entries.removeIf { (k, v) -> k.startsWith("unused") && v == PropertyString.parse(value, "file")["file"] }
            if (coldPlug && key in COLD_KEYS) waiting[key] = value else {
                now[key] = value
                waiting.remove(key)
            }
        }
        if (waiting.isEmpty()) pending.remove(g.vmid)
        sync(g)
        return null
    }

    private var lastError: String = ""

    private fun error(message: String): String? {
        lastError = message
        return null
    }

    private fun disk(g: MockState.Guest, node: MockState.Node, key: String, value: String, config: Map<String, String>, next: () -> Int): String? {
        val props = PropertyString.parse(value, "file")
        val file = props["file"].orEmpty()
        if (props["media"] == "cdrom") {
            if (file == "none" || file == "cdrom") return value
            val iso = isos.firstOrNull { it.volid == file } ?: return error("volume '$file' does not exist")
            return props.with("size", "${iso.size / MIB}M").toString()
        }
        val allocation = ALLOCATION.matchEntire(file)
        if (allocation != null) {
            val (storageId, size) = allocation.destructured
            val gib = size.toDoubleOrNull() ?: return error("invalid size '$size'")
            val storage = storage(node.name, storageId) ?: return error("storage '$storageId' does not exist")
            if ("images" !in storage.content.split(',')) return error("storage '$storageId' does not support content-type 'images'")
            if (gib * GIB > storage.total - storage.used) return error("not enough free space on storage '$storageId'")
            return props.withFirst("file", volumeName(storage, g, next(), container = false), implicit = true)
                .with("size", "${size}G").toString()
        }
        // An existing volume: one of this guest's unused volumes (re-attaching).
        if (config.none { (k, v) -> k.startsWith("unused") && v == file }) return error("volume '$file' does not exist")
        return value
    }

    private fun mountPoint(g: MockState.Guest, value: String, root: Boolean = false, next: () -> Int): String? {
        val props = PropertyString.parse(value, "volume")
        val volume = props["volume"].orEmpty()
        if (!root) {
            val path = props["mp"] ?: return error("missing mount point path (mp)")
            if (!path.startsWith("/") || path == "/") return error("invalid mount point path '$path'")
        }
        val allocation = ALLOCATION.matchEntire(volume) ?: return value
        val (storageId, size) = allocation.destructured
        val storage = storage(g.node, storageId) ?: return error("storage '$storageId' does not exist")
        if ("rootdir" !in storage.content.split(',')) return error("storage '$storageId' does not support content-type 'rootdir'")
        val gib = size.toDoubleOrNull() ?: return error("invalid size '$size'")
        if (gib * GIB > storage.total - storage.used) return error("not enough free space on storage '$storageId'")
        return props.withFirst("volume", volumeName(storage, g, next(), container = true), implicit = true).with("size", "${size}G").toString()
    }

    private fun vmNet(g: MockState.Guest, key: String, value: String): String? {
        val props = PropertyString.parse(value)
        val model = props.firstKey?.takeIf { it in VmNet.MODELS } ?: return error("$key: invalid format - unknown network model")
        props["bridge"]?.let { b -> if (bridges.none { it.iface == b && it.isBridge }) return error("bridge '$b' does not exist") }
        props["tag"]?.let { t -> if (t.toIntOrNull() !in 1..4094) return error("$key: invalid VLAN tag '$t'") }
        val withMac = if (props.isImplicit(model)) props.withFirst(model, mac(g, key.removePrefix("net").toInt())) else props
        return withMac.toString()
    }

    private fun ctNet(g: MockState.Guest, key: String, value: String): String? {
        val props = PropertyString.parse(value)
        if (props["name"].isNullOrBlank()) return error("$key: missing property - 'name'")
        props["bridge"]?.let { b -> if (bridges.none { it.iface == b && it.isBridge }) return error("bridge '$b' does not exist") }
        props["ip"]?.let { ip -> if (ip != "dhcp" && ip != "manual" && '/' !in ip) return error("$key: invalid format - ip: invalid IPv4 network '$ip'") }
        return (if (props["hwaddr"] == null) props.with("hwaddr", mac(g, key.removePrefix("net").toInt())) else props).toString()
    }

    // ------------------------------------------------------------------ resize and move

    /** `PUT …/resize`; returns an error or null. */
    fun resize(g: MockState.Guest, disk: String, size: String): String? {
        val config = of(g)
        val value = config[disk] ?: return "unable to find disk '$disk'"
        val props = PropertyString.parse(value, if (g.type == "qemu") "file" else "volume")
        if (props["media"] == "cdrom") return "can't resize a CD/DVD drive"
        val before = DiskSize.parse(props["size"]) ?: 0
        val after = when {
            size.startsWith("+") -> before + (DiskSize.parse(size.drop(1)) ?: return "invalid size '$size'")
            else -> DiskSize.parse(size) ?: return "invalid size '$size'"
        }
        if (after < before) return "shrinking disks is not supported"
        config[disk] = props.with("size", "${after / GIB}G").toString()
        sync(g)
        return null
    }

    /** `move_disk` / `move_volume`: checks now (error or null); [moveEffect] does it when the task ends. */
    fun checkMove(g: MockState.Guest, key: String, storageId: String): String? {
        val value = of(g)[key] ?: return "unable to find disk '$key'"
        val props = PropertyString.parse(value, if (g.type == "qemu") "file" else "volume")
        val volume = props[if (g.type == "qemu") "file" else "volume"].orEmpty()
        if (props["media"] == "cdrom") return "you can't move a cdrom"
        if (g.type == "lxc" && g.status == "running") return "cannot move volumes of a running container"
        val target = storage(g.node, storageId) ?: return "storage '$storageId' does not exist"
        if ((if (g.type == "qemu") "images" else "rootdir") !in target.content.split(',')) return "storage '$storageId' does not support this content type"
        if (volume.substringBefore(':') == storageId) return "you can't move to the same storage with same format"
        return null
    }

    fun moveEffect(g: MockState.Guest, key: String, storageId: String, deleteSource: Boolean) {
        val config = of(g)
        val qemu = g.type == "qemu"
        val props = PropertyString.parse(config[key] ?: return, if (qemu) "file" else "volume")
        val old = props[if (qemu) "file" else "volume"]!!
        val target = storage(g.node, storageId) ?: return
        config[key] = props.withFirst(if (qemu) "file" else "volume", volumeName(target, g, nextDiskNumber(g), container = !qemu), implicit = true).toString()
        if (!deleteSource) config[freeKey(config, "unused")] = old
    }

    // ------------------------------------------------------------------ helpers

    private fun storage(node: String, id: String) = storages.firstOrNull { it.node == node && it.id == id }

    private fun volumeName(storage: MockState.Storage, g: MockState.Guest, n: Int, container: Boolean): String = when (storage.plugin) {
        "zfspool" -> "${storage.id}:${if (container) "subvol" else "vm"}-${g.vmid}-disk-$n"
        else -> "${storage.id}:${g.vmid}/vm-${g.vmid}-disk-$n.${if (container) "raw" else "qcow2"}"
    }

    private fun nextDiskNumber(g: MockState.Guest): Int =
        (effective(g).values + of(g).values).mapNotNull { DISK_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()?.plus(1) ?: 0

    private fun freeKey(config: Map<String, String>, prefix: String): String =
        generateSequence(0) { it + 1 }.map { "$prefix$it" }.first { it !in config }

    private fun mac(g: MockState.Guest, index: Int) = "BC:24:11:%02X:%02X:%02X".format((g.vmid shr 8) and 0xff, g.vmid and 0xff, index)

    /** Keeps the guest's summary (CPU count, memory, disk size) in step with its config. */
    fun sync(g: MockState.Guest) {
        val c = of(g)
        if (g.type == "qemu") {
            g.cpus = (c["sockets"]?.toIntOrNull() ?: 1) * (c["cores"]?.toIntOrNull() ?: 1)
            c["memory"]?.toLongOrNull()?.let { g.maxmem = it * MIB }
            val first = PropertyString.parse(c["boot"], "legacy")["order"]?.split(';')?.firstOrNull { DISK.matches(it) && it in c } ?: "scsi0"
            c[first]?.let { DiskSize.parse(PropertyString.parse(it, "file")["size"]) }?.let { g.maxdisk = it }
        } else {
            g.cpus = c["cores"]?.toIntOrNull() ?: nodes.first { it.name == g.node }.cpus
            c["memory"]?.toLongOrNull()?.let { g.maxmem = it * MIB }
            c["rootfs"]?.let { DiskSize.parse(PropertyString.parse(it, "volume")["size"]) }?.let { g.maxdisk = it }
        }
    }

    private fun seed(g: MockState.Guest): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        val storage = storage(g.node, g.diskStorage) ?: storage(g.node, "local-zfs")!!
        if (g.type == "qemu") {
            val windows = g.name.startsWith("win")
            m["ostype"] = if (windows) "win11" else "l26"
            m["sockets"] = "1"
            m["cores"] = g.cpus.toString()
            m["cpu"] = if (windows) "host" else "x86-64-v2-AES"
            m["memory"] = (g.maxmem / MIB).toString()
            if (g.vmid == 100 || g.vmid == 101) m["balloon"] = (g.maxmem / MIB / 4).toString()
            m["scsihw"] = "virtio-scsi-single"
            if (windows) {
                m["bios"] = "ovmf"
                m["machine"] = "pc-q35-8.1"
                m["efidisk0"] = "${volumeName(storage, g, 1, false)},efitype=4m,pre-enrolled-keys=1,size=1M"
                m["tpmstate0"] = "${volumeName(storage, g, 2, false)},size=4M,version=v2.0"
            }
            m["scsi0"] = "${volumeName(storage, g, 0, false)},iothread=1,size=${g.maxdisk / GIB}G"
            m["ide2"] = if (windows) "${isos[2].volid},media=cdrom,size=708M" else "none,media=cdrom"
            if (g.vmid == 103) m["ide0"] = "${storage.id}:vm-103-cloudinit,media=cdrom"
            m["net0"] = "virtio=${mac(g, 0)},bridge=vmbr0,firewall=1"
            m["boot"] = "order=scsi0;ide2;net0"
            if (g.vmid == 104) m["unused0"] = volumeName(storage, g, 3, false)
        } else {
            m["ostype"] = "debian"
            m["arch"] = "amd64"
            m["cores"] = g.cpus.toString()
            m["memory"] = (g.maxmem / MIB).toString()
            m["swap"] = "512"
            m["rootfs"] = "${volumeName(storage, g, 0, true)},size=${g.maxdisk / GIB}G"
            if (g.vmid == 201) m["mp0"] = "${volumeName(storage, g, 1, true)},mp=/var/www,backup=1,size=16G"
            m["net0"] = if (g.vmid == 201) {
                "name=eth0,bridge=vmbr0,firewall=1,gw=10.0.0.1,hwaddr=${mac(g, 0)},ip=10.0.0.201/24,type=veth"
            } else {
                "name=eth0,bridge=vmbr0,firewall=1,hwaddr=${mac(g, 0)},ip=dhcp,type=veth"
            }
            m["unprivileged"] = "1"
        }
        return m
    }

    private companion object {
        val DISK = Regex("^(?:(?:ide|sata|scsi|virtio)\\d+|efidisk0|tpmstate0)$")
        val NET = Regex("^net\\d+$")
        val ALLOCATION = Regex("^([A-Za-z0-9][A-Za-z0-9_.-]*):(\\d+(?:\\.\\d+)?)$")
        val DISK_NUMBER = Regex("-disk-(\\d+)")

        /** Keys a running VM only picks up at the next start. */
        val COLD_KEYS = setOf("sockets", "cores", "cpu", "memory", "balloon", "boot", "scsihw", "bios", "machine", "ostype")
    }
}
