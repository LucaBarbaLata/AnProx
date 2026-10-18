package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.auth.Realm
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `/access/…` of the mock (milestone M7): users with API tokens, groups, roles, the ACL, realms
 * (an Active Directory one that "syncs" two users) and second factors with real TOTP checks.
 * All passwords are `anprox` unless changed.
 */
internal class MockAccess(private val clockSeconds: () -> Long) {
    data class User(
        val id: String,
        var enabled: Boolean = true,
        var expire: Long = 0,
        var firstName: String? = null,
        var lastName: String? = null,
        var email: String? = null,
        var comment: String? = null,
        var groups: MutableSet<String> = mutableSetOf(),
        var keys: String? = null,
        var password: String = MockState.PASSWORD,
        var tfaLockedUntil: Long? = null,
        val tokens: LinkedHashMap<String, Token> = linkedMapOf(),
    )

    data class Token(val id: String, val comment: String?, val privsep: Boolean, val expire: Long, val secret: String)

    data class Tfa(val id: String, val type: String, var description: String, val created: Long, var enabled: Boolean = true, val secret: String? = null)

    data class Acl(val path: String, val type: String, val ugid: String, val role: String, val propagate: Boolean)

    private val random = SecureRandom()
    private val users = linkedMapOf<String, User>()
    private val groups = linkedMapOf<String, String?>()
    private val roles = linkedMapOf<String, Pair<Set<String>, Boolean>>()
    private val acl = mutableListOf<Acl>()
    private val realms = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val tfa = linkedMapOf<String, MutableList<Tfa>>()

    init {
        val now = clockSeconds()
        users["root@pam"] = User("root@pam", comment = "Built-in Superuser", email = "root@example.com").apply {
            tokens["automation"] = Token("automation", "Terraform", privsep = false, expire = 0, secret = uuid())
        }
        users["admin@pve"] = User("admin@pve", firstName = "Ada", lastName = "Admin", email = "ada@example.com", groups = mutableSetOf("admins"))
        users["auditor@pve"] = User("auditor@pve", comment = "Read-only", groups = mutableSetOf("auditors"))
        users["alice@corp"] = User("alice@corp", firstName = "Alice", lastName = "Ops", email = "alice@corp.example", groups = mutableSetOf("ops"))
        users["intern@pve"] = User("intern@pve", enabled = false, expire = now - 86_400, comment = "Summer internship")

        groups["admins"] = "Full access"
        groups["auditors"] = "Read everything"
        groups["ops"] = "Operations team"

        val vmUser = setOf("VM.Audit", "VM.Console", "VM.PowerMgmt", "VM.Backup", "VM.Snapshot", "VM.Config.CDROM", "VM.Config.Cloudinit")
        roles["Administrator"] = PRIVILEGES.toSet() to true
        roles["NoAccess"] = emptySet<String>() to true
        roles["PVEAuditor"] = setOf("Datastore.Audit", "Mapping.Audit", "Pool.Audit", "SDN.Audit", "Sys.Audit", "VM.Audit") to true
        roles["PVEVMUser"] = vmUser to true
        roles["PVEVMAdmin"] = (vmUser + PRIVILEGES.filter { it.startsWith("VM.") }) to true
        roles["PVEDatastoreUser"] = setOf("Datastore.AllocateSpace", "Datastore.Audit") to true
        roles["Operator"] = setOf("VM.Audit", "VM.Console", "VM.PowerMgmt") to false

        acl += Acl("/", "group", "admins", "Administrator", true)
        acl += Acl("/", "user", "auditor@pve", "PVEAuditor", true)
        acl += Acl("/vms", "group", "ops", "PVEVMUser", true)
        acl += Acl("/pool/prod", "token", "root@pam!automation", "PVEVMAdmin", true)

        realms["pam"] = linkedMapOf("type" to "pam", "comment" to "Linux PAM standard authentication", "default" to "1")
        realms["pve"] = linkedMapOf("type" to "pve", "comment" to "Proxmox VE authentication server")
        realms["corp"] = linkedMapOf(
            "type" to "ad", "comment" to "Corporate Active Directory", "server1" to "dc1.corp.example",
            "domain" to "corp.example", "mode" to "ldaps", "verify" to "1",
        )
        realms["sso"] = linkedMapOf(
            "type" to "openid", "comment" to "Authentik SSO", "issuer-url" to "https://auth.example.com/application/o/proxmox/",
            "client-id" to "proxmox", "username-claim" to "username", "autocreate" to "1",
        )

        tfa["admin@pve"] = mutableListOf(
            Tfa("totp-1", "totp", "Phone", now - 90 * 86_400),
            Tfa("recovery", "recovery", "", now - 90 * 86_400),
        )
    }

    // ---- logins

    fun password(userId: String): String? = synchronized(this) { users[userId]?.takeIf { it.enabled }?.password }

    // ---- users

    fun users(): JsonArray = synchronized(this) {
        buildJsonArray { users.values.forEach { add(userJson(it)) } }
    }

    fun user(id: String): JsonObject? = synchronized(this) { users[id]?.let(::userJson) }

    private fun userJson(u: User) = buildJsonObject {
        put("userid", u.id)
        put("enable", if (u.enabled) 1 else 0)
        put("expire", u.expire)
        u.firstName?.let { put("firstname", it) }
        u.lastName?.let { put("lastname", it) }
        u.email?.let { put("email", it) }
        u.comment?.let { put("comment", it) }
        u.keys?.let { put("keys", it) }
        put("groups", u.groups.sorted().joinToString(","))
        put("realm-type", realms[u.id.substringAfterLast('@')]?.get("type") ?: "pve")
        u.tfaLockedUntil?.let { put("tfa-locked-until", it) }
        put("tokens", buildJsonArray { u.tokens.values.forEach { add(tokenJson(it)) } })
    }

    fun createUser(p: Map<String, String>): String? = synchronized(this) {
        val id = p["userid"].orEmpty()
        if (!Regex("^[^\\s:/@!]+@[A-Za-z][A-Za-z0-9.\\-_]+$").matches(id)) return "userid: value does not look like a valid user ID"
        if (id in users) return "create user failed: user '$id' already exists"
        val realm = id.substringAfterLast('@')
        if (realm !in realms) return "create user failed: no such realm '$realm'"
        p["password"]?.let { if (it.length < 5) return "password: value must be at least 5 characters long" }
        val groupList = p["groups"].orEmpty().split(',').filter { it.isNotBlank() }
        groupList.firstOrNull { it !in groups }?.let { return "create user failed: no such group '$it'" }
        users[id] = User(id, password = p["password"] ?: MockState.PASSWORD).also { apply(it, p) }
        null
    }

    fun updateUser(id: String, p: Map<String, String>): String? = synchronized(this) {
        val u = users[id] ?: return "update user failed: no such user '$id'"
        p["groups"]?.split(',')?.filter { it.isNotBlank() }?.firstOrNull { it !in groups }?.let { return "update user failed: no such group '$it'" }
        apply(u, p)
        p["delete"].orEmpty().split(',').forEach {
            when (it.trim()) {
                "firstname" -> u.firstName = null
                "lastname" -> u.lastName = null
                "email" -> u.email = null
                "comment" -> u.comment = null
                "keys" -> u.keys = null
            }
        }
        null
    }

    private fun apply(u: User, p: Map<String, String>) {
        p["firstname"]?.let { u.firstName = it }
        p["lastname"]?.let { u.lastName = it }
        p["email"]?.let { u.email = it }
        p["comment"]?.let { u.comment = it }
        p["keys"]?.let { u.keys = it }
        p["enable"]?.let { u.enabled = it == "1" }
        p["expire"]?.let { u.expire = it.toLongOrNull() ?: 0 }
        p["groups"]?.let { g -> u.groups = g.split(',').filter { it.isNotBlank() }.toMutableSet() }
    }

    fun deleteUser(id: String): String? = synchronized(this) {
        if (id == "root@pam") return "can't delete the root user"
        users.remove(id) ?: return "delete user failed: no such user '$id'"
        acl.removeAll { it.ugid == id || it.ugid.startsWith("$id!") }
        tfa.remove(id)
        null
    }

    /** [by] is the signed-in user; anyone but root confirms with their own password. */
    fun changePassword(by: String, p: Map<String, String>): String? = synchronized(this) {
        val id = p["userid"].orEmpty()
        val u = users[id] ?: return "no such user '$id'"
        val password = p["password"].orEmpty()
        if (password.length < 5) return "password: value must be at least 5 characters long"
        if (by != "root@pam" && p["confirmation-password"] != users[by]?.password) return "confirmation password is wrong"
        u.password = password
        null
    }

    fun unlockTfa(id: String): String? = synchronized(this) {
        val u = users[id] ?: return "no such user '$id'"
        u.tfaLockedUntil = null
        null
    }

    // ---- tokens

    private fun tokenJson(t: Token) = buildJsonObject {
        put("tokenid", t.id)
        t.comment?.let { put("comment", it) }
        put("privsep", if (t.privsep) 1 else 0)
        put("expire", t.expire)
    }

    fun tokens(userId: String): JsonArray? = synchronized(this) {
        val u = users[userId] ?: return null
        buildJsonArray { u.tokens.values.forEach { add(tokenJson(it)) } }
    }

    fun createToken(userId: String, tokenId: String, p: Map<String, String>): Pair<JsonObject?, String?> = synchronized(this) {
        val u = users[userId] ?: return null to "no such user '$userId'"
        if (!Regex("^[A-Za-z][A-Za-z0-9.\\-_]*$").matches(tokenId)) return null to "tokenid: invalid format"
        if (tokenId in u.tokens) return null to "Token already exists."
        val token = Token(tokenId, p["comment"], p["privsep"] != "0", p["expire"]?.toLongOrNull() ?: 0, uuid())
        u.tokens[tokenId] = token
        buildJsonObject {
            put("full-tokenid", "$userId!$tokenId")
            put("value", token.secret)
            put("info", tokenJson(token))
        } to null
    }

    fun deleteToken(userId: String, tokenId: String): String? = synchronized(this) {
        users[userId]?.tokens?.remove(tokenId) ?: return "no such token '$tokenId' for user '$userId'"
        acl.removeAll { it.ugid == "$userId!$tokenId" }
        null
    }

    // ---- groups

    fun groups(): JsonArray = synchronized(this) {
        buildJsonArray {
            groups.forEach { (id, comment) ->
                add(
                    buildJsonObject {
                        put("groupid", id)
                        comment?.let { put("comment", it) }
                        put("users", users.values.filter { id in it.groups }.joinToString(",") { it.id })
                    },
                )
            }
        }
    }

    fun group(id: String): JsonObject? = synchronized(this) {
        if (id !in groups) return null
        buildJsonObject {
            groups[id]?.let { put("comment", it) }
            put("members", buildJsonArray { users.values.filter { id in it.groups }.forEach { add(JsonPrimitive(it.id)) } })
        }
    }

    fun createGroup(p: Map<String, String>): String? = synchronized(this) {
        val id = p["groupid"].orEmpty()
        if (!Regex("^[A-Za-z0-9.\\-_]+$").matches(id)) return "groupid: invalid format"
        if (id in groups) return "create group failed: group '$id' already exists"
        groups[id] = p["comment"]
        null
    }

    fun updateGroup(id: String, p: Map<String, String>): String? = synchronized(this) {
        if (id !in groups) return "group '$id' does not exist"
        p["comment"]?.let { groups[id] = it.ifBlank { null } }
        null
    }

    fun deleteGroup(id: String): String? = synchronized(this) {
        if (id !in groups) return "group '$id' does not exist"
        groups.remove(id)
        users.values.forEach { it.groups.remove(id) }
        acl.removeAll { it.type == "group" && it.ugid == id }
        null
    }

    // ---- roles

    fun roles(): JsonArray = synchronized(this) {
        buildJsonArray {
            roles.forEach { (id, r) ->
                add(
                    buildJsonObject {
                        put("roleid", id)
                        put("privs", r.first.sorted().joinToString(","))
                        if (r.second) put("special", 1)
                    },
                )
            }
        }
    }

    fun createRole(p: Map<String, String>): String? = synchronized(this) {
        val id = p["roleid"].orEmpty()
        if (!Regex("^[A-Za-z0-9.\\-_]+$").matches(id)) return "roleid: invalid format"
        if (id in roles) return "role '$id' already exists"
        val privs = privileges(p["privs"]) ?: return "invalid privilege"
        roles[id] = privs to false
        null
    }

    fun updateRole(id: String, p: Map<String, String>): String? = synchronized(this) {
        val r = roles[id] ?: return "role '$id' does not exist"
        if (r.second) return "cannot modify special role '$id'"
        val privs = privileges(p["privs"]) ?: return "invalid privilege"
        roles[id] = (if (p["append"] == "1") r.first + privs else privs) to false
        null
    }

    private fun privileges(list: String?): Set<String>? {
        val privs = list.orEmpty().split(',', ';', ' ').filter { it.isNotBlank() }.toSet()
        return privs.takeIf { PRIVILEGES.containsAll(it) }
    }

    fun deleteRole(id: String): String? = synchronized(this) {
        val r = roles[id] ?: return "role '$id' does not exist"
        if (r.second) return "cannot delete special role '$id'"
        roles.remove(id)
        acl.removeAll { it.role == id }
        null
    }

    // ---- ACL

    fun acl(): JsonArray = synchronized(this) {
        buildJsonArray {
            acl.forEach {
                add(
                    buildJsonObject {
                        put("path", it.path)
                        put("type", it.type)
                        put("ugid", it.ugid)
                        put("roleid", it.role)
                        put("propagate", if (it.propagate) 1 else 0)
                    },
                )
            }
        }
    }

    fun updateAcl(p: Map<String, String>): String? = synchronized(this) {
        val path = p["path"].orEmpty()
        if (!path.startsWith("/")) return "path: invalid ACL path"
        val roleIds = p["roles"].orEmpty().split(',').filter { it.isNotBlank() }
        roleIds.firstOrNull { it !in roles }?.let { return "role '$it' does not exist" }
        val subjects = buildList {
            p["users"]?.split(',')?.filter { it.isNotBlank() }?.forEach { add("user" to it) }
            p["groups"]?.split(',')?.filter { it.isNotBlank() }?.forEach { add("group" to it) }
            p["tokens"]?.split(',')?.filter { it.isNotBlank() }?.forEach { add("token" to it) }
        }
        if (subjects.isEmpty()) return "either 'users', 'groups' or 'tokens' is required"
        subjects.forEach { (type, id) ->
            val exists = when (type) {
                "user" -> id in users
                "group" -> id in groups
                else -> users[id.substringBefore('!')]?.tokens?.containsKey(id.substringAfter('!')) == true
            }
            if (!exists) return "$type '$id' does not exist"
        }
        val delete = p["delete"] == "1"
        val propagate = p["propagate"] != "0"
        subjects.forEach { (type, id) ->
            roleIds.forEach { role ->
                acl.removeAll { it.path == path && it.type == type && it.ugid == id && it.role == role }
                if (!delete) acl += Acl(path, type, id, role, propagate)
            }
        }
        null
    }

    /** Privileges [userId] has on each ACL path (or just [path]), from their own and their groups' entries. */
    fun permissions(userId: String, path: String?): JsonObject = synchronized(this) {
        val groupsOf = users[userId]?.groups.orEmpty()
        val mine = acl.filter { (it.type == "user" && it.ugid == userId) || (it.type == "group" && it.ugid in groupsOf) }
        val paths = path?.let { listOf(it) } ?: mine.map { it.path }.distinct()
        buildJsonObject {
            paths.forEach { p ->
                val privs = mine.filter { it.path == p || (it.propagate && (it.path == "/" || p.startsWith(it.path + "/"))) }
                    .flatMap { roles[it.role]?.first.orEmpty() }.toSortedSet()
                if (privs.isNotEmpty()) put(p, buildJsonObject { privs.forEach { put(it, 1) } })
            }
        }
    }

    // ---- realms

    fun realmList(): List<Realm> = synchronized(this) {
        realms.map { (id, c) -> Realm(id, c["type"].orEmpty(), c["comment"], default = c["default"] == "1") }
    }

    fun realmConfig(id: String): JsonObject? = synchronized(this) {
        val c = realms[id] ?: return null
        buildJsonObject {
            put("realm", id)
            c.forEach { (k, v) -> put(k, v) }
        }
    }

    fun createRealm(p: Map<String, String>): String? = synchronized(this) {
        val id = p["realm"].orEmpty()
        val type = p["type"].orEmpty()
        if (!Regex("^[A-Za-z][A-Za-z0-9.\\-_]+$").matches(id)) return "realm: invalid format"
        if (id in realms) return "domain '$id' already exists"
        if (type !in setOf("ldap", "ad", "openid")) return "type: value '$type' does not have a value in the enumeration 'ad, ldap, openid'"
        when (type) {
            "ldap" -> if (p["server1"].isNullOrBlank() || p["base_dn"].isNullOrBlank()) return "server1 and base_dn are required"
            "ad" -> if (p["server1"].isNullOrBlank() || p["domain"].isNullOrBlank()) return "server1 and domain are required"
            "openid" -> if (p["issuer-url"].isNullOrBlank() || p["client-id"].isNullOrBlank()) return "issuer-url and client-id are required"
        }
        if (p["default"] == "1") realms.values.forEach { it.remove("default") }
        realms[id] = LinkedHashMap(p.filterKeys { it != "realm" && it != "password" && it != "client-key" })
        null
    }

    fun updateRealm(id: String, p: Map<String, String>): String? = synchronized(this) {
        val c = realms[id] ?: return "domain '$id' does not exist"
        if ("username-claim" in p) return "username-claim: can't be changed"
        if (p["default"] == "1") realms.values.forEach { it.remove("default") }
        p.filterKeys { it != "password" && it != "client-key" && it != "delete" }.forEach { (k, v) -> c[k] = v }
        if (p["default"] == "0") c.remove("default")
        null
    }

    fun deleteRealm(id: String): String? = synchronized(this) {
        val c = realms[id] ?: return "domain '$id' does not exist"
        if (c["type"] == "pam" || c["type"] == "pve") return "can't delete realm of type '${c["type"]}'"
        realms.remove(id)
        null
    }

    /** What a sync of [realm] finds in its directory, and the log of applying it (or not). */
    fun sync(realm: String, p: Map<String, String>): Pair<List<String>, (() -> Unit)?> = synchronized(this) {
        val type = realms[realm]?.get("type")
        require(type == "ad" || type == "ldap") { "realm '$realm' can't be synced" }
        val dryRun = p["dry-run"] == "1"
        val scope = p["scope"] ?: "both"
        val directoryUsers = listOf("bob@$realm", "carol@$realm")
        val directoryGroup = "devs-$realm"
        val log = buildList {
            if (dryRun) add("dry run: nothing will be changed")
            if (scope != "groups") directoryUsers.filter { it !in users }.forEach { add("adding user '$it'") }
            if (scope != "users" && directoryGroup !in groups) add("adding group '$directoryGroup'")
        }
        val effect: (() -> Unit)? = if (dryRun) null else {
            {
                synchronized(this) {
                    if (scope != "groups") directoryUsers.forEach { users.getOrPut(it) { User(it, enabled = p["enable-new"] != "0") } }
                    if (scope != "users") groups.putIfAbsent(directoryGroup, "Synced from $realm")
                }
            }
        }
        log to effect
    }

    // ---- second factors

    fun tfaList(): JsonArray = synchronized(this) {
        buildJsonArray {
            users.keys.forEach { id ->
                add(
                    buildJsonObject {
                        put("userid", id)
                        put("entries", buildJsonArray { tfa[id].orEmpty().forEach { add(tfaJson(it)) } })
                    },
                )
            }
        }
    }

    private fun tfaJson(t: Tfa) = buildJsonObject {
        put("id", t.id)
        put("type", t.type)
        put("description", t.description)
        put("created", t.created)
        put("enable", if (t.enabled) 1 else 0)
    }

    /** Anyone but root confirms changes to second factors with their own password. */
    private fun confirmed(by: String, password: String?): Boolean = by == "root@pam" || password == users[by]?.password

    fun addTfa(by: String, userId: String, p: Map<String, String>): Pair<JsonObject?, String?> = synchronized(this) {
        if (userId !in users) return null to "no such user '$userId'"
        if (!confirmed(by, p["password"])) return null to "authentication failure"
        val list = tfa.getOrPut(userId) { mutableListOf() }
        val now = clockSeconds()
        when (p["type"]) {
            "totp" -> {
                val uri = p["totp"].orEmpty()
                val secret = MockTotp.otpSecret(uri) ?: return null to "totp: invalid otpauth URI"
                if (p["value"] != MockTotp.code(secret, now) && p["value"] != MockTotp.code(secret, now - 30)) return null to "failed to verify TOTP challenge"
                val id = "totp-${list.size + 1}"
                list += Tfa(id, "totp", p["description"].orEmpty(), now, secret = secret)
                buildJsonObject { put("id", id) } to null
            }
            "recovery" -> {
                list.removeAll { it.type == "recovery" }
                list += Tfa("recovery", "recovery", "", now)
                val keys = List(10) { (1..4).joinToString("-") { "%04x".format(random.nextInt(0x10000)) } }
                buildJsonObject {
                    put("id", "recovery")
                    put("recovery", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
                } to null
            }
            else -> null to "type: value '${p["type"]}' is not supported by the mock"
        }
    }

    fun updateTfa(by: String, userId: String, id: String, p: Map<String, String>): String? = synchronized(this) {
        val t = tfa[userId]?.firstOrNull { it.id == id } ?: return "no such entry '$id'"
        if (!confirmed(by, p["password"])) return "authentication failure"
        p["description"]?.let { t.description = it }
        p["enable"]?.let { t.enabled = it == "1" }
        null
    }

    fun deleteTfa(by: String, userId: String, id: String, password: String?): String? = synchronized(this) {
        if (!confirmed(by, password)) return "authentication failure"
        tfa[userId]?.removeAll { it.id == id }?.takeIf { it } ?: return "no such entry '$id'"
        null
    }

    private fun uuid(): String = java.util.UUID(random.nextLong(), random.nextLong()).toString()

    companion object {
        /** The privileges of Proxmox VE 8. */
        val PRIVILEGES = listOf(
            "Datastore.Allocate", "Datastore.AllocateSpace", "Datastore.AllocateTemplate", "Datastore.Audit",
            "Group.Allocate", "Mapping.Audit", "Mapping.Modify", "Mapping.Use", "Permissions.Modify",
            "Pool.Allocate", "Pool.Audit", "Realm.Allocate", "Realm.AllocateUser", "SDN.Allocate", "SDN.Audit", "SDN.Use",
            "Sys.AccessNetwork", "Sys.Audit", "Sys.Console", "Sys.Incoming", "Sys.Modify", "Sys.PowerMgmt", "Sys.Syslog",
            "User.Modify", "VM.Allocate", "VM.Audit", "VM.Backup", "VM.Clone", "VM.Config.CDROM", "VM.Config.CPU",
            "VM.Config.Cloudinit", "VM.Config.Disk", "VM.Config.HWType", "VM.Config.Memory", "VM.Config.Network",
            "VM.Config.Options", "VM.Console", "VM.Migrate", "VM.Monitor", "VM.PowerMgmt", "VM.Snapshot", "VM.Snapshot.Rollback",
        )
    }
}

/** TOTP for the mock and the tests that talk to it. */
object MockTotp {
    /** The Base32 secret of an `otpauth://totp/…?secret=…` URI. */
    fun otpSecret(uri: String): String? = runCatching {
        val u = URI(uri)
        if (u.scheme != "otpauth" || u.host != "totp") return null
        u.rawQuery.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == "secret" }?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()

    /** RFC 6238 TOTP: six digits, 30-second steps, HMAC-SHA1. */
    fun code(base32Secret: String, epochSeconds: Long): String {
        val key = base32Decode(base32Secret)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(epochSeconds / 30).array())
        val offset = hash.last().toInt() and 0x0f
        val code = ((hash[offset].toInt() and 0x7f) shl 24) or ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or (hash[offset + 3].toInt() and 0xff)
        return "%06d".format(code % 1_000_000)
    }

    private fun base32Decode(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s.uppercase().filter { it != '=' }) {
            buffer = (buffer shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
