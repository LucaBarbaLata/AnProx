package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.json.objects
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.json.strings
import io.github.lucabarbalata.anprox.model.pve.AclEntry
import io.github.lucabarbalata.anprox.model.pve.AclForm
import io.github.lucabarbalata.anprox.model.pve.ApiToken
import io.github.lucabarbalata.anprox.model.pve.EffectivePermissions
import io.github.lucabarbalata.anprox.model.pve.NewApiToken
import io.github.lucabarbalata.anprox.model.pve.PveGroup
import io.github.lucabarbalata.anprox.model.pve.PveRole
import io.github.lucabarbalata.anprox.model.pve.PveUser
import io.github.lucabarbalata.anprox.model.pve.RealmForm
import io.github.lucabarbalata.anprox.model.pve.RealmSync
import io.github.lucabarbalata.anprox.model.pve.TfaUser
import io.github.lucabarbalata.anprox.model.pve.TokenForm
import io.github.lucabarbalata.anprox.model.pve.TotpSetup
import io.github.lucabarbalata.anprox.model.pve.UserForm

/**
 * Datacenter → Permissions (questionnaire Q23, milestone M7): users and their API tokens, groups,
 * roles, the ACL, realms (with directory sync) and second factors.
 */
class AccessRepository(private val hub: ServerHub) {

    // ---- users

    suspend fun users(serverId: String): List<PveUser> =
        hub.use(serverId) { it.pve { users() } }.objects().map(PveUser::fromJson).sortedWith(compareBy({ it.realm }, { it.id }))

    suspend fun createUser(serverId: String, form: UserForm) {
        hub.use(serverId) { it.pveNoData { createUser(form.createParams()) } }
    }

    suspend fun updateUser(serverId: String, user: PveUser, form: UserForm) {
        hub.use(serverId) { it.pveNoData { updateUser(user.id, form.updateParams(user)) } }
    }

    suspend fun deleteUser(serverId: String, userId: String) {
        hub.use(serverId) { it.pveNoData { deleteUser(userId) } }
    }

    /**
     * Sets [userId]'s password. Proxmox VE 8.1 and later want the password of the user making the
     * change ([yourPassword]) too, unless that's root.
     */
    suspend fun changePassword(serverId: String, userId: String, password: String, yourPassword: String?) {
        val params = buildMap {
            put("userid", userId)
            put("password", password)
            yourPassword?.takeIf { it.isNotEmpty() }?.let { put("confirmation-password", it) }
        }
        hub.use(serverId) { it.pveNoData { changePassword(params) } }
    }

    suspend fun unlockTfa(serverId: String, userId: String) {
        hub.use(serverId) { it.pveNoData { unlockTfa(userId) } }
    }

    // ---- API tokens

    suspend fun tokens(serverId: String, userId: String): List<ApiToken> =
        hub.use(serverId) { it.pve { tokens(userId) } }.objects().map(ApiToken::fromJson).sortedBy { it.id }

    /** The secret is in the answer only; Proxmox can't show it again. */
    suspend fun createToken(serverId: String, userId: String, form: TokenForm): NewApiToken =
        NewApiToken.fromJson(hub.use(serverId) { it.pve { createToken(userId, form.id.trim(), form.params()) } })

    suspend fun deleteToken(serverId: String, userId: String, tokenId: String) {
        hub.use(serverId) { it.pveNoData { deleteToken(userId, tokenId) } }
    }

    // ---- groups

    suspend fun groups(serverId: String): List<PveGroup> =
        hub.use(serverId) { it.pve { groups() } }.objects().map(PveGroup::fromJson).sortedBy { it.id.lowercase() }

    suspend fun createGroup(serverId: String, id: String, comment: String) {
        val params = buildMap {
            put("groupid", id.trim())
            comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        }
        hub.use(serverId) { it.pveNoData { createGroup(params) } }
    }

    suspend fun setGroupComment(serverId: String, id: String, comment: String) {
        hub.use(serverId) { it.pveNoData { updateGroup(id, mapOf("comment" to comment.trim())) } }
    }

    suspend fun deleteGroup(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteGroup(id) } }
    }

    // ---- roles

    suspend fun roles(serverId: String): List<PveRole> =
        hub.use(serverId) { it.pve { roles() } }.objects().map(PveRole::fromJson)
            .sortedWith(compareBy({ it.builtIn }, { it.id.lowercase() }))

    /** Every privilege the server knows: the built-in Administrator role has them all. */
    fun privileges(roles: List<PveRole>): List<String> =
        (roles.firstOrNull { it.id == "Administrator" }?.privileges ?: roles.flatMap { it.privileges }).toSortedSet().toList()

    suspend fun createRole(serverId: String, id: String, privileges: Set<String>) {
        hub.use(serverId) { it.pveNoData { createRole(mapOf("roleid" to id.trim(), "privs" to privileges.sorted().joinToString(","))) } }
    }

    suspend fun updateRole(serverId: String, id: String, privileges: Set<String>) {
        hub.use(serverId) { it.pveNoData { updateRole(id, mapOf("privs" to privileges.sorted().joinToString(","))) } }
    }

    suspend fun deleteRole(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteRole(id) } }
    }

    // ---- ACL

    suspend fun acl(serverId: String): List<AclEntry> =
        hub.use(serverId) { it.pve { acl() } }.objects().map(AclEntry::fromJson)
            .sortedWith(compareBy({ it.path }, { it.subject }, { it.ugid }, { it.role }))

    suspend fun addAcl(serverId: String, form: AclForm) {
        hub.use(serverId) { it.pveNoData { updateAcl(form.params()) } }
    }

    suspend fun removeAcl(serverId: String, entry: AclEntry) {
        hub.use(serverId) { it.pveNoData { updateAcl(entry.removeParams()) } }
    }

    // ---- realms

    suspend fun realms(serverId: String): List<Realm> = hub.use(serverId) { it.realms() }.sortedBy { it.realm }

    suspend fun realmForm(serverId: String, realm: String): RealmForm = RealmForm.from(realm, hub.use(serverId) { it.pve { realmConfig(realm) } })

    suspend fun createRealm(serverId: String, form: RealmForm) {
        hub.use(serverId) { it.pveNoData { createRealm(form.createParams()) } }
    }

    suspend fun updateRealm(serverId: String, form: RealmForm) {
        hub.use(serverId) { it.pveNoData { updateRealm(form.id, form.updateParams()) } }
    }

    suspend fun deleteRealm(serverId: String, realm: String) {
        hub.use(serverId) { it.pveNoData { deleteRealm(realm) } }
    }

    /** Returns the UPID of the sync task; its log lists what was (or, in a dry run, would be) changed. */
    suspend fun syncRealm(serverId: String, realm: String, sync: RealmSync): String =
        hub.use(serverId) { it.pve { syncRealm(realm, sync.params()) } }

    // ---- second factors

    suspend fun tfa(serverId: String): List<TfaUser> =
        hub.use(serverId) { it.pve { tfa() } }.objects().map(TfaUser::fromJson).sortedBy { it.userId }

    /** Adds a TOTP factor; [code] is the authenticator app's current code for [setup]. */
    suspend fun addTotp(serverId: String, setup: TotpSetup, description: String, code: String, yourPassword: String?): String {
        val params = buildMap {
            put("type", "totp")
            put("description", description.trim().ifEmpty { "AnProx" })
            put("totp", setup.uri)
            put("value", code.trim())
            yourPassword?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
        }
        return hub.use(serverId) { it.pve { addTfa(setup.userId, params) } }.str("id").orEmpty()
    }

    /** Creates recovery keys (replacing earlier ones); they are only shown in this answer. */
    suspend fun addRecoveryKeys(serverId: String, userId: String, yourPassword: String?): List<String> {
        val params = buildMap {
            put("type", "recovery")
            yourPassword?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
        }
        return hub.use(serverId) { it.pve { addTfa(userId, params) } }.strings("recovery")
    }

    suspend fun setTfaEnabled(serverId: String, userId: String, id: String, enabled: Boolean, yourPassword: String?) {
        val params = buildMap {
            put("enable", if (enabled) "1" else "0")
            yourPassword?.takeIf { it.isNotEmpty() }?.let { put("password", it) }
        }
        hub.use(serverId) { it.pveNoData { updateTfa(userId, id, params) } }
    }

    suspend fun deleteTfa(serverId: String, userId: String, id: String, yourPassword: String?) {
        hub.use(serverId) { it.pveNoData { deleteTfa(userId, id, yourPassword?.takeIf { p -> p.isNotEmpty() }) } }
    }

    // ---- effective permissions

    suspend fun permissions(serverId: String, userId: String?, path: String? = null): EffectivePermissions =
        EffectivePermissions.fromJson(hub.use(serverId) { it.pve { permissions(userId, path) } })

    /** The signed-in user, to tell whether a change needs their own password. */
    fun currentUser(serverId: String): String? = hub.server(serverId)?.profile?.let { "${it.username}@${it.realm}" }
}
