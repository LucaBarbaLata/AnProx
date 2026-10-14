package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guest agent and container interfaces against the mock (questionnaire Q18). */
class GuestAgentTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(): GuestRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return GuestRepository(hub)
    }

    @Test
    fun `a running VM with the agent reports its OS and addresses`() = runBlocking {
        val info = repo().networkInfo(GuestRef("pve", "pve2", GuestType.QEMU, 103))
        assertEquals("Ubuntu 24.04.1 LTS", info.os)
        assertEquals(listOf("ens18"), info.nics.map { it.name })
        assertEquals(listOf("10.0.0.103"), info.nics[0].ipv4)
        assertNull(info.agentProblem)
    }

    @Test
    fun `a VM without the agent explains why`() = runBlocking {
        val info = repo().networkInfo(GuestRef("pve", "pve2", GuestType.QEMU, 102))
        assertTrue(info.nics.isEmpty())
        assertEquals("No QEMU guest agent configured", info.agentProblem)
    }

    @Test
    fun `containers report interfaces without an agent`() = runBlocking {
        val info = repo().networkInfo(GuestRef("pve", "pve1", GuestType.LXC, 200))
        assertEquals(listOf("10.0.1.10"), info.nics.single().ipv4)
        assertNull(info.os)
    }

    @Test
    fun `the network flow starts with a loading null`() = runBlocking {
        val first = repo().observeNetwork(GuestRef("pve", "pve1", GuestType.QEMU, 100)).take(2).toList()
        assertNull(first[0])
        assertEquals("Debian GNU/Linux 12 (bookworm)", first[1]!!.os)
    }
}
