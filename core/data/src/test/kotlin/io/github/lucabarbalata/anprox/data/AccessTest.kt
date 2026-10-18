package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.mock.MockTotp
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.AclForm
import io.github.lucabarbalata.anprox.model.pve.AclSubject
import io.github.lucabarbalata.anprox.model.pve.RealmForm
import io.github.lucabarbalata.anprox.model.pve.RealmSync
import io.github.lucabarbalata.anprox.model.pve.RealmType
import io.github.lucabarbalata.anprox.model.pve.TokenForm
import io.github.lucabarbalata.anprox.model.pve.TotpSetup
import io.github.lucabarbalata.anprox.model.pve.UserForm
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Access control (M7) against the mock. */
class AccessTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hub: ServerHub

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private fun profile(id: String, user: String, realm: String) = ServerProfile(
        id = id, name = id, product = ProxmoxProduct.PVE,
        endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
    )

    private suspend fun repo(): AccessRepository {
        hub = ServerHub(InMemoryProfileStore(listOf(profile("pve", "root", "pam"), profile("bob", "bob", "pve"))), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return AccessRepository(hub)
    }

    @Test
    fun `users, a new one, its password and its first sign-in`() = runBlocking {
        val repo = repo()
        val users = repo.users("pve")
        assertTrue(users.any { it.id == "root@pam" && it.tokens.single().id == "automation" })
        assertTrue(users.single { it.id == "intern@pve" }.let { !it.enabled && it.expired(System.currentTimeMillis() / 1000) })
        repo.createUser("pve", UserForm(name = "bob", password = "first-pass", confirmPassword = "first-pass", groups = setOf("ops"), email = "bob@example.com"))
        val bob = repo.users("pve").single { it.id == "bob@pve" }
        assertEquals(listOf("ops"), bob.groups)
        // Unknown group: refused by Proxmox.
        assertFailsWith<ApiException> { repo.createUser("pve", UserForm(name = "eve", password = "12345678", confirmPassword = "12345678", groups = setOf("nope"))) }
        repo.updateUser("pve", bob, UserForm.from(bob).copy(email = "", comment = "Night shift"))
        val updated = repo.users("pve").single { it.id == "bob@pve" }
        assertEquals("Night shift", updated.comment)
        assertEquals(null, updated.email)
        repo.changePassword("pve", "bob@pve", "second-pass", yourPassword = null)
        assertIs<LoginResult.Success>(hub.login("bob", "second-pass", remember = false))
        repo.deleteUser("pve", "bob@pve")
        assertFalse(repo.users("pve").any { it.id == "bob@pve" })
        assertFailsWith<ApiException> { repo.deleteUser("pve", "root@pam") }
        Unit
    }

    @Test
    fun `api tokens show their secret once`() = runBlocking {
        val repo = repo()
        val token = repo.createToken("pve", "root@pam", TokenForm(id = "ci", comment = "GitHub Actions", privsep = true))
        assertEquals("root@pam!ci", token.fullId)
        assertTrue(token.header.startsWith("PVEAPIToken=root@pam!ci="))
        assertEquals(listOf("automation", "ci"), repo.tokens("pve", "root@pam").map { it.id })
        assertFailsWith<ApiException> { repo.createToken("pve", "root@pam", TokenForm(id = "ci")) }
        repo.deleteToken("pve", "root@pam", "ci")
        assertEquals(listOf("automation"), repo.tokens("pve", "root@pam").map { it.id })
        Unit
    }

    @Test
    fun `groups, roles and the acl`() = runBlocking {
        val repo = repo()
        repo.createGroup("pve", "night", "Night shift")
        assertEquals("Night shift", repo.groups("pve").single { it.id == "night" }.comment)
        assertEquals(listOf("alice@corp"), repo.groups("pve").single { it.id == "ops" }.members)
        val roles = repo.roles("pve")
        assertEquals("Operator", roles.first().id) // custom roles first
        assertTrue("VM.PowerMgmt" in repo.privileges(roles))
        repo.createRole("pve", "Backupper", setOf("VM.Backup", "Datastore.AllocateSpace"))
        repo.updateRole("pve", "Backupper", setOf("VM.Backup"))
        assertEquals(setOf("VM.Backup"), repo.roles("pve").single { it.id == "Backupper" }.privileges)
        assertFailsWith<ApiException> { repo.deleteRole("pve", "Administrator") }
        val form = AclForm(path = "/storage/local", subject = AclSubject.GROUP, ugid = "night", role = "Backupper")
        repo.addAcl("pve", form)
        val entry = repo.acl("pve").single { it.ugid == "night" }
        assertEquals("/storage/local", entry.path)
        assertTrue("VM.Backup" in repo.permissions("pve", "admin@pve").paths["/"].orEmpty())
        repo.removeAcl("pve", entry)
        assertFalse(repo.acl("pve").any { it.ugid == "night" })
        assertFailsWith<ApiException> { repo.addAcl("pve", form.copy(role = "NoSuchRole")) }
        repo.deleteGroup("pve", "night")
        repo.deleteRole("pve", "Backupper")
        Unit
    }

    @Test
    fun `realms, and a directory sync with a dry run first`() = runBlocking {
        val repo = repo()
        assertEquals(listOf("corp", "pam", "pve", "sso"), repo.realms("pve").map { it.realm })
        val corp = repo.realmForm("pve", "corp")
        assertEquals(RealmType.AD, corp.type)
        assertEquals("corp.example", corp.domain)
        repo.updateRealm("pve", corp.copy(comment = "AD (new DCs)", server2 = "dc2.corp.example"))
        assertEquals("dc2.corp.example", repo.realmForm("pve", "corp").server2)
        repo.createRealm("pve", RealmForm(RealmType.LDAP, id = "lab", server1 = "ldap.lab", baseDn = "dc=lab,dc=example"))
        assertEquals("dc=lab,dc=example", repo.realmForm("pve", "lab").baseDn)
        assertFailsWith<ApiException> { repo.deleteRealm("pve", "pam") }
        repo.deleteRealm("pve", "lab")

        val guests = GuestRepository(hub)
        val dry = repo.syncRealm("pve", "corp", RealmSync(dryRun = true))
        guests.awaitTask("pve", "pve1", dry)
        assertTrue(guests.taskLog("pve", "pve1", dry).any { it.t == "adding user 'bob@corp'" })
        assertFalse(repo.users("pve").any { it.id == "bob@corp" })
        guests.awaitTask("pve", "pve1", repo.syncRealm("pve", "corp", RealmSync(dryRun = false)))
        assertTrue(repo.users("pve").any { it.id == "bob@corp" })
        assertTrue(repo.groups("pve").any { it.id == "devs-corp" })
        Unit
    }

    @Test
    fun `second factors - totp checked like Proxmox does, recovery keys, enable and delete`() = runBlocking {
        val repo = repo()
        assertEquals(listOf("totp", "recovery"), repo.tfa("pve").single { it.userId == "admin@pve" }.entries.map { it.type })
        val setup = TotpSetup.random("root@pam")
        assertFailsWith<ApiException> { repo.addTotp("pve", setup, "Phone", "000000", yourPassword = null) }
        val code = MockTotp.code(setup.secret, System.currentTimeMillis() / 1000)
        val id = repo.addTotp("pve", setup, "Phone", code, yourPassword = null)
        assertEquals(10, repo.addRecoveryKeys("pve", "root@pam", yourPassword = null).size)
        repo.setTfaEnabled("pve", "root@pam", id, enabled = false, yourPassword = null)
        assertFalse(repo.tfa("pve").single { it.userId == "root@pam" }.entries.single { it.id == id }.enabled)
        repo.deleteTfa("pve", "root@pam", id, yourPassword = null)
        assertEquals(listOf("recovery"), repo.tfa("pve").single { it.userId == "root@pam" }.entries.map { it.type })
        repo.unlockTfa("pve", "admin@pve")
        Unit
    }
}
