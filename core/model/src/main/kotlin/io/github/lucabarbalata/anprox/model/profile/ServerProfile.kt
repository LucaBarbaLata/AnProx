package io.github.lucabarbalata.anprox.model.profile

import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import kotlinx.serialization.Serializable

/**
 * A saved server (a PVE cluster or a PBS instance) as the user configured it.
 * Secrets (password, session ticket) are **not** stored here — see `SecretStore` in :core:data.
 */
@Serializable
data class ServerProfile(
    /** Stable random id (UUID). */
    val id: String,
    /** User-chosen label, e.g. "Homelab" or "Customer A — prod". */
    val name: String,
    val product: ProxmoxProduct = ProxmoxProduct.PVE,
    /** Tried in order; the first one that answers wins (questionnaire Q14). Never empty. */
    val endpoints: List<Endpoint>,
    /** User name without realm, e.g. `root`. */
    val username: String,
    /** Realm id, e.g. `pam`, `pve`, `pbs`, or an LDAP/AD/OpenID realm. */
    val realm: String = "pam",
    /**
     * Skip TLS certificate and hostname verification for this server (questionnaire Q10).
     * Off by default. See docs/security.md for the risk.
     */
    val allowInsecureTls: Boolean = false,
    /** Keep the password (encrypted) so expired sessions can be renewed without asking. */
    val rememberPassword: Boolean = false,
    /** Position in the server list. */
    val sortOrder: Int = 0,
) {
    /** Proxmox user id, e.g. `root@pam`. */
    val userId: String get() = "$username@$realm"
}

/**
 * One way to reach a server. [url] is normalised (see [EndpointUrl.normalize]): scheme, host,
 * port and an optional sub-path (for reverse proxies), without a trailing slash.
 */
@Serializable
data class Endpoint(
    val url: String,
    /** Optional label shown in the UI, e.g. "LAN", "VPN", "Public". */
    val label: String? = null,
    /** Extra HTTP headers sent with every request to this endpoint (e.g. reverse-proxy auth). */
    val headers: Map<String, String> = emptyMap(),
)
