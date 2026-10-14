package io.github.lucabarbalata.anprox.model.auth

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.json.toPveBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * What second factors the server will accept, decoded from a partial ("!tfa!") ticket.
 *
 * Flow (PVE 7+/PBS 2+, `new-format=1`):
 * 1. `POST /access/ticket {username, password}` → `NeedTFA: 1` and a *partial* ticket that
 *    embeds this challenge as URL-encoded JSON after the marker `!tfa!`.
 * 2. `POST /access/ticket {username, tfa-challenge: <partial ticket>, password: "<kind>:<answer>"}`
 *    where kind is one of [TfaKind.wirePrefix].
 */
data class TfaChallenge(
    val totp: Boolean = false,
    val yubico: Boolean = false,
    /** Number of unused recovery keys, or `null` if recovery keys aren't set up. */
    val recoveryKeysLeft: Int? = null,
    /** Raw WebAuthn request options (JSON) when the user has WebAuthn credentials. */
    val webAuthn: JsonElement? = null,
    /** Raw legacy U2F challenge (JSON). */
    val u2f: JsonElement? = null,
) {
    /** Factors AnProx can answer, in the order the UI should offer them. */
    val availableKinds: List<TfaKind>
        get() = buildList {
            if (totp) add(TfaKind.TOTP)
            if (webAuthn != null) add(TfaKind.WEBAUTHN)
            if (yubico) add(TfaKind.YUBICO)
            if (recoveryKeysLeft != null) add(TfaKind.RECOVERY)
        }

    companion object {
        const val MARKER = "!tfa!"

        fun isPartialTicket(ticket: String): Boolean = ticket.contains(MARKER)

        /**
         * Extracts the challenge from a partial ticket. Tolerates both the URL-encoded form
         * (`PVE:!tfa!%7B...%7D:65A1B2C3::sig`) and raw JSON.
         */
        fun fromTicket(ticket: String): TfaChallenge {
            val start = ticket.indexOf(MARKER)
            require(start >= 0) { "Not a TFA ticket" }
            val rest = ticket.substring(start + MARKER.length)
            val json = if (rest.startsWith("{")) {
                rest.substring(0, matchingBraceEnd(rest) + 1)
            } else {
                URLDecoder.decode(rest.substringBefore(':'), StandardCharsets.UTF_8)
            }
            return fromJson(ProxmoxJson.parseToJsonElement(json))
        }

        fun fromJson(element: JsonElement): TfaChallenge {
            val obj = element as? JsonObject ?: return TfaChallenge()
            fun flag(key: String) = (obj[key] as? JsonPrimitive)?.toPveBoolean() ?: false
            fun present(key: String) = obj[key]?.takeUnless { it is JsonNull }
            val recovery = when (val r = obj["recovery"]) {
                null, JsonNull -> null
                is JsonArray -> r.size.takeIf { it > 0 }
                is JsonPrimitive -> if (r.toPveBoolean()) Int.MAX_VALUE else null
                else -> null
            }
            return TfaChallenge(
                totp = flag("totp"),
                yubico = flag("yubico"),
                recoveryKeysLeft = recovery,
                webAuthn = present("webauthn"),
                u2f = present("u2f"),
            )
        }

        private fun matchingBraceEnd(s: String): Int {
            var depth = 0
            var inString = false
            var escaped = false
            for ((i, c) in s.withIndex()) {
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> if (--depth == 0) return i
                }
            }
            error("Unbalanced TFA challenge JSON")
        }
    }
}

/** A second factor kind and how its answer is prefixed on the wire. */
enum class TfaKind(val wirePrefix: String) {
    TOTP("totp"),
    RECOVERY("recovery"),
    YUBICO("yubico"),
    WEBAUTHN("webauthn"),
    ;

    fun encode(answer: String): String = "$wirePrefix:${answer.trim()}"
}
