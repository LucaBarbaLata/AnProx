package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * A task ("worker") as listed by `GET /cluster/tasks` (PVE) or `GET /nodes/{node}/tasks`
 * (PVE and PBS). PBS calls some fields `worker_type`/`worker_id`; [JsonNames] accepts both.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskSummary(
    val upid: String,
    val node: String = "",
    @JsonNames("worker_type") val type: String = "",
    @JsonNames("worker_id") val id: String? = null,
    val user: String = "",
    val starttime: Long = 0,
    val endtime: Long? = null,
    /** `OK`, `WARNINGS: n`, or an error message. Missing while the task is still running. */
    val status: String? = null,
) {
    val state: TaskState
        get() = when {
            status == null && endtime == null -> TaskState.RUNNING
            status == "OK" -> TaskState.OK
            status?.startsWith("WARNINGS") == true -> TaskState.WARNING
            else -> TaskState.FAILED
        }
}

enum class TaskState { RUNNING, OK, WARNING, FAILED }

/** `GET /nodes/{node}/tasks/{upid}/status`. */
@Serializable
data class TaskStatus(
    /** `running` or `stopped`. */
    val status: String,
    /** Set once stopped: `OK`, `WARNINGS: n`, or the error. */
    val exitstatus: String? = null,
    val upid: String? = null,
    val node: String? = null,
    val type: String? = null,
    val id: String? = null,
    val user: String? = null,
    val starttime: Long? = null,
) {
    val isRunning: Boolean get() = status == "running"
    val succeeded: Boolean get() = !isRunning && (exitstatus == "OK" || exitstatus?.startsWith("WARNINGS") == true)

    companion object {
        /**
         * Proxmox gave up waiting for the guest's config lock: another operation still holds it.
         * After a stop or a rollback with RAM, `qm cleanup` keeps it for some 15–25 seconds on its
         * own, so the same action usually works when tried again a little later.
         */
        fun isLockTimeout(exitstatus: String?): Boolean =
            exitstatus != null && "can't lock file" in exitstatus && "timeout" in exitstatus
    }
}

/** One line of `GET /nodes/{node}/tasks/{upid}/log`. */
@Serializable
data class TaskLogLine(val n: Int, val t: String)
