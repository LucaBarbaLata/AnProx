package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.GuestOs
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NewContainer
import io.github.lucabarbalata.anprox.model.pve.NewVm
import io.github.lucabarbalata.anprox.model.pve.VmDisk
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
import kotlin.test.assertTrue

/** Creating VMs and containers against the mock (questionnaire Q17). */
class GuestCreationTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun hub(user: String = "root", realm: String = "pam"): ServerHub {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return hub
    }

    @Test
    fun `create a Linux VM and start it`() = runBlocking {
        val hub = hub()
        val guests = GuestRepository(hub)
        val hw = HardwareRepository(hub)
        val used = guests.usedVmids("pve")
        assertTrue(100 in used && 200 in used && 9000 in used)
        val id = guests.nextVmid("pve")
        val iso = hw.isoImages("pve", "pve1").first { "debian" in it.volid }.volid

        val vm = NewVm(vmid = id, name = "fresh", iso = iso, storage = "local-zfs", diskGiB = 20, cores = 2, memoryMiB = 2048, start = true)
        val upid = guests.createVm("pve", "pve1", vm)
        assertTrue(guests.awaitTask("pve", "pve1", upid).succeeded)

        val ref = GuestRef("pve", "pve1", GuestType.QEMU, id)
        val status = guests.status(ref)
        assertEquals("fresh", status.name)
        assertEquals("running", status.status)
        assertEquals(20L shl 30, status.maxdisk)

        val created = hw.vm(ref)
        assertEquals("local-zfs:vm-$id-disk-0", created.disk("scsi0")!!.volume)
        assertEquals("debian-12.7.0-amd64-netinst.iso", created.disk("ide2")!!.isoName)
        assertNotNull(created.nets.single().mac)
        assertEquals(listOf("scsi0", "ide2", "net0"), created.bootOrder)
        assertTrue(id in guests.usedVmids("pve"))
    }

    @Test
    fun `a Windows VM gets an EFI disk and a TPM`() = runBlocking {
        val hub = hub()
        val guests = GuestRepository(hub)
        val id = guests.nextVmid("pve")
        val upid = guests.createVm("pve", "pve2", NewVm(vmid = id, name = "win-test", os = GuestOs.WINDOWS, storage = "local-zfs", diskGiB = 64))
        assertTrue(guests.awaitTask("pve", "pve2", upid).succeeded)
        val created = HardwareRepository(hub).vm(GuestRef("pve", "pve2", GuestType.QEMU, id))
        assertEquals(listOf("sata0"), created.hardDisks.map { it.key })
        assertEquals(setOf(VmDisk.Kind.EFI, VmDisk.Kind.TPM), created.system.map { it.kind }.toSet())
        assertEquals("e1000e", created.nets.single().model)
        assertEquals("stopped", guests.status(GuestRef("pve", "pve2", GuestType.QEMU, id)).status)
    }

    @Test
    fun `create a container from a template`() = runBlocking {
        val hub = hub()
        val guests = GuestRepository(hub)
        val hw = HardwareRepository(hub)
        val templates = hw.containerTemplates("pve", "pve2")
        assertEquals(3, templates.size)
        assertEquals("vztmpl", templates.first().content)
        val id = guests.nextVmid("pve")
        val ct = NewContainer(
            vmid = id,
            hostname = "dns",
            template = templates.first { "debian" in it.volid }.volid,
            password = "secret1",
            storage = "local-zfs",
            diskGiB = 4,
            memoryMiB = 256,
            network = CtNetSettings(ipv4 = CtNetSettings.Ipv4Mode.STATIC, ip = "10.0.0.53/24", gateway = "10.0.0.1"),
        )
        assertTrue(guests.awaitTask("pve", "pve2", guests.createContainer("pve", "pve2", ct)).succeeded)
        val ref = GuestRef("pve", "pve2", GuestType.LXC, id)
        assertEquals("dns", guests.status(ref).name)
        val created = hw.container(ref)
        assertEquals("local-zfs:subvol-$id-disk-0", created.rootfs!!.volume)
        assertEquals(4L shl 30, created.rootfs!!.sizeBytes)
        assertEquals(256, created.memoryMiB)
        assertEquals("10.0.0.53/24", created.nets.single().ip)
        assertNotNull(created.nets.single().hwaddr)
        Unit
    }

    @Test
    fun `refusals from Proxmox`() = runBlocking {
        val hub = hub()
        val guests = GuestRepository(hub)
        // The model would catch these first; the server refuses them too.
        assertFailsWith<ApiException> { guests.createVm("pve", "pve1", NewVm(vmid = 100, storage = "local-zfs")) }
        assertFailsWith<ApiException> { guests.createVm("pve", "pve3", NewVm(vmid = 150, storage = "local-zfs")) } // offline node
        assertFailsWith<ApiException> { guests.createVm("pve", "pve1", NewVm(vmid = 150, storage = "local-zfs", diskGiB = 5000)) }
        assertFailsWith<ApiException> {
            guests.createContainer("pve", "pve1", NewContainer(vmid = 150, template = "local:vztmpl/nope.tar.zst", password = "secret1", storage = "local-zfs"))
        }
        assertFailsWith<ApiException.Forbidden> {
            GuestRepository(hub(user = "auditor", realm = "pve")).createVm("pve", "pve1", NewVm(vmid = 150, storage = "local-zfs"))
        }
        assertTrue(150 !in guests.usedVmids("pve"))
    }
}
