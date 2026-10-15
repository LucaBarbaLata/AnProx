package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.NotBackedUp
import io.github.lucabarbalata.anprox.model.pve.ScheduledRun
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Datacenter → Backup: scheduled backup jobs and the storage configuration.
 * Jobs keep their options as Proxmox's flat key/value pairs, like `/etc/pve/jobs.cfg`.
 */
internal class MockJobs(
    private val storages: List<MockState.Storage>,
    private val clockMillis: () -> Long,
    private val pools: MockPools,
) {
    private val jobs = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private var counter = 0


    init {
        add(linkedMapOf("schedule" to "21:00", "storage" to "pbs", "all" to "1", "mode" to "snapshot", "enabled" to "1",
            "prune-backups" to "keep-daily=7,keep-weekly=4", "notes-template" to "{{guestname}}"))
        add(linkedMapOf("schedule" to "sun 01:00", "storage" to "local", "vmid" to "200,201", "mode" to "stop", "enabled" to "0",
            "compress" to "zstd", "node" to "pve1", "comment" to "Containers, weekly"))
    }

    private fun add(options: LinkedHashMap<String, String>): String {
        val id = "backup-%08x".format(0x5eed0000 + ++counter)
        jobs[id] = options
        return id
    }

    fun list(): List<JsonObject> = jobs.map { (id, o) ->
        val next = if (o["enabled"] != "0") MockSchedule.next(o["schedule"].orEmpty(), clockMillis() / 1000, 1)?.firstOrNull() else null
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(id))
                put("type", JsonPrimitive("vzdump"))
                // Proxmox VE 8 sends flags as numbers and newer versions send retention as an object; mimic both.
                o.forEach { (k, v) -> put(k, if (k == "enabled" || k == "all") JsonPrimitive(v.toInt()) else JsonPrimitive(v)) }
                o["prune-backups"]?.let { prune ->
                    put("prune-backups", JsonObject(prune.split(',').associate { it.substringBefore('=') to JsonPrimitive(it.substringAfter('=')) }))
                }
                next?.let { put("next-run", JsonPrimitive(it)) }
            },
        )
    }

    fun job(id: String): Map<String, String>? = jobs[id]

    private fun validate(o: Map<String, String>): String? {
        val schedule = o["schedule"]
        if (schedule.isNullOrBlank() || MockSchedule.next(schedule, 0, 1) == null) return "value '${schedule.orEmpty()}' does not look like a valid calendar event"
        val storage = o["storage"] ?: return "storage: property is missing and it is not optional"
        val s = storages.firstOrNull { it.id == storage } ?: return "storage '$storage' does not exist"
        if ("backup" !in s.content.split(',')) return "storage '$storage' does not support backups"
        if (o["mode"] != null && o["mode"] !in listOf("snapshot", "suspend", "stop")) return "mode: value '${o["mode"]}' does not have a value in the enumeration"
        val covers = o["all"] == "1" || !o["vmid"].isNullOrBlank() || !o["pool"].isNullOrBlank()
        if (!covers) return "please specify either 'all', 'vmid' or 'pool'"
        return null
    }

    fun create(params: Map<String, String>): String? {
        val o = LinkedHashMap(params.filterKeys { it != "id" })
        o.putIfAbsent("enabled", "1")
        validate(o)?.let { return it }
        add(o)
        return null
    }

    fun update(id: String, params: Map<String, String>): String? {
        val current = jobs[id] ?: return "Job '$id' does not exist"
        val next = LinkedHashMap(current)
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> next[k] = v }
        params["delete"].orEmpty().split(',', ';', ' ').filter { it.isNotBlank() }.forEach { next.remove(it) }
        validate(next)?.let { return it }
        jobs[id] = next
        return null
    }

    fun delete(id: String): String? = if (jobs.remove(id) == null) "Job '$id' does not exist" else null

    /** Guests on [node] that a job's selection (or a vzdump call's) covers. */
    fun selected(params: Map<String, String>, guests: List<MockState.Guest>, node: String?): List<MockState.Guest> {
        val candidates = guests.filter { !it.template && (node == null || it.node == node) }
        val pool = params["pool"]
        return when {
            !pool.isNullOrBlank() -> candidates.filter { it.vmid in pools.members(pool) }
            params["all"] == "1" -> {
                val exclude = ids(params["exclude"])
                candidates.filter { it.vmid !in exclude }
            }
            else -> {
                val wanted = ids(params["vmid"])
                candidates.filter { it.vmid in wanted }
            }
        }
    }

    fun notBackedUp(guests: List<MockState.Guest>): List<NotBackedUp> {
        val covered = jobs.values.filter { it["enabled"] != "0" }
            .flatMap { o -> selected(o, guests, o["node"]) }
            .map { it.vmid }.toSet()
        return guests.filter { !it.template && it.vmid !in covered }.map { NotBackedUp(it.vmid, it.name, it.type) }
    }

    private fun ids(text: String?) = text.orEmpty().split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.toSet()
}

/**
 * A subset of Proxmox's calendar events, enough for the app's presets and simple custom
 * schedules: `[weekdays] [hours:]minutes` with lists (`,`), ranges (`..`), steps (`/`) and `*`,
 * plus `hourly`, `daily` and `weekly`. Times are UTC.
 */
internal object MockSchedule {
    private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun next(schedule: String, fromEpochSec: Long, count: Int): List<Long>? {
        val spec = parse(schedule) ?: return null
        val runs = ArrayList<Long>()
        var t = Instant.ofEpochSecond(fromEpochSec).truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
        val end = t.plus(15, ChronoUnit.DAYS)
        while (runs.size < count && t.isBefore(end)) {
            val z = t.atZone(ZoneOffset.UTC)
            if (z.dayOfWeek.value - 1 in spec.days && z.hour in spec.hours && z.minute in spec.minutes) runs += t.epochSecond
            t = t.plus(1, ChronoUnit.MINUTES)
        }
        return runs
    }

    fun analyze(schedule: String, fromEpochSec: Long, iterations: Int): List<ScheduledRun>? =
        next(schedule, fromEpochSec, iterations)?.map { ScheduledRun(it, Instant.ofEpochSecond(it).toString()) }

    private class Spec(val days: Set<Int>, val hours: Set<Int>, val minutes: Set<Int>)

    private fun parse(text: String): Spec? {
        val s = text.trim().lowercase()
        when (s) {
            "hourly" -> return Spec((0..6).toSet(), (0..23).toSet(), setOf(0))
            "daily" -> return Spec((0..6).toSet(), setOf(0), setOf(0))
            "weekly" -> return Spec(setOf(0), setOf(0), setOf(0))
        }
        val parts = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return null
        val dayPart = parts.first().takeIf { p -> DAYS.any { p.startsWith(it) } }
        val timePart = if (dayPart != null) parts.getOrNull(1) else parts.singleOrNull() ?: return null
        val days = dayPart?.let { days(it) ?: return null } ?: (0..6).toSet()
        if (timePart == null) return Spec(days, setOf(0), setOf(0))
        val (hourText, minuteText) = if (':' in timePart) timePart.substringBefore(':') to timePart.substringAfter(':') else "*" to timePart
        val hours = values(hourText, 0..23) ?: return null
        val minutes = values(minuteText, 0..59) ?: return null
        return Spec(days, hours, minutes)
    }

    private fun days(text: String): Set<Int>? = text.split(',').flatMap { item ->
        val range = item.split("..")
        val from = DAYS.indexOfFirst { range[0].startsWith(it) }
        val to = DAYS.indexOfFirst { range.last().startsWith(it) }
        if (from < 0 || to < 0 || range.size > 2) return null
        (from..to).toList()
    }.toSet()

    private fun values(text: String, bounds: IntRange): Set<Int>? = text.split(',').flatMap { item ->
        val (base, step) = item.split('/').let { it[0] to it.getOrNull(1)?.toIntOrNull() }
        if (item.contains('/') && (step == null || step <= 0)) return null
        val range = when {
            base == "*" -> bounds
            ".." in base -> {
                val a = base.substringBefore("..").toIntOrNull() ?: return null
                val b = base.substringAfter("..").toIntOrNull() ?: return null
                a..b
            }
            else -> {
                val a = base.toIntOrNull() ?: return null
                if (step != null) a..bounds.last else a..a
            }
        }
        if (range.first !in bounds || range.last !in bounds) return null
        range.step(step ?: 1).toList()
    }.toSet()
}
