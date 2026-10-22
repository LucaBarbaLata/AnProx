package io.github.lucabarbalata.anprox.mock

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * A Backup Server's access control (`/access/…` on PBS), written from proxmox-backup's
 * `src/api2/access/{user,acl,role,mod}.rs`. Unlike Proxmox VE: no groups, fixed roles, the ACL
 * takes one `role` and an `auth-id`, `delete` repeats its key, and tokens list a `token-name`.
 *
 * Seeded with `root@pam` and `backup@pbs`, who may back up to store1.
 */
internal class MockPbsAccess {
    private class User(
        val id: String,
        var comment: String? = null,
        var enable: Boolean = true,
        var expire: Long? = null,
        var firstname: String? = null,
        var lastname: String? = null,
        var email: String? = null,
        var password: String? = null,
        val tokens: MutableMap<String, Token> = linkedMapOf(),
    )

    private class Token(var comment: String?, var enable: Boolean, var expire: Long?, val secret: String)

    private data class Entry(val path: String, val authId: String, val role: String, val propagate: Boolean)

    private val users = linkedMapOf(
        "root@pam" to User("root@pam", comment = "Superuser"),
        "backup@pbs" to User("backup@pbs", comment = "Proxmox VE backups", email = "backup@example.com", password = "backup-pass"),
    )
    private val acl = mutableListOf(Entry("/datastore/store1", "backup@pbs", "DatastoreBackup", true))

    /** The password of a `pbs` realm user, for signing in. */
    fun password(userId: String): String? = synchronized(this) { users[userId]?.password }

    fun users(includeTokens: Boolean): JsonArray = synchronized(this) {
        JsonArray(
            users.values.map { u ->
                buildJsonObject {
                    put("userid", u.id)
                    u.comment?.let { put("comment", it) }
                    put("enable", u.enable)
                    u.expire?.let { put("expire", it) }
                    u.firstname?.let { put("firstname", it) }
                    u.lastname?.let { put("lastname", it) }
                    u.email?.let { put("email", it) }
                    if (includeTokens) put("tokens", JsonArray(u.tokens.map { (name, t) -> token(u.id, name, t, withName = false) }))
                }
            },
        )
    }

    private fun token(userId: String, name: String, t: Token, withName: Boolean) = buildJsonObject {
        if (withName) put("token-name", name)
        put("tokenid", "$userId!$name")
        t.comment?.let { put("comment", it) }
        put("enable", t.enable)
        t.expire?.let { put("expire", it) }
    }

    fun createUser(p: Map<String, List<String>>): String? = synchronized(this) {
        unknown(p, setOf("userid", "comment", "enable", "expire", "firstname", "lastname", "email", "password"))?.let { return it }
        val id = p.one("userid").orEmpty()
        val realm = id.substringAfterLast('@', "")
        if (realm !in setOf("pam", "pbs")) return "userid: realm '$realm' does not exist"
        if (id in users) return "user '$id' already exists."
        val u = User(id)
        apply(u, p)
        if (realm == "pbs") u.password = p.one("password")
        users[id] = u
        null
    }

    fun updateUser(id: String, p: Map<String, List<String>>): String? = synchronized(this) {
        unknown(p, setOf("comment", "enable", "expire", "firstname", "lastname", "email", "password", "delete", "digest"))?.let { return it }
        val u = users[id] ?: return "no such user '$id'"
        p["delete"].orEmpty().forEach { d ->
            when (d) {
                "comment" -> u.comment = null
                "firstname" -> u.firstname = null
                "lastname" -> u.lastname = null
                "email" -> u.email = null
                else -> return "delete: value '$d' is not defined in the enumeration."
            }
        }
        apply(u, p)
        p.one("password")?.let { u.password = it }
        null
    }

    private fun apply(u: User, p: Map<String, List<String>>) {
        p.one("comment")?.let { u.comment = it.trim().ifEmpty { null } }
        p.one("enable")?.let { u.enable = it == "1" || it == "true" }
        p.one("expire")?.toLongOrNull()?.let { u.expire = it.takeIf { e -> e > 0 } }
        p.one("firstname")?.let { u.firstname = it.ifEmpty { null } }
        p.one("lastname")?.let { u.lastname = it.ifEmpty { null } }
        p.one("email")?.let { u.email = it.ifEmpty { null } }
    }

    fun deleteUser(id: String): String? = synchronized(this) {
        if (users.remove(id) == null) return "no such user '$id'"
        acl.removeAll { it.authId == id || it.authId.startsWith("$id!") }
        null
    }

    fun changePassword(p: Map<String, List<String>>): String? = synchronized(this) {
        val u = users[p.one("userid").orEmpty()] ?: return "no such user"
        if (!u.id.endsWith("@pbs")) return "changing the password of a '${u.id.substringAfter('@')}' user is not supported here"
        u.password = p.one("password") ?: return "password: property is missing and it is not optional"
        null
    }

    fun tokens(userId: String): JsonArray? = synchronized(this) {
        val u = users[userId] ?: return null
        JsonArray(u.tokens.map { (name, t) -> token(u.id, name, t, withName = true) })
    }

    fun createToken(userId: String, name: String, p: Map<String, List<String>>): Pair<JsonObject?, String?> = synchronized(this) {
        unknown(p, setOf("comment", "enable", "expire", "digest"))?.let { return null to it }
        val u = users[userId] ?: return null to "no such user '$userId'"
        if (name in u.tokens) return null to "token '$name' for user '$userId' already exists."
        val secret = UUID.randomUUID().toString()
        u.tokens[name] = Token(p.one("comment"), p.one("enable") != "0", p.one("expire")?.toLongOrNull()?.takeIf { it > 0 }, secret)
        buildJsonObject {
            put("tokenid", "$userId!$name")
            put("value", secret)
        } to null
    }

    fun deleteToken(userId: String, name: String): String? = synchronized(this) {
        val u = users[userId] ?: return "no such user '$userId'"
        if (u.tokens.remove(name) == null) return "no such token '$name' for user '$userId'"
        acl.removeAll { it.authId == "$userId!$name" }
        null
    }

    fun roles(): JsonArray = JsonArray(
        ROLES.map { (id, privs) ->
            buildJsonObject {
                put("roleid", id)
                put("privs", JsonArray(privs.map(::JsonPrimitive)))
            }
        },
    )

    fun acl(): JsonArray = synchronized(this) {
        JsonArray(
            acl.map { e ->
                buildJsonObject {
                    put("path", e.path)
                    put("ugid", e.authId)
                    put("ugid_type", "user")
                    put("roleid", e.role)
                    put("propagate", e.propagate)
                }
            },
        )
    }

    /** `PUT /access/acl`. Null on success, or an error message. */
    fun updateAcl(p: Map<String, List<String>>): String? = synchronized(this) {
        unknown(p, setOf("path", "role", "propagate", "auth-id", "group", "delete", "digest"))?.let { return it }
        if (p.one("group") != null) return "groups are currently not supported"
        val authId = p.one("auth-id") ?: return "missing 'userid' or 'group' parameter."
        val path = p.one("path") ?: return "path: property is missing and it is not optional"
        val role = p.one("role") ?: return "role: property is missing and it is not optional"
        if (ROLES.none { it.first == role }) return "role: value '$role' is not defined in the enumeration."
        val user = authId.substringBefore('!')
        val owner = users[user] ?: return "no such user '$user'"
        if ('!' in authId && authId.substringAfter('!') !in owner.tokens) return "no such API token '$authId'"
        val delete = p.one("delete") == "1" || p.one("delete") == "true"
        acl.removeAll { it.path == path && it.authId == authId && it.role == role }
        if (!delete) acl += Entry(path, authId, role, p.one("propagate") != "0" && p.one("propagate") != "false")
        null
    }

    /** `GET /access/permissions`: root may do everything; others what the ACL gives them. */
    fun permissions(authId: String?, caller: String): JsonObject = synchronized(this) {
        val who = authId ?: caller
        buildJsonObject {
            if (who == "root@pam") {
                putJsonObject("/") { ROLES.first().second.forEach { put(it, true) } }
            } else {
                acl.filter { it.authId == who }.groupBy { it.path }.forEach { (path, entries) ->
                    putJsonObject(path) { entries.flatMap { e -> ROLES.first { it.first == e.role }.second.map { it to e.propagate } }.forEach { (priv, prop) -> put(priv, prop) } }
                }
            }
        }
    }

    fun unlockTfa(id: String): Boolean? = synchronized(this) { if (id in users) false else null }

    private fun Map<String, List<String>>.one(key: String): String? = this[key]?.firstOrNull()

    /** A Backup Server refuses parameters it doesn't know (`groups`, `keys`, `privsep`, …). */
    private fun unknown(p: Map<String, List<String>>, known: Set<String>): String? =
        p.keys.firstOrNull { it !in known }?.let { "parameter verification failed - '$it': schema does not allow additional properties" }

    private companion object {
        val ROLES = listOf(
            "Admin" to listOf("Datastore.Allocate", "Datastore.Audit", "Datastore.Backup", "Datastore.Modify", "Datastore.Prune", "Datastore.Read", "Datastore.Verify", "Permissions.Modify", "Remote.Audit", "Remote.Modify", "Remote.Read", "Sys.Audit", "Sys.Console", "Sys.Modify", "Sys.PowerManagement", "Tape.Audit", "Tape.Modify", "Tape.Read", "Tape.Write"),
            "Audit" to listOf("Datastore.Audit", "Remote.Audit", "Sys.Audit", "Tape.Audit"),
            "DatastoreAdmin" to listOf("Datastore.Allocate", "Datastore.Audit", "Datastore.Backup", "Datastore.Modify", "Datastore.Prune", "Datastore.Read", "Datastore.Verify"),
            "DatastoreAudit" to listOf("Datastore.Audit"),
            "DatastoreBackup" to listOf("Datastore.Backup"),
            "DatastorePowerUser" to listOf("Datastore.Backup", "Datastore.Prune"),
            "DatastoreReader" to listOf("Datastore.Audit", "Datastore.Read", "Datastore.Verify"),
            "NoAccess" to emptyList(),
            "RemoteAdmin" to listOf("Remote.Audit", "Remote.Modify", "Remote.Read"),
            "RemoteAudit" to listOf("Remote.Audit"),
            "RemoteSyncOperator" to listOf("Remote.Audit", "Remote.Read"),
            "TapeAdmin" to listOf("Tape.Audit", "Tape.Modify", "Tape.Read", "Tape.Write"),
        )
    }
}
