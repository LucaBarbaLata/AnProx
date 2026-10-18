package io.github.lucabarbalata.anprox.model.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Reading Proxmox's loosely typed JSON: numbers arrive as strings or numbers, booleans as 0/1 or
 * true/false, lists as arrays or comma-separated strings, depending on the endpoint and version.
 */

/** A non-blank string, whatever the JSON type of the value. */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()

fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()

/** `1`, `true`, `on`, `yes` are true; missing is [default]. */
fun JsonObject.bool(key: String, default: Boolean = false): Boolean = when (str(key)?.lowercase()) {
    null -> default
    "1", "true", "on", "yes" -> true
    else -> false
}

/** An array of strings, or a string separated by commas, semicolons or spaces. */
fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> v.contentOrNull.orEmpty().split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    else -> emptyList()
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** The objects of a JSON array, skipping anything else. */
fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
