package io.github.lucabarbalata.anprox.model.json

import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration used for every Proxmox response.
 *
 * - `ignoreUnknownKeys`: Proxmox adds fields between versions; we only model what we use.
 * - `coerceInputValues`: a `null` for a non-null field with a default falls back to the default.
 * - `isLenient`: accept quoted booleans and other small spec deviations.
 * - `explicitNulls = false`: don't send `"field": null` in request bodies.
 *
 * Numbers that arrive quoted (`"cpu": "0.03"`) are accepted by kotlinx.serialization by default.
 * Booleans that arrive as `0`/`1` need [PveBooleanSerializer].
 */
val ProxmoxJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
}
