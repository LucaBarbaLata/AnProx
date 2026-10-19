package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.metrics.MetricsRepository
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.RrdTimeframe
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

/** Performance history end to end: repository → HTTP client → mock server (questionnaire Q25). */
class MetricsTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(): MetricsRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return MetricsRepository(hub)
    }

    @Test
    fun `running VM has a full hour of samples`() = runBlocking {
        val points = repo().history(MetricsSource.Guest(GuestRef("pve", "pve1", GuestType.QEMU, 100)), RrdTimeframe.HOUR)
        assertEquals(70, points.size)
        assertTrue(points.zipWithNext().all { (a, b) -> b.time - a.time == 60L })
        assertTrue(points.all { p -> p.cpu != null && p.cpu!! in 0.0..1.0 })
        assertTrue(points.all { p -> p.mem!! <= p.maxmem!! })
        Unit
    }

    @Test
    fun `stopped container has gaps for the recent part`() = runBlocking {
        val points = repo().history(MetricsSource.Guest(GuestRef("pve", "pve2", GuestType.LXC, 202)), RrdTimeframe.WEEK)
        assertEquals(3 * 60 * 60L, points[1].time - points[0].time)
        assertNotNull(points.first().cpu)
        assertNull(points.last().cpu)
        assertNotNull(points.last().maxmem)
        Unit
    }

    @Test
    fun `node history has memory, load and network`() = runBlocking {
        val points = repo().history(MetricsSource.Node("pve", "pve1"), RrdTimeframe.DAY)
        val last = points.last()
        assertEquals(64.0 * 1024 * 1024 * 1024, last.memtotal)
        assertTrue(last.memused!! < last.memtotal!!)
        assertNotNull(last.loadavg)
        assertNotNull(last.netin)
        Unit
    }

    @Test
    fun `storage history climbs to today's usage and keeps the size`() = runBlocking {
        val repo = repo()
        val points = repo.history(MetricsSource.Storage("pve", "pve1", "local-zfs"), RrdTimeframe.MONTH)
        assertEquals(70, points.size)
        val gib = 1024.0 * 1024 * 1024
        assertTrue(points.all { it.total == 1800 * gib })
        assertTrue(points.all { it.used!! <= it.total!! })
        assertTrue(points.first().used!! < points.last().used!!)
        assertTrue(points.last().used!! in (1600 * gib)..(1700 * gib))
        assertNull(points.last().cpu)

        val missing = assertFailsWith<ApiException.Server> { repo.history(MetricsSource.Storage("pve", "pve1", "nope"), RrdTimeframe.DAY) }
        assertTrue("does not exist" in missing.message.orEmpty())
        Unit
    }

    @Test
    fun `offline node fails like Proxmox does`() = runBlocking {
        val error = assertFailsWith<ApiException.Server> { repo().history(MetricsSource.Node("pve", "pve3"), RrdTimeframe.HOUR) }
        assertEquals(595, error.code)
        Unit
    }
}
