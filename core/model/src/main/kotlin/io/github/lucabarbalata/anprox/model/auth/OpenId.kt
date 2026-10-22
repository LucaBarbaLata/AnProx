package io.github.lucabarbalata.anprox.model.auth

import java.net.URI
import java.net.URLDecoder

/**
 * OpenID Connect sign-in (questionnaire Q9), the way Proxmox's web interface does it:
 * `POST /access/openid/auth-url` with the realm and a redirect address gives the identity
 * provider's sign-in page; after sign-in the provider sends the browser back to the redirect
 * address with `code` and `state`, which `POST /access/openid/login` trades for a ticket.
 *
 * The redirect address is the server's own origin (`https://pve.example.com:8006`), because that
 * is what the web interface sends and so what the identity provider already allows. The app
 * catches the browser on its way there instead of loading the page.
 */
sealed interface OpenIdCallback {
    /** Signed in at the identity provider: trade these for a ticket. */
    data class Code(val code: String, val state: String) : OpenIdCallback

    /** The identity provider refused or the user cancelled (`error=access_denied`). */
    data class Error(val error: String, val description: String?) : OpenIdCallback

    companion object {
        /**
         * What [url] says if it is the way back to [redirectUrl]; null for any other page of the
         * sign-in flow (the provider's own pages, its redirects). Same scheme, host and port as
         * [redirectUrl], and its path or below.
         */
        fun parse(url: String, redirectUrl: String): OpenIdCallback? {
            val target = runCatching { URI(url) }.getOrNull() ?: return null
            val back = runCatching { URI(redirectUrl) }.getOrNull() ?: return null
            if (!sameOrigin(target, back)) return null
            val basePath = back.rawPath.orEmpty().trimEnd('/')
            val path = target.rawPath.orEmpty()
            if (path.trimEnd('/') != basePath && !path.startsWith("$basePath/")) return null
            val query = parameters(target.rawQuery)
            query["error"]?.let { return Error(it, query["error_description"]) }
            val code = query["code"] ?: return null
            val state = query["state"] ?: return null
            return Code(code, state)
        }

        /**
         * The origin of a server address, as a browser's `location.origin` has it: scheme, host
         * and port (left out when it's the scheme's default), without a path.
         */
        fun origin(url: String): String {
            val u = URI(url.trim())
            val scheme = u.scheme.lowercase()
            val port = if (u.port == -1 || u.port == defaultPort(scheme)) "" else ":${u.port}"
            return "$scheme://${u.host}$port"
        }

        private fun sameOrigin(a: URI, b: URI): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                effectivePort(a) == effectivePort(b)

        private fun effectivePort(u: URI): Int = if (u.port == -1) defaultPort(u.scheme) else u.port

        private fun defaultPort(scheme: String?): Int = if (scheme.equals("http", ignoreCase = true)) 80 else 443

        private fun parameters(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                // The String overload: the Charset one is Android 13+.
                val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                key to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
    }
}
