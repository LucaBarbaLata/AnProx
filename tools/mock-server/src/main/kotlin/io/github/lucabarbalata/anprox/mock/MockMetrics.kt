package io.github.lucabarbalata.anprox.mock

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * External metric servers (`/cluster/metrics/server`), written from pve-manager's
 * `PVE/API2/Cluster/MetricServer.pm` and the plugins in `PVE/Status/`: each kind accepts only its own
 * options, the InfluxDB token is kept but never returned, and enabled HTTP or TCP servers are
 * tried when saved (here: a host named `unreachable…` fails).
 *
 * Seeded with an InfluxDB server over UDP.
 */
internal class MockMetrics {
    private class Entry(val type: String, val props: MutableMap<String, String>)

    private val servers = linkedMapOf(
        "influx" to Entry("influxdb", mutableMapOf("server" to "influx.home.lan", "port" to "8089", "influxdbproto" to "udp")),
    )
    private var version = 0

    fun list(): JsonArray = synchronized(this) {
        JsonArray(
            servers.map { (id, e) ->
                buildJsonObject {
                    put("id", id)
                    put("type", e.type)
                    put("server", e.props["server"])
                    e.props["port"]?.toIntOrNull()?.let { put("port", it) }
                    put("disable", if (e.props["disable"] == "1") 1 else 0)
                }
            },
        )
    }

    fun get(id: String): JsonObject? = synchronized(this) {
        val e = servers[id] ?: return null
        buildJsonObject {
            put("id", id)
            put("type", e.type)
            e.props.filterKeys { it != "token" }.forEach { (k, v) -> put(k, v) }
            put("digest", digest())
        }
    }

    /** Null on success, or an error message. */
    fun create(id: String, params: Map<String, String>): String? = synchronized(this) {
        if (!Regex("^[A-Za-z][A-Za-z0-9_-]+$").matches(id)) return "id: invalid format - invalid configuration ID '$id'"
        if (id in servers) return "Metric server '$id' already exists"
        val type = params["type"] ?: return "type: property is missing and it is not optional"
        val allowed = OPTIONS[type] ?: return "type: value '$type' does not have a value in the enumeration"
        val props = params - "type"
        props.keys.firstOrNull { it !in allowed }?.let { return "parameter verification failed - '$it': property is not defined in schema and the schema does not allow additional properties" }
        if (props["server"].isNullOrBlank()) return "server: property is missing and it is not optional"
        if (type == "influxdb" && props["port"].isNullOrBlank()) return "port: property is missing and it is not optional"
        val entry = Entry(type, props.toMutableMap())
        tryConnection(entry)?.let { return it }
        servers[id] = entry
        version++
        null
    }

    fun update(id: String, params: Map<String, String>): String? = synchronized(this) {
        val e = servers[id] ?: return "no such server '$id'"
        params["digest"]?.let { if (it != digest()) return "detected modified configuration - file changed by other user? Try again." }
        val allowed = OPTIONS.getValue(e.type)
        val set = params - "digest" - "delete"
        set.keys.firstOrNull { it !in allowed }?.let { return "parameter verification failed - '$it': property is not defined in schema and the schema does not allow additional properties" }
        val delete = params["delete"]?.split(',', ';', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        delete.firstOrNull { it in set }?.let { return "cannot set and delete property '$it' at the same time!" }
        delete.firstOrNull { it == "server" || (it == "port" && e.type == "influxdb") }?.let { return "unable to delete required option '$it'" }
        val updated = Entry(e.type, (e.props + set - delete.toSet()).toMutableMap())
        tryConnection(updated)?.let { return it }
        servers[id] = updated
        version++
        null
    }

    fun delete(id: String): String? = synchronized(this) {
        servers.remove(id) ?: return "no such server '$id'"
        version++
        null
    }

    /** Like `test_connection`: only enabled servers spoken to over HTTP(S) or TCP are tried. */
    private fun tryConnection(e: Entry): String? {
        if (e.props["disable"] == "1") return null
        val proto = e.props["influxdbproto"] ?: e.props["proto"] ?: e.props["otel-protocol"] ?: "udp"
        if (proto == "udp") return null
        val host = e.props["server"].orEmpty()
        return if (host.startsWith("unreachable")) "500 Can't connect to $host:${e.props["port"] ?: ""} (Connection refused)" else null
    }

    private fun digest(): String = MessageDigest.getInstance("SHA-1").digest("metrics:$version".toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val COMMON = setOf("server", "port", "disable")
        val OPTIONS = mapOf(
            "influxdb" to COMMON + setOf("mtu", "organization", "bucket", "token", "influxdbproto", "timeout", "max-body-size", "api-path-prefix", "verify-certificate"),
            "graphite" to COMMON + setOf("mtu", "proto", "timeout", "path"),
            "opentelemetry" to COMMON + setOf(
                "otel-protocol", "otel-path", "otel-timeout", "otel-headers", "otel-verify-ssl", "otel-max-body-size", "otel-resource-attributes", "otel-compression",
            ),
        )
    }
}
