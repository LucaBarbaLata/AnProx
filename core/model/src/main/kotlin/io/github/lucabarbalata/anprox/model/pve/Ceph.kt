package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.array
import io.github.lucabarbalata.anprox.model.json.bool
import io.github.lucabarbalata.anprox.model.json.double
import io.github.lucabarbalata.anprox.model.json.int
import io.github.lucabarbalata.anprox.model.json.long
import io.github.lucabarbalata.anprox.model.json.obj
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.json.strings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /cluster/ceph/status`, trimmed to what the app reads. A cluster without Ceph answers
 * with an error instead.
 */
@Serializable
data class CephStatus(val health: CephHealth = CephHealth())

@Serializable
data class CephHealth(
    /** `HEALTH_OK`, `HEALTH_WARN` or `HEALTH_ERR`. */
    val status: String? = null,
)

/*
 * Ceph management (questionnaire Q21, milestone M6), as `/nodes/{node}/ceph/…` and
 * `/cluster/ceph/…` return it. Proxmox passes most of it through from Ceph's own JSON, whose
 * shape moved a little between releases (the OSD map was nested before Quincy).
 */

/** One health check, e.g. `OSD_DOWN` with "1 osds down". */
data class CephHealthCheck(
    val id: String,
    /** `HEALTH_WARN` or `HEALTH_ERR`. */
    val severity: String,
    val summary: String,
    val details: List<String> = emptyList(),
    val muted: Boolean = false,
) {
    val error: Boolean get() = severity == "HEALTH_ERR"
}

/** `GET /nodes/{node}/ceph/status`: the cluster at a glance. */
data class CephOverview(
    /** `HEALTH_OK`, `HEALTH_WARN` or `HEALTH_ERR`. */
    val health: String,
    val checks: List<CephHealthCheck> = emptyList(),
    val monitors: Int = 0,
    val quorum: List<String> = emptyList(),
    val osds: Int = 0,
    val osdsUp: Int = 0,
    val osdsIn: Int = 0,
    val pgs: Int = 0,
    /** PG states with their counts, the most common first (`active+clean` 129). */
    val pgStates: List<Pair<String, Int>> = emptyList(),
    val bytesUsed: Long = 0,
    val bytesAvail: Long = 0,
    val bytesTotal: Long = 0,
    val readBytesPerSec: Long = 0,
    val writeBytesPerSec: Long = 0,
    val readOpsPerSec: Long = 0,
    val writeOpsPerSec: Long = 0,
    val recoveringBytesPerSec: Long = 0,
    val managerAvailable: Boolean = false,
    val managerStandbys: Int = 0,
    val mdsUp: Int = 0,
    val mdsStandby: Int = 0,
) {
    val usedFraction: Double? get() = if (bytesTotal > 0) bytesUsed.toDouble() / bytesTotal else null

    /** PGs not `active+clean`: degraded, recovering, peering, … */
    val pgsNotClean: Int get() = pgStates.filter { it.first != "active+clean" }.sumOf { it.second }

    companion object {
        fun fromJson(o: JsonObject): CephOverview {
            val health = o.obj("health")
            val checks = health?.obj("checks")?.entries.orEmpty().mapNotNull { (id, v) ->
                val c = v as? JsonObject ?: return@mapNotNull null
                CephHealthCheck(
                    id = id,
                    severity = c.str("severity") ?: "HEALTH_WARN",
                    summary = c.obj("summary")?.str("message") ?: id,
                    details = c.array("detail").mapNotNull { it.str("message") },
                    muted = c.bool("muted"),
                )
            }.sortedWith(compareBy({ !it.error }, { it.id }))
            // Before Quincy the OSD map sat one level deeper.
            val osdmap = o.obj("osdmap")?.let { it.obj("osdmap") ?: it }
            val pgmap = o.obj("pgmap")
            val fsmap = o.obj("fsmap")
            val mgrmap = o.obj("mgrmap")
            val monmap = o.obj("monmap")
            val quorum = o.strings("quorum_names")
            return CephOverview(
                health = health?.str("status") ?: health?.str("overall_status") ?: "HEALTH_UNKNOWN",
                checks = checks,
                monitors = monmap?.int("num_mons") ?: monmap?.array("mons")?.size ?: quorum.size,
                quorum = quorum,
                osds = osdmap?.int("num_osds") ?: 0,
                osdsUp = osdmap?.int("num_up_osds") ?: 0,
                osdsIn = osdmap?.int("num_in_osds") ?: 0,
                pgs = pgmap?.int("num_pgs") ?: 0,
                pgStates = pgmap?.array("pgs_by_state").orEmpty()
                    .map { (it.str("state_name") ?: "?") to (it.int("count") ?: 0) }
                    .sortedByDescending { it.second },
                bytesUsed = pgmap?.long("bytes_used") ?: 0,
                bytesAvail = pgmap?.long("bytes_avail") ?: 0,
                bytesTotal = pgmap?.long("bytes_total") ?: 0,
                readBytesPerSec = pgmap?.long("read_bytes_sec") ?: 0,
                writeBytesPerSec = pgmap?.long("write_bytes_sec") ?: 0,
                readOpsPerSec = pgmap?.long("read_op_per_sec") ?: 0,
                writeOpsPerSec = pgmap?.long("write_op_per_sec") ?: 0,
                recoveringBytesPerSec = pgmap?.long("recovering_bytes_per_sec") ?: 0,
                managerAvailable = mgrmap?.bool("available") ?: false,
                managerStandbys = mgrmap?.int("num_standbys") ?: mgrmap?.array("standbys")?.size ?: 0,
                mdsUp = fsmap?.int("up") ?: 0,
                mdsStandby = fsmap?.int("up:standby") ?: 0,
            )
        }
    }
}

/** An OSD from `GET /nodes/{node}/ceph/osd`. */
data class CephOsd(
    val id: Int,
    val name: String = "osd.$id",
    val host: String? = null,
    val up: Boolean = false,
    val inCluster: Boolean = false,
    /** `hdd`, `ssd`, `nvme`, or a custom class. */
    val deviceClass: String? = null,
    /** 0–100. */
    val percentUsed: Double? = null,
    val bytesUsed: Long? = null,
    val totalSpace: Long? = null,
    val commitLatencyMs: Long? = null,
    val applyLatencyMs: Long? = null,
    val crushWeight: Double? = null,
    val version: String? = null,
    /** `bluestore` or `filestore`. */
    val osdType: String? = null,
    val device: String? = null,
    val dbDevice: String? = null,
    val walDevice: String? = null,
) {
    /** Ceph only lets an OSD go once it is out and down (the data has moved off it). */
    val canDestroy: Boolean get() = !up && !inCluster

    /** The systemd unit name Proxmox's start/stop/restart take. */
    val service: String get() = "osd.$id"

    companion object {
        fun fromJson(o: JsonObject, host: String?) = CephOsd(
            id = o.int("id") ?: 0,
            name = o.str("name") ?: "osd.${o.int("id") ?: 0}",
            host = o.str("host") ?: host,
            up = o.str("status") == "up",
            inCluster = o.bool("in"),
            deviceClass = o.str("device_class"),
            percentUsed = o.double("percent_used"),
            bytesUsed = o.long("bytes_used"),
            totalSpace = o.long("total_space"),
            commitLatencyMs = o.long("commit_latency_ms"),
            applyLatencyMs = o.long("apply_latency_ms"),
            crushWeight = o.double("crush_weight"),
            version = o.str("ceph_version_short") ?: o.str("ceph_version"),
            osdType = o.str("osdtype"),
            device = o.str("blfsdev"),
            dbDevice = o.str("dbdev"),
            walDevice = o.str("waldev"),
        )
    }
}

data class CephOsdHost(val name: String, val version: String? = null, val osds: List<CephOsd> = emptyList())

/** The OSD tree flattened to hosts with their OSDs, and the OSD map flags that are set. */
data class CephOsdTree(val hosts: List<CephOsdHost> = emptyList(), val flags: List<String> = emptyList()) {
    val osds: List<CephOsd> get() = hosts.flatMap { it.osds }

    companion object {
        fun fromJson(o: JsonObject): CephOsdTree {
            val hosts = linkedMapOf<String, CephOsdHost>()
            val loose = mutableListOf<CephOsd>()
            fun walk(node: JsonObject, host: String?) {
                when (node.str("type")) {
                    "osd" -> {
                        val osd = CephOsd.fromJson(node, host)
                        val h = osd.host
                        if (h == null) loose += osd else hosts[h] = (hosts[h] ?: CephOsdHost(h)).let { it.copy(osds = it.osds + osd) }
                    }
                    "host" -> {
                        val name = node.str("name").orEmpty()
                        hosts.putIfAbsent(name, CephOsdHost(name, node.str("version")))
                        node.array("children").forEach { walk(it, name) }
                    }
                    else -> node.array("children").forEach { walk(it, host) }
                }
            }
            o.obj("root")?.let { walk(it, null) }
            val list = hosts.values.map { h -> h.copy(osds = h.osds.sortedBy { it.id }) }.sortedBy { it.name } +
                (if (loose.isEmpty()) emptyList() else listOf(CephOsdHost("", osds = loose.sortedBy { it.id })))
            return CephOsdTree(list, o.strings("flags"))
        }
    }
}

/** Creating an OSD on an unused disk of [node]. */
data class CephOsdForm(
    val node: String = "",
    val device: String = "",
    /** Empty lets Ceph detect it. */
    val deviceClass: String = "",
    val encrypted: Boolean = false,
    /** A faster disk for the RocksDB database, or empty. */
    val dbDevice: String = "",
) {
    val valid: Boolean get() = node.isNotBlank() && device.isNotBlank() && device != dbDevice

    fun params(): Map<String, String> = buildMap {
        put("dev", device)
        if (deviceClass.isNotBlank()) put("crush-device-class", deviceClass)
        if (encrypted) put("encrypted", "1")
        if (dbDevice.isNotBlank()) put("db_dev", dbDevice)
    }

    companion object {
        val DEVICE_CLASSES = listOf("", "hdd", "ssd", "nvme")
    }
}

/** A pool from `GET /nodes/{node}/ceph/pool`. */
data class CephPool(
    val name: String,
    val id: Int? = null,
    /** `replicated` or `erasure`. */
    val type: String? = null,
    val size: Int = 3,
    val minSize: Int = 2,
    val pgNum: Int = 0,
    /** What the autoscaler would choose. */
    val pgNumFinal: Int? = null,
    /** `on`, `warn` or `off`. */
    val autoscale: String? = null,
    val crushRule: String? = null,
    val bytesUsed: Long? = null,
    /** 0–1. */
    val percentUsed: Double? = null,
    val applications: List<String> = emptyList(),
) {
    /** Ceph's own pools (the manager's `.mgr`): not for guests, and not to be removed. */
    val internal: Boolean get() = name.startsWith(".") || name == "device_health_metrics"

    companion object {
        fun fromJson(o: JsonObject) = CephPool(
            name = o.str("pool_name") ?: o.str("name").orEmpty(),
            id = o.int("pool"),
            type = o.str("type"),
            size = o.int("size") ?: 3,
            minSize = o.int("min_size") ?: 2,
            pgNum = o.int("pg_num") ?: 0,
            pgNumFinal = o.int("pg_num_final"),
            autoscale = o.str("pg_autoscale_mode"),
            crushRule = o.str("crush_rule_name"),
            bytesUsed = o.long("bytes_used"),
            percentUsed = o.double("percent_used"),
            applications = o.obj("application_metadata")?.keys?.sorted().orEmpty(),
        )
    }
}

/** Creating or editing a pool; the name only counts while creating. */
data class CephPoolForm(
    val name: String = "",
    val size: String = "3",
    val minSize: String = "2",
    /** `on`, `warn` or `off`. */
    val autoscale: String = "on",
    val pgNum: String = "128",
    /** `rbd` (VM disks), `cephfs` or `rgw`. */
    val application: String = "rbd",
    val crushRule: String = "",
    /** Also add it as a Proxmox storage (RBD). */
    val addStorage: Boolean = true,
) {
    enum class Problem { NAME, SIZE, MIN_SIZE, PG_NUM }

    fun problem(creating: Boolean): Problem? {
        val s = size.toIntOrNull()
        val m = minSize.toIntOrNull()
        val pg = pgNum.toIntOrNull()
        return when {
            creating && !NAME.matches(name.trim()) -> Problem.NAME
            s == null || s !in 1..7 -> Problem.SIZE
            m == null || m !in 1..s -> Problem.MIN_SIZE
            pg == null || pg !in 1..32768 -> Problem.PG_NUM
            else -> null
        }
    }

    /** Losing one copy stops writes (or loses data) with these settings: Proxmox warns too. */
    val risky: Boolean get() = (size.toIntOrNull() ?: 3) < 3 || (minSize.toIntOrNull() ?: 2) < 2

    /** pg_num that isn't a power of two: Ceph warns about it. */
    val pgNotPowerOfTwo: Boolean get() = pgNum.toIntOrNull()?.let { it > 0 && it and (it - 1) != 0 } ?: false

    fun createParams(): Map<String, String> = buildMap {
        put("name", name.trim())
        // Inside buildMap, a bare `size` is the map's own size.
        put("size", this@CephPoolForm.size)
        put("min_size", minSize)
        put("pg_autoscale_mode", autoscale)
        put("pg_num", pgNum)
        put("application", application)
        if (crushRule.isNotBlank()) put("crush_rule", crushRule)
        if (addStorage) put("add_storages", "1")
    }

    /** Only what changed. */
    fun updateParams(original: CephPool): Map<String, String> = buildMap {
        val size = this@CephPoolForm.size
        if (size != original.size.toString()) put("size", size)
        if (minSize != original.minSize.toString()) put("min_size", minSize)
        if (autoscale != (original.autoscale ?: "")) put("pg_autoscale_mode", autoscale)
        if (pgNum != original.pgNum.toString()) put("pg_num", pgNum)
        if (crushRule.isNotBlank() && crushRule != original.crushRule) put("crush_rule", crushRule)
    }

    companion object {
        val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._\\-]{0,63}$")
        val AUTOSCALE = listOf("on", "warn", "off")
        val APPLICATIONS = listOf("rbd", "cephfs", "rgw")

        fun from(p: CephPool) = CephPoolForm(
            name = p.name,
            size = p.size.toString(),
            minSize = p.minSize.toString(),
            autoscale = p.autoscale ?: "warn",
            pgNum = p.pgNum.toString(),
            application = p.applications.firstOrNull { it in APPLICATIONS } ?: "rbd",
            crushRule = p.crushRule.orEmpty(),
            addStorage = false,
        )
    }
}

/** Monitors, managers and metadata servers. */
enum class CephServiceType(val apiName: String) {
    MON("mon"),
    MGR("mgr"),
    MDS("mds"),
}

/** A monitor, manager or metadata server from `GET /nodes/{node}/ceph/{mon,mgr,mds}`. */
data class CephService(
    val type: CephServiceType,
    val name: String,
    val host: String? = null,
    val address: String? = null,
    /** `running`, `stopped`, `active`, `standby`, `up:active`, `up:standby`, `unknown`, … */
    val state: String? = null,
    /** Monitors only. */
    val inQuorum: Boolean? = null,
    val version: String? = null,
) {
    /** The systemd unit name Proxmox's start/stop/restart take, e.g. `mon.pve1`. */
    val service: String get() = "${type.apiName}.$name"

    val running: Boolean get() = when (state?.lowercase()) {
        null, "stopped", "unknown", "down" -> type == CephServiceType.MON && inQuorum == true
        else -> true
    }

    companion object {
        fun fromJson(type: CephServiceType, o: JsonObject) = CephService(
            type = type,
            name = o.str("name").orEmpty(),
            host = o.str("host"),
            address = o.str("addr"),
            state = o.str("state"),
            inQuorum = if (type == CephServiceType.MON) o.bool("quorum") else null,
            version = o.str("ceph_version_short") ?: o.str("ceph_version"),
        )
    }
}

/** A CephFS from `GET /nodes/{node}/ceph/fs`. */
data class CephFs(val name: String, val metadataPool: String? = null, val dataPools: List<String> = emptyList()) {
    companion object {
        fun fromJson(o: JsonObject) = CephFs(o.str("name").orEmpty(), o.str("metadata_pool"), o.strings("data_pool"))
    }
}

/** Creating a CephFS (needs a metadata server). */
data class CephFsForm(val name: String = "cephfs", val pgNum: String = "128", val addStorage: Boolean = true) {
    val valid: Boolean get() = CephPoolForm.NAME.matches(name.trim()) && (pgNum.toIntOrNull() ?: 0) in 1..32768

    fun params(): Map<String, String> = buildMap {
        put("pg_num", pgNum)
        if (addStorage) put("add-storage", "1")
    }
}

/** An OSD map flag from `GET /cluster/ceph/flags`. */
data class CephFlag(val name: String, val value: Boolean, val description: String? = null) {
    companion object {
        fun fromJson(o: JsonObject) = CephFlag(o.str("name").orEmpty(), o.bool("value"), o.str("description"))

        /** The flags maintenance usually needs, first in the list. */
        val COMMON = listOf("noout", "norebalance", "nobackfill", "norecover", "noscrub", "nodeep-scrub")
    }
}

/** A setting from Ceph's configuration database (`GET /nodes/{node}/ceph/cfg/db`). */
data class CephConfigEntry(val section: String, val name: String, val value: String) {
    companion object {
        fun fromJson(o: JsonObject) = CephConfigEntry(o.str("section").orEmpty(), o.str("name").orEmpty(), o.str("value").orEmpty())
    }
}

/** Errors that mean "there is no Ceph here", rather than a failure to report. */
fun cephNotSetUp(message: String?): Boolean {
    val m = message?.lowercase() ?: return false
    return "not installed" in m || "not initialized" in m || "rados_connect failed" in m ||
        "no such file or directory" in m && "ceph" in m || "ceph is not" in m || "no ceph" in m
}
