package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry of `GET /nodes/{node}/storage` (with `content=backup`: where backups can go). */
@Serializable
data class NodeStorage(
    val storage: String,
    /** `dir`, `nfs`, `pbs`, `zfspool`, … */
    val type: String = "",
    /** Comma-separated content types, e.g. `iso,vztmpl,backup`. */
    val content: String = "",
    @Serializable(with = PveBooleanSerializer::class)
    val active: Boolean = true,
    @Serializable(with = PveBooleanSerializer::class)
    val enabled: Boolean = true,
    @Serializable(with = PveBooleanSerializer::class)
    val shared: Boolean = false,
    val total: Long? = null,
    val used: Long? = null,
    val avail: Long? = null,
) {
    /** A Proxmox Backup Server datastore: deduplicated, incremental, verifiable. */
    val isPbs: Boolean get() = type == "pbs"
}

/** One item of `GET /nodes/{node}/storage/{storage}/content` (used here for backups). */
@Serializable
data class StorageContentItem(
    /** `storage:path`, e.g. `pbs:backup/vm/100/2026-10-13T02:00:00Z`. */
    val volid: String,
    val content: String = "",
    /** Creation time, Unix seconds. */
    val ctime: Long? = null,
    val size: Long? = null,
    /** `pbs-vm`, `pbs-ct`, `vma.zst`, `tar.zst`, … */
    val format: String? = null,
    val notes: String? = null,
    /** Protected backups can't be pruned or deleted. */
    @SerialName("protected")
    @Serializable(with = PveBooleanSerializer::class)
    val isProtected: Boolean = false,
    val vmid: Int? = null,
    /** `qemu` or `lxc` (PVE 7 and later). */
    val subtype: String? = null,
    /** Backup Server only: the last verification. */
    val verification: BackupVerification? = null,
) {
    val storage: String get() = volid.substringBefore(':')

    /** From [subtype], or guessed from the volume name on older servers. */
    val guestType: GuestType?
        get() = subtype?.let(GuestType::fromApi) ?: when {
            "vzdump-qemu-" in volid || "/vm/" in volid -> GuestType.QEMU
            "vzdump-lxc-" in volid || "vzdump-openvz-" in volid || "/ct/" in volid -> GuestType.LXC
            else -> null
        }
}

@Serializable
data class BackupVerification(
    /** `ok` or `failed`. */
    val state: String? = null,
    val upid: String? = null,
) {
    val ok: Boolean get() = state == "ok"
}

/** The `mode` of `POST /nodes/{node}/vzdump`: how consistent the backup is versus how much downtime. */
enum class BackupMode(val apiName: String) {
    /** Live backup, no downtime (uses the QEMU guest agent / storage snapshots when available). */
    SNAPSHOT("snapshot"),

    /** Pauses the guest briefly for a consistent copy. */
    SUSPEND("suspend"),

    /** Shuts the guest down for the most consistent backup, then starts it again. */
    STOP("stop"),
}
