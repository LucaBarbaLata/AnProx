package io.github.lucabarbalata.anprox.mock

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A three-node Ceph cluster: two SSD OSDs on pve1, two HDD OSDs on pve2 and two on pve3, which
 * is offline, so its OSDs and monitor are down and the cluster reports HEALTH_WARN. Off until
 * [installed] is set (tests that don't expect Ceph keep it off; the runnable mock turns it on),
 * so that a cluster without Ceph answers like a real one.
 *
 * Changes that Proxmox runs as tasks come back as a [Change]: either refused with Proxmox's
 * reason, or an effect to apply when the task ends.
 */
class MockCeph internal constructor(
    private val online: (String) -> Boolean,
    private val diskUsed: (node: String, device: String, used: Boolean) -> Unit,
) {
    sealed interface Change {
        data class Refused(val reason: String) : Change
        data class Ok(val effect: () -> Unit) : Change
    }

    @Volatile var installed = false

    private class Osd(val id: Int, val host: String, val deviceClass: String, val device: String, val total: Long, var used: Long, var inCluster: Boolean = true, var running: Boolean = true)
    private class Pool(val name: String, var size: Int, var minSize: Int, var pgNum: Int, var autoscale: String, val application: String, var used: Long)
    private class Daemon(val name: String, var running: Boolean = true)

    private val osds = mutableListOf(
        Osd(0, "pve1", "ssd", "/dev/nvme1n1", 960 * GIB, 403 * GIB),
        Osd(1, "pve1", "ssd", "/dev/nvme2n1", 960 * GIB, 385 * GIB),
        Osd(2, "pve2", "hdd", "/dev/sdd", 4000 * GIB, 2210 * GIB),
        Osd(3, "pve2", "hdd", "/dev/sde", 4000 * GIB, 2050 * GIB),
        Osd(4, "pve3", "hdd", "/dev/sdd", 4000 * GIB, 2130 * GIB),
        Osd(5, "pve3", "hdd", "/dev/sde", 4000 * GIB, 1990 * GIB, inCluster = false),
    )
    private val pools = mutableListOf(
        Pool(".mgr", 3, 2, 1, "on", "mgr", 12 * MIB),
        Pool("vm-pool", 3, 2, 128, "on", "rbd", 2600 * GIB),
        Pool("cephfs_data", 3, 2, 64, "on", "cephfs", 410 * GIB),
        Pool("cephfs_metadata", 3, 2, 16, "on", "cephfs", 2 * GIB),
    )
    private val mons = linkedMapOf("pve1" to Daemon("pve1"), "pve2" to Daemon("pve2"), "pve3" to Daemon("pve3"))
    private val mgrs = linkedMapOf("pve1" to Daemon("pve1"), "pve2" to Daemon("pve2"))
    private val mdss = linkedMapOf("pve1" to Daemon("pve1"), "pve2" to Daemon("pve2"))
    private val filesystems = linkedMapOf("cephfs" to ("cephfs_metadata" to "cephfs_data"))
    private val flags = sortedSetOf<String>()

    private fun Osd.up() = running && online(host)
    private fun Daemon.up() = running && online(name)

    // ---- reads

    fun healthStatus(): String = synchronized(this) { if (checks().isEmpty()) "HEALTH_OK" else "HEALTH_WARN" }

    private fun checks(): List<Triple<String, String, List<String>>> = buildList {
        val down = osds.filter { !it.up() }
        if (down.isNotEmpty()) add(Triple("OSD_DOWN", "${down.size} osds down", down.map { "osd.${it.id} (root=default,host=${it.host}) is down" }))
        val downHosts = osds.map { it.host }.distinct().filter { !online(it) }
        downHosts.forEach { h -> add(Triple("OSD_HOST_DOWN", "1 host (${osds.count { it.host == h }} osds) down", listOf("host $h (root=default) (${osds.count { it.host == h }} osds) is down"))) }
        val quorum = mons.values.filter { it.up() }.map { it.name }
        if (quorum.size < mons.size) add(Triple("MON_DOWN", "${mons.size - quorum.size}/${mons.size} mons down, quorum ${quorum.joinToString(",")}", emptyList()))
        if (flags.isNotEmpty()) add(Triple("OSDMAP_FLAGS", "${flags.joinToString(",")} flag(s) set", emptyList()))
        if (down.any { it.inCluster }) add(Triple("PG_DEGRADED", "Degraded data redundancy: 1204/3612 objects degraded (33.333%), 43 pgs degraded", emptyList()))
    }

    fun status(): JsonObject = synchronized(this) {
        val checks = checks()
        val pgs = pools.sumOf { it.pgNum }
        val degraded = if (osds.any { !it.up() && it.inCluster }) minOf(43, pgs) else 0
        val counted = osds.filter { it.up() && it.inCluster }
        buildJsonObject {
            put("fsid", "5f6c1a3e-2b1d-4c1e-9a8f-0d2c9b7e4a11")
            putJsonObject("health") {
                put("status", if (checks.isEmpty()) "HEALTH_OK" else "HEALTH_WARN")
                putJsonObject("checks") {
                    checks.forEach { (id, summary, detail) ->
                        putJsonObject(id) {
                            put("severity", "HEALTH_WARN")
                            putJsonObject("summary") {
                                put("message", summary)
                                put("count", 1)
                            }
                            putJsonArray("detail") { detail.forEach { d -> addJsonObject { put("message", d) } } }
                            put("muted", false)
                        }
                    }
                }
            }
            putJsonArray("quorum_names") { mons.values.filter { it.up() }.forEach { add(it.name) } }
            putJsonObject("monmap") { put("num_mons", mons.size) }
            putJsonObject("osdmap") {
                put("num_osds", osds.size)
                put("num_up_osds", osds.count { it.up() })
                put("num_in_osds", osds.count { it.inCluster })
            }
            putJsonObject("pgmap") {
                put("num_pgs", pgs)
                putJsonArray("pgs_by_state") {
                    if (pgs - degraded > 0) addJsonObject {
                        put("state_name", "active+clean")
                        put("count", pgs - degraded)
                    }
                    if (degraded > 0) addJsonObject {
                        put("state_name", "active+undersized+degraded")
                        put("count", degraded)
                    }
                }
                put("bytes_total", counted.sumOf { it.total })
                put("bytes_used", counted.sumOf { it.used })
                put("bytes_avail", counted.sumOf { it.total - it.used })
                put("read_bytes_sec", 18_400_000)
                put("write_bytes_sec", 6_200_000)
                put("read_op_per_sec", 412)
                put("write_op_per_sec", 158)
            }
            putJsonObject("mgrmap") {
                put("available", mgrs.values.any { it.up() })
                put("num_standbys", (mgrs.values.count { it.up() } - 1).coerceAtLeast(0))
            }
            putJsonObject("fsmap") {
                put("up", if (filesystems.isEmpty()) 0 else minOf(1, mdss.values.count { it.up() }))
                put("up:standby", (mdss.values.count { it.up() } - filesystems.size).coerceAtLeast(0))
            }
        }
    }

    fun osdTree(): JsonObject = synchronized(this) {
        buildJsonObject {
            putJsonObject("root") {
                put("leaf", 0)
                putJsonArray("children") {
                    addJsonObject {
                        put("id", -1)
                        put("name", "default")
                        put("type", "root")
                        putJsonArray("children") {
                            osds.map { it.host }.distinct().sorted().forEachIndexed { i, host ->
                                addJsonObject {
                                    put("id", -2 - i)
                                    put("name", host)
                                    put("type", "host")
                                    if (online(host)) put("version", "18.2.4")
                                    putJsonArray("children") {
                                        osds.filter { it.host == host }.forEach { o ->
                                            addJsonObject {
                                                put("id", o.id)
                                                put("name", "osd.${o.id}")
                                                put("type", "osd")
                                                put("host", o.host)
                                                put("status", if (o.up()) "up" else "down")
                                                put("in", if (o.inCluster) 1 else 0)
                                                put("device_class", o.deviceClass)
                                                put("crush_weight", o.total.toDouble() / (1L shl 40))
                                                put("total_space", o.total)
                                                put("bytes_used", o.used)
                                                put("percent_used", o.used * 100.0 / o.total)
                                                put("commit_latency_ms", if (o.deviceClass == "ssd") 1 else 9)
                                                put("apply_latency_ms", if (o.deviceClass == "ssd") 1 else 9)
                                                put("osdtype", "bluestore")
                                                put("blfsdev", o.device)
                                                put("ceph_version_short", "18.2.4")
                                                put("leaf", 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (flags.isNotEmpty()) put("flags", flags.joinToString(","))
        }
    }

    fun pools(): JsonArray = synchronized(this) {
        buildJsonArray {
            pools.forEachIndexed { i, p ->
                addJsonObject {
                    put("pool", i + 1)
                    put("pool_name", p.name)
                    put("type", "replicated")
                    put("size", p.size)
                    put("min_size", p.minSize)
                    put("pg_num", p.pgNum)
                    put("pg_num_final", p.pgNum)
                    put("pg_autoscale_mode", p.autoscale)
                    put("crush_rule", 0)
                    put("crush_rule_name", "replicated_rule")
                    put("bytes_used", p.used)
                    put("percent_used", p.used.toDouble() / (12_000 * GIB))
                    putJsonObject("application_metadata") { putJsonObject(p.application) {} }
                }
            }
        }
    }

    fun rules(): JsonArray = buildJsonArray { addJsonObject { put("name", "replicated_rule") } }

    fun services(type: String): JsonArray? = synchronized(this) {
        val daemons = daemons(type) ?: return null
        buildJsonArray {
            daemons.values.forEachIndexed { i, d ->
                addJsonObject {
                    put("name", d.name)
                    put("host", d.name)
                    put("addr", "10.0.0.1${d.name.removePrefix("pve")}:6789/0")
                    when (type) {
                        "mon" -> {
                            put("rank", i)
                            put("quorum", d.up())
                            put("state", if (d.up()) "running" else "stopped")
                        }
                        "mgr" -> put("state", if (!d.up()) "stopped" else if (daemons.values.first { it.up() } == d) "active" else "standby")
                        "mds" -> put("state", if (!d.up()) "stopped" else if (filesystems.isNotEmpty() && daemons.values.first { it.up() } == d) "up:active" else "up:standby")
                    }
                    if (d.up()) put("ceph_version_short", "18.2.4")
                }
            }
        }
    }

    fun filesystems(): JsonArray = synchronized(this) {
        buildJsonArray {
            filesystems.forEach { (name, p) ->
                addJsonObject {
                    put("name", name)
                    put("metadata_pool", p.first)
                    putJsonArray("data_pool") { add(p.second) }
                }
            }
        }
    }

    fun flags(): JsonArray = synchronized(this) {
        buildJsonArray {
            FLAGS.forEach { (name, description) ->
                addJsonObject {
                    put("name", name)
                    put("value", name in flags)
                    put("description", description)
                }
            }
        }
    }

    val config: String
        get() = """
            [global]
            	auth_client_required = cephx
            	auth_cluster_required = cephx
            	auth_service_required = cephx
            	cluster_network = 10.0.1.0/24
            	fsid = 5f6c1a3e-2b1d-4c1e-9a8f-0d2c9b7e4a11
            	mon_allow_pool_delete = true
            	mon_host = 10.0.0.11 10.0.0.12 10.0.0.13
            	osd_pool_default_min_size = 2
            	osd_pool_default_size = 3
            	public_network = 10.0.0.0/24

            [client]
            	keyring = /etc/pve/priv/${'$'}cluster.${'$'}name.keyring

            [mon.pve1]
            	public_addr = 10.0.0.11
        """.trimIndent() + "\n"

    fun configDb(): JsonArray = buildJsonArray {
        addJsonObject {
            put("section", "global")
            put("name", "osd_pool_default_pg_autoscale_mode")
            put("value", "on")
        }
        addJsonObject {
            put("section", "mgr")
            put("name", "mgr/dashboard/ssl")
            put("value", "false")
        }
    }

    fun crushMap(): String = synchronized(this) {
        buildString {
            appendLine("# begin crush map")
            appendLine("tunable choose_total_tries 50")
            appendLine()
            appendLine("# devices")
            osds.forEach { appendLine("device ${it.id} osd.${it.id} class ${it.deviceClass}") }
            appendLine()
            appendLine("# buckets")
            osds.map { it.host }.distinct().sorted().forEachIndexed { i, h ->
                appendLine("host $h {")
                appendLine("\tid ${-2 - i}")
                appendLine("\talg straw2")
                osds.filter { it.host == h }.forEach { appendLine("\titem osd.${it.id} weight %.3f".format(java.util.Locale.ROOT, it.total.toDouble() / (1L shl 40))) }
                appendLine("}")
            }
            appendLine()
            appendLine("# rules")
            appendLine("rule replicated_rule {")
            appendLine("\tid 0")
            appendLine("\ttype replicated")
            appendLine("\tstep take default")
            appendLine("\tstep chooseleaf firstn 0 type host")
            appendLine("\tstep emit")
            appendLine("}")
            appendLine("# end crush map")
        }
    }

    fun log(limit: Int): JsonArray = buildJsonArray {
        val lines = listOf(
            "2026-10-18T09:00:00.000000+0200 mon.pve1 (mon.0) 1201 : cluster [INF] overall HEALTH_OK",
            "2026-10-18T09:41:12.412000+0200 mon.pve1 (mon.0) 1250 : cluster [WRN] Health check failed: 1/3 mons down, quorum pve1,pve2 (MON_DOWN)",
            "2026-10-18T09:41:20.003000+0200 mon.pve1 (mon.0) 1253 : cluster [WRN] Health check failed: 2 osds down (OSD_DOWN)",
            "2026-10-18T09:41:20.004000+0200 mon.pve1 (mon.0) 1254 : cluster [WRN] Health check failed: 1 host (2 osds) down (OSD_HOST_DOWN)",
            "2026-10-18T09:51:31.880000+0200 mon.pve1 (mon.0) 1301 : cluster [INF] osd.5 marked itself out",
            "2026-10-18T10:00:00.000000+0200 mon.pve1 (mon.0) 1350 : cluster [WRN] overall HEALTH_WARN 1/3 mons down; 2 osds down; 1 host (2 osds) down",
        ).takeLast(limit)
        lines.forEachIndexed { i, t ->
            addJsonObject {
                put("n", i + 1)
                put("t", t)
            }
        }
    }

    // ---- changes

    fun setIn(id: Int, value: Boolean): String? = synchronized(this) {
        val osd = osds.firstOrNull { it.id == id } ?: return "osd.$id does not exist"
        osd.inCluster = value
        null
    }

    fun scrub(id: Int): String? = synchronized(this) { if (osds.none { it.id == id }) "osd.$id does not exist" else null }

    /** [node] is where the request went; [deviceClass] comes from the disk when not given. */
    fun createOsd(node: String, device: String, deviceClass: String): Change = synchronized(this) {
        if (osds.any { it.host == node && it.device == device }) return Change.Refused("device '$device' is already in use")
        Change.Ok {
            synchronized(this) {
                val id = (osds.maxOfOrNull { it.id } ?: -1) + 1
                osds += Osd(id, node, deviceClass, device, 1000 * GIB, 1 * GIB)
            }
            diskUsed(node, device, true)
        }
    }

    fun destroyOsd(node: String, id: Int, cleanup: Boolean): Change = synchronized(this) {
        val osd = osds.firstOrNull { it.id == id } ?: return Change.Refused("osd.$id does not exist")
        if (osd.host != node) return Change.Refused("OSD osd.$id does not belong to node $node!")
        if (osd.up()) return Change.Refused("osd is still running")
        if (osd.inCluster) return Change.Refused("osd is still in")
        Change.Ok {
            synchronized(this) { osds.remove(osd) }
            if (cleanup) diskUsed(node, osd.device, false)
        }
    }

    fun createPool(p: Map<String, String>): Change = synchronized(this) {
        val name = p["name"].orEmpty()
        if (name.isBlank()) return Change.Refused("name: property is missing and it is not optional")
        if (pools.any { it.name == name }) return Change.Refused("pool '$name' already exists")
        val size = p["size"]?.toIntOrNull() ?: 3
        val minSize = p["min_size"]?.toIntOrNull() ?: 2
        if (minSize > size) return Change.Refused("min_size ($minSize) must be smaller than or equal to size ($size)")
        val pool = Pool(name, size, minSize, p["pg_num"]?.toIntOrNull() ?: 128, p["pg_autoscale_mode"] ?: "warn", p["application"] ?: "rbd", 0)
        Change.Ok { synchronized(this) { pools += pool } }
    }

    fun updatePool(name: String, p: Map<String, String>): Change = synchronized(this) {
        val pool = pools.firstOrNull { it.name == name } ?: return Change.Refused("pool '$name' does not exist")
        val size = p["size"]?.toIntOrNull() ?: pool.size
        val minSize = p["min_size"]?.toIntOrNull() ?: pool.minSize
        if (minSize > size) return Change.Refused("min_size ($minSize) must be smaller than or equal to size ($size)")
        Change.Ok {
            synchronized(this) {
                pool.size = size
                pool.minSize = minSize
                p["pg_num"]?.toIntOrNull()?.let { pool.pgNum = it }
                p["pg_autoscale_mode"]?.let { pool.autoscale = it }
            }
        }
    }

    fun deletePool(name: String): Change = synchronized(this) {
        val pool = pools.firstOrNull { it.name == name } ?: return Change.Refused("pool '$name' does not exist")
        filesystems.entries.firstOrNull { name == it.value.first || name == it.value.second }?.let {
            return Change.Refused("pool '$name' is in use by CephFS '${it.key}'")
        }
        Change.Ok { synchronized(this) { pools.remove(pool) } }
    }

    fun createService(type: String, node: String): Change = synchronized(this) {
        val daemons = daemons(type) ?: return Change.Refused("unknown service type '$type'")
        if (node in daemons) return Change.Refused("$type '$node' already exists")
        Change.Ok { synchronized(this) { daemons[node] = Daemon(node) } }
    }

    fun destroyService(type: String, name: String): Change = synchronized(this) {
        val daemons = daemons(type) ?: return Change.Refused("unknown service type '$type'")
        if (name !in daemons) return Change.Refused("no such $type '$name'")
        if (type == "mon" && daemons.size <= 1) return Change.Refused("can't remove last monitor")
        Change.Ok { synchronized(this) { daemons.remove(name) } }
    }

    /** [service] like `osd.3`, `mon.pve1`; [action] `start`, `stop` or `restart`. */
    fun serviceAction(node: String, action: String, service: String): Change = synchronized(this) {
        val type = service.substringBefore('.')
        val name = service.substringAfter('.', "")
        val running = action != "stop"
        if (type == "osd") {
            val osd = osds.firstOrNull { it.id.toString() == name } ?: return Change.Refused("osd.$name does not exist")
            if (osd.host != node) return Change.Refused("osd.$name is not on node $node")
            return Change.Ok { synchronized(this) { osd.running = running } }
        }
        val daemon = daemons(type)?.get(name) ?: return Change.Refused("unknown service '$service'")
        Change.Ok { synchronized(this) { daemon.running = running } }
    }

    fun createFs(name: String, p: Map<String, String>): Change = synchronized(this) {
        if (name in filesystems) return Change.Refused("ceph fs '$name' already exists")
        if (mdss.values.count { it.up() } <= filesystems.size) return Change.Refused("need MDS to add storage, but none got active!")
        val pgNum = p["pg_num"]?.toIntOrNull() ?: 128
        Change.Ok {
            synchronized(this) {
                pools += Pool("${name}_data", 3, 2, pgNum, "on", "cephfs", 0)
                pools += Pool("${name}_metadata", 3, 2, pgNum / 4, "on", "cephfs", 0)
                filesystems[name] = "${name}_metadata" to "${name}_data"
            }
        }
    }

    fun setFlag(name: String, value: Boolean): String? = synchronized(this) {
        if (FLAGS.none { it.first == name }) return "flag: value '$name' does not have a value in the enumeration"
        if (value) flags += name else flags -= name
        null
    }

    private fun daemons(type: String): MutableMap<String, Daemon>? = when (type) {
        "mon" -> mons
        "mgr" -> mgrs
        "mds" -> mdss
        else -> null
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val GIB = 1024 * MIB

        val FLAGS = listOf(
            "nobackfill" to "Backfilling of PGs is suspended.",
            "nodeep-scrub" to "Deep Scrubbing is disabled.",
            "nodown" to "OSD failure reports are being ignored, such that the monitors will not mark OSDs down.",
            "noin" to "OSDs that were previously marked out will not be marked back in when they start.",
            "noout" to "OSDs will not automatically be marked out after the configured interval.",
            "norebalance" to "Rebalancing of PGs is suspended.",
            "norecover" to "Recovery of PGs is suspended.",
            "noscrub" to "Scrubbing is disabled.",
            "notieragent" to "Cache tiering activity is suspended.",
            "noup" to "OSDs are not allowed to start.",
            "pause" to "Pauses read and writes.",
        )

        /** What Proxmox answers on a cluster where Ceph isn't installed. */
        const val NOT_INSTALLED = "binary not installed: /usr/bin/ceph-mon"
    }
}
