package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.AclEntry
import io.github.lucabarbalata.anprox.model.pve.AclForm
import io.github.lucabarbalata.anprox.model.pve.AclSubject
import io.github.lucabarbalata.anprox.model.pve.EffectivePermissions
import io.github.lucabarbalata.anprox.model.pve.ExpiryDate
import io.github.lucabarbalata.anprox.model.pve.PveUser
import io.github.lucabarbalata.anprox.model.pve.RealmForm
import io.github.lucabarbalata.anprox.model.pve.RealmSync
import io.github.lucabarbalata.anprox.model.pve.RealmSyncJob
import io.github.lucabarbalata.anprox.model.pve.RealmSyncJobForm
import io.github.lucabarbalata.anprox.model.pve.RealmType
import io.github.lucabarbalata.anprox.model.pve.TotpSetup
import io.github.lucabarbalata.anprox.model.pve.UserForm
import io.github.lucabarbalata.anprox.model.pve.privilegeGroups
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessModelTest {
    private fun json(text: String): JsonObject = ProxmoxJson.parseToJsonElement(text).jsonObject

    @Test
    fun `base32 follows RFC 4648`() {
        assertEquals("", TotpSetup.base32(ByteArray(0)))
        assertEquals("MY", TotpSetup.base32("f".toByteArray()))
        assertEquals("MZXW6YTBOI", TotpSetup.base32("foobar".toByteArray()))
        val setup = TotpSetup("root@pam", "MZXW6YTBOI")
        assertEquals("otpauth://totp/Proxmox:root%40pam?secret=MZXW6YTBOI&issuer=Proxmox&algorithm=SHA1&digits=6&period=30", setup.uri)
        assertEquals(32, TotpSetup.random("a@pve").secret.length)
    }

    @Test
    fun `users read groups as text or list, and know when they expire`() {
        val u = PveUser.fromJson(json("""{"userid":"bob@pve","enable":0,"expire":100,"groups":"ops,admins","firstname":"Bob","tokens":[{"tokenid":"ci","privsep":"0"}]}"""))
        assertEquals(listOf("ops", "admins"), u.groups)
        assertFalse(u.enabled)
        assertTrue(u.expired(nowSeconds = 200))
        assertEquals("Bob", u.fullName)
        assertEquals("pve", u.realm)
        assertFalse(u.tokens.single().privsep)
        assertEquals(listOf("a", "b"), PveUser.fromJson(json("""{"userid":"x@pve","groups":["a","b"]}""")).groups)
    }

    @Test
    fun `user form creates and updates`() {
        val form = UserForm(name = "bob", realm = "pve", password = "secret123", confirmPassword = "secret123", email = "bob@example.com", groups = setOf("ops"))
        assertNull(form.problem(creating = true))
        assertEquals(UserForm.Problem.PASSWORD_MISMATCH, form.copy(confirmPassword = "x").problem(creating = true))
        assertEquals(UserForm.Problem.NAME, form.copy(name = "bo b").problem(creating = true))
        assertEquals(UserForm.Problem.EMAIL, form.copy(email = "nope").problem(creating = true))
        // PAM users have no password Proxmox stores.
        assertNull(form.copy(realm = "pam", password = "").problem(creating = true))
        val params = form.createParams()
        assertEquals("bob@pve", params["userid"])
        assertEquals("secret123", params["password"])
        assertEquals("ops", params["groups"])
        val original = PveUser("bob@pve", email = "bob@example.com", comment = "old")
        val update = UserForm.from(original).copy(email = "", comment = "").updateParams(original)
        assertEquals("email,comment", update["delete"])
        assertNull(update["password"])
    }

    @Test
    fun `acl entries and forms`() {
        val e = AclEntry.fromJson(json("""{"path":"/vms/100","type":"token","ugid":"root@pam!ci","roleid":"PVEVMAdmin","propagate":1}"""))
        assertEquals(AclSubject.TOKEN, e.subject)
        assertEquals(mapOf("path" to "/vms/100", "roles" to "PVEVMAdmin", "tokens" to "root@pam!ci", "delete" to "1"), e.removeParams())
        val form = AclForm(path = "/storage/local", subject = AclSubject.GROUP, ugid = "ops", role = "PVEDatastoreUser", propagate = false)
        assertNull(form.problem())
        assertEquals("ops", form.params()["groups"])
        assertEquals("0", form.params()["propagate"])
        assertEquals(AclForm.Problem.PATH, form.copy(path = "vms").problem())
        assertEquals(AclForm.Problem.PATH, form.copy(path = "/vms/").problem())
        assertNull(form.copy(path = "/").problem())
    }

    @Test
    fun `realm forms by type`() {
        val ldap = RealmForm(RealmType.LDAP, id = "lab", server1 = "ldap.lab", baseDn = "dc=lab", mode = "ldaps", bindDn = "cn=admin,dc=lab", bindPassword = "pw")
        assertNull(ldap.problem(creating = true))
        val p = ldap.createParams()
        assertEquals("ldap", p["type"])
        assertEquals("1", p["verify"])
        assertEquals("pw", p["password"])
        assertEquals(RealmForm.Problem.BASE_DN, ldap.copy(baseDn = "").problem(creating = true))
        val ad = RealmForm(RealmType.AD, id = "corp", server1 = "dc1", domain = "corp.example")
        assertEquals("corp.example", ad.createParams()["domain"])
        assertNull(ad.createParams()["verify"]) // plain LDAP has nothing to verify
        val oidc = RealmForm(RealmType.OPENID, id = "sso", issuerUrl = "https://auth.example.com", clientId = "pve", usernameClaim = "email")
        assertNull(oidc.problem(creating = true))
        assertNull(oidc.updateParams()["username-claim"])
        val read = RealmForm.from("corp", json("""{"type":"ad","server1":"dc1","domain":"corp.example","secure":1}"""))
        assertEquals("ldaps", read.mode)
        assertEquals(mapOf("scope" to "users", "enable-new" to "1", "remove-vanished" to "acl;entry", "dry-run" to "0"),
            RealmSync(scope = "users", removeVanished = setOf("entry", "acl"), dryRun = false).params())
    }

    @Test
    fun `scheduled realm sync jobs`() {
        val form = RealmSyncJobForm(id = "nightly", realm = "corp", sync = RealmSync(scope = "users", enableNew = false, removeVanished = setOf("entry", "acl"), dryRun = false))
        assertNull(form.problem())
        assertEquals(
            mapOf("realm" to "corp", "schedule" to "02:00", "enabled" to "1", "scope" to "users", "enable-new" to "0", "remove-vanished" to "acl;entry"),
            form.params(create = true),
        )
        // A change can't move the job to another realm, and an emptied comment is deleted.
        val change = form.copy(enabled = false).params(create = false)
        assertNull(change["realm"])
        assertEquals("0", change["enabled"])
        assertEquals("comment", change["delete"])
        assertEquals(RealmSyncJobForm.Problem.ID, form.copy(id = "x").problem())

        val job = RealmSyncJob.fromJson(json("""{"id":"nightly","realm":"corp","schedule":"02:00","enabled":0,"scope":"both","remove-vanished":"none","next-run":1800000000}"""))
        assertEquals(false, job.enabled)
        assertTrue(job.sync.removeVanished.isEmpty())
        assertEquals(1800000000L, job.nextRun)
        assertEquals(form.copy(sync = form.sync.copy(scope = "both", enableNew = true, removeVanished = emptySet()), enabled = false), RealmSyncJobForm.from(job))
    }

    @Test
    fun `effective permissions`() {
        val p = EffectivePermissions.fromJson(json("""{"/":{"Sys.Audit":1},"/vms":{"VM.PowerMgmt":0}}"""))
        assertEquals(true, p.paths["/"]?.get("Sys.Audit"))
        assertEquals(false, p.paths["/vms"]?.get("VM.PowerMgmt"))
        // A Backup Server answers with booleans.
        val pbs = EffectivePermissions.fromJson(json("""{"/datastore/store1":{"Datastore.Read":true,"Datastore.Audit":false}}"""))
        assertEquals(true, pbs.paths["/datastore/store1"]?.get("Datastore.Read"))
        assertEquals(false, pbs.paths["/datastore/store1"]?.get("Datastore.Audit"))
    }

    @Test
    fun `expiry dates and privilege groups`() {
        assertEquals(1798675200L, ExpiryDate.parse("2026-12-31", ZoneOffset.UTC))
        assertEquals("2026-12-31", ExpiryDate.format(1798675200L, ZoneOffset.UTC))
        assertNull(ExpiryDate.parse("31.12.2026", ZoneOffset.UTC))
        assertNull(ExpiryDate.parse("2026-02-30", ZoneOffset.UTC))
        val groups = privilegeGroups(listOf("VM.PowerMgmt", "Sys.Audit", "VM.Audit", "Datastore.Audit", "VM.Audit"))
        assertEquals(listOf("Datastore", "Sys", "VM"), groups.map { it.first })
        assertEquals(listOf("VM.Audit", "VM.PowerMgmt"), groups.last().second)
    }
}
