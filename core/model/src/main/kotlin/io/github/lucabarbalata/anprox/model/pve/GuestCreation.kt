package io.github.lucabarbalata.anprox.model.pve

/** What a new VM will run. It sets Proxmox's `ostype` and the defaults that suit the system. */
enum class GuestOs(val ostype: String) {
    LINUX("l26"),
    WINDOWS("win11"),
    OTHER("other"),
}

/**
 * A new VM (questionnaire Q17: a short flow, with "Advanced" for the rest). The defaults follow
 * the Proxmox VE 8 wizard. Windows has no VirtIO drivers out of the box, so it gets a SATA disk
 * and an Intel e1000e network card, plus UEFI and a TPM (Windows 11 needs both), unless
 * Advanced says otherwise.
 *
 * @property iso volume ID of an ISO image for the CD/DVD drive (`local:iso/…`), or null for none.
 * @property bus, netModel, uefi: null means "the default for [os]".
 */
data class NewVm(
    val vmid: Int,
    val name: String = "",
    val os: GuestOs = GuestOs.LINUX,
    val iso: String? = null,
    val storage: String = "",
    val diskGiB: Int = 32,
    val cores: Int = 2,
    val memoryMiB: Int = 2048,
    val bridge: String = "vmbr0",
    val start: Boolean = false,
    // Advanced
    val sockets: Int = 1,
    val cpuType: String = "x86-64-v2-AES",
    val bus: DiskBus? = null,
    val netModel: String? = null,
    val vlan: Int? = null,
    val firewall: Boolean = true,
    val ballooning: Boolean = true,
    val discard: Boolean = true,
    val agent: Boolean = true,
    val onBoot: Boolean = false,
    val uefi: Boolean? = null,
) {
    val diskBus: DiskBus get() = bus ?: if (os == GuestOs.WINDOWS) DiskBus.SATA else DiskBus.SCSI
    val nic: String get() = netModel ?: if (os == GuestOs.WINDOWS) "e1000e" else "virtio"
    val usesUefi: Boolean get() = uefi ?: (os == GuestOs.WINDOWS)

    enum class Problem { ID, ID_TAKEN, NAME, STORAGE, SIZE, SOCKETS, CORES, TOO_MANY_CPUS, MEMORY, BRIDGE, VLAN }

    /** [takenIds]: VMIDs in use in the cluster; [hostCpus]: the node's CPU threads; [freeBytes]: free space on [storage]. */
    fun problem(takenIds: Set<Int>, hostCpus: Int?, freeBytes: Long?): Problem? = when {
        vmid !in CloneRequest.VMID_RANGE -> Problem.ID
        vmid in takenIds -> Problem.ID_TAKEN
        name.isNotBlank() && !GuestOptions.isValidName(name.trim()) -> Problem.NAME
        storage.isBlank() -> Problem.STORAGE
        diskGiB < 1 || (freeBytes != null && diskGiB.toLong() * DiskSize.GIB > freeBytes) -> Problem.SIZE
        sockets !in 1..VmCpuMemory.MAX_SOCKETS -> Problem.SOCKETS
        cores !in 1..VmCpuMemory.MAX_CORES -> Problem.CORES
        hostCpus != null && hostCpus > 0 && sockets * cores > hostCpus -> Problem.TOO_MANY_CPUS
        memoryMiB < VmCpuMemory.MIN_MEMORY_MIB -> Problem.MEMORY
        bridge.isBlank() -> Problem.BRIDGE
        vlan != null && vlan !in VmNetSettings.VLAN_TAGS -> Problem.VLAN
        else -> null
    }

    /** The form for `POST /nodes/{node}/qemu`. */
    fun params(): Map<String, String> = buildMap {
        val disk = "${diskBus.prefix}0"
        put("vmid", vmid.toString())
        name.trim().takeIf { it.isNotEmpty() }?.let { put("name", it) }
        put("ostype", os.ostype)
        put("sockets", sockets.toString())
        put("cores", cores.toString())
        put("cpu", cpuType)
        put("memory", memoryMiB.toString())
        if (!ballooning) put("balloon", "0")
        put("scsihw", "virtio-scsi-single")
        put(
            disk,
            PropertyString.EMPTY.withFirst("file", "$storage:$diskGiB", implicit = true).with(
                "iothread" to "1".takeIf { diskBus == DiskBus.SCSI || diskBus == DiskBus.VIRTIO },
                "discard" to "on".takeIf { discard },
            ).toString(),
        )
        put("ide2", "${iso ?: "none"},media=cdrom")
        put(
            "net0",
            PropertyString.EMPTY.withFirst(nic, nic, implicit = true).with(
                "bridge" to bridge.trim(),
                "firewall" to "1".takeIf { firewall },
                "tag" to vlan?.toString(),
            ).toString(),
        )
        put("boot", BootOrder.value(listOf(disk, "ide2", "net0")))
        if (usesUefi) {
            put("bios", "ovmf")
            put("machine", "q35")
            // The size is ignored for EFI disks; Proxmox allocates what the firmware needs.
            put("efidisk0", "$storage:1,efitype=4m,pre-enrolled-keys=1")
        }
        if (os == GuestOs.WINDOWS) put("tpmstate0", "$storage:1,version=v2.0")
        if (agent) put("agent", "1")
        if (onBoot) put("onboot", "1")
        if (start) put("start", "1")
    }
}

/**
 * A new container (questionnaire Q17). Unprivileged with nesting on, like the Proxmox VE 8
 * wizard. It needs a root password, an SSH key, or both.
 *
 * @property template volume ID of a container template (`local:vztmpl/…`).
 * @property sshKeys OpenSSH public keys, one per line.
 */
data class NewContainer(
    val vmid: Int,
    val hostname: String = "",
    val template: String = "",
    val password: String = "",
    val sshKeys: String = "",
    val storage: String = "",
    val diskGiB: Int = 8,
    val cores: Int = 1,
    val memoryMiB: Int = 512,
    val network: CtNetSettings = CtNetSettings(),
    val start: Boolean = false,
    // Advanced
    val swapMiB: Int = 512,
    val unprivileged: Boolean = true,
    val nesting: Boolean = true,
    val onBoot: Boolean = false,
) {
    enum class Problem { ID, ID_TAKEN, HOSTNAME, TEMPLATE, PASSWORD, SSH_KEY, LOGIN, STORAGE, SIZE, CORES, MEMORY, SWAP, NETWORK }

    fun problem(takenIds: Set<Int>, hostCpus: Int?, freeBytes: Long?): Problem? = when {
        vmid !in CloneRequest.VMID_RANGE -> Problem.ID
        vmid in takenIds -> Problem.ID_TAKEN
        hostname.isNotBlank() && !GuestOptions.isValidName(hostname.trim()) -> Problem.HOSTNAME
        template.isBlank() -> Problem.TEMPLATE
        password.isNotEmpty() && password.length < MIN_PASSWORD -> Problem.PASSWORD
        sshKeyLines().any { !isSshKey(it) } -> Problem.SSH_KEY
        password.isEmpty() && sshKeyLines().isEmpty() -> Problem.LOGIN
        storage.isBlank() -> Problem.STORAGE
        diskGiB < 1 || (freeBytes != null && diskGiB.toLong() * DiskSize.GIB > freeBytes) -> Problem.SIZE
        cores < 1 || (hostCpus != null && hostCpus > 0 && cores > hostCpus) -> Problem.CORES
        memoryMiB < VmCpuMemory.MIN_MEMORY_MIB -> Problem.MEMORY
        swapMiB < 0 -> Problem.SWAP
        network.problem(CtHardware(), null) != null -> Problem.NETWORK
        else -> null
    }

    private fun sshKeyLines() = sshKeys.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The form for `POST /nodes/{node}/lxc`. */
    fun params(): Map<String, String> = buildMap {
        put("vmid", vmid.toString())
        hostname.trim().takeIf { it.isNotEmpty() }?.let { put("hostname", it) }
        put("ostemplate", template)
        if (password.isNotEmpty()) put("password", password)
        sshKeyLines().takeIf { it.isNotEmpty() }?.let { put("ssh-public-keys", it.joinToString("\n")) }
        put("rootfs", "$storage:$diskGiB")
        put("cores", cores.toString())
        put("memory", memoryMiB.toString())
        put("swap", swapMiB.toString())
        put("net0", network.value(null))
        put("unprivileged", if (unprivileged) "1" else "0")
        if (nesting) put("features", "nesting=1")
        if (onBoot) put("onboot", "1")
        if (start) put("start", "1")
    }

    companion object {
        /** Proxmox's minimum for the root password. */
        const val MIN_PASSWORD = 5

        private val KEY_TYPES = listOf("ssh-", "ecdsa-", "sk-")

        /** An OpenSSH public key line: type, base64 key, optional comment. */
        fun isSshKey(line: String): Boolean {
            val parts = line.trim().split(Regex("\\s+"))
            return parts.size >= 2 && KEY_TYPES.any { parts[0].startsWith(it) } && parts[1].length >= 16 &&
                parts[1].all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        }

        /** `local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst` → `debian-12-standard_12.7-1_amd64`. */
        fun templateName(volid: String): String =
            volid.substringAfterLast('/').substringAfter(':').replace(Regex("\\.tar(\\.(gz|xz|zst|bz2))?$"), "")
    }
}
