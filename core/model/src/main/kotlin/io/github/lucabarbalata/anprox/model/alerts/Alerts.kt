package io.github.lucabarbalata.anprox.model.alerts

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Which background alerts the user wants (questionnaire Q26/Q27). Checks run every
 * [intervalMinutes] (Android allows 15 at the least) while the app is closed.
 */
@Serializable
data class AlertSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 15,
    /** Nodes offline, quorum lost, guests that stopped without anyone stopping them. */
    val down: Boolean = true,
    /** Tasks and backups that ended with an error. */
    val failures: Boolean = true,
    /** CPU, memory and storage above these percentages; Ceph warnings and errors. */
    val thresholds: Boolean = true,
    val cpuPercent: Int = 90,
    val memoryPercent: Int = 90,
    val storagePercent: Int = 85,
) {
    companion object {
        val INTERVALS = listOf(15, 30, 60, 180)
        val PERCENT_RANGE = 50..99
    }
}

/** A task that ended with an error. */
data class FinishedTask(val upid: String, val type: String, val id: String?, val node: String, val endtime: Long, val status: String) {
    val ok: Boolean get() = status == "OK" || status.startsWith("WARNINGS")
}

/** A guest seen running, by VMID. */
data class GuestSeen(val vmid: Int, val name: String, val node: String)

/**
 * What one check saw on one server. Fractions are 0..1; null when unknown. A Backup Server has
 * no nodes or guests, only datastores ([storages]) and tasks.
 */
data class Observation(
    val time: Long,
    /** Node → online. */
    val nodes: Map<String, Boolean> = emptyMap(),
    val runningGuests: Map<Int, GuestSeen> = emptyMap(),
    /** All guests that exist (running or not), to tell "stopped" from "gone". */
    val guestIds: Set<Int> = emptySet(),
    /** Null for a single node or when the cluster status couldn't be read. */
    val quorate: Boolean? = null,
    val nodeCpu: Map<String, Double> = emptyMap(),
    val nodeMemory: Map<String, Double> = emptyMap(),
    /** `node/storage` (or a datastore name) → used fraction. */
    val storages: Map<String, Double> = emptyMap(),
    /** Tasks that have ended, most recent first; the engine keeps those after the last check. */
    val tasks: List<FinishedTask> = emptyList(),
    /** Guests with a task still running (a shutdown, a backup in stop mode): not "stopped" yet. */
    val busyGuests: Set<Int> = emptySet(),
    /** `HEALTH_OK`, `HEALTH_WARN`, `HEALTH_ERR`, or null without Ceph. */
    val ceph: String? = null,
)

/** What the previous check remembered, so each problem is reported once. */
@Serializable
data class AlertMemory(
    val time: Long,
    val offlineNodes: Set<String> = emptySet(),
    val runningGuests: Map<Int, String> = emptyMap(),
    val guestNodes: Map<Int, String> = emptyMap(),
    val quorate: Boolean? = null,
    /** Threshold keys that are above their limit (`cpu:pve1`, `storage:pve1/local`). */
    val hot: Set<String> = emptySet(),
    val ceph: String? = null,
    /**
     * End time of the newest task seen, in the server's clock: the next check reports tasks that
     * ended after it, so a phone clock that is off doesn't skip or repeat any.
     */
    val lastTaskEnd: Long? = null,
)

/** What the latest background check found, for the alert settings screen. */
@Serializable
data class AlertRun(
    val atMillis: Long,
    /** Servers that answered. */
    val checked: Int,
    val alerts: Int,
    val unreachable: List<String> = emptyList(),
    val signInNeeded: List<String> = emptyList(),
)

/** One thing to tell the user. [subject] is a node, guest, storage or task; [detail] is extra text. */
data class Alert(val kind: Kind, val subject: String, val detail: String? = null, val percent: Int? = null) {
    enum class Kind { NODE_DOWN, QUORUM_LOST, GUEST_STOPPED, TASK_FAILED, BACKUP_FAILED, CPU_HIGH, MEMORY_HIGH, STORAGE_HIGH, CEPH_WARN, CEPH_ERR }

    /** Stable per problem, so a notification replaces the previous one about the same thing. */
    val key: String get() = "$kind:$subject"
}

/**
 * Turns two checks into alerts. Pure and deterministic, so it is tested on the JVM; the worker
 * only fetches, calls [evaluate] and shows notifications.
 *
 * - Down: a node that went offline, quorum that was lost, a running guest that stopped without
 *   a stop, shutdown, migration or destroy task for it since the last check (or one still running).
 * - Failures: tasks that ended with an error since the last check (backups separately).
 * - Thresholds: crossing a limit; it must drop [HYSTERESIS] points below before it can alert
 *   again. Ceph: a change to WARN or ERR.
 *
 * The first check (no memory) reports problems that exist right now, but no old tasks.
 */
object AlertEngine {
    /** Percentage points a value must fall below its limit to count as "back to normal". */
    const val HYSTERESIS = 5

    private val INTENTIONAL = Regex("^(qm|vz)(stop|shutdown|migrate|destroy|suspend|restore)$|^hamigrate$|^hastop$")
    private val BACKUP_TYPES = setOf("vzdump", "backup", "verify", "verificationjob", "syncjob", "prune", "prunejob")

    fun evaluate(previous: AlertMemory?, now: Observation, settings: AlertSettings): Pair<List<Alert>, AlertMemory> {
        val alerts = ArrayList<Alert>()
        val offline = now.nodes.filterValues { !it }.keys
        val sinceLast = previous?.let { it.lastTaskEnd ?: it.time }

        if (settings.down) {
            offline.filter { previous == null || it !in previous.offlineNodes }.sorted()
                .forEach { alerts += Alert(Alert.Kind.NODE_DOWN, it) }
            if (now.quorate == false && (previous == null || previous.quorate != false)) {
                alerts += Alert(Alert.Kind.QUORUM_LOST, "cluster")
            }
            if (previous != null && sinceLast != null) {
                val intended = now.tasks.filter { it.endtime >= sinceLast && INTENTIONAL.matches(it.type) }.mapNotNull { it.id?.toIntOrNull() }.toSet() + now.busyGuests
                previous.runningGuests.forEach { (vmid, name) ->
                    val stillThere = vmid in now.guestIds
                    val stopped = vmid !in now.runningGuests
                    // A guest on a node that went offline is covered by the node alert.
                    val onOfflineNode = previous.guestNodes[vmid] in offline
                    if (stillThere && stopped && vmid !in intended && !onOfflineNode) {
                        alerts += Alert(Alert.Kind.GUEST_STOPPED, name, detail = vmid.toString())
                    }
                }
            }
        }

        if (settings.failures && sinceLast != null) {
            now.tasks.filter { it.endtime > sinceLast && !it.ok }.sortedBy { it.endtime }.forEach { t ->
                val kind = if (t.type in BACKUP_TYPES) Alert.Kind.BACKUP_FAILED else Alert.Kind.TASK_FAILED
                alerts += Alert(kind, t.upid, detail = t.status)
            }
        }

        val hot = HashSet<String>()
        if (settings.thresholds) {
            fun check(kind: Alert.Kind, prefix: String, values: Map<String, Double>, limit: Int) {
                values.forEach { (name, fraction) ->
                    val percent = (fraction * 100).roundToInt()
                    val key = "$prefix:$name"
                    val wasHot = previous != null && key in previous.hot
                    when {
                        percent >= limit -> {
                            hot += key
                            if (!wasHot) alerts += Alert(kind, name, percent = percent)
                        }
                        // Still close to the limit: stays "hot" without a new alert.
                        wasHot && percent > limit - HYSTERESIS -> hot += key
                    }
                }
            }
            check(Alert.Kind.CPU_HIGH, "cpu", now.nodeCpu.filterKeys { it !in offline }, settings.cpuPercent)
            check(Alert.Kind.MEMORY_HIGH, "mem", now.nodeMemory.filterKeys { it !in offline }, settings.memoryPercent)
            check(Alert.Kind.STORAGE_HIGH, "storage", now.storages, settings.storagePercent)
            val ceph = now.ceph
            if (ceph != null && ceph != previous?.ceph) {
                when (ceph) {
                    "HEALTH_ERR" -> alerts += Alert(Alert.Kind.CEPH_ERR, "ceph")
                    "HEALTH_WARN" -> if (previous?.ceph != "HEALTH_ERR") alerts += Alert(Alert.Kind.CEPH_WARN, "ceph")
                }
            }
        }

        val memory = AlertMemory(
            time = now.time,
            offlineNodes = offline,
            runningGuests = now.runningGuests.mapValues { it.value.name },
            guestNodes = now.runningGuests.mapValues { it.value.node },
            quorate = now.quorate,
            hot = hot,
            ceph = now.ceph,
            lastTaskEnd = now.tasks.maxOfOrNull { it.endtime } ?: previous?.lastTaskEnd,
        )
        return alerts to memory
    }
}
