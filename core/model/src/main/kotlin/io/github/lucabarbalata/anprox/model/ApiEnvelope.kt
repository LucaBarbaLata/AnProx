package io.github.lucabarbalata.anprox.model

import kotlinx.serialization.Serializable

/**
 * Every Proxmox API response is wrapped like `{"data": ...}`.
 * Parameter validation failures add `"errors": {"param": "reason"}`; paged logs add `"total"`.
 */
@Serializable
data class ApiEnvelope<T>(
    val data: T? = null,
    val message: String? = null,
    val errors: Map<String, String>? = null,
    val total: Int? = null,
    /** `GET /nodes/{node}/network`: the pending changes as a unified diff. */
    val changes: String? = null,
)
