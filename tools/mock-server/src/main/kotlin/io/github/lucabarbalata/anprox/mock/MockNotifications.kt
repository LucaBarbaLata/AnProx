package io.github.lucabarbalata.anprox.mock

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Notification targets and matchers (`/cluster/notifications/…`), written from pve-manager's
 * `PVE/API2/Cluster/Notifications.pm`. Array parameters arrive as repeated keys. Secrets (the
 * SMTP password, the Gotify token, webhook secret values) are kept but never returned.
 *
 * It starts like a fresh Proxmox VE 8: the built-in `mail-to-root` target and `default-matcher`,
 * plus a Gotify target `phone`.
 */
internal class MockNotifications {
    private class Entry(val type: String, val props: MutableMap<String, MutableList<String>>, var origin: String)

    private val targets = linkedMapOf(
        "mail-to-root" to Entry("sendmail", mutableMapOf("mailto-user" to mutableListOf("root@pam"), "comment" to mutableListOf("Send mails to root@pam's email address")), "builtin"),
        "phone" to Entry("gotify", mutableMapOf("server" to mutableListOf("https://gotify.example.com"), "token" to mutableListOf("secret-token")), "user-created"),
    )
    private val matchers = linkedMapOf(
        "default-matcher" to Entry(
            "matcher",
            mutableMapOf("target" to mutableListOf("mail-to-root"), "comment" to mutableListOf("Route all notifications to mail-to-root")),
            "builtin",
        ),
    )
    private var version = 0

    /** Names of the targets that were sent a test notification, oldest first. */
    val tested = mutableListOf<String>()

    fun targets(): JsonArray = synchronized(this) {
        JsonArray(
            targets.map { (name, e) ->
                buildJsonObject {
                    put("name", name)
                    put("type", e.type)
                    e.props["comment"]?.firstOrNull()?.let { put("comment", it) }
                    put("disable", if (e.props["disable"]?.firstOrNull() == "1") 1 else 0)
                    put("origin", e.origin)
                }
            },
        )
    }

    fun endpoint(type: String, name: String): JsonObject? = synchronized(this) {
        val e = targets[name]?.takeIf { it.type == type } ?: return null
        buildJsonObject {
            put("name", name)
            e.props.forEach { (k, values) ->
                when (k) {
                    "password", "token" -> Unit
                    // Webhook secrets come back by name only.
                    "secret" -> put(k, JsonArray(values.map { JsonPrimitive(it.substringBefore(",value=")) }))
                    "mailto", "mailto-user", "header" -> put(k, JsonArray(values.map(::JsonPrimitive)))
                    else -> put(k, values.first())
                }
            }
            put("digest", digest())
        }
    }

    /** `POST …/endpoints/{type}`. Null on success, or an error message. */
    fun create(type: String, params: Map<String, List<String>>): String? = synchronized(this) {
        val name = params["name"]?.firstOrNull().orEmpty()
        if (!Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(name)) return "name: invalid format - invalid configuration ID '$name'"
        if (name in targets || name in matchers) return "Cannot create target '$name': name is already used"
        required(type, params)?.let { return it }
        targets[name] = Entry(type, params.filterKeys { it != "name" }.mapValues { it.value.toMutableList() }.toMutableMap(), "user-created")
        version++
        null
    }

    fun update(type: String, name: String, params: Map<String, List<String>>): String? = synchronized(this) {
        val e = targets[name]?.takeIf { it.type == type } ?: return "endpoint '$name' does not exist"
        params["digest"]?.firstOrNull()?.let { if (it != digest()) return "detected modified configuration - file changed by other user? Try again." }
        params["delete"].orEmpty().forEach { e.props.remove(it) }
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> e.props[k] = v.toMutableList() }
        required(type, e.props)?.let { return it }
        if (e.origin == "builtin") e.origin = "modified-builtin"
        version++
        null
    }

    fun delete(type: String, name: String): String? = synchronized(this) {
        val e = targets[name]?.takeIf { it.type == type } ?: return "endpoint '$name' does not exist"
        if (e.origin != "user-created") return "Cannot delete builtin target '$name'"
        targets.remove(name)
        version++
        null
    }

    /** `POST …/targets/{name}/test`: a Gotify server that can't be reached fails, as it would. */
    fun test(name: String): String? = synchronized(this) {
        val e = targets[name] ?: return "Target '$name' does not exist"
        if (e.type == "gotify" && e.props["server"]?.firstOrNull()?.contains("unreachable") == true) {
            return "Could not notify via target `$name`: Gotify: could not connect to the server"
        }
        tested += name
        null
    }

    fun matchers(): JsonArray = synchronized(this) {
        JsonArray(
            matchers.map { (name, e) ->
                buildJsonObject {
                    put("name", name)
                    e.props.forEach { (k, values) ->
                        if (k in ARRAYS) put(k, JsonArray(values.map(::JsonPrimitive))) else put(k, values.first())
                    }
                    put("origin", e.origin)
                    put("digest", digest())
                }
            },
        )
    }

    fun createMatcher(params: Map<String, List<String>>): String? = synchronized(this) {
        val name = params["name"]?.firstOrNull().orEmpty()
        if (!Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(name)) return "name: invalid format - invalid configuration ID '$name'"
        if (name in matchers || name in targets) return "Cannot create matcher '$name': name is already used"
        checkMatcher(params)?.let { return it }
        matchers[name] = Entry("matcher", params.filterKeys { it != "name" }.mapValues { it.value.toMutableList() }.toMutableMap(), "user-created")
        version++
        null
    }

    fun updateMatcher(name: String, params: Map<String, List<String>>): String? = synchronized(this) {
        val e = matchers[name] ?: return "matcher '$name' does not exist"
        params["digest"]?.firstOrNull()?.let { if (it != digest()) return "detected modified configuration - file changed by other user? Try again." }
        checkMatcher(params)?.let { return it }
        params["delete"].orEmpty().forEach { e.props.remove(it) }
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> e.props[k] = v.toMutableList() }
        if (e.origin == "builtin") e.origin = "modified-builtin"
        version++
        null
    }

    fun deleteMatcher(name: String): String? = synchronized(this) {
        val e = matchers[name] ?: return "matcher '$name' does not exist"
        if (e.origin != "user-created") return "Cannot delete builtin matcher '$name'"
        matchers.remove(name)
        version++
        null
    }

    fun fieldValues(backupJobs: List<String>): JsonArray = JsonArray(
        listOf("package-updates", "fencing", "replication", "vzdump", "system-mail").map { v ->
            buildJsonObject {
                put("field", "type")
                put("value", v)
            }
        } + backupJobs.map { id ->
            buildJsonObject {
                put("field", "job-id")
                put("value", id)
                put("comment", "Backup job")
            }
        },
    )

    private fun required(type: String, p: Map<String, List<String>>): String? = when (type) {
        "sendmail" -> if (p["mailto"].isNullOrEmpty() && p["mailto-user"].isNullOrEmpty()) "At least one recipient has to be specified!" else null
        "smtp" -> when {
            p["server"].isNullOrEmpty() -> "server: property is missing and it is not optional"
            p["from-address"].isNullOrEmpty() -> "from-address: property is missing and it is not optional"
            p["mailto"].isNullOrEmpty() && p["mailto-user"].isNullOrEmpty() -> "At least one recipient has to be specified!"
            else -> null
        }
        "gotify" -> when {
            p["server"].isNullOrEmpty() -> "server: property is missing and it is not optional"
            p["token"].isNullOrEmpty() -> "token: property is missing and it is not optional"
            else -> null
        }
        "webhook" -> when {
            p["url"].isNullOrEmpty() -> "url: property is missing and it is not optional"
            p["method"].isNullOrEmpty() -> "method: property is missing and it is not optional"
            else -> null
        }
        else -> "unknown endpoint type '$type'"
    }

    private fun checkMatcher(p: Map<String, List<String>>): String? {
        p["target"].orEmpty().firstOrNull { it !in targets }?.let { return "target '$it' does not exist" }
        p["match-severity"].orEmpty().flatMap { it.split(',') }.firstOrNull { it.trim() !in listOf("info", "notice", "warning", "error", "unknown") }
            ?.let { return "match-severity: invalid severity '$it'" }
        p["match-field"].orEmpty().firstOrNull { !Regex("^(exact|regex):[A-Za-z0-9_-]+=.+$").matches(it) }
            ?.let { return "match-field: invalid format '$it'" }
        return null
    }

    private fun digest(): String = MessageDigest.getInstance("SHA-1").digest("notifications:$version".toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val ARRAYS = setOf("target", "match-severity", "match-field", "match-calendar")
    }
}
