package io.github.lucabarbalata.anprox.data.metrics

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.RrdTimeframe

/** What a chart shows the history of. */
sealed interface MetricsSource {
    val serverId: String

    /** Stable text for ViewModel keys. */
    val key: String

    data class Guest(val ref: GuestRef) : MetricsSource {
        override val serverId: String get() = ref.serverId
        override val key: String get() = "guest-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    }

    data class Node(override val serverId: String, val node: String) : MetricsSource {
        override val key: String get() = "node-$serverId-$node"
    }
}

/** Performance history from Proxmox's round-robin database (questionnaire Q25). */
class MetricsRepository(private val hub: ServerHub) {

    /** Averaged samples for [timeframe], oldest first. */
    suspend fun history(source: MetricsSource, timeframe: RrdTimeframe): List<RrdPoint> {
        val points = hub.use(source.serverId) { conn ->
            when (source) {
                is MetricsSource.Guest -> conn.pve {
                    guestRrd(source.ref.node, source.ref.type.apiName, source.ref.vmid, timeframe.apiName, AVERAGE)
                }
                is MetricsSource.Node -> conn.pve { nodeRrd(source.node, timeframe.apiName, AVERAGE) }
            }
        }
        return points.sortedBy { it.time }
    }

    private companion object {
        const val AVERAGE = "AVERAGE"
    }
}
