package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.Serializable

/**
 * One sample of `GET …/rrddata` (performance history of a node, VM or container).
 *
 * Proxmox leaves out a field when it has no data for that interval (for example while a guest
 * was stopped), so everything except [time] is nullable and a chart shows a gap there.
 * `net*` and `disk*` are averaged rates in bytes per second; `cpu` and `iowait` are fractions
 * (1.0 = all cores of the node, or all vCPUs of the guest).
 */
@Serializable
data class RrdPoint(
    /** Unix seconds at the end of the interval. */
    val time: Long,
    val cpu: Double? = null,
    val maxcpu: Double? = null,
    val netin: Double? = null,
    val netout: Double? = null,
    // Nodes
    val iowait: Double? = null,
    val loadavg: Double? = null,
    val memused: Double? = null,
    val memtotal: Double? = null,
    val swapused: Double? = null,
    val swaptotal: Double? = null,
    val rootused: Double? = null,
    val roottotal: Double? = null,
    // Guests
    val mem: Double? = null,
    val maxmem: Double? = null,
    val disk: Double? = null,
    val maxdisk: Double? = null,
    val diskread: Double? = null,
    val diskwrite: Double? = null,
)

/**
 * The `timeframe` parameter of `…/rrddata`. [stepSeconds] is the interval between samples in
 * Proxmox's classic RRD layout (about 70 samples per timeframe); newer versions may return more
 * samples, which charts handle the same way.
 */
enum class RrdTimeframe(val apiName: String, val stepSeconds: Long) {
    HOUR("hour", 60),
    DAY("day", 30 * 60),
    WEEK("week", 3 * 60 * 60),
    MONTH("month", 12 * 60 * 60),
    YEAR("year", 7 * 24 * 60 * 60),
}
