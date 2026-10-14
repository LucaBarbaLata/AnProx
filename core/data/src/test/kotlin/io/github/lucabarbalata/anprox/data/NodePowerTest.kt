package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.NodePowerAction
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

/** Rebooting and shutting down nodes (questionnaire Q12: type-to-confirm in the app). */
class NodePowerTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(user: String = "root", realm: String = "pam"): GuestRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return GuestRepository(hub)
    }

    @Test
    fun `reboot and shutdown are sent as commands`() = runBlocking {
        val guests = repo()
        guests.nodePower("pve", "pve1", NodePowerAction.REBOOT)
        guests.nodePower("pve", "pve2", NodePowerAction.SHUTDOWN)
        assertEquals(listOf("pve1" to "reboot", "pve2" to "shutdown"), pve.state.nodePowerRequests.toList())
    }

    @Test
    fun `refused for read-only users and offline nodes`() = runBlocking {
        assertFailsWith<ApiException.Forbidden> { repo("auditor", "pve").nodePower("pve", "pve1", NodePowerAction.REBOOT) }
        assertFailsWith<ApiException> { repo().nodePower("pve", "pve3", NodePowerAction.REBOOT) }
        assertEquals(emptyList(), pve.state.nodePowerRequests.toList())
    }
}
