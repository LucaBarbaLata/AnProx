package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.Serializable

/**
 * One row of `GET /cluster/resources` — the endpoint the web UI's tree is built from.
 * A single call returns every node, VM, container, storage, pool and SDN zone in the cluster,
 * which makes it the backbone of the dashboard and resource list.
 */
@Serializable
data class ClusterResource(
    /** Unique id such as `qemu/100`, `lxc/101`, `node/pve1`, `storage/pve1/local-zfs`. */
    val id: String,
    /** `qemu`, `lxc`, `node`, `storage`, `pool`, `sdn`, … */
    val type: String,
    val node: String? = null,
    val vmid: Int? = null,
    val name: String? = null,
    /** `running`, `stopped`, `paused` (guests); `online`/`offline`/`unknown` (nodes); `available` (storage). */
    val status: String? = null,
    val cpu: Double? = null,
    val maxcpu: Double? = null,
    val mem: Long? = null,
    val maxmem: Long? = null,
    val disk: Long? = null,
    val maxdisk: Long? = null,
    val uptime: Long? = null,
    val netin: Long? = null,
    val netout: Long? = null,
    val diskread: Long? = null,
    val diskwrite: Long? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val template: Boolean = false,
    /** Semicolon- or comma-separated tags. */
    val tags: String? = null,
    val pool: String? = null,
    val lock: String? = null,
    val hastate: String? = null,
    /** Storage only. */
    val storage: String? = null,
    val plugintype: String? = null,
    val content: String? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val shared: Boolean = false,
) {
    val resourceType: ResourceType get() = ResourceType.fromApi(type)
    val isGuest: Boolean get() = resourceType == ResourceType.QEMU || resourceType == ResourceType.LXC
    val isRunning: Boolean get() = status == "running" || status == "online"

    val tagList: List<String>
        get() = tags.orEmpty().split(';', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    /** Best human label: "100 · web" for guests, the node/storage name otherwise. */
    val displayName: String
        get() = when {
            isGuest && vmid != null -> if (name.isNullOrBlank()) "$vmid" else "$vmid · $name"
            resourceType == ResourceType.STORAGE -> storage ?: id
            else -> name ?: node ?: id
        }

    val cpuFraction: Double? get() = cpu
    val memFraction: Double? get() = fraction(mem, maxmem)
    val diskFraction: Double? get() = fraction(disk, maxdisk)
}

internal fun fraction(used: Long?, total: Long?): Double? =
    if (used == null || total == null || total <= 0L) null else (used.toDouble() / total).coerceIn(0.0, 1.0)

enum class ResourceType(val apiName: String) {
    NODE("node"),
    QEMU("qemu"),
    LXC("lxc"),
    STORAGE("storage"),
    POOL("pool"),
    SDN("sdn"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromApi(value: String): ResourceType = entries.firstOrNull { it.apiName == value } ?: UNKNOWN
    }
}

/** The two guest kinds; their API paths differ only by this segment. */
enum class GuestType(val apiName: String, val label: String) {
    QEMU("qemu", "VM"),
    LXC("lxc", "CT"),
    ;

    companion object {
        fun fromApi(value: String): GuestType? = entries.firstOrNull { it.apiName == value }
    }
}
