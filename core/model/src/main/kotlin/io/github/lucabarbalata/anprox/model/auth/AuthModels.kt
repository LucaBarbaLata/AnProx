package io.github.lucabarbalata.anprox.model.auth

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `POST /access/ticket` (PVE and PBS). */
@Serializable
data class TicketResponse(
    val username: String,
    val ticket: String,
    @SerialName("CSRFPreventionToken") val csrfToken: String? = null,
    @SerialName("clustername") val clusterName: String? = null,
    /** Present (and `1`) when the password was right but a second factor is still required. */
    @SerialName("NeedTFA")
    @Serializable(with = PveBooleanSerializer::class)
    val needTfa: Boolean = false,
)

/** An authentication realm/domain such as `pam`, `pve`, `pbs`, an LDAP/AD or OpenID realm. */
@Serializable
data class Realm(
    val realm: String,
    val type: String = "",
    val comment: String? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val default: Boolean = false,
    /** Set when the realm enforces a specific TFA type (e.g. "oath", "yubico"). */
    val tfa: String? = null,
) {
    val isOpenId: Boolean get() = type == "openid"

    /** Human label for pickers, e.g. "Linux PAM (pam)". */
    val label: String get() = if (comment.isNullOrBlank()) realm else "$comment ($realm)"
}

/** An authenticated session. Tickets are valid for 2 hours and can be renewed before then. */
@Serializable
data class AuthSession(
    val username: String,
    val ticket: String,
    val csrfToken: String,
    /** Epoch seconds when the ticket was issued (per our clock). */
    val issuedAtEpochSeconds: Long,
    val clusterName: String? = null,
) {
    fun ageSeconds(nowEpochSeconds: Long): Long = nowEpochSeconds - issuedAtEpochSeconds

    /** Tickets live for 2h; renew after 1h to keep a wide safety margin. */
    fun needsRenewal(nowEpochSeconds: Long): Boolean = ageSeconds(nowEpochSeconds) >= RENEW_AFTER_SECONDS

    fun isExpired(nowEpochSeconds: Long): Boolean = ageSeconds(nowEpochSeconds) >= LIFETIME_SECONDS

    companion object {
        const val LIFETIME_SECONDS: Long = 2 * 60 * 60
        const val RENEW_AFTER_SECONDS: Long = 60 * 60
    }
}
