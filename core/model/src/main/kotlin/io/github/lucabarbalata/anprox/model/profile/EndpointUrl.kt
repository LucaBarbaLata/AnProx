package io.github.lucabarbalata.anprox.model.profile

import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import java.net.URI

/** Turns whatever the user typed into a canonical base URL, or explains what is wrong. */
object EndpointUrl {

    sealed interface Result {
        data class Ok(val url: String) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { EMPTY, BAD_SCHEME, BAD_HOST, BAD_PORT, MALFORMED }

    /**
     * Rules:
     * - No scheme → `https://` is assumed. Plain `http://` is rejected: Proxmox only serves HTTPS
     *   and a password must never travel in clear text.
     * - No scheme **and** no port → the product's default port (8006 PVE / 8007 PBS) is added.
     *   With an explicit `https://` and no port, 443 is kept (typical reverse proxy).
     * - A path is kept as a sub-path prefix (reverse proxy), minus `/api2/json` and trailing `/`.
     * - Query strings and fragments are dropped.
     */
    fun normalize(input: String, product: ProxmoxProduct): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)

        val hasScheme = trimmed.contains("://")
        if (hasScheme && !trimmed.startsWith("https://", ignoreCase = true)) {
            return Result.Invalid(Reason.BAD_SCHEME)
        }
        val uri = try {
            URI(if (hasScheme) trimmed else "https://$trimmed")
        } catch (_: Exception) {
            return Result.Invalid(Reason.MALFORMED)
        }
        val host = uri.host ?: return Result.Invalid(
            if (uri.rawAuthority?.contains(':') == true) Reason.BAD_PORT else Reason.BAD_HOST,
        )
        if (host.isBlank()) return Result.Invalid(Reason.BAD_HOST)
        val port = when {
            uri.port != -1 -> uri.port
            !hasScheme -> product.defaultPort
            else -> -1
        }
        if (port != -1 && port !in 1..65535) return Result.Invalid(Reason.BAD_PORT)

        val path = (uri.rawPath ?: "")
            .trimEnd('/')
            .removeSuffix("/api2/json")
            .trimEnd('/')

        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val portPart = if (port == -1 || port == 443) "" else ":$port"
        return Result.Ok("https://$hostPart$portPart$path")
    }
}
