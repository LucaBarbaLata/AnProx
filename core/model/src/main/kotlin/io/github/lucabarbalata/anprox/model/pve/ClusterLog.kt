package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.Serializable

/**
 * One entry of `GET /cluster/log`: what the cluster's nodes logged (tasks starting and ending,
 * logins, HA and replication events). [pri] is the syslog severity: 0–3 errors, 4 warnings.
 */
@Serializable
data class ClusterLogEntry(
    val uid: Long = 0,
    /** Unix seconds. */
    val time: Long = 0,
    val pri: Int = 6,
    val tag: String = "",
    val pid: Long? = null,
    val node: String = "",
    val user: String = "",
    val msg: String = "",
) {
    val isError: Boolean get() = pri <= 3
    val isWarning: Boolean get() = pri == 4

    /** For the search field: the node, service, user and message. */
    fun matches(filter: String): Boolean {
        val f = filter.trim()
        return f.isEmpty() || listOf(node, tag, user, msg).any { it.contains(f, ignoreCase = true) }
    }
}
