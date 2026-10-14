package io.github.lucabarbalata.anprox.network

/**
 * Everything that can go wrong talking to a Proxmox server, as a closed hierarchy so the UI
 * can show a precise, actionable message for each case.
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No endpoint answered. [attempts] lists what was tried and why each failed. */
    class Unreachable(val attempts: List<EndpointAttempt>) :
        ApiException("Server unreachable: " + attempts.joinToString { "${it.url} (${it.reason})" })

    /**
     * The TLS certificate is not trusted (typical for self-signed Proxmox certs). The fix offered
     * to the user is the per-server "Allow insecure certificate" switch.
     */
    class UntrustedCertificate(val url: String, cause: Throwable) :
        ApiException("The certificate of $url is not trusted", cause)

    /** 401: wrong credentials, expired ticket or bad CSRF token. */
    class Unauthorized(message: String) : ApiException(message)

    /** 403: authenticated, but the user lacks the privilege for this call. */
    class Forbidden(message: String) : ApiException(message)

    class NotFound(message: String) : ApiException(message)

    /** 400 with per-parameter messages, e.g. `{"memory": "value must be >= 16"}`. */
    class Validation(message: String, val fieldErrors: Map<String, String>) : ApiException(message)

    /** Any other non-2xx answer. Proxmox puts the human-readable reason in the status line. */
    class Server(val code: Int, message: String) : ApiException(message)

    /** The server answered something we could not understand. */
    class Protocol(message: String, cause: Throwable? = null) : ApiException(message, cause)
}

/** One failed connection attempt during endpoint failover. */
data class EndpointAttempt(val url: String, val reason: String)
