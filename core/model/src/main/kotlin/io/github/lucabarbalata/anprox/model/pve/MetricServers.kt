package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import io.github.lucabarbalata.anprox.model.json.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Where Proxmox VE sends its node, guest and storage metrics (`/cluster/metrics/server`), written
 * from pve-manager's `PVE/Status/{Plugin,InfluxDB,Graphite,OpenTelemetry}.pm`.
 */
enum class MetricServerType(val api: String, val defaultPort: Int) {
    INFLUXDB("influxdb", 8089),
    GRAPHITE("graphite", 2003),
    OPENTELEMETRY("opentelemetry", 4318),
    ;

    companion object {
        fun fromApi(value: String?): MetricServerType? = entries.firstOrNull { it.api == value }
    }
}

/** One row of `GET /cluster/metrics/server`. */
@Serializable
data class MetricServer(
    val id: String,
    val type: String,
    val server: String? = null,
    val port: Int? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val disable: Boolean = false,
) {
    val kind: MetricServerType? get() = MetricServerType.fromApi(type)
}

/**
 * Adding or changing a metric server. One form for the three kinds: [protocol] is InfluxDB's
 * `influxdbproto` (udp, http, https), Graphite's `proto` (udp, tcp) or OpenTelemetry's
 * `otel-protocol` (http, https); [timeout], [maxBodySize] and [verifyCertificate] map the same way.
 * The InfluxDB [token] is never shown again: left empty, the old one is kept.
 */
data class MetricServerForm(
    val type: MetricServerType = MetricServerType.INFLUXDB,
    val id: String = "",
    val server: String = "",
    val port: String = MetricServerType.INFLUXDB.defaultPort.toString(),
    val disabled: Boolean = false,
    val protocol: String = "udp",
    val mtu: String = "",
    val timeout: String = "",
    val maxBodySize: String = "",
    val verifyCertificate: Boolean = true,
    // InfluxDB
    val organization: String = "",
    val bucket: String = "",
    val token: String = "",
    val apiPathPrefix: String = "",
    // Graphite
    val path: String = "",
    // OpenTelemetry
    val otelPath: String = "",
    val compression: String = "gzip",
    /** `Name: value` lines, sent as base64-encoded JSON. */
    val headers: String = "",
    /** `key=value` lines, sent as base64-encoded JSON. */
    val resourceAttributes: String = "",
) {
    enum class Problem { ID, SERVER, PORT, MTU, TIMEOUT, MAX_BODY, PATH, HEADER, ATTRIBUTE }

    /** The protocols this kind offers, the first being Proxmox's default. */
    val protocols: List<String> get() = protocols(type)

    /** UDP sends packets of at most [mtu] bytes; over HTTP the body size limits a request instead. */
    val usesUdp: Boolean get() = type != MetricServerType.OPENTELEMETRY && protocol == "udp"

    /** Changes the kind, with its default protocol, and its default port unless one was typed. */
    fun withType(new: MetricServerType): MetricServerForm {
        val keepPort = port.isNotBlank() && port.trim() != type.defaultPort.toString() && port.trim() != HTTP_INFLUX_PORT.toString()
        return copy(type = new, protocol = protocols(new).first(), port = if (keepPort) port else new.defaultPort.toString())
    }

    /** Changes the protocol; InfluxDB's HTTP API listens on another port than its UDP one. */
    fun withProtocol(new: String): MetricServerForm {
        val port = if (type == MetricServerType.INFLUXDB && (port.isBlank() || port.trim() in setOf(INFLUX_UDP_PORT.toString(), HTTP_INFLUX_PORT.toString()))) {
            (if (new == "udp") INFLUX_UDP_PORT else HTTP_INFLUX_PORT).toString()
        } else {
            port
        }
        return copy(protocol = new, port = port)
    }

    fun problem(): Problem? = when {
        !ID.matches(id.trim()) -> Problem.ID
        server.isBlank() || server.trim().any { it.isWhitespace() || it == '/' } -> Problem.SERVER
        type == MetricServerType.INFLUXDB && port.isBlank() -> Problem.PORT
        !inRange(port, 1, 65536) -> Problem.PORT
        usesUdp && !inRange(mtu, 512, 65536) -> Problem.MTU
        !inRange(timeout, if (type == MetricServerType.OPENTELEMETRY) 1 else 0, if (type == MetricServerType.OPENTELEMETRY) 10 else Int.MAX_VALUE) -> Problem.TIMEOUT
        type != MetricServerType.GRAPHITE && !inRange(maxBodySize, if (type == MetricServerType.OPENTELEMETRY) 1024 else 1, Int.MAX_VALUE) -> Problem.MAX_BODY
        type == MetricServerType.GRAPHITE && path.isNotBlank() && !GRAPHITE_PATH.matches(path.trim()) -> Problem.PATH
        type == MetricServerType.OPENTELEMETRY && lines(headers).any { ':' !in it || it.startsWith(":") } -> Problem.HEADER
        type == MetricServerType.OPENTELEMETRY && lines(resourceAttributes).any { '=' !in it || it.startsWith("=") } -> Problem.ATTRIBUTE
        else -> null
    }

    /**
     * `POST /cluster/metrics/server/{id}` ([create], with the `type`) or `PUT …/{id}` with the
     * [digest]: fields emptied when changing go in `delete`.
     */
    fun params(create: Boolean, digest: String? = null): Map<String, String> = buildMap {
        val deleted = mutableListOf<String>()
        fun opt(key: String, value: String) {
            if (value.isNotBlank()) put(key, value.trim()) else if (!create) deleted += key
        }
        if (create) put("type", type.api)
        put("server", server.trim())
        if (type == MetricServerType.INFLUXDB) put("port", port.trim()) else opt("port", port)
        if (disabled) put("disable", "1") else if (!create) put("disable", "0")
        when (type) {
            MetricServerType.INFLUXDB -> {
                put("influxdbproto", protocol)
                if (protocol == "udp") opt("mtu", mtu) else if (!create) deleted += "mtu"
                opt("timeout", timeout)
                opt("max-body-size", maxBodySize)
                opt("organization", organization)
                opt("bucket", bucket)
                opt("api-path-prefix", apiPathPrefix)
                if (token.isNotBlank()) put("token", token.trim())
                if (protocol == "https") put("verify-certificate", if (verifyCertificate) "1" else "0")
            }
            MetricServerType.GRAPHITE -> {
                put("proto", protocol)
                if (protocol == "udp") opt("mtu", mtu) else if (!create) deleted += "mtu"
                opt("timeout", timeout)
                opt("path", path)
            }
            MetricServerType.OPENTELEMETRY -> {
                put("otel-protocol", protocol)
                opt("otel-path", otelPath)
                opt("otel-timeout", timeout)
                opt("otel-max-body-size", maxBodySize)
                put("otel-compression", compression)
                if (protocol == "https") put("otel-verify-ssl", if (verifyCertificate) "1" else "0")
                opt("otel-headers", lines(headers).takeIf { it.isNotEmpty() }?.let { l -> b64Json(l.map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }) }.orEmpty())
                opt("otel-resource-attributes", lines(resourceAttributes).takeIf { it.isNotEmpty() }?.let { l -> b64Json(l.map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }) }.orEmpty())
            }
        }
        if (deleted.isNotEmpty()) put("delete", deleted.joinToString(","))
        if (!create) digest?.let { put("digest", it) }
    }

    companion object {
        /** Proxmox's `pve-configid`. */
        private val ID = Regex("^[A-Za-z][A-Za-z0-9_-]+$")
        private val GRAPHITE_PATH = Regex("^[A-Za-z0-9_.-]+$")
        const val INFLUX_UDP_PORT = 8089
        const val HTTP_INFLUX_PORT = 8086

        fun protocols(type: MetricServerType): List<String> = when (type) {
            MetricServerType.INFLUXDB -> listOf("udp", "http", "https")
            MetricServerType.GRAPHITE -> listOf("udp", "tcp")
            MetricServerType.OPENTELEMETRY -> listOf("https", "http")
        }

        /** A server's settings from `GET /cluster/metrics/server/{id}` (the token is not among them). */
        fun from(o: JsonObject): MetricServerForm? {
            val type = MetricServerType.fromApi(o.str("type")) ?: return null
            return MetricServerForm(
                type = type,
                id = o.str("id").orEmpty(),
                server = o.str("server").orEmpty(),
                port = o.str("port").orEmpty(),
                disabled = o.str("disable") in setOf("1", "true"),
                protocol = when (type) {
                    MetricServerType.INFLUXDB -> o.str("influxdbproto") ?: "udp"
                    MetricServerType.GRAPHITE -> o.str("proto") ?: "udp"
                    MetricServerType.OPENTELEMETRY -> o.str("otel-protocol") ?: "https"
                },
                mtu = o.str("mtu").orEmpty(),
                timeout = (if (type == MetricServerType.OPENTELEMETRY) o.str("otel-timeout") else o.str("timeout")).orEmpty(),
                maxBodySize = (if (type == MetricServerType.OPENTELEMETRY) o.str("otel-max-body-size") else o.str("max-body-size")).orEmpty(),
                verifyCertificate = (if (type == MetricServerType.OPENTELEMETRY) o.str("otel-verify-ssl") else o.str("verify-certificate")) !in setOf("0", "false"),
                organization = o.str("organization").orEmpty(),
                bucket = o.str("bucket").orEmpty(),
                apiPathPrefix = o.str("api-path-prefix").orEmpty(),
                path = o.str("path").orEmpty(),
                otelPath = o.str("otel-path").orEmpty(),
                compression = o.str("otel-compression") ?: "gzip",
                headers = fromB64Json(o.str("otel-headers")).joinToString("\n") { (k, v) -> "$k: $v" },
                resourceAttributes = fromB64Json(o.str("otel-resource-attributes")).joinToString("\n") { (k, v) -> "$k=$v" },
            )
        }

        private fun inRange(text: String, min: Int, max: Int): Boolean =
            text.isBlank() || text.trim().toIntOrNull()?.let { it in min..max } == true

        private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        private fun b64Json(pairs: List<Pair<String, String>>): String {
            val json = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
            return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        }

        private fun fromB64Json(text: String?): List<Pair<String, String>> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                val json = Json.parseToJsonElement(String(Base64.getDecoder().decode(text.trim()), Charsets.UTF_8))
                (json as? JsonObject)?.entries?.map { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: (v as? JsonArray)?.toString() ?: v.toString()) }.orEmpty()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }
    }
}
