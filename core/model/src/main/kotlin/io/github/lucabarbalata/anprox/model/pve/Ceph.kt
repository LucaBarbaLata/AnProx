package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.Serializable

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
