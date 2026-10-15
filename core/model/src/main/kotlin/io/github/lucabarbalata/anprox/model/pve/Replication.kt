package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One row of `GET /cluster/replication` (questionnaire Q21): a guest's local disks copied to
 * another node on a schedule. Read from raw JSON because Proxmox sends numbers and flags either
 * as numbers or as strings.
 */
data class ReplicationJob(
    /** `<guest>-<job number>`, e.g. `100-0`. */
    val id: String,
    val guest: Int,
    val jobNumber: Int,
    val target: String,
    /** Proxmox calendar event; [DEFAULT_SCHEDULE] (every 15 minutes) when not set. */
    val schedule: String,
    /** MB/s; null means no limit. */
    val rate: Double?,
    val comment: String?,
    val enabled: Boolean,
    /** Deleting it is in progress: the next run removes the copies, then the job. */
    val removing: Boolean,
    val digest: String?,
) {
    companion object {
        const val DEFAULT_SCHEDULE = "*/15"

        fun fromJson(o: JsonObject): ReplicationJob {
            fun string(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val id = string("id").orEmpty()
            return ReplicationJob(
                id = id,
                guest = string("guest")?.toIntOrNull() ?: id.substringBefore('-').toIntOrNull() ?: 0,
                jobNumber = string("jobnum")?.toIntOrNull() ?: id.substringAfter('-', "").toIntOrNull() ?: 0,
                target = string("target").orEmpty(),
                schedule = string("schedule") ?: DEFAULT_SCHEDULE,
                rate = string("rate")?.toDoubleOrNull(),
                comment = string("comment"),
                enabled = !string("disable").isOn(),
                removing = string("remove_job") != null,
                digest = string("digest"),
            )
        }
    }
}

/** How a job is doing, for the list. */
enum class ReplicationState { OK, SYNCING, FAILED, NEVER_RUN, DISABLED, REMOVING }

/** One row of `GET /nodes/{node}/replication`: the job's runs as seen by the guest's node. */
data class ReplicationStatus(
    val id: String,
    /** Epoch seconds of the last successful run; null if none. */
    val lastSync: Long?,
    val lastTry: Long?,
    val nextSync: Long?,
    /** Seconds the last run took. */
    val duration: Double?,
    val failCount: Int,
    val error: String?,
    val running: Boolean,
) {
    companion object {
        fun fromJson(o: JsonObject): ReplicationStatus {
            fun string(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun time(key: String) = string(key)?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
            return ReplicationStatus(
                id = string("id").orEmpty(),
                lastSync = time("last_sync"),
                lastTry = time("last_try"),
                nextSync = time("next_sync"),
                duration = string("duration")?.toDoubleOrNull(),
                failCount = string("fail_count")?.toDoubleOrNull()?.toInt() ?: 0,
                error = string("error"),
                running = string("pid") != null,
            )
        }

        fun state(job: ReplicationJob, status: ReplicationStatus?): ReplicationState = when {
            job.removing -> ReplicationState.REMOVING
            status?.running == true -> ReplicationState.SYNCING
            !job.enabled -> ReplicationState.DISABLED
            status != null && (status.failCount > 0 || status.error != null) -> ReplicationState.FAILED
            status?.lastSync == null -> ReplicationState.NEVER_RUN
            else -> ReplicationState.OK
        }
    }
}

/**
 * Adding or editing a replication job. The guest and target can't change once the job exists.
 * [rate] is the text of the MB/s field (blank for no limit).
 */
data class ReplicationJobForm(
    val guest: Int? = null,
    val target: String? = null,
    val schedule: String = ReplicationJob.DEFAULT_SCHEDULE,
    val rate: String = "",
    val comment: String = "",
    val enabled: Boolean = true,
) {
    enum class Problem { GUEST, TARGET, SAME_NODE, DUPLICATE, SCHEDULE, RATE }

    private val rateValue: Double? get() = rate.trim().replace(',', '.').toDoubleOrNull()

    /**
     * What Proxmox would refuse. [guestNode] is where the guest runs now; [jobs] are the existing
     * jobs, checked for a second job of the same guest to the same node when [creating].
     */
    fun problem(guestNode: String?, jobs: List<ReplicationJob>, creating: Boolean): Problem? = when {
        guest == null -> Problem.GUEST
        target.isNullOrBlank() -> Problem.TARGET
        target == guestNode -> Problem.SAME_NODE
        creating && jobs.any { it.guest == guest && it.target == target } -> Problem.DUPLICATE
        schedule.isBlank() -> Problem.SCHEDULE
        rate.isNotBlank() && (rateValue ?: 0.0) < 1.0 -> Problem.RATE
        else -> null
    }

    /** `POST /cluster/replication`: the job gets the guest's lowest free job number. */
    fun createParams(jobs: List<ReplicationJob>): Map<String, String> = buildMap {
        val guest = checkNotNull(guest)
        put("id", "$guest-${nextJobNumber(guest, jobs)}")
        put("type", "local")
        put("target", target.orEmpty())
        put("schedule", schedule.trim())
        rateValue?.let { put("rate", formatRate(it)) }
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        if (!enabled) put("disable", "1")
    }

    /** `PUT /cluster/replication/{id}`: every value, deleting what was cleared, with the digest. */
    fun updateParams(original: ReplicationJob): Map<String, String> = buildMap {
        put("schedule", schedule.trim())
        rateValue?.let { put("rate", formatRate(it)) }
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        if (!enabled) put("disable", "1")
        val delete = buildList {
            if (rateValue == null && original.rate != null) add("rate")
            if (comment.isBlank() && original.comment != null) add("comment")
            if (enabled && !original.enabled) add("disable")
        }
        if (delete.isNotEmpty()) put("delete", delete.joinToString(","))
        original.digest?.let { put("digest", it) }
    }

    companion object {
        /** Every 15 minutes (Proxmox's default), every 30 minutes, every two hours, daily at 22:00. */
        val SCHEDULE_PRESETS = listOf(ReplicationJob.DEFAULT_SCHEDULE, "*/30", "*/2:00", "22:00")

        fun nextJobNumber(guest: Int, jobs: List<ReplicationJob>): Int =
            generateSequence(0) { it + 1 }.first { n -> jobs.none { it.guest == guest && it.jobNumber == n } }

        fun from(job: ReplicationJob) = ReplicationJobForm(
            guest = job.guest,
            target = job.target,
            schedule = job.schedule,
            rate = job.rate?.let(::formatRate).orEmpty(),
            comment = job.comment.orEmpty(),
            enabled = job.enabled,
        )

        private fun formatRate(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }
}
