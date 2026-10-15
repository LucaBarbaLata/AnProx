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
import io.github.lucabarbalata.anprox.model.pve.DiskStorageForm
import io.github.lucabarbalata.anprox.model.pve.DiskStorageKind
import io.github.lucabarbalata.anprox.model.pve.NetworkInterfaceForm
import io.github.lucabarbalata.anprox.model.pve.NodeDns
import io.github.lucabarbalata.anprox.model.pve.NodeDnsForm
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

    @Test
    fun `dns, hosts and the time zone`() = runBlocking {
        val repo = NodeAdminRepository(hub())
        val dns = repo.dns("pve", "pve1")
        assertEquals("home.lan", dns.search)
        val form = NodeDnsForm.from(dns)
        assertEquals(listOf("10.0.0.1", "1.1.1.1", ""), form.servers)
        assertEquals(NodeDnsForm.Problem.SERVER, form.copy(servers = listOf("dns.example", "", "")).problem())
        repo.setDns("pve", "pve1", form.copy(servers = listOf("10.0.0.2", "", "")))
        assertEquals(NodeDns("home.lan", "10.0.0.2", null, null), repo.dns("pve", "pve1"))

        val hosts = repo.hosts("pve", "pve1")
        assertTrue("pve1.home.lan" in hosts.data)
        repo.setHosts("pve", "pve1", hosts, hosts.data + "10.0.0.50 nas.home.lan nas\n")
        assertTrue("nas.home.lan" in repo.hosts("pve", "pve1").data)
        // The old copy's digest is stale.
        assertFailsWith<ApiException> { repo.setHosts("pve", "pve1", hosts, hosts.data) }

        assertEquals("Europe/Bucharest", repo.time("pve", "pve1").timezone)
        repo.setTimezone("pve", "pve1", "Europe/Berlin")
        assertEquals("Europe/Berlin", repo.time("pve", "pve1").timezone)
        assertFailsWith<ApiException> { repo.setTimezone("pve", "pve1", "Mars/Olympus") }
        Unit
    }

    @Test
    fun `network changes stay pending until applied or reverted`() = runBlocking {
        val hub = hub()
        val repo = NodeAdminRepository(hub)
        var config = repo.network("pve", "pve1")
        assertEquals(listOf("vmbr0", "vmbr1", "eno1", "eno2"), config.interfaces.map { it.iface })
        assertFalse(config.pending)
        assertEquals("10.0.0.11/24", config.interfaces.first().cidr)

        repo.createInterface("pve", "pve1", NetworkInterfaceForm(name = "vmbr2", cidr = "192.168.50.1/24"))
        config = repo.network("pve", "pve1")
        assertTrue(config.pending)
        assertTrue(config.changes.orEmpty().contains("+iface vmbr2"))
        assertFalse(config.interfaces.single { it.iface == "vmbr2" }.exists)
        repo.revertNetwork("pve", "pve1")
        assertTrue(repo.network("pve", "pve1").interfaces.none { it.iface == "vmbr2" })

        val vmbr1 = repo.network("pve", "pve1").interfaces.single { it.iface == "vmbr1" }
        repo.updateInterface("pve", "pve1", vmbr1, NetworkInterfaceForm.from(vmbr1).copy(comment = "Storage"))
        assertFailsWith<ApiException> { repo.createInterface("pve", "pve1", NetworkInterfaceForm(name = "vmbr3", bridgePorts = "eno9")) }
        val upid = repo.applyNetwork("pve", "pve1")
        assertTrue(GuestRepository(hub).awaitTask("pve", "pve1", upid).succeeded)
        config = repo.network("pve", "pve1")
        assertFalse(config.pending)
        assertEquals("Storage", config.interfaces.single { it.iface == "vmbr1" }.comments)
        Unit
    }

    @Test
    fun `disks, SMART, and a ZFS mirror on the two free ones`() = runBlocking {
        val hub = hub()
        val repo = NodeAdminRepository(hub)
        val guests = GuestRepository(hub)
        val disks = repo.disks("pve", "pve1")
        assertEquals(listOf("/dev/sdb", "/dev/sdc"), disks.filter { it.unused }.map { it.devpath })
        assertEquals(97, disks.single { it.devpath == "/dev/nvme0n1" }.wearoutPercent)
        assertTrue(repo.smart("pve", "pve1", disks.first { it.devpath == "/dev/sda" }).attributes.any { it.name == "Power_On_Hours" })
        assertTrue(repo.smart("pve", "pve1", disks.first { it.type == "nvme" }).text.orEmpty().contains("Percentage Used"))

        val form = DiskStorageForm(DiskStorageKind.ZFS, "tank2", listOf("/dev/sdb", "/dev/sdc"), raidLevel = "mirror")
        assertEquals(null, form.problem())
        assertEquals(DiskStorageForm.Problem.RAID, form.copy(raidLevel = "raidz2").problem())
        assertTrue(guests.awaitTask("pve", "pve1", repo.createStorage("pve", "pve1", form)).succeeded)
        assertTrue(repo.disks("pve", "pve1").none { it.unused })
        // The disks are in use now.
        assertFailsWith<ApiException> { repo.createStorage("pve", "pve1", form.copy(name = "tank3")) }
        Unit
    }
}
