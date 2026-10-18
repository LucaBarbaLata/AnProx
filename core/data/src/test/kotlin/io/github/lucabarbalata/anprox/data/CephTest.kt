package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.CephAction
import io.github.lucabarbalata.anprox.data.datacenter.CephRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.CephFsForm
import io.github.lucabarbalata.anprox.model.pve.CephOsdForm
import io.github.lucabarbalata.anprox.model.pve.CephPoolForm
import io.github.lucabarbalata.anprox.model.pve.CephServiceType
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

/** Ceph (M6) against the mock's three-node cluster with pve3 offline. */
class CephTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, taskDurationMillis = 0).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hub: ServerHub

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(installed: Boolean = true): CephRepository {
        pve.state.ceph.installed = installed
        val profile = ServerProfile(
            id = "pve", name = "pve", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return CephRepository(hub)
    }

    @Test
    fun `a cluster without Ceph says so`() = runBlocking {
        val repo = repo(installed = false)
        val e = assertFailsWith<ApiException> { repo.overview("pve", "pve1") }
        assertTrue(CephRepository.notSetUp(e), e.toString())
        Unit
    }

    @Test
    fun `health, OSDs, and flags`() = runBlocking {
        val repo = repo()
        assertEquals(listOf("pve1", "pve2"), repo.onlineNodes("pve"))
        val status = repo.overview("pve", "pve1")
        assertEquals("HEALTH_WARN", status.health)
        assertTrue(status.checks.any { it.id == "OSD_DOWN" && it.summary == "2 osds down" })
        assertEquals(listOf("pve1", "pve2"), status.quorum)
        assertEquals(6 to 4, status.osds to status.osdsUp)

        val tree = repo.osds("pve", "pve1")
        assertEquals(listOf("pve1", "pve2", "pve3"), tree.hosts.map { it.name })
        val osd4 = tree.osds.single { it.id == 4 }
        assertFalse(osd4.up)
        assertFailsWith<ApiException> { repo.destroyOsd("pve", osd4, cleanup = false) } // its host is offline
        val osd5 = tree.osds.single { it.id == 5 }
        assertTrue(osd5.canDestroy)

        repo.setIn("pve", "pve1", tree.osds.single { it.id == 1 }, inCluster = false)
        assertFalse(repo.osds("pve", "pve1").osds.single { it.id == 1 }.inCluster)
        repo.scrub("pve", "pve1", osd4, deep = true)

        assertEquals("noout", repo.flags("pve").first().name)
        repo.setFlag("pve", "noout", true)
        assertTrue(repo.flags("pve").single { it.name == "noout" }.value)
        assertTrue(repo.overview("pve", "pve2").checks.any { it.id == "OSDMAP_FLAGS" })
        assertEquals(listOf("noout"), repo.osds("pve", "pve1").flags)
        repo.setFlag("pve", "noout", false)
        Unit
    }

    @Test
    fun `an OSD on an unused disk, stopped, taken out and destroyed`() = runBlocking {
        val repo = repo()
        val guests = GuestRepository(hub)
        val disks = repo.unusedDisks("pve", "pve1")
        assertEquals(listOf("/dev/sdb", "/dev/sdc"), disks.map { it.devpath })
        guests.awaitTask("pve", "pve1", repo.createOsd("pve", CephOsdForm(node = "pve1", device = "/dev/sdb")))
        val created = repo.osds("pve", "pve1").osds.single { it.id == 6 }
        assertEquals("ssd", created.deviceClass) // from the disk
        assertEquals(listOf("/dev/sdc"), repo.unusedDisks("pve", "pve1").map { it.devpath })
        assertFailsWith<ApiException> { repo.destroyOsd("pve", created, cleanup = true) } // still up and in
        guests.awaitTask("pve", "pve1", repo.serviceAction("pve", "pve1", created.service, CephAction.STOP))
        repo.setIn("pve", "pve1", created, inCluster = false)
        val down = repo.osds("pve", "pve1").osds.single { it.id == 6 }
        assertTrue(down.canDestroy)
        guests.awaitTask("pve", "pve1", repo.destroyOsd("pve", down, cleanup = true))
        assertFalse(repo.osds("pve", "pve1").osds.any { it.id == 6 })
        assertEquals(listOf("/dev/sdb", "/dev/sdc"), repo.unusedDisks("pve", "pve1").map { it.devpath })
        Unit
    }

    @Test
    fun `pools - create, change, refuse what is in use, delete`() = runBlocking {
        val repo = repo()
        val guests = GuestRepository(hub)
        val pools = repo.pools("pve", "pve1")
        assertEquals(".mgr", pools.last().name) // Ceph's own last
        assertEquals(listOf("replicated_rule"), repo.rules("pve", "pve1"))
        guests.awaitTask("pve", "pve1", repo.createPool("pve", "pve1", CephPoolForm(name = "fast", pgNum = "32")))
        val fast = repo.pools("pve", "pve1").single { it.name == "fast" }
        assertEquals(32, fast.pgNum)
        assertEquals(null, repo.updatePool("pve", "pve1", fast, CephPoolForm.from(fast)))
        guests.awaitTask("pve", "pve1", repo.updatePool("pve", "pve1", fast, CephPoolForm.from(fast).copy(size = "2", minSize = "2"))!!)
        assertEquals(2, repo.pools("pve", "pve1").single { it.name == "fast" }.size)
        assertFailsWith<ApiException> { repo.createPool("pve", "pve1", CephPoolForm(name = "fast")) }
        assertFailsWith<ApiException> { repo.deletePool("pve", "pve1", "cephfs_data", removeStorages = false) }
        guests.awaitTask("pve", "pve1", repo.deletePool("pve", "pve1", "fast", removeStorages = true))
        assertFalse(repo.pools("pve", "pve1").any { it.name == "fast" })
        Unit
    }

    @Test
    fun `monitors, managers, metadata servers and CephFS`() = runBlocking {
        val repo = repo()
        val guests = GuestRepository(hub)
        val mons = repo.services("pve", "pve1", CephServiceType.MON)
        assertEquals(listOf(true, true, false), mons.map { it.inQuorum })
        assertEquals(listOf("active", "standby"), repo.services("pve", "pve1", CephServiceType.MGR).map { it.state })
        assertEquals(listOf("up:active", "up:standby"), repo.services("pve", "pve2", CephServiceType.MDS).map { it.state })
        assertFailsWith<ApiException> { repo.createService("pve", "pve1", CephServiceType.MGR) } // already there
        val mgr2 = repo.services("pve", "pve1", CephServiceType.MGR).single { it.name == "pve2" }
        guests.awaitTask("pve", "pve2", repo.destroyService("pve", mgr2))
        guests.awaitTask("pve", "pve2", repo.createService("pve", "pve2", CephServiceType.MGR))
        guests.awaitTask("pve", "pve1", repo.serviceAction("pve", "pve1", "mon.pve1", CephAction.RESTART))

        assertEquals(listOf("cephfs"), repo.filesystems("pve", "pve1").map { it.name })
        guests.awaitTask("pve", "pve1", repo.createFs("pve", "pve1", CephFsForm(name = "media", pgNum = "64")))
        assertEquals(listOf("cephfs", "media"), repo.filesystems("pve", "pve1").map { it.name })
        // Both metadata servers are busy now.
        assertFailsWith<ApiException> { repo.createFs("pve", "pve1", CephFsForm(name = "more")) }
        Unit
    }

    @Test
    fun `configuration, CRUSH map and log`() = runBlocking {
        val repo = repo()
        assertTrue("osd_pool_default_size = 3" in repo.config("pve", "pve1"))
        assertTrue(repo.configDb("pve", "pve1").any { it.name == "osd_pool_default_pg_autoscale_mode" })
        assertTrue("rule replicated_rule {" in repo.crushMap("pve", "pve1"))
        assertTrue(repo.log("pve", "pve1").last().contains("HEALTH_WARN"))
        // pve3 is offline: its node can't answer.
        assertFailsWith<ApiException> { repo.overview("pve", "pve3") }
        Unit
    }
}
