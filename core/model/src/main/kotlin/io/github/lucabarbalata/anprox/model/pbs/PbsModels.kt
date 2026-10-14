package io.github.lucabarbalata.anprox.model.pbs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of `GET /status/datastore-usage` (PBS). */
@Serializable
data class DatastoreUsage(
    val store: String,
    val total: Long? = null,
    val used: Long? = null,
    val avail: Long? = null,
    /** Epoch seconds when the datastore is predicted to be full, or -1/absent if unknown. */
    @SerialName("estimated-full-date") val estimatedFullDate: Long? = null,
    val error: String? = null,
) {
    val fraction: Double?
        get() = if (used == null || total == null || total <= 0) null else (used.toDouble() / total).coerceIn(0.0, 1.0)
}

/** One row of `GET /admin/datastore` (PBS). */
@Serializable
data class DatastoreInfo(
    val store: String,
    val comment: String? = null,
    val maintenance: String? = null,
)
