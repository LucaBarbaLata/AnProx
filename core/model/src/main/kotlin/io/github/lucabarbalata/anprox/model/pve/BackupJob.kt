package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Which guests a backup job covers. */
enum class BackupSelection {
    /** Every guest (on the job's node, or in the cluster). */
    ALL,

    /** Only the listed guests. */
    INCLUDE,

    /** Every guest except the listed ones. */
    EXCLUDE,

    /** The members of a resource pool. */
    POOL,
}

/**
 * How many backups to keep (`prune-backups`). Null counts aren't set; [keepAll] overrides them.
 * Without any retention on the job, the storage's own retention applies.
 */
data class Retention(
    val last: Int? = null,
    val hourly: Int? = null,
    val daily: Int? = null,
    val weekly: Int? = null,
    val monthly: Int? = null,
    val yearly: Int? = null,
    val keepAll: Boolean = false,
) {
    private val counts get() = listOf("last" to last, "hourly" to hourly, "daily" to daily, "weekly" to weekly, "monthly" to monthly, "yearly" to yearly)

    val isEmpty: Boolean get() = !keepAll && counts.all { it.second == null }

    /** The `prune-backups` property string. */
    fun value(): String =
        if (keepAll) "keep-all=1" else counts.mapNotNull { (k, v) -> v?.let { "keep-$k=$it" } }.joinToString(",")

    companion object {
        /** Proxmox answers with a property string or, in newer versions, an object. */
        fun parse(element: JsonElement?): Retention? {
            val pairs: Map<String, String> = when (element) {
                is JsonPrimitive -> PropertyString.parse(element.contentOrNull.orEmpty(), defaultKey = "keep-all").let { ps ->
                    ps.keys.associateWith { ps[it].orEmpty() }
                }
                is JsonObject -> element.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }
                else -> return null
            }
            if (pairs.isEmpty()) return null
            fun count(key: String) = pairs["keep-$key"]?.toIntOrNull()
            return Retention(
                last = count("last"), hourly = count("hourly"), daily = count("daily"),
                weekly = count("weekly"), monthly = count("monthly"), yearly = count("yearly"),
                keepAll = pairs["keep-all"].isOn(),
            ).takeUnless { it.isEmpty }
        }
    }
}

/** One row of `GET /cluster/backup`: a scheduled backup (questionnaire Q20). */
data class BackupJob(
    val id: String,
    /** Proxmox calendar event, e.g. `21:00`, `mon..fri 22:30`, `sun 01:00`. */
    val schedule: String,
    val enabled: Boolean,
    val storage: String?,
    /** Only guests on this node; null for the whole cluster. */
    val node: String?,
    val selection: BackupSelection,
    /** The listed guests for [BackupSelection.INCLUDE] and [BackupSelection.EXCLUDE]. */
    val vmids: List<Int>,
    val pool: String?,
    val mode: BackupMode,
    val compress: String?,
    val retention: Retention?,
    val notesTemplate: String?,
    val comment: String?,
    /** Epoch seconds. */
    val nextRun: Long?,
) {
    companion object {
        fun fromJson(o: JsonObject): BackupJob {
            fun string(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun ids(key: String) = string(key).orEmpty().split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }
            val pool = string("pool")
            val all = string("all").isOn()
            val exclude = ids("exclude")
            val selection = when {
                pool != null -> BackupSelection.POOL
                all && exclude.isNotEmpty() -> BackupSelection.EXCLUDE
                all -> BackupSelection.ALL
                else -> BackupSelection.INCLUDE
            }
            return BackupJob(
                id = string("id").orEmpty(),
                schedule = string("schedule") ?: string("starttime").orEmpty(),
                // Absent means enabled.
                enabled = string("enabled")?.isOn() ?: true,
                storage = string("storage"),
                node = string("node"),
                selection = selection,
                vmids = when (selection) {
                    BackupSelection.INCLUDE -> ids("vmid")
                    BackupSelection.EXCLUDE -> exclude
                    else -> emptyList()
                },
                pool = pool,
                mode = BackupMode.entries.firstOrNull { it.apiName == string("mode") } ?: BackupMode.SNAPSHOT,
                compress = string("compress"),
                retention = Retention.parse(o["prune-backups"]),
                notesTemplate = string("notes-template"),
                comment = string("comment"),
                nextRun = string("next-run")?.toLongOrNull(),
            )
        }
    }
}

/**
 * The backup job editor. [problem] explains what Proxmox would refuse; [createParams] and
 * [updateParams] build `POST /cluster/backup` and `PUT /cluster/backup/{id}`.
 */
data class BackupJobForm(
    val enabled: Boolean = true,
    val schedule: String = SCHEDULE_PRESETS.first(),
    val storage: String? = null,
    val node: String? = null,
    val selection: BackupSelection = BackupSelection.ALL,
    val vmids: Set<Int> = emptySet(),
    val pool: String? = null,
    val mode: BackupMode = BackupMode.SNAPSHOT,
    val compress: String = "zstd",
    /** Null: the storage's own retention. */
    val retention: Retention? = null,
    val notesTemplate: String = DEFAULT_NOTES,
    val comment: String = "",
) {
    enum class Problem { SCHEDULE, STORAGE, NO_GUESTS, POOL, RETENTION }

    fun problem(): Problem? = when {
        schedule.isBlank() -> Problem.SCHEDULE
        storage.isNullOrBlank() -> Problem.STORAGE
        (selection == BackupSelection.INCLUDE || selection == BackupSelection.EXCLUDE) && vmids.isEmpty() -> Problem.NO_GUESTS
        selection == BackupSelection.POOL && pool.isNullOrBlank() -> Problem.POOL
        retention != null && retention.isEmpty -> Problem.RETENTION
        else -> null
    }

    /** What the job runs; shared by create, update and "run now". */
    fun commonParams(): Map<String, String> = buildMap {
        put("storage", storage.orEmpty())
        put("mode", mode.apiName)
        put("compress", compress)
        when (selection) {
            BackupSelection.ALL -> put("all", "1")
            BackupSelection.INCLUDE -> put("vmid", vmids.sorted().joinToString(","))
            BackupSelection.EXCLUDE -> {
                put("all", "1")
                put("exclude", vmids.sorted().joinToString(","))
            }
            BackupSelection.POOL -> put("pool", pool.orEmpty())
        }
        retention?.let { put("prune-backups", it.value()) }
        notesTemplate.trim().takeIf { it.isNotEmpty() }?.let { put("notes-template", it) }
    }

    fun createParams(): Map<String, String> = buildMap {
        putAll(commonParams())
        put("schedule", schedule.trim())
        put("enabled", if (enabled) "1" else "0")
        node?.let { put("node", it) }
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
    }

    /** Sends every value and deletes what no longer applies, so the job matches the form exactly. */
    fun updateParams(): Map<String, String> {
        val set = LinkedHashMap(createParams())
        if (selection != BackupSelection.ALL && selection != BackupSelection.EXCLUDE) set["all"] = "0"
        val delete = buildList {
            if (selection != BackupSelection.INCLUDE) add("vmid")
            if (selection != BackupSelection.EXCLUDE) add("exclude")
            if (selection != BackupSelection.POOL) add("pool")
            if (node == null) add("node")
            if (retention == null) add("prune-backups")
            if (notesTemplate.isBlank()) add("notes-template")
            if (comment.isBlank()) add("comment")
        }
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        return set
    }

    companion object {
        /** Daily at 21:00, weekdays, weekly, every six hours. */
        val SCHEDULE_PRESETS = listOf("21:00", "mon..fri 21:00", "sun 01:00", "0/6:00")
        val COMPRESSIONS = listOf("zstd", "lzo", "gzip", "0")
        const val DEFAULT_NOTES = "{{guestname}}"

        fun from(job: BackupJob) = BackupJobForm(
            enabled = job.enabled,
            schedule = job.schedule,
            storage = job.storage,
            node = job.node,
            selection = job.selection,
            vmids = job.vmids.toSet(),
            pool = job.pool,
            mode = job.mode,
            compress = job.compress ?: "zstd",
            retention = job.retention,
            notesTemplate = job.notesTemplate.orEmpty(),
            comment = job.comment.orEmpty(),
        )
    }
}

/** One row of `GET /cluster/backup-info/not-backed-up`. */
@Serializable
data class NotBackedUp(val vmid: Int, val name: String? = null, val type: String = "qemu")

/** One row of `GET /cluster/jobs/schedule-analyze`. */
@Serializable
data class ScheduledRun(val timestamp: Long, val utc: String? = null)

/** One row of `GET /storage`: a storage as configured for the whole cluster. */
@Serializable
data class StorageConfig(
    val storage: String,
    val type: String = "",
    /** Comma-separated: `images,rootdir,iso,vztmpl,backup,snippets,import`. */
    val content: String = "",
    @Serializable(with = io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer::class)
    val shared: Boolean = false,
    @Serializable(with = io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer::class)
    val disable: Boolean = false,
    /** Comma-separated node names it is limited to; null for all. */
    val nodes: String? = null,
) {
    val contents: Set<String> get() = content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val holdsBackups: Boolean get() = "backup" in contents && !disable
    val isPbs: Boolean get() = type == "pbs"
}

/** One row of `GET /pools`. */
@Serializable
data class ResourcePool(@SerialName("poolid") val id: String, val comment: String? = null)
