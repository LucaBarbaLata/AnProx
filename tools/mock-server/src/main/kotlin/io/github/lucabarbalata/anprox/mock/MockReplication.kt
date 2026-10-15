package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Storage replication: jobs in `/cluster/replication` and their runs as each node reports them.
 * Seeded with k8s-master copied to pve1 (in sync) and nginx-proxy copied to the offline pve3
 * (failing). "Run now" runs a job at once; Proxmox starts it within a minute.
 */
internal class MockReplication(private val clockMillis: () -> Long) {
    private class Run(var lastSync: Long = 0, var lastTry: Long = 0, var duration: Double = 0.0, var failCount: Int = 0, var error: String? = null, var log: List<String> = emptyList())

    private val jobs = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private val runs = HashMap<String, Run>()
    private var version = 1

    init {
        val now = clockMillis() / 1000
        jobs["103-0"] = linkedMapOf("type" to "local", "guest" to "103", "jobnum" to "0", "target" to "pve1")
        runs["103-0"] = Run(lastSync = now - 6 * 60, lastTry = now - 6 * 60, duration = 3.41, log = okLog("103-0", "pve1"))
        jobs["201-0"] = linkedMapOf("type" to "local", "guest" to "201", "jobnum" to "0", "target" to "pve3", "schedule" to "*/30", "comment" to "Copy to the lab node")
        runs["201-0"] = Run(lastSync = now - 3 * 3600, lastTry = now - 20 * 60, duration = 0.62, failCount = 3, error = UNREACHABLE)
        runs["201-0"]!!.log = failLog("201-0")
    }

    private val digest get() = "repl%04d".format(version)

    fun list(): List<JsonObject> = jobs.map { (id, o) ->
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(id))
                put("digest", JsonPrimitive(digest))
                // Proxmox VE 8 sends numbers and flags as numbers.
                o.forEach { (k, v) -> put(k, if (k in NUMERIC) JsonPrimitive(v.toDouble().let { d -> if (d % 1.0 == 0.0) d.toLong() else d }) else JsonPrimitive(v)) }
            },
        )
    }

    /** The jobs of guests on [node], with their runs. */
    fun status(node: String, guestNode: (Int) -> String?): List<JsonObject> = jobs.filter { (_, o) -> guestNode(o["guest"]!!.toInt()) == node }.map { (id, o) ->
        val run = runs.getOrPut(id) { Run() }
        val schedule = o["schedule"] ?: "*/15"
        val next = MockSchedule.next(schedule, maxOf(run.lastTry, clockMillis() / 1000 - 60), 1)?.firstOrNull()
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(id))
                put("guest", JsonPrimitive(o["guest"]!!.toInt()))
                put("jobnum", JsonPrimitive(o["jobnum"]!!.toInt()))
                put("target", JsonPrimitive(o["target"]))
                put("type", JsonPrimitive("local"))
                put("source", JsonPrimitive(node))
                put("schedule", JsonPrimitive(schedule))
                put("last_sync", JsonPrimitive(run.lastSync))
                put("last_try", JsonPrimitive(run.lastTry))
                put("duration", JsonPrimitive(run.duration))
                put("fail_count", JsonPrimitive(run.failCount))
                if (o["disable"] != "1") next?.let { put("next_sync", JsonPrimitive(it)) }
                run.error?.let { put("error", JsonPrimitive(it)) }
            },
        )
    }

    fun create(params: Map<String, String>, guestNode: (Int) -> String?, nodeExists: (String) -> Boolean): String? {
        val id = params["id"].orEmpty()
        val match = Regex("^(\\d+)-(\\d+)$").matchEntire(id) ?: return "id: invalid format - replication job id '$id' invalid"
        val guest = match.groupValues[1].toInt()
        val source = guestNode(guest) ?: return "guest '$guest' does not exist"
        if (id in jobs) return "replication job '$id' already exists"
        val target = params["target"].orEmpty()
        if (!nodeExists(target)) return "target: no such cluster node '$target'"
        if (target == source) return "replication target node '$target' is the same as the source node"
        if (jobs.values.any { it["guest"] == guest.toString() && it["target"] == target }) return "guest '$guest' is already replicated to node '$target'"
        validate(params)?.let { return it }
        jobs[id] = LinkedHashMap(params.filterKeys { it != "id" }).apply {
            put("guest", guest.toString())
            put("jobnum", match.groupValues[2])
        }
        version++
        return null
    }

    fun update(id: String, params: Map<String, String>): String? {
        val o = jobs[id] ?: return "replication job '$id' does not exist"
        params["digest"]?.let { if (it != digest) return "detected modified configuration - file changed by other user? Try again." }
        validate(params)?.let { return it }
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> o[k] = v }
        params["delete"].orEmpty().split(',').filter { it.isNotBlank() }.forEach { o.remove(it) }
        version++
        return null
    }

    /** Proxmox marks the job and its next run removes the copies; the mock removes it at once. */
    fun delete(id: String): String? {
        jobs.remove(id) ?: return "replication job '$id' does not exist"
        runs.remove(id)
        version++
        return null
    }

    /** Runs the job now: it fails while the target node is offline. */
    fun runNow(id: String, targetOnline: (String) -> Boolean): String? {
        val o = jobs[id] ?: return "replication job '$id' does not exist"
        val run = runs.getOrPut(id) { Run() }
        val now = clockMillis() / 1000
        run.lastTry = now
        if (targetOnline(o["target"]!!)) {
            run.lastSync = now
            run.duration = 2.17
            run.failCount = 0
            run.error = null
            run.log = okLog(id, o["target"]!!)
        } else {
            run.failCount++
            run.duration = 0.58
            run.error = UNREACHABLE
            run.log = failLog(id)
        }
        return null
    }

    fun log(id: String): List<TaskLogLine>? = runs[id]?.log?.mapIndexed { i, t -> TaskLogLine(i + 1, t) } ?: if (id in jobs) emptyList() else null

    private fun validate(p: Map<String, String>): String? {
        p["schedule"]?.let { if (it.isBlank() || MockSchedule.next(it, 0, 1) == null) return "schedule: value '$it' does not look like a valid calendar event" }
        p["rate"]?.let { r -> if ((r.toDoubleOrNull() ?: 0.0) < 1.0) return "rate: value must have a minimum value of 1" }
        return null
    }

    private fun okLog(id: String, target: String) = listOf(
        "$TIME $id: start replication job",
        "$TIME $id: guest => ${id.substringBefore('-')}, running => 1",
        "$TIME $id: volumes => local-zfs:vm-${id.substringBefore('-')}-disk-0",
        "$TIME $id: create snapshot '__replicate_${id}_${clockMillis() / 1000}__' on local-zfs:vm-${id.substringBefore('-')}-disk-0",
        "$TIME $id: using secure transmission, rate limit: none",
        "$TIME $id: incremental sync 'local-zfs:vm-${id.substringBefore('-')}-disk-0' to $target",
        "$TIME $id: end replication job",
    )

    private fun failLog(id: String) = listOf(
        "$TIME $id: start replication job",
        "$TIME $id: end replication job with error: $UNREACHABLE",
    )

    private companion object {
        val NUMERIC = setOf("guest", "jobnum", "disable", "rate")
        const val TIME = "2026-10-15 10:00:00"
        const val UNREACHABLE = "command '/usr/bin/ssh -e none -o 'BatchMode=yes' -o 'HostKeyAlias=pve3' root@10.0.0.13 -- /usr/bin/true' failed: exit code 255"
    }
}
