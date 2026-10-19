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
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A Backup Server's content and jobs. `store1` has namespaces `prod`, `prod/db` and `lab` and the
 * backups of the homelab (VMs 100 and 101, container 200 with a browsable catalog, host pve1);
 * `store2` holds synced copies, one of which failed verification. Jobs: a sync from the remote
 * `offsite`, a weekly verify that failed last time and a prune job.
 */
internal class MockPbs(private val clockSeconds: () -> Long) {
    private class Snapshot(val type: String, val id: String, val time: Long, val size: Long, var notes: String? = null, var verify: String? = null, var protected: Boolean = false) {
        val catalog: Boolean get() = type != "vm"
    }

    private class Store(val comment: String, val namespaces: MutableMap<String, String?>, val snapshots: MutableMap<String, MutableList<Snapshot>>, var maintenance: String? = null, var lastGc: Long, var gcRemoved: Long)

    private class Job(val kind: String, val id: String, val store: String, val schedule: String, var lastState: String, var lastEnd: Long, val extra: Map<String, String>, var disabled: Boolean = false)

    private val now0 = clockSeconds()
    private fun ago(days: Int, hours: Int = 0) = now0 - days * 86_400L - hours * 3_600L

    private val stores = linkedMapOf(
        "store1" to Store(
            "Primary ZFS mirror",
            linkedMapOf("" to null, "prod" to "Production", "prod/db" to "Databases", "lab" to null),
            linkedMapOf(
                "" to mutableListOf(
                    Snapshot("vm", "100", ago(0, 3), 12 * GIB, notes = "web", verify = "ok"),
                    Snapshot("vm", "100", ago(1, 3), 12 * GIB, notes = "web", verify = "ok"),
                    Snapshot("vm", "100", ago(2, 3), 11 * GIB, notes = "web"),
                    Snapshot("vm", "101", ago(0, 4), 48 * GIB, notes = "db", verify = "ok", protected = true),
                    Snapshot("vm", "101", ago(7, 4), 47 * GIB, notes = "db"),
                    Snapshot("ct", "200", ago(0, 5), 900 * MIB, notes = "pihole", verify = "ok"),
                    Snapshot("ct", "200", ago(1, 5), 890 * MIB, notes = "pihole"),
                    Snapshot("host", "pve1", ago(3), 2 * GIB, notes = "etc and /root of pve1"),
                ),
                "prod" to mutableListOf(Snapshot("vm", "100", ago(0, 2), 12 * GIB, notes = "web, before the upgrade")),
                "prod/db" to mutableListOf(),
                "lab" to mutableListOf(),
            ),
            lastGc = ago(0, 6),
            gcRemoved = 3 * GIB,
        ),
        "store2" to Store(
            "Offsite copy",
            linkedMapOf("" to null),
            linkedMapOf(
                "" to mutableListOf(
                    Snapshot("vm", "100", ago(1, 1), 12 * GIB, verify = "ok"),
                    Snapshot("vm", "100", ago(2, 1), 11 * GIB, verify = "failed"),
                ),
            ),
            lastGc = ago(2),
            gcRemoved = 512 * MIB,
        ),
    )

    private val remotes = linkedMapOf(
        "offsite" to linkedMapOf("host" to "pbs2.example.net", "auth-id" to "sync@pbs", "fingerprint" to "ab:".repeat(31) + "cd", "comment" to "Second site"),
    )

    private val jobs = mutableListOf(
        Job("sync", "s-offsite", "store2", "daily", "sync failed: remote unreachable", ago(0, 2), mapOf("remote" to "offsite", "remote-store" to "backup")),
        Job("verify", "v-weekly", "store1", "sat 03:00", "verification failed: 1 snapshot", ago(4), emptyMap()),
        Job("prune", "p-keep", "store1", "daily", "OK", ago(0, 1), mapOf("keep-daily" to "14", "keep-weekly" to "4")),
    )

    private fun store(name: String) = stores[name]

    private fun list(s: Store, ns: String) = s.snapshots[ns]

    // ---- datastore

    fun status(store: String): JsonObject? = synchronized(this) {
        val s = store(store) ?: return null
        buildJsonObject {
            put("total", if (store == "store1") 16_000 * GIB else 8_000 * GIB)
            put("used", if (store == "store1") 11_200 * GIB else 7_400 * GIB)
            put("avail", if (store == "store1") 4_800 * GIB else 600 * GIB)
            putJsonObject("counts") {
                listOf("vm", "ct", "host").forEach { type ->
                    val all = s.snapshots.values.flatten().filter { it.type == type }
                    if (all.isNotEmpty()) putJsonObject(type) {
                        put("groups", all.map { it.id }.distinct().size)
                        put("snapshots", all.size)
                    }
                }
            }
            putJsonObject("gc-status") { gcFields(s) }
        }
    }

    fun gc(store: String): JsonObject? = synchronized(this) {
        val s = store(store) ?: return null
        buildJsonObject {
            put("store", store)
            put("last-run-state", "OK")
            put("last-run-endtime", s.lastGc)
            put("next-run", s.lastGc + 86_400)
            put("schedule", "daily")
            gcFields(s)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.gcFields(s: Store) {
        put("removed-bytes", s.gcRemoved)
        put("pending-bytes", 1 * GIB)
        put("disk-bytes", 9_000 * GIB)
        put("index-data-bytes", 27_000 * GIB)
    }

    /** Starts garbage collection: refused while the datastore is offline. */
    fun startGc(store: String): Pair<String?, (() -> Unit)?> = synchronized(this) {
        val s = store(store) ?: return "datastore '$store' does not exist" to null
        if (s.maintenance?.contains("offline") == true) return "datastore is in maintenance mode (offline)" to null
        null to { synchronized(this) { s.lastGc = clockSeconds(); s.gcRemoved = 640 * MIB } }
    }

    fun config(store: String): JsonObject? = synchronized(this) {
        val s = store(store) ?: return null
        buildJsonObject {
            put("name", store)
            put("path", "/mnt/datastore/$store")
            put("comment", s.comment)
            put("gc-schedule", "daily")
            s.maintenance?.let { put("maintenance-mode", it) }
        }
    }

    fun updateConfig(store: String, p: Map<String, String>): String? = synchronized(this) {
        val s = store(store) ?: return "datastore '$store' does not exist"
        if (p["delete"]?.split(',')?.contains("maintenance-mode") == true) s.maintenance = null
        p["maintenance-mode"]?.let { mode ->
            val type = mode.split(',').firstOrNull { it.startsWith("type=") }?.substringAfter('=')
            if (type !in setOf("read-only", "offline")) return "maintenance-mode: invalid type"
            s.maintenance = mode
        }
        null
    }

    // ---- namespaces

    fun namespaces(store: String): JsonArray? = synchronized(this) {
        val s = store(store) ?: return null
        buildJsonArray {
            s.namespaces.forEach { (ns, comment) ->
                addJsonObject {
                    put("ns", ns)
                    comment?.let { put("comment", it) }
                }
            }
        }
    }

    fun createNamespace(store: String, name: String, parent: String?): String? = synchronized(this) {
        val s = store(store) ?: return "datastore '$store' does not exist"
        if (!Regex("^[A-Za-z0-9_][A-Za-z0-9._\\-]{0,30}$").matches(name)) return "name: invalid namespace name"
        val p = parent.orEmpty()
        if (p !in s.namespaces) return "parent namespace '$p' does not exist"
        val ns = if (p.isEmpty()) name else "$p/$name"
        if (ns.count { it == '/' } >= 7) return "namespace depth exceeds maximum of 7"
        if (ns in s.namespaces) return "namespace '$ns' already exists"
        s.namespaces[ns] = null
        s.snapshots[ns] = mutableListOf()
        null
    }

    fun deleteNamespace(store: String, ns: String, deleteGroups: Boolean): String? = synchronized(this) {
        val s = store(store) ?: return "datastore '$store' does not exist"
        if (ns.isEmpty()) return "cannot delete the root namespace"
        if (ns !in s.namespaces) return "namespace '$ns' does not exist"
        val below = s.namespaces.keys.filter { it == ns || it.startsWith("$ns/") }
        if (!deleteGroups && below.any { s.snapshots[it].orEmpty().isNotEmpty() }) return "namespace '$ns' is not empty"
        if (below.any { n -> s.snapshots[n].orEmpty().any { it.protected } }) return "cannot remove namespace '$ns', it contains protected snapshots"
        below.forEach { s.namespaces.remove(it); s.snapshots.remove(it) }
        null
    }

    // ---- groups and snapshots

    fun groups(store: String, ns: String): JsonArray? = synchronized(this) {
        val all = store(store)?.let { list(it, ns) } ?: return null
        buildJsonArray {
            all.groupBy { it.type to it.id }.forEach { (key, snaps) ->
                addJsonObject {
                    put("backup-type", key.first)
                    put("backup-id", key.second)
                    put("last-backup", snaps.maxOf { it.time })
                    put("backup-count", snaps.size)
                    put("owner", "root@pam")
                    snaps.maxBy { it.time }.notes?.let { put("comment", it) }
                }
            }
        }
    }

    fun snapshots(store: String, ns: String, type: String?, id: String?): JsonArray? = synchronized(this) {
        val all = store(store)?.let { list(it, ns) } ?: return null
        buildJsonArray {
            all.filter { (type == null || it.type == type) && (id == null || it.id == id) }.forEach { s ->
                addJsonObject {
                    put("backup-type", s.type)
                    put("backup-id", s.id)
                    put("backup-time", s.time)
                    put("size", s.size)
                    s.notes?.let { put("comment", it) }
                    s.verify?.let { v ->
                        putJsonObject("verification") {
                            put("state", v)
                            put("upid", "UPID:pbs:0000:0:0:0:verify:${s.type}/${s.id}:root@pam:")
                        }
                    }
                    put("protected", s.protected)
                    put("owner", "root@pam")
                    putJsonArray("files") {
                        addJsonObject {
                            put("filename", "index.json.blob")
                            put("size", 600)
                            put("crypt-mode", "none")
                        }
                        if (s.catalog) {
                            addJsonObject {
                                put("filename", if (s.type == "host") "etc.pxar.didx" else "root.pxar.didx")
                                put("size", s.size)
                                put("crypt-mode", "none")
                            }
                            addJsonObject {
                                put("filename", "catalog.pcat1.didx")
                                put("size", 40_000)
                                put("crypt-mode", "none")
                            }
                        } else {
                            addJsonObject {
                                put("filename", "drive-scsi0.img.fidx")
                                put("size", s.size)
                                put("crypt-mode", "none")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun find(store: String, ns: String, p: Map<String, String>): Snapshot? =
        store(store)?.let { list(it, ns) }?.firstOrNull { it.type == p["backup-type"] && it.id == p["backup-id"] && it.time.toString() == p["backup-time"] }

    fun setNotes(store: String, ns: String, p: Map<String, String>): String? = synchronized(this) {
        val s = find(store, ns, p) ?: return "snapshot not found"
        s.notes = p["notes"]?.takeIf { it.isNotEmpty() }
        null
    }

    fun setProtected(store: String, ns: String, p: Map<String, String>): String? = synchronized(this) {
        val s = find(store, ns, p) ?: return "snapshot not found"
        s.protected = p["protected"] == "1" || p["protected"] == "true"
        null
    }

    fun deleteSnapshot(store: String, ns: String, p: Map<String, String>): String? = synchronized(this) {
        val s = find(store, ns, p) ?: return "snapshot not found"
        if (s.protected) return "cannot remove protected snapshot"
        store(store)!!.snapshots[ns]!!.remove(s)
        null
    }

    fun deleteGroup(store: String, ns: String, type: String, id: String): String? = synchronized(this) {
        val all = store(store)?.let { list(it, ns) } ?: return "datastore '$store' does not exist"
        val group = all.filter { it.type == type && it.id == id }
        if (group.isEmpty()) return "group '$type/$id' not found"
        if (group.any { it.protected }) return "cannot remove group '$type/$id', it contains protected snapshots"
        all.removeAll(group)
        null
    }

    /** What a verify covers; its effect marks those snapshots verified. */
    fun verify(store: String, ns: String, p: Map<String, String>): Pair<String?, (() -> Unit)?> = synchronized(this) {
        val all = store(store)?.let { list(it, ns) } ?: return "datastore '$store' does not exist" to null
        val targets = all.filter { s ->
            (p["backup-type"] == null || s.type == p["backup-type"]) && (p["backup-id"] == null || s.id == p["backup-id"]) &&
                (p["backup-time"] == null || s.time.toString() == p["backup-time"]) && (p["ignore-verified"] != "1" || s.verify == null)
        }
        if (p["backup-time"] != null && targets.isEmpty()) return "snapshot not found" to null
        null to { synchronized(this) { targets.forEach { it.verify = "ok" } } }
    }

    /** Like PBS: each rule keeps the newest snapshot of each hour/day/week/… until its count is reached; protected ones always stay. */
    fun prune(store: String, ns: String, p: Map<String, String>): Pair<String?, JsonArray?> = synchronized(this) {
        val all = store(store)?.let { list(it, ns) } ?: return "datastore '$store' does not exist" to null
        val group = all.filter { it.type == p["backup-type"] && it.id == p["backup-id"] }.sortedByDescending { it.time }
        val rules = listOf<Pair<String, (Long) -> String>>(
            "keep-last" to { t -> t.toString() },
            "keep-hourly" to { t -> Instant.ofEpochSecond(t).truncatedTo(ChronoUnit.HOURS).toString() },
            "keep-daily" to { t -> Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).toLocalDate().toString() },
            "keep-weekly" to { t -> Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).let { "${it.get(IsoFields.WEEK_BASED_YEAR)}-${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" } },
            "keep-monthly" to { t -> Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).let { "${it.year}-${it.monthValue}" } },
            "keep-yearly" to { t -> Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).year.toString() },
        )
        if (rules.none { p[it.first] != null }) return "keep options: at least one is required" to null
        val keep = mutableSetOf<Snapshot>()
        for ((name, key) in rules) {
            val count = p[name]?.toIntOrNull() ?: continue
            val seen = mutableSetOf<String>()
            for (s in group) {
                if (seen.size >= count) break
                val k = key(s.time)
                if (k in seen) continue
                seen += k
                keep += s
            }
        }
        val result = buildJsonArray {
            group.forEach { s ->
                addJsonObject {
                    put("backup-type", s.type)
                    put("backup-id", s.id)
                    put("backup-time", s.time)
                    put("keep", s in keep || s.protected)
                    put("protected", s.protected)
                }
            }
        }
        if (p["dry-run"] != "1") all.removeAll(group.filter { it !in keep && !it.protected })
        null to result
    }

    // ---- catalog and files

    private val tree: Map<String, List<Pair<String, String>>> = mapOf(
        // directory → (name, type); files hold FILES[path]
        "/" to listOf("root.pxar.didx" to "d", "catalog.pcat1.didx" to "f"),
        "/root.pxar.didx" to listOf("etc" to "d", "root" to "d", "var" to "d"),
        "/root.pxar.didx/etc" to listOf("hostname" to "f", "hosts" to "f", "pihole" to "d"),
        "/root.pxar.didx/etc/pihole" to listOf("pihole.toml" to "f"),
        "/root.pxar.didx/root" to listOf(".bashrc" to "f"),
        "/root.pxar.didx/var" to listOf("log" to "d"),
        "/root.pxar.didx/var/log" to listOf("pihole.log" to "f"),
    )

    private val files = mapOf(
        "/root.pxar.didx/etc/hostname" to "pihole\n",
        "/root.pxar.didx/etc/hosts" to "127.0.0.1 localhost\n10.10.0.101 pihole.lan pihole\n",
        "/root.pxar.didx/etc/pihole/pihole.toml" to "[dns]\n  upstreams = [\"9.9.9.9\"]\n",
        "/root.pxar.didx/root/.bashrc" to "alias ll='ls -l'\n",
        "/root.pxar.didx/var/log/pihole.log" to "Oct 18 10:00:01 dnsmasq[1]: query[A] proxmox.com from 10.10.0.20\n",
    )

    private fun decode(filepath: String): String =
        if (filepath == "root" || filepath == "/") "/" else String(Base64.getDecoder().decode(filepath), Charsets.UTF_8)

    private fun encode(path: String): String = Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))

    fun catalog(store: String, ns: String, p: Map<String, String>): Pair<String?, JsonArray?> = synchronized(this) {
        val s = find(store, ns, p) ?: return "snapshot not found" to null
        if (!s.catalog) return "no catalog found (VM images have none; use file restore in Proxmox VE)" to null
        val path = runCatching { decode(p["filepath"].orEmpty()) }.getOrNull() ?: return "filepath: invalid base64" to null
        val entries = tree[path.trimEnd('/').ifEmpty { "/" }] ?: return "unable to find directory '$path'" to null
        null to buildJsonArray {
            entries.forEach { (name, type) ->
                val full = (if (path == "/") "" else path.trimEnd('/')) + "/" + name
                addJsonObject {
                    put("filepath", encode(full))
                    put("text", name)
                    put("type", type)
                    put("leaf", type != "d")
                    if (type == "f") put("size", files[full]?.length ?: 40_000)
                    put("mtime", s.time)
                }
            }
        }
    }

    /** A file's bytes, or a directory as a zip. */
    fun download(store: String, ns: String, p: Map<String, String>): Pair<String?, ByteArray?> = synchronized(this) {
        val s = find(store, ns, p) ?: return "snapshot not found" to null
        if (!s.catalog) return "no catalog found" to null
        val path = runCatching { decode(p["filepath"].orEmpty()) }.getOrNull() ?: return "filepath: invalid base64" to null
        files[path]?.let { return null to it.toByteArray() }
        if (path in tree) {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                files.filterKeys { it.startsWith("$path/") }.forEach { (f, content) ->
                    zip.putNextEntry(ZipEntry(f.removePrefix("$path/")))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            return null to out.toByteArray()
        }
        "file '$path' not found" to null
    }

    // ---- jobs and remotes

    fun jobs(kind: String): JsonArray = synchronized(this) {
        buildJsonArray {
            jobs.filter { it.kind == kind }.forEach { j ->
                addJsonObject {
                    put("id", j.id)
                    put("store", j.store)
                    put("schedule", j.schedule)
                    put("last-run-state", j.lastState)
                    put("last-run-endtime", j.lastEnd)
                    put("last-run-upid", "UPID:pbs:0000:0:0:0:${j.kind}job:${j.store}:${j.id}:root@pam:")
                    put("next-run", j.lastEnd + 86_400)
                    if (j.disabled) put("disable", true)
                    j.extra.forEach { (k, v) -> if (v.toIntOrNull() != null) put(k, v.toInt()) else put(k, v) }
                }
            }
        }
    }

    fun runJob(kind: String, id: String): Pair<String?, (() -> Unit)?> = synchronized(this) {
        val job = jobs.firstOrNull { it.kind == kind && it.id == id } ?: return "job '$id' does not exist" to null
        if (job.kind == "sync" && job.extra["remote"] !in remotes) return "remote '${job.extra["remote"]}' does not exist" to null
        null to { synchronized(this) { job.lastState = "OK"; job.lastEnd = clockSeconds() } }
    }

    fun setPruneDisabled(id: String, p: Map<String, String>): String? = synchronized(this) {
        val job = jobs.firstOrNull { it.kind == "prune" && it.id == id } ?: return "prune job '$id' does not exist"
        if (p["delete"]?.split(',')?.contains("disable") == true) job.disabled = false
        if (p["disable"] == "1" || p["disable"] == "true") job.disabled = true
        null
    }

    fun remotes(): JsonArray = synchronized(this) {
        buildJsonArray {
            remotes.forEach { (name, r) ->
                addJsonObject {
                    put("name", name)
                    r.forEach { (k, v) -> put(k, v) }
                }
            }
        }
    }

    fun createRemote(p: Map<String, String>): String? = synchronized(this) {
        val name = p["name"].orEmpty()
        if (!Regex("^[A-Za-z0-9_][A-Za-z0-9._\\-]{2,31}$").matches(name)) return "name: invalid format"
        if (name in remotes) return "remote '$name' already exists"
        if (p["host"].isNullOrBlank() || p["auth-id"].isNullOrBlank() || p["password"].isNullOrEmpty()) return "host, auth-id and password are required"
        remotes[name] = LinkedHashMap(p.filterKeys { it != "name" && it != "password" })
        null
    }

    fun deleteRemote(name: String): String? = synchronized(this) {
        if (name !in remotes) return "remote '$name' does not exist"
        jobs.firstOrNull { it.extra["remote"] == name }?.let { return "remote '$name' is used by sync job '${it.id}'" }
        remotes.remove(name)
        null
    }

    fun nodeStatus(): JsonObject = buildJsonObject {
        put("cpu", 0.06)
        putJsonObject("memory") {
            put("total", 16 * GIB)
            put("used", 5 * GIB)
            put("free", 11 * GIB)
        }
        putJsonObject("root") {
            put("total", 64 * GIB)
            put("used", 9 * GIB)
            put("avail", 55 * GIB)
        }
        put("uptime", 1_209_600)
        putJsonArray("loadavg") {
            add(0.12)
            add(0.18)
            add(0.2)
        }
        putJsonObject("current-kernel") {
            put("sysname", "Linux")
            put("release", "6.8.12-4-pve")
        }
        putJsonObject("cpuinfo") {
            put("model", "Intel(R) Xeon(R) E-2236 CPU @ 3.40GHz")
            put("cpus", 12)
        }
        putJsonObject("info") { put("fingerprint", "12:".repeat(31) + "34") }
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val GIB = 1024 * MIB
    }
}
