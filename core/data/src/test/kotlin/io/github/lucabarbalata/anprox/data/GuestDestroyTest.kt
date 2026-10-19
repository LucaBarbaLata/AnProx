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
import io.github.lucabarbalata.anprox.model.pve.PowerAction
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Destroying guests and stopping tasks against the mock (questionnaire Q12, Q28). */
class GuestDestroyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<MockProxmoxServer>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.stop() }
    }

    private suspend fun repo(taskDurationMillis: Long = 0): Pair<MockProxmoxServer, GuestRepository> {
        val pve = MockProxmoxServer(ProxmoxProduct.PVE, taskDurationMillis = taskDurationMillis).start().also { servers += it }
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return pve to GuestRepository(hub)
    }

    @Test
    fun `a stopped guest is destroyed by a task`() = runBlocking {
        val (pve, guests) = repo()
        val win11 = GuestRef("pve", "pve2", GuestType.QEMU, 102)
        val upid = guests.destroy(win11, purge = true, destroyUnreferencedDisks = true)
        assertTrue(":qmdestroy:102:" in upid)
        assertTrue(guests.awaitTask("pve", "pve2", upid).succeeded)
        assertNull(pve.state.guest(102))
        // Gone for every later call too.
        assertFailsWith<ApiException.Server> { guests.options(win11) }
        Unit
    }

    @Test
    fun `running, protected and HA-managed guests are refused`() = runBlocking {
        val (pve, guests) = repo()
        val running = assertFailsWith<ApiException.Server> { guests.destroy(GuestRef("pve", "pve1", GuestType.LXC, 200), purge = true, destroyUnreferencedDisks = true) }
        assertTrue("is running" in running.message.orEmpty())

        val backupClient = GuestRef("pve", "pve2", GuestType.LXC, 202)
        val options = guests.options(backupClient)
        guests.saveOptions(backupClient, options, options.copy(protection = true))
        val protected = assertFailsWith<ApiException.Server> { guests.destroy(backupClient, purge = true, destroyUnreferencedDisks = true) }
        assertTrue("protection mode enabled" in protected.message.orEmpty())

        // web is managed by HA: stop it, then it can only go with purge.
        val web = GuestRef("pve", "pve1", GuestType.QEMU, 100)
        guests.awaitTask("pve", "pve1", guests.power(web, PowerAction.STOP))
        val ha = assertFailsWith<ApiException.Server> { guests.destroy(web, purge = false, destroyUnreferencedDisks = true) }
        assertTrue("purge parameter not set" in ha.message.orEmpty())
        assertTrue(guests.awaitTask("pve", "pve1", guests.destroy(web, purge = true, destroyUnreferencedDisks = true)).succeeded)
        assertNull(pve.state.guest(100))
        Unit
    }

    @Test
    fun `a running task can be stopped before it takes effect`() = runBlocking {
        val (pve, guests) = repo(taskDurationMillis = 60_000)
        val upid = guests.power(GuestRef("pve", "pve2", GuestType.QEMU, 102), PowerAction.START)
        assertTrue(guests.taskStatus("pve", "pve2", upid).isRunning)

        guests.stopTask("pve", "pve2", upid)
        val status = guests.taskStatus("pve", "pve2", upid)
        assertEquals("interrupted by signal", status.exitstatus)
        assertEquals("stopped", pve.state.guest(102)?.status)
        assertTrue(guests.taskLog("pve", "pve2", upid).last().t.startsWith("TASK ERROR"))
        // Stopping it again is harmless.
        guests.stopTask("pve", "pve2", upid)
        assertNotNull(pve.state.guest(102))
        Unit
    }
}
