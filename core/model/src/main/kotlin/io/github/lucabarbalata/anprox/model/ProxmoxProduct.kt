package io.github.lucabarbalata.anprox.model

import kotlinx.serialization.Serializable

/**
 * The Proxmox products AnProx can talk to (questionnaire Q4).
 *
 * Both share the same REST conventions (`/api2/json`, ticket cookie, CSRF header) but differ
 * in default port, cookie name and ticket prefix.
 */
@Serializable
enum class ProxmoxProduct(
    val displayName: String,
    val defaultPort: Int,
    /** Name of the cookie that carries the authentication ticket. */
    val cookieName: String,
    /** Every ticket issued by this product starts with "<prefix>:". */
    val ticketPrefix: String,
) {
    PVE(displayName = "Proxmox VE", defaultPort = 8006, cookieName = "PVEAuthCookie", ticketPrefix = "PVE"),
    PBS(displayName = "Proxmox Backup Server", defaultPort = 8007, cookieName = "PBSAuthCookie", ticketPrefix = "PBS"),
}
