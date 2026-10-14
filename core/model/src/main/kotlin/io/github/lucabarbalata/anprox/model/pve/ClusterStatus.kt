package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.Serializable

/**
 * One row of `GET /cluster/status`. There is one `type = "cluster"` row (absent on a
 * standalone node) and one `type = "node"` row per member.
 */
@Serializable
data class ClusterStatusEntry(
    val type: String,
    val id: String = "",
    val name: String = "",
    /** cluster row: number of nodes. */
    val nodes: Int? = null,
    /** cluster row: `1` when the cluster has quorum. */
    @Serializable(with = PveBooleanSerializer::class)
    val quorate: Boolean = false,
    /** cluster row: config version. */
    val version: Int? = null,
    /** node row. */
    @Serializable(with = PveBooleanSerializer::class)
    val online: Boolean = false,
    /** node row: `1` for the node that answered this request. */
    @Serializable(with = PveBooleanSerializer::class)
    val local: Boolean = false,
    val ip: String? = null,
    val nodeid: Int? = null,
    val level: String? = null,
)

/** `GET /version` — PVE and PBS. */
@Serializable
data class VersionInfo(
    val version: String,
    val release: String? = null,
    val repoid: String? = null,
) {
    /** Major version number, e.g. 9 for "9.0.10". */
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
}
