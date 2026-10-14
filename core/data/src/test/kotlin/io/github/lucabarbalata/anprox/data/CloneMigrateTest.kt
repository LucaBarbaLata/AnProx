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
import io.github.lucabarbalata.anprox.model.pve.CloneRequest
import io.github.lucabarbalata.anprox.model.pve.GuestType
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

/** Clone, convert to template and migrate against the mock (questionnaire Q18). */
class CloneMigrateTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun vm(vmid: Int, node: String) = GuestRef("pve", node, GuestType.QEMU, vmid)
    private fun ct(vmid: Int, node: String) = GuestRef("pve", node, GuestType.LXC, vmid)

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
    fun `clone a VM to another node and a template as a linked clone`() = runBlocking {
        val guests = repo()
        assertEquals(mapOf("pve1" to true, "pve2" to true, "pve3" to false), guests.clusterNodes("pve"))
        assertEquals(listOf("local-zfs", "nas-nfs"), guests.diskStorages("pve", "pve2", GuestType.QEMU).map { it.storage })
        assertEquals(listOf("local-zfs"), guests.diskStorages("pve", "pve2", GuestType.LXC).map { it.storage })

        val web = vm(100, "pve1") // on shared storage
        val id = guests.nextVmid("pve")
        val upid = guests.clone(web, CloneRequest(id, "web-copy", targetNode = "pve2"))
        assertTrue(guests.awaitTask("pve", "pve1", upid).succeeded)
        val copy = guests.status(vm(id, "pve2"))
        assertEquals("web-copy", copy.name)
        assertEquals("stopped", copy.status)

        val template = vm(9000, "pve1")
        val linked = guests.clone(template, CloneRequest(id + 1, "from-template", full = false))
        assertTrue(guests.awaitTask("pve", "pve1", linked).succeeded)
        assertEquals("from-template", guests.status(vm(id + 1, "pve1")).name)
    }

    @Test
    fun `clone refusals come from Proxmox`() = runBlocking {
        val guests = repo()
        val k8s = vm(103, "pve2") // on local storage
        val local = assertFailsWith<ApiException.Server> { guests.clone(k8s, CloneRequest(150, "", targetNode = "pve1")) }
        assertTrue("local storage" in local.message.orEmpty())
        // With a target storage on the other node it works (a full copy).
        val upid = guests.clone(k8s, CloneRequest(150, "k8s-copy", targetNode = "pve1", storage = "local-zfs"))
        assertTrue(guests.awaitTask("pve", "pve2", upid).succeeded)
        assertFailsWith<ApiException.Server> { guests.clone(k8s, CloneRequest(150, "again")) } // id taken
        assertFailsWith<ApiException.Forbidden> { repo("auditor", "pve").clone(k8s, CloneRequest(151, "")) }
        Unit
    }

    @Test
    fun `convert to template`() = runBlocking {
        val guests = repo()
        assertNull(guests.convertToTemplate(ct(202, "pve2"))) // containers convert at once
        assertTrue(guests.status(ct(202, "pve2")).template)

        val win11 = vm(102, "pve2")
        val upid = assertNotNull(guests.convertToTemplate(win11))
        assertTrue(guests.awaitTask("pve", "pve2", upid).succeeded)
        assertTrue(guests.status(win11).template)

        val running = assertFailsWith<ApiException.Server> { guests.convertToTemplate(ct(200, "pve1")) }
        assertTrue("running" in running.message.orEmpty())
        Unit
    }

    @Test
    fun `migrate a running VM live and a running container with a restart`() = runBlocking {
        val guests = repo()
        val k8s = vm(103, "pve2")
        val check = assertNotNull(guests.migrationCheck(k8s))
        assertTrue(check.running)
        assertNull(check.allowedNodes) // Proxmox only lists them for stopped VMs
        assertEquals(1, check.disksToCopy.size)

        val upid = guests.migrate(k8s, "pve1", running = true, check = check)
        assertTrue(guests.awaitTask("pve", "pve2", upid).succeeded)
        assertEquals("k8s-master", guests.status(vm(103, "pve1")).name)
        assertFailsWith<ApiException.Server> { guests.status(k8s) } // no longer on pve2

        val nginx = ct(201, "pve2")
        assertNull(guests.migrationCheck(nginx))
        assertTrue(guests.awaitTask("pve", "pve2", guests.migrate(nginx, "pve1", running = true, check = null)).succeeded)
        assertEquals("nginx-proxy", guests.status(ct(201, "pve1")).name)

        val offline = assertFailsWith<ApiException.Server> { guests.migrate(vm(100, "pve1"), "pve3", running = true, check = null) }
        assertTrue("not online" in offline.message.orEmpty())
        Unit
    }
}
