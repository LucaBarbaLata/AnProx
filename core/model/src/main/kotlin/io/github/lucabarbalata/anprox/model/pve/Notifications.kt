package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import io.github.lucabarbalata.anprox.model.json.bool
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.json.strings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/**
 * Form fields for calls whose array parameters repeat their key (`mailto=a&mailto=b`), which a
 * map can't hold. Proxmox's notification API is the first to need it.
 */
typealias FormFields = List<Pair<String, String>>

/**
 * An array parameter as Proxmox returns it: a JSON array, or a single string when there is one
 * entry. Unlike [strings], an entry is never split: headers, fields and calendar events contain
 * commas and spaces.
 */
private fun JsonObject.items(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> listOfNotNull(v.contentOrNull?.takeIf { it.isNotBlank() })
    else -> emptyList()
}

/** Where Proxmox VE sends notifications (`/cluster/notifications/endpoints/{type}`). */
enum class NotifyTargetType(val api: String) {
    SENDMAIL("sendmail"),
    SMTP("smtp"),
    GOTIFY("gotify"),
    WEBHOOK("webhook"),
    ;

    companion object {
        fun fromApi(value: String?): NotifyTargetType? = entries.firstOrNull { it.api == value }
    }
}

/** A notification target (`GET /cluster/notifications/targets`). */
@Serializable
data class NotifyTarget(
    val name: String,
    val type: String,
    val comment: String? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val disable: Boolean = false,
    /** `builtin`, `modified-builtin` or `user-created`; built-in ones can't be deleted. */
    val origin: String? = null,
) {
    val kind: NotifyTargetType? get() = NotifyTargetType.fromApi(type)
    val builtin: Boolean get() = origin == "builtin" || origin == "modified-builtin"
}

/** Severities a matcher can pick, in Proxmox's order. */
val NotifySeverities = listOf("info", "notice", "warning", "error", "unknown")

/**
 * Adding or changing a target. Secrets (the SMTP password, the Gotify token, webhook secrets)
 * are never sent back by Proxmox; left empty when changing a target, they are kept.
 */
data class NotifyTargetForm(
    val type: NotifyTargetType = NotifyTargetType.SENDMAIL,
    val name: String = "",
    val comment: String = "",
    val disabled: Boolean = false,
    /** E-mail addresses, comma separated. */
    val mailto: String = "",
    /** Proxmox users whose e-mail address is used, comma separated (`root@pam`). */
    val mailtoUser: String = "",
    val fromAddress: String = "",
    val author: String = "",
    val server: String = "",
    val port: String = "",
    val mode: String = "tls",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val url: String = "",
    val method: String = "post",
    /** `Name: value` lines. */
    val headers: String = "",
    val body: String = "",
    /** `name=value` lines. */
    val secrets: String = "",
) {
    enum class Problem { NAME, RECIPIENT, FROM, SERVER, PORT, TOKEN, URL, HEADER, SECRET }

    fun problem(create: Boolean): Problem? = when {
        !Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(name.trim()) -> Problem.NAME
        (type == NotifyTargetType.SENDMAIL || type == NotifyTargetType.SMTP) && list(mailto).isEmpty() && list(mailtoUser).isEmpty() -> Problem.RECIPIENT
        (type == NotifyTargetType.SENDMAIL || type == NotifyTargetType.SMTP) && list(mailto).any { !EMAIL.matches(it) } -> Problem.RECIPIENT
        type == NotifyTargetType.SMTP && !EMAIL.matches(fromAddress.trim()) -> Problem.FROM
        type == NotifyTargetType.SENDMAIL && fromAddress.isNotBlank() && !EMAIL.matches(fromAddress.trim()) -> Problem.FROM
        (type == NotifyTargetType.SMTP || type == NotifyTargetType.GOTIFY) && server.isBlank() -> Problem.SERVER
        type == NotifyTargetType.GOTIFY && !server.trim().startsWith("http") -> Problem.SERVER
        type == NotifyTargetType.SMTP && port.isNotBlank() && port.trim().toIntOrNull()?.let { it in 1..65535 } != true -> Problem.PORT
        type == NotifyTargetType.GOTIFY && create && token.isBlank() -> Problem.TOKEN
        type == NotifyTargetType.WEBHOOK && !url.trim().let { it.startsWith("http://") || it.startsWith("https://") } -> Problem.URL
        type == NotifyTargetType.WEBHOOK && lines(headers).any { ':' !in it || it.startsWith(":") } -> Problem.HEADER
        type == NotifyTargetType.WEBHOOK && lines(secrets).any { '=' !in it || it.startsWith("=") } -> Problem.SECRET
        else -> null
    }

    /**
     * The fields for `POST …/endpoints/{type}` ([create]) or `PUT …/endpoints/{type}/{name}`.
     * When changing, emptied optional fields go in `delete`.
     */
    fun fields(create: Boolean, digest: String? = null): FormFields = buildList {
        val deleted = mutableListOf<String>()
        fun opt(key: String, value: String) {
            if (value.isNotBlank()) add(key to value.trim()) else if (!create) deleted += key
        }
        fun many(key: String, values: List<String>) {
            if (values.isNotEmpty()) values.forEach { add(key to it) } else if (!create) deleted += key
        }
        if (create) add("name" to name.trim())
        opt("comment", comment)
        if (disabled) add("disable" to "1") else if (!create) deleted += "disable"
        when (type) {
            NotifyTargetType.SENDMAIL, NotifyTargetType.SMTP -> {
                many("mailto", list(mailto))
                many("mailto-user", list(mailtoUser))
                if (type == NotifyTargetType.SMTP) add("from-address" to fromAddress.trim()) else opt("from-address", fromAddress)
                opt("author", author)
                if (type == NotifyTargetType.SMTP) {
                    add("server" to server.trim())
                    opt("port", port)
                    add("mode" to mode)
                    opt("username", username)
                    if (password.isNotEmpty()) add("password" to password)
                }
            }
            NotifyTargetType.GOTIFY -> {
                add("server" to server.trim())
                if (token.isNotBlank()) add("token" to token.trim())
            }
            NotifyTargetType.WEBHOOK -> {
                add("url" to url.trim())
                add("method" to method)
                many("header", lines(headers).map { line -> pair(line.substringBefore(':').trim(), line.substringAfter(':').trim()) })
                if (body.isNotBlank()) add("body" to b64(body)) else if (!create) deleted += "body"
                // Secrets are only sent when typed; Proxmox keeps the old ones otherwise.
                lines(secrets).takeIf { it.isNotEmpty() }?.forEach { line -> add("secret" to pair(line.substringBefore('=').trim(), line.substringAfter('=').trim())) }
            }
        }
        deleted.forEach { add("delete" to it) }
        if (!create) digest?.let { add("digest" to it) }
    }

    companion object {
        private val EMAIL = Regex("^[^@\\s,]+@[^@\\s,]+$")

        private fun list(text: String) = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        private fun pair(name: String, value: String) = "name=$name,value=${b64(value)}"

        private fun unb64(text: String?): String = text?.let {
            try {
                String(Base64.getDecoder().decode(it), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                it
            }
        }.orEmpty()

        /** A target's settings (`GET …/endpoints/{type}/{name}`) as a form; secrets stay empty. */
        fun from(type: NotifyTargetType, o: JsonObject): NotifyTargetForm = NotifyTargetForm(
            type = type,
            name = o.str("name").orEmpty(),
            comment = o.str("comment").orEmpty(),
            disabled = o.bool("disable"),
            mailto = o.strings("mailto").joinToString(", "),
            mailtoUser = o.strings("mailto-user").joinToString(", "),
            fromAddress = o.str("from-address").orEmpty(),
            author = o.str("author").orEmpty(),
            server = o.str("server").orEmpty(),
            port = o.str("port").orEmpty(),
            mode = o.str("mode") ?: "tls",
            username = o.str("username").orEmpty(),
            url = o.str("url").orEmpty(),
            method = o.str("method") ?: "post",
            headers = o.items("header").joinToString("\n") { h ->
                val p = PropertyString.parse(h)
                "${p["name"].orEmpty()}: ${unb64(p["value"])}"
            },
            body = unb64(o.str("body")),
        )
    }
}

/** A notification matcher (`GET /cluster/notifications/matchers`): which notifications go where. */
data class NotifyMatcher(
    val name: String,
    val targets: List<String> = emptyList(),
    val severities: List<String> = emptyList(),
    val fields: List<String> = emptyList(),
    val calendar: List<String> = emptyList(),
    val mode: String = "all",
    val invert: Boolean = false,
    val comment: String? = null,
    val disabled: Boolean = false,
    val origin: String? = null,
    val digest: String? = null,
) {
    val builtin: Boolean get() = origin == "builtin" || origin == "modified-builtin"

    companion object {
        fun from(o: JsonObject) = NotifyMatcher(
            name = o.str("name").orEmpty(),
            targets = o.strings("target"),
            // Each entry may itself list several: `warning,error`.
            severities = o.items("match-severity").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() },
            fields = o.items("match-field"),
            calendar = o.items("match-calendar"),
            mode = o.str("mode") ?: "all",
            invert = o.bool("invert-match"),
            comment = o.str("comment"),
            disabled = o.bool("disable"),
            origin = o.str("origin"),
            digest = o.str("digest"),
        )
    }
}

/** Adding or changing a matcher. [fields] and [calendar] are one entry per line. */
data class NotifyMatcherForm(
    val name: String = "",
    val targets: Set<String> = emptySet(),
    val severities: Set<String> = emptySet(),
    /** `exact:type=vzdump` or `regex:hostname=pve.*`, one per line. */
    val fields: String = "",
    val calendar: String = "",
    val mode: String = "all",
    val invert: Boolean = false,
    val comment: String = "",
    val disabled: Boolean = false,
) {
    enum class Problem { NAME, TARGET, FIELD }

    fun problem(): Problem? = when {
        !Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(name.trim()) -> Problem.NAME
        targets.isEmpty() -> Problem.TARGET
        lines(fields).any { !Regex("^(exact|regex):[A-Za-z0-9_-]+=.+$").matches(it) } -> Problem.FIELD
        else -> null
    }

    fun fields(create: Boolean, digest: String? = null): FormFields = buildList {
        val deleted = mutableListOf<String>()
        fun many(key: String, values: List<String>) {
            if (values.isNotEmpty()) values.forEach { add(key to it) } else if (!create) deleted += key
        }
        if (create) add("name" to name.trim())
        many("target", targets.sorted())
        many("match-severity", if (severities.isEmpty()) emptyList() else listOf(NotifySeverities.filter { it in severities }.joinToString(",")))
        many("match-field", lines(fields))
        many("match-calendar", lines(calendar))
        add("mode" to mode)
        if (invert) add("invert-match" to "1") else if (!create) deleted += "invert-match"
        if (comment.isNotBlank()) add("comment" to comment.trim()) else if (!create) deleted += "comment"
        if (disabled) add("disable" to "1") else if (!create) deleted += "disable"
        deleted.forEach { add("delete" to it) }
        if (!create) digest?.let { add("digest" to it) }
    }

    companion object {
        private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        fun from(m: NotifyMatcher) = NotifyMatcherForm(
            name = m.name,
            targets = m.targets.toSet(),
            severities = m.severities.toSet(),
            fields = m.fields.joinToString("\n"),
            calendar = m.calendar.joinToString("\n"),
            mode = m.mode,
            invert = m.invert,
            comment = m.comment.orEmpty(),
            disabled = m.disabled,
        )
    }
}
