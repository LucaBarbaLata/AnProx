package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.MetricServerForm
import io.github.lucabarbalata.anprox.model.pve.MetricServerType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricServerModelTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `each kind sends only its own options`() {
        val influx = MetricServerForm(id = "influx", server = "influx.lan", port = "8089")
        assertEquals(mapOf("type" to "influxdb", "server" to "influx.lan", "port" to "8089", "influxdbproto" to "udp"), influx.params(create = true))

        val http = influx.withProtocol("https").copy(organization = "home", bucket = "proxmox", token = "t0ken", verifyCertificate = false)
        assertEquals("8086", http.port)                       // InfluxDB's HTTP API, not its UDP listener
        val params = http.params(create = true)
        assertEquals("https", params["influxdbproto"])
        assertEquals("0", params["verify-certificate"])
        assertEquals("t0ken", params["token"])
        assertNull(params["mtu"])

        val graphite = MetricServerForm().withType(MetricServerType.GRAPHITE).copy(id = "carbon", server = "graphite.lan", path = "proxmox.home")
        assertEquals("2003", graphite.port)
        assertEquals(mapOf("type" to "graphite", "server" to "graphite.lan", "port" to "2003", "proto" to "udp", "path" to "proxmox.home"), graphite.params(create = true))
    }

    @Test
    fun `a change deletes emptied fields and keeps the token unless typed`() {
        val form = MetricServerForm(id = "influx", server = "influx.lan", port = "8086", protocol = "http", bucket = "", organization = "home")
        val params = form.params(create = false, digest = "abc")
        assertEquals("0", params["disable"])
        assertEquals("abc", params["digest"])
        assertNull(params["token"])
        assertNull(params["type"])
        val deleted = params.getValue("delete").split(',').toSet()
        assertTrue("bucket" in deleted && "mtu" in deleted && "api-path-prefix" in deleted)
        assertTrue("organization" !in deleted)
        // Nothing is set and deleted at once (Proxmox refuses that).
        assertTrue(deleted.none { it in params.keys })
    }

    @Test
    fun `OpenTelemetry headers and attributes travel as base64 JSON`() {
        val form = MetricServerForm().withType(MetricServerType.OPENTELEMETRY)
            .copy(id = "otel", server = "otel.lan", headers = "Authorization: Bearer x", resourceAttributes = "site=home")
        assertEquals("https", form.protocol)
        assertEquals("4318", form.port)
        val params = form.params(create = true)
        assertEquals("""{"Authorization":"Bearer x"}""", String(Base64.getDecoder().decode(params.getValue("otel-headers"))))
        assertEquals("""{"site":"home"}""", String(Base64.getDecoder().decode(params.getValue("otel-resource-attributes"))))

        val back = MetricServerForm.from(json("""{"type":"opentelemetry","server":"otel.lan","otel-protocol":"http","otel-headers":"${params["otel-headers"]}","otel-verify-ssl":0}"""))!!
        assertEquals("Authorization: Bearer x", back.headers)
        assertEquals("http", back.protocol)
        assertEquals(false, back.verifyCertificate)
    }

    @Test
    fun `problems are found before Proxmox would refuse them`() {
        val ok = MetricServerForm(id = "influx", server = "influx.lan")
        assertNull(ok.problem())
        assertEquals(MetricServerForm.Problem.ID, ok.copy(id = "1x").problem())
        assertEquals(MetricServerForm.Problem.SERVER, ok.copy(server = "http://x").problem())
        assertEquals(MetricServerForm.Problem.PORT, ok.copy(port = "").problem())
        assertEquals(MetricServerForm.Problem.MTU, ok.copy(mtu = "100").problem())
        assertNull(ok.withProtocol("http").copy(mtu = "100").problem())          // the MTU only counts for UDP
        assertEquals(MetricServerForm.Problem.TIMEOUT, MetricServerForm(type = MetricServerType.OPENTELEMETRY, protocol = "https", id = "otel", server = "o", timeout = "30").problem())
        assertEquals(MetricServerForm.Problem.PATH, MetricServerForm(type = MetricServerType.GRAPHITE, protocol = "udp", id = "g1", server = "g", path = "a b").problem())
    }
}
