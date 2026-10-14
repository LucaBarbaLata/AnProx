package io.github.lucabarbalata.anprox.model

import kotlinx.serialization.Serializable

/**
 * Every Proxmox API response is wrapped like `{"data": ...}`.
 * Parameter validation failures add `"errors": {"param": "reason"}`.
 */
@Serializable
data class ApiEnvelope<T>(
    val data: T? = null,
    val message: String? = null,
    val errors: Map<String, String>? = null,
)
