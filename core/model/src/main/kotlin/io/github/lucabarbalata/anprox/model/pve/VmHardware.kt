package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.floor

/** The buses a VM disk can sit on, with Proxmox's slot limits. */
enum class DiskBus(val prefix: String, val slots: Int) {
    SCSI("scsi", 31),
    VIRTIO("virtio", 16),
    SATA("sata", 6),
    IDE("ide", 4),
    ;

    /** `ssd=1` (SSD emulation) is not available on VirtIO Block. */
    val supportsSsd: Boolean get() = this != VIRTIO

    companion object {
        fun of(key: String): DiskBus? = entries.firstOrNull { key.startsWith(it.prefix) && key.removePrefix(it.prefix).toIntOrNull() != null }
    }
}

/**
 * One disk-like entry of a VM config: a hard disk, a CD/DVD drive, the cloud-init drive, EFI or
 * TPM state, or an unused volume (`unusedN`, left behind when a disk is detached).
 */
data class VmDisk(val key: String, val props: PropertyString) {
    enum class Kind { DISK, CDROM, CLOUD_INIT, EFI, TPM, UNUSED }

    val volume: String get() = props["file"].orEmpty()

    val kind: Kind
        get() = when {
            key.startsWith("unused") -> Kind.UNUSED
            key == "efidisk0" -> Kind.EFI
            key == "tpmstate0" -> Kind.TPM
            props["media"] == "cdrom" && "cloudinit" in volume -> Kind.CLOUD_INIT
            props["media"] == "cdrom" -> Kind.CDROM
            else -> Kind.DISK
        }

    val bus: DiskBus? get() = DiskBus.of(key)

    /** The storage ID; null for an empty drive (`none`), the host's drive (`cdrom`) or a path. */
    val storage: String? get() = volume.substringBefore(':', "").ifEmpty { null }

    val sizeBytes: Long? get() = DiskSize.parse(props["size"])

    /** CD/DVD drives: the ISO file name, or null when the drive is empty. */
    val isoName: String? get() = if (kind == Kind.CDROM && storage != null) volume.substringAfter(':').substringAfterLast('/') else null

    val iothread: Boolean get() = props["iothread"].isOn()
    val ssd: Boolean get() = props["ssd"].isOn()
    val discard: Boolean get() = props["discard"] == "on"

    /** Hard disks can be resized and moved; other kinds can't (or only through other screens). */
    val isResizable: Boolean get() = kind == Kind.DISK && storage != null
}

/** A VM network device (`netN: virtio=BC:24:11:…,bridge=vmbr0,firewall=1`). */
data class VmNet(val key: String, val props: PropertyString) {
    val model: String get() = props.keys.firstOrNull { it in MODELS } ?: props.firstKey ?: "virtio"

    /** Null when Proxmox will generate one (the model was written without `=MAC`). */
    val mac: String? get() = props[model]?.takeUnless { props.isImplicit(model) }
    val bridge: String? get() = props["bridge"]
    val tag: Int? get() = props["tag"]?.toIntOrNull()
    val firewall: Boolean get() = props["firewall"].isOn()
    val linkDown: Boolean get() = props["link_down"].isOn()

    /** Rate limit in MB/s, or null for unlimited. */
    val rateMBps: Double? get() = props["rate"]?.toDoubleOrNull()

    companion object {
        /** The models the app offers, VirtIO first (the fastest; Windows needs its driver). */
        val MODELS = listOf("virtio", "e1000", "e1000e", "rtl8139", "vmxnet3")
    }
}

/**
 * A VM's hardware (questionnaire Q19), read from `GET …/qemu/{vmid}/config`, which returns pending
 * values (changes that take effect at the next start). [pending] names those keys.
 *
 * @property balloonMiB null: ballooning on with the minimum equal to [memoryMiB] (Proxmox's
 *   default); 0: ballooning off.
 */
data class VmHardware(
    val sockets: Int = 1,
    val cores: Int = 1,
    val cpu: PropertyString = PropertyString.EMPTY,
    val memoryMiB: Int = 512,
    val balloonMiB: Int? = null,
    val disks: List<VmDisk> = emptyList(),
    val nets: List<VmNet> = emptyList(),
    val bootOrder: List<String> = emptyList(),
    val scsiController: String? = null,
    val pending: Set<String> = emptySet(),
    val digest: String? = null,
) {
    /** Null means Proxmox's default (`kvm64`). */
    val cpuType: String? get() = cpu["cputype"]
    val vcpus: Int get() = sockets * cores

    val hardDisks: List<VmDisk> get() = disks.filter { it.kind == VmDisk.Kind.DISK }
    val drives: List<VmDisk> get() = disks.filter { it.kind == VmDisk.Kind.CDROM || it.kind == VmDisk.Kind.CLOUD_INIT }
    val unused: List<VmDisk> get() = disks.filter { it.kind == VmDisk.Kind.UNUSED }
    val system: List<VmDisk> get() = disks.filter { it.kind == VmDisk.Kind.EFI || it.kind == VmDisk.Kind.TPM }

    /** `iothread=1` on SCSI only works with the VirtIO SCSI single controller. */
    fun supportsIothread(bus: DiskBus): Boolean = bus == DiskBus.VIRTIO || (bus == DiskBus.SCSI && scsiController == "virtio-scsi-single")

    fun freeSlot(bus: DiskBus): String? = (0 until bus.slots).map { "${bus.prefix}$it" }.firstOrNull { k -> disks.none { it.key == k } }

    fun freeNetKey(): String? = (0 until MAX_NETS).map { "net$it" }.firstOrNull { k -> nets.none { it.key == k } }

    /** Devices that can be in the boot order: hard disks, CD/DVD drives and network devices. */
    val bootable: List<String>
        get() = hardDisks.map { it.key } + disks.filter { it.kind == VmDisk.Kind.CDROM }.map { it.key } + nets.map { it.key }

    fun disk(key: String): VmDisk? = disks.firstOrNull { it.key == key }

    companion object {
        const val MAX_NETS = 32

        /** Common CPU types; the default for new VMs in Proxmox VE 8 is `x86-64-v2-AES`. */
        val CPU_TYPES = listOf("host", "x86-64-v2-AES", "x86-64-v3", "x86-64-v4", "kvm64", "qemu64", "max")

        private val DISK_KEY = Regex("^(?:(?:ide|sata|scsi|virtio)\\d+|efidisk0|tpmstate0|unused\\d+)$")
        private val NET_KEY = Regex("^net\\d+$")

        fun fromConfig(config: JsonObject, pending: Set<String> = emptySet()): VmHardware {
            fun string(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull
            val disks = config.keys.filter { DISK_KEY.matches(it) }
                .map { VmDisk(it, PropertyString.parse(string(it), "file")) }
                .sortedWith(compareBy({ diskOrder(it.key) }, { it.key.trimEnd { c -> c.isDigit() } }, { it.key.takeLastWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }))
            val nets = config.keys.filter { NET_KEY.matches(it) }
                .map { VmNet(it, PropertyString.parse(string(it))) }
                .sortedBy { it.key.removePrefix("net").toIntOrNull() ?: 0 }
            val memory = PropertyString.parse(string("memory"), "current")["current"]?.toDoubleOrNull()?.toInt()
            return VmHardware(
                sockets = string("sockets")?.toIntOrNull() ?: 1,
                cores = string("cores")?.toIntOrNull() ?: 1,
                cpu = PropertyString.parse(string("cpu"), "cputype"),
                memoryMiB = memory ?: 512,
                balloonMiB = string("balloon")?.toDoubleOrNull()?.toInt(),
                disks = disks,
                nets = nets,
                bootOrder = BootOrder.parse(string("boot"), string("bootdisk"), disks, nets),
                scsiController = string("scsihw"),
                pending = pending,
                digest = string("digest"),
            )
        }

        private fun diskOrder(key: String) = when {
            DiskBus.of(key) != null -> 0
            key.startsWith("unused") -> 2
            else -> 1
        }
    }
}

/** The `boot` option: `order=scsi0;ide2;net0`, or the legacy letters (`cdn`) with `bootdisk`. */
object BootOrder {
    fun parse(boot: String?, bootdisk: String?, disks: List<VmDisk>, nets: List<VmNet>): List<String> {
        val props = PropertyString.parse(boot, "legacy")
        props["order"]?.let { order -> return order.split(';').map { it.trim() }.filter { it.isNotEmpty() } }
        val legacy = props["legacy"] ?: "cdn"
        return legacy.flatMap { letter ->
            when (letter) {
                'c' -> listOfNotNull(bootdisk ?: disks.firstOrNull { it.kind == VmDisk.Kind.DISK }?.key)
                'd' -> listOfNotNull(disks.firstOrNull { it.kind == VmDisk.Kind.CDROM }?.key)
                'n' -> nets.map { it.key }
                else -> emptyList()
            }
        }.distinct()
    }

    fun value(order: List<String>): String = "order=" + order.joinToString(";")
}

/** The Processor and Memory form of a VM. */
data class VmCpuMemory(
    val sockets: Int,
    val cores: Int,
    val cpuType: String?,
    val memoryMiB: Int,
    val ballooning: Boolean,
    val minMemoryMiB: Int,
) {
    enum class Problem { SOCKETS, CORES, TOO_MANY_CPUS, MEMORY, MIN_MEMORY }

    /** [hostCpus]: the node's CPU threads; Proxmox refuses more vCPUs than that. */
    fun problem(hostCpus: Int?): Problem? = when {
        sockets !in 1..MAX_SOCKETS -> Problem.SOCKETS
        cores !in 1..MAX_CORES -> Problem.CORES
        hostCpus != null && hostCpus > 0 && sockets * cores > hostCpus -> Problem.TOO_MANY_CPUS
        memoryMiB < MIN_MEMORY_MIB -> Problem.MEMORY
        ballooning && minMemoryMiB !in 0..memoryMiB -> Problem.MIN_MEMORY
        else -> null
    }

    /** The `PUT …/config` form, or an empty map if nothing changed. */
    fun changes(old: VmHardware): Map<String, String> {
        val set = LinkedHashMap<String, String>()
        val delete = ArrayList<String>()
        if (sockets != old.sockets) set["sockets"] = sockets.toString()
        if (cores != old.cores) set["cores"] = cores.toString()
        if (cpuType != old.cpuType) {
            // Keep flags and other CPU settings; only the type changes.
            val cpu = if (cpuType == null) old.cpu.with("cputype", null) else old.cpu.withFirst("cputype", cpuType, implicit = true)
            if (cpu.toString().isEmpty()) delete += "cpu" else set["cpu"] = cpu.toString()
        }
        if (memoryMiB != old.memoryMiB) set["memory"] = memoryMiB.toString()
        val balloon = when {
            !ballooning -> 0
            minMemoryMiB == memoryMiB -> null
            else -> minMemoryMiB
        }
        val oldBalloon = old.balloonMiB?.takeUnless { it == old.memoryMiB }
        if (balloon != oldBalloon) {
            if (balloon == null) delete += "balloon" else set["balloon"] = balloon.toString()
        }
        return finish(set, delete, old.digest)
    }

    companion object {
        const val MAX_SOCKETS = 4
        const val MAX_CORES = 512
        const val MIN_MEMORY_MIB = 16

        fun from(hw: VmHardware) = VmCpuMemory(
            sockets = hw.sockets,
            cores = hw.cores,
            cpuType = hw.cpuType,
            memoryMiB = hw.memoryMiB,
            ballooning = hw.balloonMiB != 0,
            minMemoryMiB = hw.balloonMiB?.takeIf { it > 0 } ?: hw.memoryMiB,
        )
    }
}

/** A new VM hard disk, allocated on [storage]. */
data class NewVmDisk(
    val bus: DiskBus = DiskBus.SCSI,
    val storage: String = "",
    val sizeGiB: Int = 32,
    val iothread: Boolean = true,
    val discard: Boolean = true,
    val ssd: Boolean = false,
) {
    enum class Problem { STORAGE, SIZE, NO_SLOT }

    fun problem(hw: VmHardware, freeBytes: Long?): Problem? = when {
        storage.isBlank() -> Problem.STORAGE
        sizeGiB < 1 || (freeBytes != null && sizeGiB.toLong() * DiskSize.GIB > freeBytes) -> Problem.SIZE
        hw.freeSlot(bus) == null -> Problem.NO_SLOT
        else -> null
    }

    /** The config form: `scsi1=local-lvm:32,iothread=1,discard=on`. */
    fun params(hw: VmHardware): Map<String, String> {
        val key = hw.freeSlot(bus) ?: return emptyMap()
        val value = PropertyString.EMPTY.withFirst("file", "$storage:$sizeGiB", implicit = true).with(
            "iothread" to "1".takeIf { iothread && hw.supportsIothread(bus) },
            "discard" to "on".takeIf { discard },
            "ssd" to "1".takeIf { ssd && bus.supportsSsd },
        )
        return finish(linkedMapOf(key to value.toString()), emptyList(), hw.digest)
    }
}

/** Disk operations that are not plain config changes. */
object VmDiskOps {
    /** `PUT …/qemu/{vmid}/resize`: Proxmox can only grow disks. */
    fun resize(disk: VmDisk, addGiB: Int, digest: String?): Map<String, String> =
        finish(linkedMapOf("disk" to disk.key, "size" to "+${addGiB}G"), emptyList(), digest)

    /** `POST …/qemu/{vmid}/move_disk`; [deleteSource] removes the old copy (otherwise it stays as `unusedN`). */
    fun move(disk: VmDisk, storage: String, deleteSource: Boolean, digest: String?): Map<String, String> =
        finish(linkedMapOf("disk" to disk.key, "storage" to storage, "delete" to if (deleteSource) "1" else "0"), emptyList(), digest)

    /** Detaching keeps the volume as `unusedN`; removing an unused volume destroys it. */
    fun remove(disk: VmDisk, digest: String?): Map<String, String> = finish(LinkedHashMap(), listOf(disk.key), digest)

    /** Attaches an unused volume to a free slot on [bus]. */
    fun attach(disk: VmDisk, bus: DiskBus, hw: VmHardware): Map<String, String> {
        val key = hw.freeSlot(bus) ?: return emptyMap()
        return finish(linkedMapOf(key to disk.volume), emptyList(), hw.digest)
    }

    /** Puts an ISO in a CD/DVD drive ([isoVolume] like `local:iso/debian.iso`), or ejects it (null). */
    fun insert(drive: VmDisk, isoVolume: String?, digest: String?): Map<String, String> {
        val value = drive.props.withFirst("file", isoVolume ?: "none", implicit = true).with("media" to "cdrom", "size" to null)
        return finish(linkedMapOf(drive.key to value.toString()), emptyList(), digest)
    }

    /** A new CD/DVD drive on the first free IDE (or SATA) slot. */
    fun addDrive(hw: VmHardware, isoVolume: String?): Map<String, String> {
        val key = hw.freeSlot(DiskBus.IDE) ?: hw.freeSlot(DiskBus.SATA) ?: return emptyMap()
        return finish(linkedMapOf(key to "${isoVolume ?: "none"},media=cdrom"), emptyList(), hw.digest)
    }

    fun bootOrder(order: List<String>, digest: String?): Map<String, String> =
        finish(linkedMapOf("boot" to BootOrder.value(order)), emptyList(), digest)
}

/** The form of a VM network device. */
data class VmNetSettings(
    val model: String = "virtio",
    val bridge: String = "vmbr0",
    val tag: Int? = null,
    val firewall: Boolean = true,
    val linkDown: Boolean = false,
    val rateMBps: Double? = null,
) {
    enum class Problem { BRIDGE, TAG, RATE }

    fun problem(): Problem? = when {
        bridge.isBlank() -> Problem.BRIDGE
        tag != null && tag !in VLAN_TAGS -> Problem.TAG
        rateMBps != null && rateMBps <= 0.0 -> Problem.RATE
        else -> null
    }

    /** The `netN` value; the MAC address and settings the app doesn't edit are kept. */
    fun value(old: VmNet?): String {
        val mac = old?.mac
        var props = old?.props ?: PropertyString.EMPTY
        VmNet.MODELS.forEach { props = props.with(it, null) }
        old?.model?.let { props = props.with(it, null) }
        props = if (mac != null) props.withFirst(model, mac) else props.withFirst(model, model, implicit = true)
        return props.with(
            "bridge" to bridge.trim(),
            "tag" to tag?.toString(),
            "firewall" to "1".takeIf { firewall },
            "link_down" to "1".takeIf { linkDown },
            "rate" to rateMBps?.let { formatRate(it) },
        ).toString()
    }

    /** `PUT …/config` to change [old] (or add a device when null) on [hw]. */
    fun params(hw: VmHardware, old: VmNet?): Map<String, String> {
        val key = old?.key ?: hw.freeNetKey() ?: return emptyMap()
        val value = value(old)
        if (old != null && value == old.props.toString()) return emptyMap()
        return finish(linkedMapOf(key to value), emptyList(), hw.digest)
    }

    companion object {
        val VLAN_TAGS = 1..4094

        fun from(net: VmNet) = VmNetSettings(net.model, net.bridge.orEmpty(), net.tag, net.firewall, net.linkDown, net.rateMBps)

        private fun formatRate(value: Double): String =
            if (value == floor(value)) value.toLong().toString() else value.toString()
    }
}

/** Adds `delete` and `digest` to a config form; empty if there is nothing to change. */
internal fun finish(set: LinkedHashMap<String, String>, delete: List<String>, digest: String?): Map<String, String> {
    if (set.isEmpty() && delete.isEmpty()) return emptyMap()
    if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
    digest?.let { set["digest"] = it }
    return set
}

internal fun String?.isOn(): Boolean = when (this?.trim()?.lowercase()) {
    "1", "on", "yes", "true" -> true
    else -> false
}
