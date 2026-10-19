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
import io.github.lucabarbalata.anprox.model.pve.FsFreezeStatus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.network.ApiException
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
import kotlin.test.assertFailsWith
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

    @Test
    fun `filesystems freeze, refuse a second freeze and thaw again`() = runBlocking {
        val repo = repo()
        val web = GuestRef("pve", "pve1", GuestType.QEMU, 100)
        assertEquals(FsFreezeStatus.THAWED, repo.freezeStatus(web))
        assertEquals(2, repo.freeze(web))
        assertEquals(FsFreezeStatus.FROZEN, repo.freezeStatus(web))
        assertFailsWith<ApiException.Server> { repo.freeze(web) }
        // Trimming needs writes, so it waits for the thaw.
        assertFailsWith<ApiException.Server> { repo.trim(web) }
        assertEquals(2, repo.thaw(web))
        assertEquals(0, repo.thaw(web))
        assertEquals(FsFreezeStatus.THAWED, repo.freezeStatus(web))
        Unit
    }

    @Test
    fun `trim reports each filesystem`() = runBlocking {
        val paths = repo().trim(GuestRef("pve", "pve1", GuestType.QEMU, 101))
        assertEquals(listOf("/", "/boot/efi"), paths.map { it.path })
        assertEquals(3L * 1024 * 1024 * 1024, paths[0].trimmed)
        Unit
    }

    @Test
    fun `agent tools need the agent and a running VM`() = runBlocking {
        val repo = repo()
        val noAgent = assertFailsWith<ApiException.Server> { repo.freezeStatus(GuestRef("pve", "pve2", GuestType.QEMU, 102)) }
        assertTrue("No QEMU guest agent" in noAgent.message.orEmpty())
        Unit
    }

    @Test
    fun `a guest user's password can be set through the agent`() = runBlocking {
        val repo = repo()
        val db = GuestRef("pve", "pve1", GuestType.QEMU, 101)
        repo.setGuestPassword(db, " ubuntu ", "s3cret-pass")
        assertEquals("s3cret-pass", pve.state.agentPasswords[101 to "ubuntu"])

        val unknown = assertFailsWith<ApiException.Server> { repo.setGuestPassword(db, "nobody", "s3cret-pass") }
        assertTrue("does not exist" in unknown.message.orEmpty())
        assertFailsWith<ApiException.Validation> { repo.setGuestPassword(db, "root", "abc") }
        Unit
    }
}
