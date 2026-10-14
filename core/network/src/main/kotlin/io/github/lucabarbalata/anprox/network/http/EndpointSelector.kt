package io.github.lucabarbalata.anprox.network.http

import io.github.lucabarbalata.anprox.model.profile.Endpoint

/**
 * Remembers which of a server's endpoints worked last, so later requests try it first
 * (questionnaire Q14: LAN first, then VPN/public, with automatic failover).
 *
 * The preference expires after [preferenceTtlMillis] and can be cleared with [reset]
 * (the app does that when the phone's network changes), so the primary endpoint — usually
 * the fast LAN address — gets another chance.
 */
class EndpointSelector(
    val endpoints: List<Endpoint>,
    private val preferenceTtlMillis: Long = 15 * 60 * 1000L,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(endpoints.isNotEmpty()) { "A server needs at least one endpoint" }
    }

    @Volatile private var preferred: Int = -1
    @Volatile private var preferredAt: Long = 0

    /** Endpoints in the order they should be tried. */
    fun candidates(): List<Endpoint> {
        val p = preferred
        if (p < 0 || clockMillis() - preferredAt > preferenceTtlMillis) return endpoints
        return listOf(endpoints[p]) + endpoints.filterIndexed { i, _ -> i != p }
    }

    /** The endpoint that answered most recently, if any. */
    val active: Endpoint? get() = preferred.takeIf { it >= 0 }?.let { endpoints[it] }

    fun markGood(endpoint: Endpoint) {
        val i = endpoints.indexOf(endpoint)
        if (i >= 0) {
            preferred = i
            preferredAt = clockMillis()
        }
    }

    fun reset() {
        preferred = -1
    }
}
