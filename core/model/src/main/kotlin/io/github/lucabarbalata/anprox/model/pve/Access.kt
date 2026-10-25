package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.array
import io.github.lucabarbalata.anprox.model.json.bool
import io.github.lucabarbalata.anprox.model.json.long
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.json.strings
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Access control (questionnaire Q23, milestone M7): users, API tokens, groups, roles, ACLs,
 * realms and second factors, as `/access/…` returns them.
 */

/** `user@realm`, split. */
data class UserId(val name: String, val realm: String) {
    override fun toString(): String = "$name@$realm"

    companion object {
        fun parse(id: String): UserId = UserId(id.substringBeforeLast('@'), id.substringAfterLast('@', ""))

        /** User names: no spaces, `@`, `!` or `:` (Proxmox's `pve-userid` format). */
        val NAME = Regex("^[^\\s:/@!]{1,64}$")
    }
}

/** One row of `GET /access/users?full=1`. */
data class PveUser(
    val id: String,
    val enabled: Boolean = true,
    /** Epoch seconds; null or 0 never expires. */
    val expire: Long? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val comment: String? = null,
    val groups: List<String> = emptyList(),
    val keys: String? = null,
    val tokens: List<ApiToken> = emptyList(),
    val realmType: String? = null,
    /** Locked out of TOTP after too many wrong codes, or of all second factors until then. */
    val tfaLockedUntil: Long? = null,
    val totpLocked: Boolean = false,
) {
    val userId: UserId get() = UserId.parse(id)
    val realm: String get() = userId.realm
    val fullName: String? get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null }

    fun expired(nowSeconds: Long): Boolean = expire != null && expire > 0 && expire <= nowSeconds
    fun tfaLocked(nowSeconds: Long): Boolean = totpLocked || (tfaLockedUntil ?: 0) > nowSeconds

    companion object {
        fun fromJson(o: JsonObject) = PveUser(
            id = o.str("userid").orEmpty(),
            enabled = o.bool("enable", default = true),
            expire = o.long("expire")?.takeIf { it > 0 },
            firstName = o.str("firstname"),
            lastName = o.str("lastname"),
            email = o.str("email"),
            comment = o.str("comment"),
            groups = o.strings("groups"),
            keys = o.str("keys"),
            tokens = o.array("tokens").map(ApiToken::fromJson),
            realmType = o.str("realm-type"),
            tfaLockedUntil = o.long("tfa-locked-until"),
            totpLocked = o.bool("totp-locked"),
        )
    }
}

/** An API token of a user: `user@realm!tokenid`. */
data class ApiToken(
    val id: String,
    val comment: String? = null,
    val expire: Long? = null,
    /** Privilege separation: the token has only the permissions given to it, not the user's. */
    val privsep: Boolean = true,
) {
    companion object {
        val ID = Regex("^[A-Za-z][A-Za-z0-9.\\-_]{0,63}$")

        fun fromJson(o: JsonObject) = ApiToken(
            // Proxmox VE sends the name as `tokenid`; a Backup Server sends `token-name`, and
            // `tokenid` as the full `user@realm!name`.
            id = (o.str("token-name") ?: o.str("tokenid")?.substringAfter('!')).orEmpty(),
            comment = o.str("comment"),
            expire = o.long("expire")?.takeIf { it > 0 },
            privsep = o.bool("privsep", default = true),
        )
    }
}

/**
 * What `POST /access/users/{userid}/token/{tokenid}` returns once: the secret is never shown again.
 * A Backup Server answers `tokenid` where Proxmox VE answers `full-tokenid`.
 */
data class NewApiToken(val fullId: String, val secret: String, val backupServer: Boolean = false) {
    /** The `Authorization` header value: `PVEAPIToken=user@realm!id=secret`, or `PBSAPIToken=…:secret`. */
    val header: String get() = if (backupServer) "PBSAPIToken=$fullId:$secret" else "PVEAPIToken=$fullId=$secret"

    companion object {
        fun fromJson(o: JsonObject, backupServer: Boolean = false) =
            NewApiToken((o.str("full-tokenid") ?: o.str("tokenid")).orEmpty(), o.str("value").orEmpty(), backupServer)
    }
}

data class PveGroup(val id: String, val comment: String? = null, val members: List<String> = emptyList()) {
    companion object {
        val ID = Regex("^[A-Za-z0-9.\\-_]{1,64}$")

        fun fromJson(o: JsonObject) = PveGroup(
            id = o.str("groupid").orEmpty(),
            comment = o.str("comment"),
            members = (o.strings("users") + o.strings("members")).distinct(),
        )
    }
}

data class PveRole(val id: String, val privileges: Set<String> = emptySet(), val builtIn: Boolean = false) {
    companion object {
        val ID = Regex("^[A-Za-z0-9.\\-_]{1,64}$")

        fun fromJson(o: JsonObject) = PveRole(
            id = o.str("roleid").orEmpty(),
            privileges = o.strings("privs").toSet(),
            builtIn = o.bool("special"),
        )
    }
}

/** Who an ACL entry gives a role to. */
enum class AclSubject(val apiName: String, val paramName: String) {
    USER("user", "users"),
    GROUP("group", "groups"),
    TOKEN("token", "tokens"),
    ;

    companion object {
        fun fromApi(type: String?): AclSubject = entries.firstOrNull { it.apiName == type } ?: USER
    }
}

/** One row of `GET /access/acl`. */
data class AclEntry(
    val path: String,
    val subject: AclSubject,
    /** The user, group or token id. */
    val ugid: String,
    val role: String,
    val propagate: Boolean = true,
) {
    /** `PUT /access/acl` with `delete=1` removes exactly this entry. */
    fun removeParams(): Map<String, String> = mapOf(
        "path" to path,
        "roles" to role,
        subject.paramName to ugid,
        "delete" to "1",
    )

    /** The same on a Backup Server: one `role`, and `auth-id` for users and tokens alike. */
    fun pbsRemoveParams(): Map<String, String> = mapOf("path" to path, "role" to role, "auth-id" to ugid, "delete" to "1")

    companion object {
        fun fromJson(o: JsonObject) = AclEntry(
            path = o.str("path") ?: "/",
            // A Backup Server says `ugid_type` (user or group); a token is a user id with `!`.
            subject = if (o.str("ugid").orEmpty().contains('!')) AclSubject.TOKEN else AclSubject.fromApi(o.str("type") ?: o.str("ugid_type")),
            ugid = o.str("ugid").orEmpty(),
            role = o.str("roleid").orEmpty(),
            propagate = o.bool("propagate", default = true),
        )
    }
}

/** Adding a permission. */
data class AclForm(
    val path: String = "/",
    val subject: AclSubject = AclSubject.USER,
    val ugid: String = "",
    val role: String = "",
    val propagate: Boolean = true,
) {
    enum class Problem { PATH, SUBJECT, ROLE }

    fun problem(): Problem? = when {
        !PATH.matches(path.trim()) -> Problem.PATH
        ugid.isBlank() -> Problem.SUBJECT
        role.isBlank() -> Problem.ROLE
        else -> null
    }

    fun params(): Map<String, String> = mapOf(
        "path" to path.trim(),
        "roles" to role,
        subject.paramName to ugid,
        "propagate" to if (propagate) "1" else "0",
    )

    /** `PUT /access/acl` on a Backup Server: users and tokens are both an `auth-id`; there are no groups. */
    fun pbsParams(): Map<String, String> = mapOf(
        "path" to path.trim(),
        "role" to role,
        "auth-id" to ugid,
        "propagate" to if (propagate) "1" else "0",
    )

    companion object {
        /** `/`, `/vms/100`, `/storage/local`, `/pool/prod`, `/nodes/pve1`, `/sdn/zones/z1`, … */
        private val PATH = Regex("^/([A-Za-z0-9._\\-]+(/[A-Za-z0-9._\\-]+)*)?$")

        /** Common paths, to pick from. */
        val SUGGESTED_PATHS = listOf("/", "/vms", "/storage", "/pool", "/nodes", "/sdn", "/access", "/mapping")

        /** The same for a Backup Server. */
        val PBS_PATHS = listOf("/", "/datastore", "/remote", "/system", "/access", "/tape")
    }
}

/** Creating or editing a user. [password] is only sent when creating a `pve` realm user. */
data class UserForm(
    val name: String = "",
    val realm: String = "pve",
    val password: String = "",
    val confirmPassword: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val comment: String = "",
    val groups: Set<String> = emptySet(),
    val enabled: Boolean = true,
    /** Epoch seconds, or null for never. */
    val expire: Long? = null,
    val keys: String = "",
) {
    enum class Problem { NAME, PASSWORD, PASSWORD_MISMATCH, EMAIL }

    /** Only users of the `pve` (or, on a Backup Server, `pbs`) realm have a password Proxmox stores. */
    val hasPassword: Boolean get() = realm == "pve" || realm == "pbs"

    fun problem(creating: Boolean): Problem? = when {
        creating && !UserId.NAME.matches(name.trim()) -> Problem.NAME
        creating && hasPassword && password.length < MIN_PASSWORD -> Problem.PASSWORD
        creating && hasPassword && password != confirmPassword -> Problem.PASSWORD_MISMATCH
        email.isNotBlank() && !EMAIL.matches(email.trim()) -> Problem.EMAIL
        else -> null
    }

    private fun values(): Map<String, String> = buildMap {
        firstName.trim().takeIf { it.isNotEmpty() }?.let { put("firstname", it) }
        lastName.trim().takeIf { it.isNotEmpty() }?.let { put("lastname", it) }
        email.trim().takeIf { it.isNotEmpty() }?.let { put("email", it) }
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        this@UserForm.keys.trim().takeIf { it.isNotEmpty() }?.let { put("keys", it) }
        put("groups", groups.sorted().joinToString(","))
        put("enable", if (enabled) "1" else "0")
        put("expire", (expire ?: 0).toString())
    }

    fun createParams(): Map<String, String> = values() + buildMap {
        put("userid", "${name.trim()}@$realm")
        if (hasPassword) put("password", password)
    }.let { m -> if (groups.isEmpty()) m - "groups" else m }

    /** `PUT /access/users/{userid}`: every value, deleting the cleared ones. */
    fun updateParams(original: PveUser): Map<String, String> {
        val set = LinkedHashMap(values())
        val delete = buildList {
            if (firstName.isBlank() && original.firstName != null) add("firstname")
            if (lastName.isBlank() && original.lastName != null) add("lastname")
            if (email.isBlank() && original.email != null) add("email")
            if (comment.isBlank() && original.comment != null) add("comment")
            if (keys.isBlank() && original.keys != null) add("keys")
        }
        if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
        return set
    }

    /** `POST /access/users` on a Backup Server: no groups or keys. */
    fun pbsCreateParams(): Map<String, String> = createParams() - "groups" - "keys"

    /**
     * `PUT /access/users/{userid}` on a Backup Server: no groups or keys, and the cleared fields
     * as repeated `delete` keys (it doesn't take a comma-separated list).
     */
    fun pbsUpdateFields(original: PveUser): FormFields {
        val params = updateParams(original)
        return params.filterKeys { it != "delete" && it != "groups" && it != "keys" }.toList() +
            params["delete"].orEmpty().split(',').filter { it.isNotEmpty() && it != "keys" }.map { "delete" to it }
    }

    companion object {
        const val MIN_PASSWORD = 8
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+$")

        fun from(u: PveUser) = UserForm(
            name = u.userId.name,
            realm = u.realm,
            firstName = u.firstName.orEmpty(),
            lastName = u.lastName.orEmpty(),
            email = u.email.orEmpty(),
            comment = u.comment.orEmpty(),
            groups = u.groups.toSet(),
            enabled = u.enabled,
            expire = u.expire,
            keys = u.keys.orEmpty(),
        )
    }
}

/** A new API token. */
data class TokenForm(val id: String = "", val comment: String = "", val privsep: Boolean = true, val expire: Long? = null) {
    val valid: Boolean get() = ApiToken.ID.matches(id.trim())

    fun params(): Map<String, String> = buildMap {
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        put("privsep", if (privsep) "1" else "0")
        put("expire", (expire ?: 0).toString())
    }

    /** A Backup Server's tokens always have only their own permissions: no `privsep`. */
    fun pbsParams(): Map<String, String> = params() - "privsep"
}

/** Account and token expiry as a date (`YYYY-MM-DD`), as the Proxmox web UI shows it: the start of that day. */
object ExpiryDate {
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
        runCatching { LocalDate.parse(text.trim()).atStartOfDay(zone).toEpochSecond() }.getOrNull()

    fun format(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate().toString()
}

/** Privileges grouped by what they are about (`VM.`, `Datastore.`, `Sys.`, …), for long pick lists. */
fun privilegeGroups(privileges: Collection<String>): List<Pair<String, List<String>>> =
    privileges.distinct().groupBy { it.substringBefore('.') }.toSortedMap().map { (group, list) -> group to list.sorted() }

/** Realm types the app can add (`pam` and `pve` exist once and can't be added). */
enum class RealmType(val apiName: String) {
    LDAP("ldap"),
    AD("ad"),
    OPENID("openid"),
    ;

    companion object {
        fun fromApi(type: String?): RealmType? = entries.firstOrNull { it.apiName == type }
    }
}

/**
 * Adding or editing a realm (Datacenter → Permissions → Realms): the settings most setups need.
 * Options this form doesn't show are left as they are when editing.
 */
data class RealmForm(
    val type: RealmType = RealmType.LDAP,
    val id: String = "",
    val comment: String = "",
    val isDefault: Boolean = false,
    // LDAP and AD
    val server1: String = "",
    val server2: String = "",
    val port: String = "",
    /** `ldap`, `ldaps` or `ldap+starttls`. */
    val mode: String = "ldap",
    val verify: Boolean = true,
    // LDAP
    val baseDn: String = "",
    val userAttr: String = "uid",
    val bindDn: String = "",
    val bindPassword: String = "",
    // AD
    val domain: String = "",
    // OpenID Connect
    val issuerUrl: String = "",
    val clientId: String = "",
    val clientKey: String = "",
    val usernameClaim: String = "",
    val autocreate: Boolean = false,
    val scopes: String = "",
) {
    enum class Problem { ID, SERVER, PORT, BASE_DN, DOMAIN, ISSUER, CLIENT }

    fun problem(creating: Boolean): Problem? = when {
        creating && !ID.matches(id.trim()) -> Problem.ID
        type != RealmType.OPENID && server1.isBlank() -> Problem.SERVER
        port.isNotBlank() && (port.trim().toIntOrNull() ?: 0) !in 1..65535 -> Problem.PORT
        type == RealmType.LDAP && baseDn.isBlank() -> Problem.BASE_DN
        type == RealmType.AD && domain.isBlank() -> Problem.DOMAIN
        type == RealmType.OPENID && !issuerUrl.trim().startsWith("https://") && !issuerUrl.trim().startsWith("http://") -> Problem.ISSUER
        type == RealmType.OPENID && clientId.isBlank() -> Problem.CLIENT
        else -> null
    }

    private fun values(): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        put("default", if (isDefault) "1" else "0")
        when (type) {
            RealmType.LDAP, RealmType.AD -> {
                put("server1", server1.trim())
                server2.trim().takeIf { it.isNotEmpty() }?.let { put("server2", it) }
                port.trim().takeIf { it.isNotEmpty() }?.let { put("port", it) }
                put("mode", mode)
                if (mode != "ldap") put("verify", if (verify) "1" else "0")
                if (type == RealmType.LDAP) {
                    put("base_dn", baseDn.trim())
                    put("user_attr", userAttr.trim().ifEmpty { "uid" })
                    bindDn.trim().takeIf { it.isNotEmpty() }?.let { put("bind_dn", it) }
                } else {
                    put("domain", domain.trim())
                }
                bindPassword.takeIf { it.isNotEmpty() }?.let { put("password", it) }
            }
            RealmType.OPENID -> {
                put("issuer-url", issuerUrl.trim())
                put("client-id", clientId.trim())
                clientKey.takeIf { it.isNotEmpty() }?.let { put("client-key", it) }
                usernameClaim.trim().takeIf { it.isNotEmpty() }?.let { put("username-claim", it) }
                put("autocreate", if (autocreate) "1" else "0")
                scopes.trim().takeIf { it.isNotEmpty() }?.let { put("scopes", it) }
            }
        }
    }

    fun createParams(): Map<String, String> = values() + mapOf("realm" to id.trim(), "type" to type.apiName)

    /** Proxmox refuses to change a realm's username claim after it was created. */
    fun updateParams(): Map<String, String> = values().apply { remove("username-claim") }

    companion object {
        val ID = Regex("^[A-Za-z][A-Za-z0-9.\\-_]{1,31}$")
        val MODES = listOf("ldap", "ldaps", "ldap+starttls")

        fun from(id: String, o: JsonObject): RealmForm {
            val type = RealmType.fromApi(o.str("type")) ?: RealmType.LDAP
            return RealmForm(
                type = type,
                id = id,
                comment = o.str("comment").orEmpty(),
                isDefault = o.bool("default"),
                server1 = o.str("server1").orEmpty(),
                server2 = o.str("server2").orEmpty(),
                port = o.str("port").orEmpty(),
                mode = o.str("mode") ?: if (o.bool("secure")) "ldaps" else "ldap",
                verify = o.bool("verify", default = true),
                baseDn = o.str("base_dn").orEmpty(),
                userAttr = o.str("user_attr") ?: "uid",
                bindDn = o.str("bind_dn").orEmpty(),
                domain = o.str("domain").orEmpty(),
                issuerUrl = o.str("issuer-url").orEmpty(),
                clientId = o.str("client-id").orEmpty(),
                usernameClaim = o.str("username-claim").orEmpty(),
                autocreate = o.bool("autocreate"),
                scopes = o.str("scopes").orEmpty(),
            )
        }
    }
}

/** `POST /access/domains/{realm}/sync`. */
data class RealmSync(
    /** `users`, `groups` or `both`. */
    val scope: String = "both",
    val enableNew: Boolean = true,
    /** What to remove for users and groups that vanished from the directory: `acl`, `entry`, `properties`. */
    val removeVanished: Set<String> = emptySet(),
    val dryRun: Boolean = true,
) {
    fun params(): Map<String, String> = buildMap {
        put("scope", scope)
        put("enable-new", if (enableNew) "1" else "0")
        put("remove-vanished", removeVanished.sorted().joinToString(";").ifEmpty { "none" })
        put("dry-run", if (dryRun) "1" else "0")
    }

    companion object {
        val SCOPES = listOf("users", "groups", "both")
        val VANISHED = listOf("acl", "entry", "properties")
    }
}

/** A second factor of a user (`GET /access/tfa`). */
data class TfaEntry(
    val id: String,
    /** `totp`, `webauthn`, `recovery`, `yubico`, `u2f`. */
    val type: String,
    val description: String? = null,
    val created: Long? = null,
    val enabled: Boolean = true,
) {
    companion object {
        fun fromJson(o: JsonObject) = TfaEntry(
            id = o.str("id").orEmpty(),
            type = o.str("type").orEmpty(),
            description = o.str("description"),
            created = o.long("created"),
            enabled = o.bool("enable", default = true),
        )
    }
}

data class TfaUser(val userId: String, val entries: List<TfaEntry>) {
    companion object {
        fun fromJson(o: JsonObject) = TfaUser(o.str("userid").orEmpty(), o.array("entries").map(TfaEntry::fromJson))
    }
}

/**
 * Setting up a TOTP second factor: a random secret, the `otpauth://` link an authenticator app
 * opens or scans, and the first code that proves the app has it.
 */
data class TotpSetup(val userId: String, val secret: String, val issuer: String = "Proxmox") {
    /** What `POST /access/tfa/{userid}` takes as `totp`. */
    val uri: String
        get() = "otpauth://totp/${encode(issuer)}:${encode(userId)}?secret=$secret&issuer=${encode(issuer)}&algorithm=SHA1&digits=6&period=30"

    companion object {
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        fun random(userId: String, random: SecureRandom = SecureRandom()): TotpSetup {
            val bytes = ByteArray(20).also(random::nextBytes)
            return TotpSetup(userId, base32(bytes))
        }

        /** RFC 4648 Base32 without padding. */
        fun base32(bytes: ByteArray): String = buildString {
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    append(BASE32[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) append(BASE32[(buffer shl (5 - bits)) and 31])
        }

        private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** `GET /access/permissions`: path → privilege → propagates. */
data class EffectivePermissions(val paths: Map<String, Map<String, Boolean>>) {
    companion object {
        fun fromJson(o: JsonObject): EffectivePermissions = EffectivePermissions(
            o.entries.associate { (path, v) ->
                path to ((v as? JsonObject)?.entries?.associate { (priv, flag) ->
                    // Whether it propagates: 1/0 on Proxmox VE, true/false on a Backup Server.
                    priv to (flag.toString().trim('"') in setOf("1", "true"))
                }.orEmpty())
            },
        )
    }
}
