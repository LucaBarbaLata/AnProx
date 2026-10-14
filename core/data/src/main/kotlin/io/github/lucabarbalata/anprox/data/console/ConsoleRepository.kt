package io.github.lucabarbalata.anprox.data.console

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.network.ApiException

/**
 * What a WebView needs to show one of Proxmox's console pages: the page, the session cookie for
 * its origin, and whether a self-signed certificate may be accepted.
 *
 * @property headers the endpoint's custom headers. A WebView only sends them with the page
 *   itself, not with its scripts and WebSocket, so consoles behind header-based proxy auth may fail.
 */
data class ConsoleSession(
    val url: String,
    val origin: String,
    val cookieName: String,
    val ticket: String,
    val allowInsecureTls: Boolean,
    val headers: Map<String, String>,
) {
    /** For `CookieManager.setCookie(origin, …)`. */
    val cookie: String get() = "$cookieName=$ticket; Path=/; Secure"
}

class ConsoleRepository(private val hub: ServerHub) {

    /**
     * Renews the session if it is due (consoles stay open a long time) and picks a reachable
     * address, then describes the console page on it.
     */
    suspend fun open(serverId: String, target: ConsoleTarget): ConsoleSession = hub.use(serverId) { conn ->
        conn.version()
        val session = conn.session ?: throw ApiException.Unauthorized("Not logged in")
        val endpoint = conn.endpoints.active ?: conn.endpoints.candidates().first()
        ConsoleSession(
            url = target.url(endpoint.url),
            origin = originOf(endpoint.url),
            cookieName = conn.product.cookieName,
            ticket = session.ticket,
            allowInsecureTls = conn.profile.allowInsecureTls,
            headers = endpoint.headers,
        )
    }

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val hostEnd = url.indexOf('/', if (schemeEnd < 0) 0 else schemeEnd + 3)
        return if (hostEnd < 0) url else url.substring(0, hostEnd)
    }
}
