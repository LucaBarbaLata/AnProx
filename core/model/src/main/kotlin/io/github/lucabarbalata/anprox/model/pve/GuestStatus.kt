package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /nodes/{node}/{qemu|lxc}/{vmid}/status/current`. Fields shared by VMs and containers. */
@Serializable
data class GuestStatus(
    /** `running`, `stopped`, `paused`. */
    val status: String = "unknown",
    /** VMs only: the QEMU monitor state, e.g. `running`, `paused`, `prelaunch`, `suspended`. */
    val qmpstatus: String? = null,
    val name: String? = null,
    val cpus: Double? = null,
    val cpu: Double? = null,
    val mem: Long? = null,
    val maxmem: Long? = null,
    val disk: Long? = null,
    val maxdisk: Long? = null,
    val uptime: Long? = null,
    val netin: Long? = null,
    val netout: Long? = null,
    val diskread: Long? = null,
    val diskwrite: Long? = null,
    val pid: Long? = null,
    val lock: String? = null,
    val tags: String? = null,
    /** VMs only: `1` if the QEMU guest agent is enabled in the config. */
    @Serializable(with = PveBooleanSerializer::class)
    val agent: Boolean = false,
    @Serializable(with = PveBooleanSerializer::class)
    val template: Boolean = false,
    val ha: HaStatus? = null,
    @SerialName("running-qemu") val runningQemu: String? = null,
    @SerialName("running-machine") val runningMachine: String? = null,
) {
    val isRunning: Boolean get() = status == "running"
    val isPaused: Boolean get() = qmpstatus == "paused" || status == "paused"
    val isSuspended: Boolean get() = qmpstatus == "suspended"
    val memFraction: Double? get() = fraction(mem, maxmem)
    val diskFraction: Double? get() = fraction(disk, maxdisk)
}

@Serializable
data class HaStatus(
    @Serializable(with = PveBooleanSerializer::class)
    val managed: Boolean = false,
    val state: String? = null,
    val group: String? = null,
)

/**
 * Power operations on a guest. [apiPath] is appended to `/nodes/{node}/{type}/{vmid}/status/`.
 * Each returns a task UPID.
 */
enum class PowerAction(val apiPath: String, val danger: DangerLevel, val vmOnly: Boolean = false) {
    START("start", DangerLevel.SAFE),
    SHUTDOWN("shutdown", DangerLevel.CONFIRM),
    REBOOT("reboot", DangerLevel.CONFIRM),
    STOP("stop", DangerLevel.CONFIRM),
    RESET("reset", DangerLevel.CONFIRM, vmOnly = true),
    SUSPEND("suspend", DangerLevel.CONFIRM),
    RESUME("resume", DangerLevel.SAFE),
    ;

    fun appliesTo(type: GuestType): Boolean = !vmOnly || type == GuestType.QEMU
}

/**
 * Tiered confirmation (questionnaire Q12):
 * - SAFE: no confirmation.
 * - CONFIRM: a simple confirm dialog (stop, reboot, …).
 * - TYPE_TO_CONFIRM: the user types the guest name/ID; also requires biometric re-auth when
 *   the app lock is enabled (Q11). Used for delete/destroy/purge/rollback.
 */
enum class DangerLevel { SAFE, CONFIRM, TYPE_TO_CONFIRM }
