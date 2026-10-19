package io.github.lucabarbalata.anprox.mock

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `datacenter.cfg` behind `GET/PUT /cluster/options`. Like Proxmox, the format properties
 * (`migration`, `ha`, `next-id`) are answered as objects and written as property strings.
 */
internal class MockClusterOptions {
    private val values = linkedMapOf(
        "keyboard" to "en-us",
        "console" to "html5",
        "email_from" to "pve@example.com",
        "migration" to "type=secure,network=10.0.0.0/24",
        "ha" to "shutdown_policy=conditional",
        "description" to "Homelab cluster",
    )
    private var version = 1

    private val digest get() = "dc%06d".format(version)

    private val formats = setOf("migration", "ha", "next-id")
    private val known = setOf("console", "keyboard", "language", "http_proxy", "email_from", "mac_prefix", "max_workers", "description") + formats

    @Synchronized
    fun get(): JsonObject = buildJsonObject {
        values.forEach { (k, v) ->
            if (k in formats) {
                put(k, JsonObject(v.split(',').associate { part -> part.substringBefore('=') to JsonPrimitive(part.substringAfter('=')) }))
            } else {
                put(k, v)
            }
        }
        put("digest", digest)
    }

    /** Null when applied, or Proxmox's error message. */
    @Synchronized
    fun update(params: Map<String, String>): String? {
        params["digest"]?.let { if (it != digest) return "detected modified configuration - file changed by other user? Try again." }
        val delete = params["delete"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val set = params.filterKeys { it != "digest" && it != "delete" }
        (set.keys + delete).firstOrNull { it !in known }?.let { return "property '$it' is not defined in schema" }
        set["console"]?.let { if (it !in listOf("applet", "vv", "html5", "xtermjs")) return "console: value '$it' does not have a value in the enumeration 'applet, vv, html5, xtermjs'" }
        set["mac_prefix"]?.let { if (!Regex("^[0-9a-fA-F][02468aceACE](:[0-9a-fA-F]{2}){0,2}:?$").matches(it)) return "mac_prefix: value does not match the regex pattern" }
        set["http_proxy"]?.let { if (!Regex("^https?://\\S+$").matches(it)) return "http_proxy: value does not match the regex pattern" }
        set["migration"]?.let { m ->
            m.split(',').forEach { part ->
                val key = part.substringBefore('=')
                if (key !in listOf("type", "network")) return "migration: invalid format - property '$key' is not defined"
                if (key == "type" && part.substringAfter('=') !in listOf("secure", "insecure")) return "migration: invalid format - value does not have a value in the enumeration 'secure, insecure'"
            }
        }
        values.putAll(set)
        delete.forEach { values.remove(it) }
        version++
        return null
    }
}
