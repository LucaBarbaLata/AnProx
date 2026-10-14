package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A clone of a VM or container (`POST …/{qemu|lxc}/{vmid}/clone`).
 *
 * @property targetNode null or the source node: clone on the same node. Another node needs the
 *   source on shared storage; Proxmox says so if it isn't.
 * @property full a full copy. Linked clones (false) need a template and share its disks.
 * @property storage where a full clone's disks go; null keeps the source's storage.
 */
data class CloneRequest(
    val newId: Int,
    val name: String,
    val targetNode: String? = null,
    val full: Boolean = true,
    val storage: String? = null,
) {
    enum class Problem { ID, NAME, LINKED_NEEDS_TEMPLATE }

    fun problem(isTemplate: Boolean): Problem? = when {
        newId !in VMID_RANGE -> Problem.ID
        name.isNotBlank() && !GuestOptions.isValidName(name.trim()) -> Problem.NAME
        !full && !isTemplate -> Problem.LINKED_NEEDS_TEMPLATE
        else -> null
    }

    /** The form for `POST …/clone`. */
    fun params(type: GuestType, sourceNode: String): Map<String, String> = buildMap {
        put("newid", newId.toString())
        name.trim().takeIf { it.isNotEmpty() }?.let { put(if (type == GuestType.QEMU) "name" else "hostname", it) }
        targetNode?.takeIf { it != sourceNode }?.let { put("target", it) }
        put("full", if (full) "1" else "0")
        if (full) storage?.let { put("storage", it) }
    }

    companion object {
        /** Proxmox's VMID range. */
        val VMID_RANGE = 100..999_999_999

        /** `web` → `web-clone`; empty if the source name isn't a valid name to build on. */
        fun suggestName(source: String): String {
            val base = source.trim().takeIf { GuestOptions.isValidName(it) } ?: return ""
            return "$base-clone"
        }
    }
}

/**
 * `GET /nodes/{node}/qemu/{vmid}/migrate`: Proxmox's own pre-flight check. Containers have no such
 * endpoint; they are checked by Proxmox when the migration starts.
 */
@Serializable
data class MigrationCheck(
    @Serializable(with = PveBooleanSerializer::class)
    val running: Boolean = false,
    /** Only reported for stopped VMs. */
    @SerialName("allowed_nodes") val allowedNodes: List<String>? = null,
    @SerialName("not_allowed_nodes") val notAllowedNodes: Map<String, NodeRestriction> = emptyMap(),
    @SerialName("local_disks") val localDisks: List<LocalDisk> = emptyList(),
    /** Host devices (`hostpci0`, `usb0`, …) that tie the VM to its node. */
    @SerialName("local_resources") val localResources: List<String> = emptyList(),
) {
    /** Disks that have to be copied, so live migration needs `with-local-disks`. */
    val disksToCopy: List<LocalDisk> get() = localDisks.filterNot { it.unused || it.cdrom }
}

@Serializable
data class NodeRestriction(
    @SerialName("unavailable_storages") val unavailableStorages: List<String> = emptyList(),
    @SerialName("unavailable-resources") val unavailableResources: List<String> = emptyList(),
) {
    val reasons: List<String> get() = unavailableStorages + unavailableResources
}

@Serializable
data class LocalDisk(
    val volid: String,
    val drivename: String? = null,
    val size: Long? = null,
    @SerialName("is_unused")
    @Serializable(with = PveBooleanSerializer::class)
    val unused: Boolean = false,
    @Serializable(with = PveBooleanSerializer::class)
    val cdrom: Boolean = false,
)

/** A node the guest could move to, and if not, why. */
data class MigrationTarget(val node: String, val online: Boolean, val missing: List<String> = emptyList()) {
    val allowed: Boolean get() = online && missing.isEmpty()
}

/** How to move a guest to another node (`POST …/{qemu|lxc}/{vmid}/migrate`). */
object MigrationPlan {

    /**
     * Every other node of the cluster: usable ones first, then the ones Proxmox would refuse
     * (offline, or missing a storage or device the guest needs).
     */
    fun targets(current: String, nodes: Map<String, Boolean>, check: MigrationCheck?): List<MigrationTarget> =
        nodes.filterKeys { it != current }
            .map { (node, online) -> MigrationTarget(node, online, check?.notAllowedNodes?.get(node)?.reasons.orEmpty()) }
            .sortedWith(compareBy({ !it.allowed }, { it.node }))

    /**
     * A running VM moves live (`online`), copying local disks if it has any. A running container
     * can't move live: it is shut down, moved and started again (`restart`), waiting up to
     * [shutdownTimeoutSeconds] for it to stop. Stopped guests just move.
     */
    fun params(type: GuestType, target: String, running: Boolean, check: MigrationCheck?, shutdownTimeoutSeconds: Int = 180): Map<String, String> =
        buildMap {
            put("target", target)
            if (running) {
                when (type) {
                    GuestType.QEMU -> {
                        put("online", "1")
                        if (check?.disksToCopy?.isNotEmpty() == true) put("with-local-disks", "1")
                    }
                    GuestType.LXC -> {
                        put("restart", "1")
                        put("timeout", shutdownTimeoutSeconds.toString())
                    }
                }
            }
        }
}
