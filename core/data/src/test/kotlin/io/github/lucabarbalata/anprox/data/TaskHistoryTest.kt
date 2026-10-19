package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A node's task history, page by page, on Proxmox VE and the Backup Server. */
class TaskHistoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<MockProxmoxServer>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.stop() }
    }

    private suspend fun repo(product: ProxmoxProduct): GuestRepository {
        val server = MockProxmoxServer(product).start().also { servers += it }
        val profile = ServerProfile(
            id = "s", name = "Server", product = product,
            endpoints = listOf(Endpoint(server.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("s", "anprox", remember = false)
        return GuestRepository(hub)
    }

    @Test
    fun `a node's tasks come newest first, in pages, optionally only the failed ones`() = runBlocking {
        val guests = repo(ProxmoxProduct.PVE)
        val all = guests.taskHistory("s", "pve2", start = 0, limit = 100, errorsOnly = false)
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.node == "pve2" })
        assertTrue(all.zipWithNext().all { (a, b) -> a.starttime >= b.starttime })

        val first = guests.taskHistory("s", "pve2", start = 0, limit = 1, errorsOnly = false)
        val second = guests.taskHistory("s", "pve2", start = 1, limit = 1, errorsOnly = false)
        assertEquals(all.take(2).map { it.upid }, (first + second).map { it.upid })

        val failed = guests.taskHistory("s", "pve2", start = 0, limit = 100, errorsOnly = true)
        assertTrue(failed.isNotEmpty())
        assertTrue(failed.all { it.state == TaskState.FAILED || it.state == TaskState.WARNING })
        Unit
    }

    @Test
    fun `the Backup Server's history works the same`() = runBlocking {
        val guests = repo(ProxmoxProduct.PBS)
        val failed = guests.taskHistory("s", "localhost", start = 0, limit = 50, errorsOnly = true)
        assertEquals(listOf("syncjob"), failed.map { it.type })
        Unit
    }
}
