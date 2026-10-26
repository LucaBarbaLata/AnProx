package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.pve.MetricServer
import io.github.lucabarbalata.anprox.model.pve.MetricServerForm

/** A metric server's settings for its editor, with the digest a change is checked against. */
data class MetricServerSettings(val form: MetricServerForm, val digest: String?)

/**
 * Datacenter → Metric servers (milestone M9): InfluxDB, Graphite and OpenTelemetry servers that
 * Proxmox VE sends its node, guest and storage metrics to. Proxmox tries an enabled HTTP or TCP
 * server when it is saved and refuses the change if it can't be reached.
 */
class MetricServerRepository(private val hub: ServerHub) {

    suspend fun servers(serverId: String): List<MetricServer> = hub.use(serverId) { it.pve { metricServers() } }.sortedBy { it.id }

    suspend fun settings(serverId: String, id: String): MetricServerSettings {
        val o = hub.use(serverId) { it.pve { metricServer(id) } }
        val form = MetricServerForm.from(o) ?: error("unknown metric server type ${o.str("type")}")
        return MetricServerSettings(form.copy(id = id), o.str("digest"))
    }

    suspend fun create(serverId: String, form: MetricServerForm) {
        hub.use(serverId) { it.pveNoData { createMetricServer(form.id.trim(), form.params(create = true)) } }
    }

    suspend fun update(serverId: String, form: MetricServerForm, digest: String?) {
        hub.use(serverId) { it.pveNoData { updateMetricServer(form.id, form.params(create = false, digest = digest)) } }
    }

    suspend fun delete(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteMetricServer(id) } }
    }
}
