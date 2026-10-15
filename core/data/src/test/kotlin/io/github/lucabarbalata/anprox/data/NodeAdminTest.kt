package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.node.NodeAdminRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.ServiceAction
import io.github.lucabarbalata.anprox.network.ApiException
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
import kotlin.test.assertTrue

/** Updates, repositories, services and the journal against the mock. */
class NodeAdminTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, taskDurationMillis = 0).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun hub(): ServerHub {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return hub
    }

    @Test
    fun `updates, changelog and a refresh task`() = runBlocking {
        val hub = hub()
        val repo = NodeAdminRepository(hub)
        val updates = repo.updates("pve", "pve1")
        assertEquals(listOf("openssl", "proxmox-kernel-6.8", "pve-manager"), updates.map { it.name })
        assertEquals("8.4.5", updates.single { it.name == "pve-manager" }.version)
        assertTrue(repo.changelog("pve", "pve1", updates.first()).startsWith("openssl"))
        val upid = repo.refresh("pve", "pve1")
        assertTrue(GuestRepository(hub).awaitTask("pve", "pve1", upid).succeeded)
        assertFalse(repo.subscription("pve", "pve1")!!.active)
        assertTrue(repo.versions("pve", "pve1").any { it.name == "pve-manager" })
        Unit
    }

    @Test
    fun `repositories warn about the enterprise one, which can be swapped for no-subscription`() = runBlocking {
        val repo = NodeAdminRepository(hub())
        var repos = repo.repositories("pve", "pve1")
        assertTrue(repos.warnings.any { "enterprise" in it.message })
        assertEquals(listOf("no-subscription", "test"), repos.addable.map { it.handle })

        repo.setRepositoryEnabled("pve", "pve1", repos, "/etc/apt/sources.list.d/pve-enterprise.list", 0, false)
        // The digest changed, so the old view is refused.
        assertFailsWith<ApiException> { repo.addStandardRepository("pve", "pve1", repos, "no-subscription") }
        repos = repo.repositories("pve", "pve1")
        assertTrue(repos.warnings.any { "No Proxmox VE repository" in it.message })
        repo.addStandardRepository("pve", "pve1", repos, "no-subscription")
        repos = repo.repositories("pve", "pve1")
        assertTrue(repos.warnings.isEmpty())
        assertTrue(repos.files.flatMap { it.repositories }.any { it.enabled && "pve-no-subscription" in it.components })
        assertEquals(1, repos.standard.single { it.handle == "no-subscription" }.status)
        Unit
    }

    @Test
    fun `services start and stop through tasks, the journal shows the newest lines`() = runBlocking {
        val hub = hub()
        val repo = NodeAdminRepository(hub)
        val ksm = repo.services("pve", "pve1").single { it.id == "ksmtuned" }
        assertFalse(ksm.running)
        val upid = repo.serviceAction("pve", "pve1", ksm, ServiceAction.START)
        assertTrue(GuestRepository(hub).awaitTask("pve", "pve1", upid).succeeded)
        assertTrue(repo.services("pve", "pve1").single { it.id == "ksmtuned" }.running)

        val journal = repo.journal("pve", "pve1", 100)
        assertEquals(100, journal.size)
        assertTrue(journal.none { it.startsWith("-- cursor") })
        Unit
    }
}
