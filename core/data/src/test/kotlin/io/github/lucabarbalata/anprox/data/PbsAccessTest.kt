package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.AclForm
import io.github.lucabarbalata.anprox.model.pve.AclSubject
import io.github.lucabarbalata.anprox.model.pve.TokenForm
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Backup Server's users, API tokens, roles and ACL through the same repository as Proxmox VE's. */
class PbsAccessTest {
    private val pbs = MockProxmoxServer(ProxmoxProduct.PBS).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pbs.stop()
    }

    private fun profile(id: String = "pbs", user: String = "root", realm: String = "pam") = ServerProfile(
        id = id, name = "Backups", product = ProxmoxProduct.PBS,
        endpoints = listOf(Endpoint(pbs.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
    )

    private suspend fun hub(vararg profiles: ServerProfile = arrayOf(profile())): ServerHub {
        val hub = ServerHub(InMemoryProfileStore(profiles.toList()), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        return hub
    }

    @Test
    fun `users, a password and API tokens`() = runBlocking {
        val hub = hub()
        hub.login("pbs", "anprox", remember = false)
        val repo = AccessRepository(hub)
        assertTrue(repo.isBackupServer("pbs"))
        assertEquals(listOf("root@pam", "backup@pbs"), repo.users("pbs").map { it.id })
        // A Backup Server has no groups; the call doesn't even go out.
        assertTrue(repo.groups("pbs").isEmpty())

        // No groups or keys are sent (the server refuses parameters it doesn't know).
        repo.createUser("pbs", UserForm(name = "ops", realm = "pbs", password = "ops-pass-1", confirmPassword = "ops-pass-1", email = "ops@example.com", comment = "On call"))
        var ops = repo.users("pbs").single { it.id == "ops@pbs" }
        assertEquals("ops@example.com", ops.email)
        // Clearing two fields sends two `delete` keys.
        repo.updateUser("pbs", ops, UserForm.from(ops).copy(email = "", comment = "", firstName = "Ops"))
        ops = repo.users("pbs").single { it.id == "ops@pbs" }
        assertNull(ops.email)
        assertNull(ops.comment)
        assertEquals("Ops", ops.firstName)

        // The new user signs in with that password; then it is changed.
        val other = hub(profile("ops", user = "ops", realm = "pbs"))
        assertIs<LoginResult.Success>(other.login("ops", "ops-pass-1", remember = false))
        repo.changePassword("pbs", "ops@pbs", "ops-pass-2", yourPassword = null)

        // Tokens: the server answers `tokenid`, and the header is PBS's.
        val token = repo.createToken("pbs", "ops@pbs", TokenForm(id = "ci", comment = "CI"))
        assertEquals("ops@pbs!ci", token.fullId)
        assertTrue(token.header.startsWith("PBSAPIToken=ops@pbs!ci:"))
        assertEquals(listOf("ci"), repo.tokens("pbs", "ops@pbs").map { it.id })
        assertEquals(listOf("ci"), repo.users("pbs").single { it.id == "ops@pbs" }.tokens.map { it.id })
        repo.deleteToken("pbs", "ops@pbs", "ci")
        assertTrue(repo.tokens("pbs", "ops@pbs").isEmpty())

        repo.deleteUser("pbs", "ops@pbs")
        assertTrue(repo.users("pbs").none { it.id == "ops@pbs" })
    }

    @Test
    fun `roles are built in, the ACL takes an auth-id`() = runBlocking {
        val hub = hub()
        hub.login("pbs", "anprox", remember = false)
        val repo = AccessRepository(hub)
        val roles = repo.roles("pbs")
        assertTrue(roles.all { it.builtIn })
        assertTrue("Datastore.Backup" in repo.privileges(roles))

        val seeded = repo.acl("pbs").single()
        assertEquals("/datastore/store1", seeded.path)
        assertEquals(AclSubject.USER, seeded.subject)
        assertEquals("DatastoreBackup", seeded.role)
        assertEquals(setOf("Datastore.Backup"), repo.permissions("pbs", "backup@pbs").paths["/datastore/store1"]?.keys)

        repo.createToken("pbs", "backup@pbs", TokenForm(id = "pve"))
        repo.addAcl("pbs", AclForm("/datastore/store1", AclSubject.TOKEN, "backup@pbs!pve", "DatastoreBackup", propagate = false))
        val tokenEntry = repo.acl("pbs").single { it.ugid == "backup@pbs!pve" }
        assertEquals(AclSubject.TOKEN, tokenEntry.subject)
        assertEquals(false, tokenEntry.propagate)
        repo.removeAcl("pbs", tokenEntry)
        assertEquals(1, repo.acl("pbs").size)
        assertFailsWith<ApiException> { repo.addAcl("pbs", AclForm("/", AclSubject.USER, "nobody@pbs", "Audit")) }
        Unit
    }
}
