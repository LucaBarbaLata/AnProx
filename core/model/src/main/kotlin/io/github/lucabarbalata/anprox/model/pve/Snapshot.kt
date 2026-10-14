package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One entry of `GET /nodes/{node}/{qemu|lxc}/{vmid}/snapshot`.
 *
 * Proxmox adds a pseudo-entry named `current` ("You are here!") whose [parent] is the snapshot
 * the guest currently runs on. Snapshots form a tree through [parent]: rolling back and taking a
 * new snapshot starts a new branch.
 *
 * (Named `GuestSnapshot` because the data layer already has a "snapshot" of dashboard state.)
 */
@Serializable
data class GuestSnapshot(
    val name: String,
    val description: String? = null,
    /** Unix seconds. Missing for `current`. */
    val snaptime: Long? = null,
    val parent: String? = null,
    /** VMs only: the snapshot also saved the RAM (the VM resumes running after a rollback). */
    @Serializable(with = PveBooleanSerializer::class)
    val vmstate: Boolean = false,
    /** VMs only, on the `current` entry: whether the VM is running right now. */
    @Serializable(with = PveBooleanSerializer::class)
    val running: Boolean = false,
) {
    val isCurrent: Boolean get() = name == CURRENT

    companion object {
        /** Name of the "You are here" pseudo-snapshot. Proxmox reserves it. */
        const val CURRENT = "current"
    }
}

/** A snapshot placed in the tree: [depth] 0 is a root. */
data class SnapshotRow(val snapshot: GuestSnapshot, val depth: Int)

object SnapshotTree {
    /**
     * Orders snapshots depth-first, oldest first among siblings, with `current` after its
     * siblings (it is always the newest). Entries whose parent is missing become roots, and a
     * corrupt parent cycle can't loop forever.
     */
    fun flatten(snapshots: List<GuestSnapshot>): List<SnapshotRow> {
        val byName = snapshots.associateBy { it.name }
        val children = snapshots.groupBy { s -> s.parent?.takeIf { it in byName && it != s.name } }
        val order = compareBy<GuestSnapshot>({ it.isCurrent }, { it.snaptime ?: Long.MAX_VALUE }, { it.name })
        val rows = ArrayList<SnapshotRow>(snapshots.size)
        val visited = HashSet<String>()

        fun visit(s: GuestSnapshot, depth: Int) {
            if (!visited.add(s.name)) return
            rows += SnapshotRow(s, depth)
            children[s.name].orEmpty().sortedWith(order).forEach { visit(it, depth + 1) }
        }
        children[null].orEmpty().sortedWith(order).forEach { visit(it, 0) }
        // Whatever is left sits in a parent cycle: show it flat rather than hide it.
        snapshots.filter { it.name !in visited }.sortedWith(order).forEach { visit(it, 0) }
        return rows
    }
}

/** Snapshot name rules of Proxmox (`pve-configid`, at most 40 characters). */
object SnapshotName {
    enum class Problem { EMPTY, FORMAT, TOO_LONG, RESERVED, DUPLICATE }

    const val MAX_LENGTH = 40
    private val pattern = Regex("^[A-Za-z][A-Za-z0-9_-]+$")

    /** Returns what is wrong with [name], or null if Proxmox will accept it. */
    fun validate(name: String, existing: Collection<String> = emptyList()): Problem? = when {
        name.isEmpty() -> Problem.EMPTY
        name.length > MAX_LENGTH -> Problem.TOO_LONG
        !pattern.matches(name) -> Problem.FORMAT
        name.equals(GuestSnapshot.CURRENT, ignoreCase = true) -> Problem.RESERVED
        existing.any { it == name } -> Problem.DUPLICATE
        else -> null
    }

    /** A valid default such as `snap-20261014-1530`. */
    fun suggest(year: Int, month: Int, day: Int, hour: Int, minute: Int): String =
        // Locale.ROOT: some locales format digits as non-ASCII numerals, which Proxmox rejects.
        String.format(Locale.ROOT, "snap-%04d%02d%02d-%02d%02d", year, month, day, hour, minute)
}
