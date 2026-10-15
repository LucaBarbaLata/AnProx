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
import io.github.lucabarbalata.anprox.model.pve.CtResources
import io.github.lucabarbalata.anprox.model.pve.CtVolumeOps
import io.github.lucabarbalata.anprox.model.pve.DiskBus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NewMountPoint
import io.github.lucabarbalata.anprox.model.pve.NewVmDisk
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.VmCpuMemory
import io.github.lucabarbalata.anprox.model.pve.VmDiskOps
import io.github.lucabarbalata.anprox.model.pve.VmNetSettings
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

/** VM and container hardware against the mock (questionnaire Q19). */
class HardwareTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val web = GuestRef("pve", "pve1", GuestType.QEMU, 100) // running, disks on nas-nfs
    private val win11 = GuestRef("pve", "pve2", GuestType.QEMU, 102) // stopped
    private val nginx = GuestRef("pve", "pve2", GuestType.LXC, 201) // running

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

    private suspend fun GuestRepository.done(ref: GuestRef, upid: String?) {
        assertNotNull(upid)
        assertTrue(awaitTask(ref.serverId, ref.node, upid).succeeded)
    }

    @Test
    fun `reads a VM with its bridges, ISO images and host CPUs`() = runBlocking {
        val hw = HardwareRepository(hub())
        val vm = hw.vm(web)
        assertEquals(1, vm.sockets)
        assertEquals(4, vm.cores)
        assertEquals("x86-64-v2-AES", vm.cpuType)
        assertEquals(8192, vm.memoryMiB)
        assertEquals(2048, vm.balloonMiB)
        assertEquals(listOf("scsi0"), vm.hardDisks.map { it.key })
        assertEquals("nas-nfs", vm.hardDisks[0].storage)
        assertEquals(64L shl 30, vm.hardDisks[0].sizeBytes)
        assertEquals(listOf("ide2"), vm.drives.map { it.key })
        assertEquals("vmbr0", vm.nets.single().bridge)
        assertEquals(listOf("scsi0", "ide2", "net0"), vm.bootOrder)
        assertEquals(emptySet(), vm.pending)

        assertEquals(listOf("vmbr0", "vmbr1"), hw.bridges("pve", "pve1").map { it.iface })
        assertEquals(
            listOf("debian-12.7.0-amd64-netinst.iso", "ubuntu-24.04.1-live-server-amd64.iso", "virtio-win-0.1.262.iso"),
            hw.isoImages("pve", "pve1").map { it.volid.substringAfterLast('/') },
        )
        assertEquals(16, hw.hostCpus("pve", "pve1"))
        Unit
    }

    @Test
    fun `CPU and memory of a running VM wait for the next start`() = runBlocking {
        val hub = hub()
        val hw = HardwareRepository(hub)
        val guests = GuestRepository(hub)
        val before = hw.vm(web)
        val form = VmCpuMemory.from(before).copy(cores = 2, memoryMiB = 4096, minMemoryMiB = 1024)
        assertNull(form.problem(hw.hostCpus("pve", "pve1")))
        assertNull(hw.apply(web, form.changes(before)))

        val after = hw.vm(web)
        assertEquals(2, after.cores)                 // the config shows the pending value
        assertEquals(4096, after.memoryMiB)
        assertEquals(setOf("cores", "memory", "balloon"), after.pending)
        assertEquals(4, guests.status(web).cpus?.toInt()) // still running with the old values

        // Too many vCPUs for the node: refused by Proxmox too.
        assertFailsWith<ApiException> { hw.apply(web, VmCpuMemory.from(after).copy(sockets = 2, cores = 16).changes(after)) }

        guests.done(web, guests.power(web, PowerAction.STOP))
        guests.done(web, guests.power(web, PowerAction.START))
        assertEquals(emptySet(), hw.vm(web).pending)
        assertEquals(2, guests.status(web).cpus?.toInt())
    }

    @Test
    fun `add, grow, move, detach and remove VM disks`() = runBlocking {
        val hub = hub()
        val hw = HardwareRepository(hub)
        val guests = GuestRepository(hub)
        var vm = hw.vm(web)

        val disk = NewVmDisk(DiskBus.SCSI, "local-zfs", 32)
        guests.done(web, hw.apply(web, disk.params(vm), asTask = true))
        vm = hw.vm(web)
        val scsi1 = vm.disk("scsi1")!!
        assertEquals("local-zfs", scsi1.storage)
        assertEquals(32L shl 30, scsi1.sizeBytes)
        assertTrue(scsi1.iothread)
        assertTrue(scsi1.discard)

        // pve1's local-zfs has 150 GiB free.
        assertFailsWith<ApiException> { hw.apply(web, NewVmDisk(DiskBus.SCSI, "local-zfs", 500).params(vm), asTask = true) }

        guests.done(web, hw.resize(web, VmDiskOps.resize(scsi1, 8, vm.digest)))
        vm = hw.vm(web)
        assertEquals(40L shl 30, vm.disk("scsi1")!!.sizeBytes)

        guests.done(web, hw.move(web, VmDiskOps.move(vm.disk("scsi1")!!, "nas-nfs", deleteSource = false, vm.digest)))
        vm = hw.vm(web)
        assertEquals("nas-nfs", vm.disk("scsi1")!!.storage)
        assertEquals(listOf("local-zfs"), vm.unused.map { it.storage })

        // Detaching keeps the volume as unused; removing an unused volume destroys it.
        hw.apply(web, VmDiskOps.remove(vm.disk("scsi1")!!, vm.digest))
        vm = hw.vm(web)
        assertNull(vm.disk("scsi1"))
        assertEquals(2, vm.unused.size)
        hw.apply(web, VmDiskOps.remove(vm.unused.first { it.storage == "local-zfs" }, vm.digest))
        vm = hw.vm(web)
        assertEquals(listOf("nas-nfs"), vm.unused.map { it.storage })

        // Attach it again, on VirtIO this time.
        hw.apply(web, VmDiskOps.attach(vm.unused.single(), DiskBus.VIRTIO, vm))
        vm = hw.vm(web)
        assertEquals("nas-nfs", vm.disk("virtio0")!!.storage)
        assertEquals(emptyList(), vm.unused)

        // A stale digest is refused (someone else changed the VM in between).
        val stale = vm.copy(digest = "00000000")
        assertFailsWith<ApiException> { hw.apply(web, VmCpuMemory.from(stale).copy(cores = 3).changes(stale)) }
        Unit
    }

    @Test
    fun `CD drive, boot order and network devices`() = runBlocking {
        val hw = HardwareRepository(hub())
        var vm = hw.vm(win11)
        assertEquals("virtio-win-0.1.262.iso", vm.disk("ide2")!!.isoName)
        assertEquals(listOf("efidisk0", "tpmstate0"), vm.system.map { it.key })

        val debian = hw.isoImages("pve", "pve2").first { "debian" in it.volid }.volid
        hw.apply(win11, VmDiskOps.insert(vm.disk("ide2")!!, debian, vm.digest))
        vm = hw.vm(win11)
        assertEquals("debian-12.7.0-amd64-netinst.iso", vm.disk("ide2")!!.isoName)
        hw.apply(win11, VmDiskOps.insert(vm.disk("ide2")!!, null, vm.digest))
        vm = hw.vm(win11)
        assertNull(vm.disk("ide2")!!.isoName)

        // Stopped VM: the boot order changes at once.
        hw.apply(win11, VmDiskOps.bootOrder(listOf("ide2", "scsi0"), vm.digest))
        vm = hw.vm(win11)
        assertEquals(listOf("ide2", "scsi0"), vm.bootOrder)
        assertEquals(emptySet(), vm.pending)

        // A new network device gets a MAC address from Proxmox.
        hw.apply(win11, VmNetSettings(model = "e1000", bridge = "vmbr1", tag = 20).params(vm, null))
        vm = hw.vm(win11)
        val net1 = vm.nets.first { it.key == "net1" }
        assertEquals("e1000", net1.model)
        assertNotNull(net1.mac)
        assertEquals(20, net1.tag)
        hw.apply(win11, VmNetSettings.from(net1).copy(tag = null, linkDown = true).params(vm, net1))
        vm = hw.vm(win11)
        assertEquals(net1.mac, vm.nets.first { it.key == "net1" }.mac)
        assertTrue(vm.nets.first { it.key == "net1" }.linkDown)
        assertFailsWith<ApiException> { hw.apply(win11, VmNetSettings(bridge = "vmbr9").params(vm, null)) }
        hw.apply(win11, mapOf("delete" to "net1"))
        assertEquals(listOf("net0"), hw.vm(win11).nets.map { it.key })
    }

    @Test
    fun `container resources, mount points and network`() = runBlocking {
        val hub = hub()
        val hw = HardwareRepository(hub)
        val guests = GuestRepository(hub)
        var ct = hw.container(nginx)
        assertEquals(2, ct.cores)
        assertEquals(1024, ct.memoryMiB)
        assertEquals("/var/www", ct.mountPoints.single().mountPath)
        assertEquals("10.0.0.201/24", ct.nets.single().ip)

        hw.apply(nginx, CtResources.from(ct).copy(memoryMiB = 2048, swapMiB = 0).changes(ct))
        ct = hw.container(nginx)
        assertEquals(2048, ct.memoryMiB)
        assertEquals(0, ct.swapMiB)
        assertEquals(emptySet(), ct.pending) // containers change these live

        hw.apply(nginx, NewMountPoint("local-zfs", 8, "/srv/data").params(ct))
        ct = hw.container(nginx)
        val mp1 = ct.volume("mp1")!!
        assertEquals("/srv/data", mp1.mountPath)
        assertEquals(8L shl 30, mp1.sizeBytes)
        assertTrue(mp1.backup)

        guests.done(nginx, hw.resize(nginx, CtVolumeOps.resize(ct.rootfs!!, 2, ct.digest)))
        ct = hw.container(nginx)
        assertEquals(10L shl 30, ct.rootfs!!.sizeBytes)

        // Proxmox moves container volumes only while the container is stopped.
        assertFailsWith<ApiException> { hw.move(nginx, CtVolumeOps.move(mp1, "nas-nfs", deleteSource = true, ct.digest)) }

        val net0 = ct.nets.single()
        hw.apply(nginx, CtNetSettings.from(net0).copy(ipv4 = CtNetSettings.Ipv4Mode.DHCP, ipv6 = CtNetSettings.Ipv6Mode.SLAAC).params(ct, net0))
        ct = hw.container(nginx)
        assertEquals("dhcp", ct.nets.single().ip)
        assertNull(ct.nets.single().gateway)
        assertEquals("auto", ct.nets.single().ip6)
        assertEquals(net0.hwaddr, ct.nets.single().hwaddr)

        hw.apply(nginx, CtVolumeOps.remove(ct.volume("mp1")!!, ct.digest))
        ct = hw.container(nginx)
        assertEquals(listOf("mp0"), ct.mountPoints.map { it.key })
        assertEquals(1, ct.unused.size)
    }

    @Test
    fun `read-only users can't change hardware`() = runBlocking {
        val hw = HardwareRepository(hub(user = "auditor", realm = "pve"))
        val vm = hw.vm(web)
        assertFailsWith<ApiException.Forbidden> { hw.apply(web, VmCpuMemory.from(vm).copy(cores = 2).changes(vm)) }
        assertFailsWith<ApiException.Forbidden> { hw.resize(web, VmDiskOps.resize(vm.hardDisks[0], 1, null)) }
        Unit
    }
}
