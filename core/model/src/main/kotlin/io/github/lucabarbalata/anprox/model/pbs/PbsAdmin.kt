package io.github.lucabarbalata.anprox.model.pbs

import io.github.lucabarbalata.anprox.model.json.array
import io.github.lucabarbalata.anprox.model.json.bool
import io.github.lucabarbalata.anprox.model.json.int
import io.github.lucabarbalata.anprox.model.json.long
import io.github.lucabarbalata.anprox.model.json.obj
import io.github.lucabarbalata.anprox.model.json.str
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/*
 * Proxmox Backup Server administration (questionnaire Q20, milestone M8): datastore content
 * (namespaces, backup groups, snapshots), maintenance (garbage collection, verify, prune), file
 * restore from the catalog, jobs and remotes, as `/admin/…` and `/config/…` return them.
 */

/** `vm`, `ct` or `host`. */
enum class BackupType(val apiName: String) {
    VM("vm"),
    CT("ct"),
    HOST("host"),
    ;

    companion object {
        fun fromApi(value: String?): BackupType? = entries.firstOrNull { it.apiName == value }
    }
}

/** A backup group: every snapshot of one guest or host (`GET /admin/datastore/{store}/groups`). */
data class BackupGroup(
    val type: String,
    val id: String,
    val lastBackup: Long? = null,
    val count: Int = 0,
    val owner: String? = null,
    val comment: String? = null,
) {
    val key: String get() = "$type/$id"

    companion object {
        fun fromJson(o: JsonObject) = BackupGroup(
            type = o.str("backup-type").orEmpty(),
            id = o.str("backup-id").orEmpty(),
            lastBackup = o.long("last-backup"),
            count = o.int("backup-count") ?: 0,
            owner = o.str("owner"),
            comment = o.str("comment"),
        )
    }
}

/** One file of a snapshot: `index.json.blob`, `root.pxar.didx`, `drive-scsi0.img.fidx`, … */
data class BackupFile(val name: String, val size: Long? = null, val cryptMode: String? = null) {
    val encrypted: Boolean get() = cryptMode == "encrypt"
}

/** A snapshot (`GET /admin/datastore/{store}/snapshots`). */
data class BackupSnapshot(
    val type: String,
    val id: String,
    /** Epoch seconds; with [type] and [id] it names the snapshot. */
    val time: Long,
    val size: Long? = null,
    val comment: String? = null,
    /** `ok` or `failed`; null if never verified. */
    val verifyState: String? = null,
    val protected: Boolean = false,
    val owner: String? = null,
    val files: List<BackupFile> = emptyList(),
) {
    val encrypted: Boolean get() = files.any { it.encrypted }

    /** Container and host backups have a catalog to browse; VM images don't. */
    val hasCatalog: Boolean get() = files.any { it.name.startsWith("catalog.pcat1") } && !files.first { it.name.startsWith("catalog.pcat1") }.encrypted

    /** The query parameters that name it. */
    fun params(): Map<String, String> = mapOf("backup-type" to type, "backup-id" to id, "backup-time" to time.toString())

    companion object {
        fun fromJson(o: JsonObject) = BackupSnapshot(
            type = o.str("backup-type").orEmpty(),
            id = o.str("backup-id").orEmpty(),
            time = o.long("backup-time") ?: 0,
            size = o.long("size"),
            comment = o.str("comment"),
            verifyState = o.obj("verification")?.str("state"),
            protected = o.bool("protected"),
            owner = o.str("owner"),
            files = o.array("files").map { BackupFile(it.str("filename").orEmpty(), it.long("size"), it.str("crypt-mode")) },
        )
    }
}

/** A namespace of a datastore (`GET /admin/datastore/{store}/namespace`); the root is "". */
data class PbsNamespace(val ns: String, val comment: String? = null) {
    val depth: Int get() = if (ns.isEmpty()) 0 else ns.count { it == '/' } + 1
    val name: String get() = ns.substringAfterLast('/')
    val parent: String get() = if ('/' in ns) ns.substringBeforeLast('/') else ""

    companion object {
        fun fromJson(o: JsonObject) = PbsNamespace(o.str("ns").orEmpty(), o.str("comment"))

        /** Like PBS: letters, digits, `_`, `-` and `.`, up to 31 characters, at most 7 levels. */
        val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9._\\-]{0,30}$")
        const val MAX_DEPTH = 7
    }
}

/** Garbage collection: what the last run freed and what is pending. */
data class GcStatus(
    val lastRunState: String? = null,
    val lastRunEnd: Long? = null,
    val nextRun: Long? = null,
    val schedule: String? = null,
    val removedBytes: Long? = null,
    val pendingBytes: Long? = null,
    val diskBytes: Long? = null,
    val indexDataBytes: Long? = null,
    val upid: String? = null,
) {
    /** How much the deduplication saves: logical size / size on disk. */
    val dedupFactor: Double? get() = if ((diskBytes ?: 0) > 0 && indexDataBytes != null) indexDataBytes.toDouble() / diskBytes!! else null

    companion object {
        /** Reads both `GET …/gc` (PBS 3 job status) and the `gc-status` inside `GET …/status`. */
        fun fromJson(o: JsonObject) = GcStatus(
            lastRunState = o.str("last-run-state"),
            lastRunEnd = o.long("last-run-endtime"),
            nextRun = o.long("next-run"),
            schedule = o.str("schedule"),
            removedBytes = o.long("removed-bytes"),
            pendingBytes = o.long("pending-bytes"),
            diskBytes = o.long("disk-bytes"),
            indexDataBytes = o.long("index-data-bytes"),
            upid = o.str("upid") ?: o.str("last-run-upid"),
        )
    }
}

/** `GET /admin/datastore/{store}/status`: usage and how many groups and snapshots of each type. */
data class DatastoreStatus(
    val total: Long? = null,
    val used: Long? = null,
    val avail: Long? = null,
    /** Type → (groups, snapshots). */
    val counts: Map<String, Pair<Int, Int>> = emptyMap(),
    val gc: GcStatus? = null,
) {
    val fraction: Double? get() = if (total == null || used == null || total <= 0) null else used.toDouble() / total

    companion object {
        fun fromJson(o: JsonObject) = DatastoreStatus(
            total = o.long("total"),
            used = o.long("used"),
            avail = o.long("avail"),
            counts = o.obj("counts")?.entries.orEmpty().mapNotNull { (type, v) ->
                (v as? JsonObject)?.let { type to ((it.int("groups") ?: 0) to (it.int("snapshots") ?: 0)) }
            }.toMap(),
            gc = o.obj("gc-status")?.let(GcStatus::fromJson),
        )
    }
}

/** Retention for a prune: how many of the newest, hourly, daily, … snapshots to keep. Blank keeps none by that rule. */
data class KeepOptions(
    val last: String = "",
    val hourly: String = "",
    val daily: String = "",
    val weekly: String = "",
    val monthly: String = "",
    val yearly: String = "",
) {
    private val all: List<Pair<String, String>>
        get() = listOf("keep-last" to last, "keep-hourly" to hourly, "keep-daily" to daily, "keep-weekly" to weekly, "keep-monthly" to monthly, "keep-yearly" to yearly)

    /** At least one rule, each a positive number; PBS refuses to prune everything. */
    val valid: Boolean get() = all.any { it.second.isNotBlank() } && all.all { it.second.isBlank() || (it.second.toIntOrNull() ?: 0) > 0 }

    fun params(): Map<String, String> = all.filter { it.second.isNotBlank() }.toMap()

    companion object {
        fun fromJson(o: JsonObject) = KeepOptions(
            last = o.str("keep-last").orEmpty(),
            hourly = o.str("keep-hourly").orEmpty(),
            daily = o.str("keep-daily").orEmpty(),
            weekly = o.str("keep-weekly").orEmpty(),
            monthly = o.str("keep-monthly").orEmpty(),
            yearly = o.str("keep-yearly").orEmpty(),
        )
    }
}

/** What a prune keeps or removes (`POST …/prune`, also as a dry run). */
data class PruneItem(val time: Long, val keep: Boolean, val protected: Boolean = false) {
    companion object {
        fun fromJson(o: JsonObject) = PruneItem(o.long("backup-time") ?: 0, o.bool("keep"), o.bool("protected"))
    }
}

/** An entry of a snapshot's catalog (`GET …/catalog`): a directory, file, link or archive. */
data class CatalogEntry(
    /** Base64 of the path, as the API wants it back. */
    val filepath: String,
    val name: String,
    /** `d` directory, `f` file, `l` symlink, `h` hardlink, `v` virtual (an archive), … */
    val type: String,
    val leaf: Boolean = true,
    val size: Long? = null,
    val mtime: Long? = null,
) {
    /** Opens to more entries: directories and archives. */
    val browsable: Boolean get() = !leaf || type == "d" || type == "v"

    /** Files and whole directories can be downloaded (a directory as a zip). */
    val downloadable: Boolean get() = type == "f" || type == "d" || type == "h"

    /** The decoded path, e.g. `/root.pxar.didx/etc/hosts`. */
    val path: String get() = decodePath(filepath)

    companion object {
        fun fromJson(o: JsonObject) = CatalogEntry(
            filepath = o.str("filepath").orEmpty(),
            name = o.str("text").orEmpty(),
            type = o.str("type").orEmpty(),
            leaf = o.bool("leaf", default = true),
            size = o.long("size"),
            mtime = o.long("mtime"),
        )

        /** What `filepath` takes for the top of the catalog. */
        const val ROOT = "root"

        fun encodePath(path: String): String = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))

        fun decodePath(base64: String): String =
            if (base64 == ROOT) "/" else runCatching { String(Base64.getDecoder().decode(base64), Charsets.UTF_8) }.getOrDefault(base64)
    }
}

/** A sync, verify or prune job with its last run (`GET /admin/{sync,verify,prune}`). */
data class PbsJob(
    val kind: PbsJobKind,
    val id: String,
    val store: String,
    val schedule: String? = null,
    val comment: String? = null,
    val ns: String? = null,
    /** Sync jobs: where from. */
    val remote: String? = null,
    val remoteStore: String? = null,
    val lastRunState: String? = null,
    val lastRunEnd: Long? = null,
    val lastRunUpid: String? = null,
    val nextRun: Long? = null,
    /** Prune jobs can be switched off. */
    val disabled: Boolean = false,
    val keep: KeepOptions? = null,
) {
    val failed: Boolean get() = lastRunState != null && lastRunState != "OK"

    companion object {
        fun fromJson(kind: PbsJobKind, o: JsonObject) = PbsJob(
            kind = kind,
            id = o.str("id").orEmpty(),
            store = o.str("store").orEmpty(),
            schedule = o.str("schedule"),
            comment = o.str("comment"),
            ns = o.str("ns"),
            remote = o.str("remote"),
            remoteStore = o.str("remote-store"),
            lastRunState = o.str("last-run-state"),
            lastRunEnd = o.long("last-run-endtime"),
            lastRunUpid = o.str("last-run-upid"),
            nextRun = o.long("next-run"),
            disabled = o.bool("disable"),
            keep = if (kind == PbsJobKind.PRUNE) KeepOptions.fromJson(o) else null,
        )
    }
}

enum class PbsJobKind(val apiName: String) {
    SYNC("sync"),
    VERIFY("verify"),
    PRUNE("prune"),
}

/** A remote Backup Server to sync from (`GET /config/remote`). */
data class PbsRemote(
    val name: String,
    val host: String,
    val port: Int? = null,
    val authId: String,
    val fingerprint: String? = null,
    val comment: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject) = PbsRemote(
            name = o.str("name").orEmpty(),
            host = o.str("host").orEmpty(),
            port = o.int("port"),
            authId = o.str("auth-id") ?: o.str("userid").orEmpty(),
            fingerprint = o.str("fingerprint"),
            comment = o.str("comment"),
        )
    }
}

/** Adding a remote: the server, a user or API token on it, and its certificate fingerprint if self-signed. */
data class PbsRemoteForm(
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val authId: String = "",
    val password: String = "",
    val fingerprint: String = "",
    val comment: String = "",
) {
    enum class Problem { NAME, HOST, PORT, AUTH_ID, PASSWORD, FINGERPRINT }

    fun problem(): Problem? = when {
        !NAME.matches(name.trim()) -> Problem.NAME
        host.isBlank() || ' ' in host.trim() -> Problem.HOST
        port.isNotBlank() && (port.toIntOrNull() ?: 0) !in 1..65535 -> Problem.PORT
        !AUTH_ID.matches(authId.trim()) -> Problem.AUTH_ID
        password.isEmpty() -> Problem.PASSWORD
        fingerprint.isNotBlank() && !FINGERPRINT.matches(fingerprint.trim()) -> Problem.FINGERPRINT
        else -> null
    }

    fun params(): Map<String, String> = buildMap {
        put("name", name.trim())
        put("host", host.trim())
        port.trim().takeIf { it.isNotEmpty() }?.let { put("port", it) }
        put("auth-id", authId.trim())
        put("password", password)
        fingerprint.trim().takeIf { it.isNotEmpty() }?.let { put("fingerprint", it.lowercase()) }
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
    }

    companion object {
        val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9._\\-]{2,31}$")

        /** `user@realm` or `user@realm!token`. */
        val AUTH_ID = Regex("^[^\\s:/@!]+@[A-Za-z][A-Za-z0-9.\\-_]+(![A-Za-z][A-Za-z0-9.\\-_]*)?$")

        /** SHA-256, as `aa:bb:…` (32 pairs). */
        val FINGERPRINT = Regex("^([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$")
    }
}

/** A datastore's configuration that the app changes: maintenance mode. */
data class DatastoreMaintenance(val type: String? = null, val message: String? = null) {
    companion object {
        /** `type=read-only,message=Moving disks` → read-only, "Moving disks". */
        fun parse(value: String?): DatastoreMaintenance {
            if (value.isNullOrBlank()) return DatastoreMaintenance()
            val parts = value.split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
            return DatastoreMaintenance(parts["type"] ?: value.substringBefore(',').takeIf { '=' !in it }, parts["message"])
        }

        val TYPES = listOf("read-only", "offline")

        fun value(type: String, message: String): String = buildString {
            append("type=").append(type)
            if (message.isNotBlank()) append(",message=").append(message.trim().replace(",", " "))
        }
    }
}

/** `GET /nodes/localhost/status` of a Backup Server. */
data class PbsNodeStatus(
    /** 0–1. */
    val cpu: Double? = null,
    val memoryUsed: Long? = null,
    val memoryTotal: Long? = null,
    val rootUsed: Long? = null,
    val rootTotal: Long? = null,
    val uptime: Long? = null,
    val loadAverage: List<String> = emptyList(),
    val kernel: String? = null,
    val cpuModel: String? = null,
    val cpus: Int? = null,
    /** The server certificate's fingerprint, to compare with what the app trusts. */
    val fingerprint: String? = null,
) {
    companion object {
        fun fromJson(o: JsonObject): PbsNodeStatus {
            val kernel = o.obj("current-kernel")?.let { k -> listOfNotNull(k.str("sysname"), k.str("release")).joinToString(" ") } ?: o.str("kversion")
            return PbsNodeStatus(
                cpu = o.str("cpu")?.toDoubleOrNull(),
                memoryUsed = o.obj("memory")?.long("used"),
                memoryTotal = o.obj("memory")?.long("total"),
                rootUsed = o.obj("root")?.long("used"),
                rootTotal = o.obj("root")?.long("total"),
                uptime = o.long("uptime"),
                loadAverage = (o["loadavg"] as? kotlinx.serialization.json.JsonArray)?.map { it.toString().trim('"') }.orEmpty(),
                kernel = kernel,
                cpuModel = o.obj("cpuinfo")?.str("model"),
                cpus = o.obj("cpuinfo")?.int("cpus"),
                fingerprint = o.obj("info")?.str("fingerprint"),
            )
        }
    }
}
