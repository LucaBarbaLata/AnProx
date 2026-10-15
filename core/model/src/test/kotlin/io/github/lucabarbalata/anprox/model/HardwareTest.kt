package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.BootOrder
import io.github.lucabarbalata.anprox.model.pve.CtHardware
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.CtResources
import io.github.lucabarbalata.anprox.model.pve.CtVolumeOps
import io.github.lucabarbalata.anprox.model.pve.DiskBus
import io.github.lucabarbalata.anprox.model.pve.DiskSize
import io.github.lucabarbalata.anprox.model.pve.IpAddress
import io.github.lucabarbalata.anprox.model.pve.NewMountPoint
import io.github.lucabarbalata.anprox.model.pve.NewVmDisk
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.PendingEntry
import io.github.lucabarbalata.anprox.model.pve.PropertyString
import io.github.lucabarbalata.anprox.model.pve.VmCpuMemory
import io.github.lucabarbalata.anprox.model.pve.VmDisk
import io.github.lucabarbalata.anprox.model.pve.VmDiskOps
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import io.github.lucabarbalata.anprox.model.pve.VmNetSettings
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareTest {

    private fun config(json: String) = ProxmoxJson.decodeFromString<ApiEnvelope<JsonObject>>(json).data!!

    // Shape of GET /nodes/pve1/qemu/100/config on Proxmox VE 8, trimmed.
    private val vm = VmHardware.fromConfig(
        config(
            """{"data":{"name":"web","sockets":1,"cores":4,"cpu":"x86-64-v2-AES,flags=+aes","memory":"8192","balloon":2048,
            "scsihw":"virtio-scsi-single","boot":"order=scsi0;ide2;net0",
            "scsi0":"local-lvm:vm-100-disk-0,iothread=1,size=32G","virtio1":"nas-nfs:100/vm-100-disk-1.qcow2,size=1536M",
            "ide2":"local:iso/debian-12.7.0-amd64-netinst.iso,media=cdrom,size=631M","ide0":"local-lvm:vm-100-cloudinit,media=cdrom",
            "efidisk0":"local-lvm:vm-100-disk-2,efitype=4m,pre-enrolled-keys=1,size=4M","unused0":"local-lvm:vm-100-disk-3",
            "net0":"virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1","net1":"e1000=BC:24:11:00:00:01,bridge=vmbr1,tag=20,link_down=1,rate=12.5",
            "digest":"d1"}}""",
        ),
        pending = setOf("memory"),
    )

    @Test
    fun `property strings keep order and unknown keys`() {
        val p = PropertyString.parse("local-lvm:vm-100-disk-0,iothread=1,size=32G", "file")
        assertEquals("local-lvm:vm-100-disk-0", p["file"])
        assertTrue(p.isImplicit("file"))
        assertEquals("local-lvm:vm-100-disk-0,iothread=0,size=32G,ssd=1", p.with("iothread", "0").with("ssd", "1").toString())
        assertEquals("local-lvm:vm-100-disk-0,size=32G", p.with("iothread", null).toString())
        assertEquals("none,iothread=1,size=32G", p.withFirst("file", "none", implicit = true).toString())
        assertEquals("", PropertyString.parse(null).toString())
        assertEquals(32L shl 30, DiskSize.parse("32G"))
        assertEquals(1536L shl 20, DiskSize.parse("1536M"))
        assertEquals((1.5 * (1L shl 40)).toLong(), DiskSize.parse("1.5T"))
        assertEquals(4096L, DiskSize.parse("4096"))
        assertNull(DiskSize.parse("lots"))
    }

    @Test
    fun `reads VM hardware`() {
        assertEquals(1, vm.sockets)
        assertEquals(4, vm.cores)
        assertEquals("x86-64-v2-AES", vm.cpuType)
        assertEquals(8192, vm.memoryMiB)
        assertEquals(2048, vm.balloonMiB)
        assertEquals(listOf("scsi0", "virtio1"), vm.hardDisks.map { it.key })
        assertEquals(listOf("ide0", "ide2"), vm.drives.map { it.key })
        assertEquals(VmDisk.Kind.CLOUD_INIT, vm.disk("ide0")!!.kind)
        assertEquals("debian-12.7.0-amd64-netinst.iso", vm.disk("ide2")!!.isoName)
        assertEquals(listOf("efidisk0"), vm.system.map { it.key })
        assertEquals(listOf("unused0"), vm.unused.map { it.key })
        assertEquals("local-lvm", vm.disk("scsi0")!!.storage)
        assertEquals(32L shl 30, vm.disk("scsi0")!!.sizeBytes)
        assertTrue(vm.disk("scsi0")!!.iothread)
        assertEquals(listOf("scsi0", "ide2", "net0"), vm.bootOrder)
        assertEquals(listOf("scsi0", "virtio1", "ide2", "net0", "net1"), vm.bootable)
        assertEquals(setOf("memory"), vm.pending)

        val net1 = vm.nets[1]
        assertEquals("e1000", net1.model)
        assertEquals("BC:24:11:00:00:01", net1.mac)
        assertEquals("vmbr1", net1.bridge)
        assertEquals(20, net1.tag)
        assertTrue(net1.linkDown)
        assertFalse(net1.firewall)
        assertEquals(12.5, net1.rateMBps)

        assertEquals("scsi1", vm.freeSlot(DiskBus.SCSI))
        assertEquals("ide1", vm.freeSlot(DiskBus.IDE))
        assertEquals("net2", vm.freeNetKey())
    }

    @Test
    fun `defaults for a minimal config and the legacy boot order`() {
        val hw = VmHardware.fromConfig(
            config("""{"data":{"memory":"current=2048","boot":"cdn","bootdisk":"sata0","sata0":"local:100/vm-100-disk-0.raw,size=8G",
                "ide2":"none,media=cdrom","net0":"virtio,bridge=vmbr0"}}"""),
        )
        assertEquals(1, hw.sockets)
        assertEquals(1, hw.cores)
        assertNull(hw.cpuType)
        assertEquals(2048, hw.memoryMiB)
        assertNull(hw.balloonMiB)
        assertEquals(listOf("sata0", "ide2", "net0"), hw.bootOrder)
        assertNull(hw.disk("ide2")!!.isoName)
        assertNull(hw.nets[0].mac)
        assertEquals("virtio", hw.nets[0].model)
        assertEquals(listOf("sata0"), BootOrder.parse(null, null, hw.disks.filter { it.key == "sata0" }, emptyList()))
    }

    @Test
    fun `processor and memory changes`() {
        val form = VmCpuMemory.from(vm)
        assertTrue(form.ballooning)
        assertEquals(2048, form.minMemoryMiB)
        assertEquals(emptyMap(), form.changes(vm))

        assertEquals(
            mapOf("cores" to "2", "cpu" to "host,flags=+aes", "memory" to "4096", "digest" to "d1"),
            form.copy(cores = 2, cpuType = "host", memoryMiB = 4096).changes(vm),
        )
        // Minimum = memory is Proxmox's default: the key is removed.
        assertEquals(mapOf("delete" to "balloon", "digest" to "d1"), form.copy(minMemoryMiB = 8192).changes(vm))
        assertEquals(mapOf("balloon" to "0", "digest" to "d1"), form.copy(ballooning = false).changes(vm))
        // Back to the default CPU type keeps the flags.
        assertEquals(mapOf("cpu" to "flags=+aes", "digest" to "d1"), form.copy(cpuType = null).changes(vm))

        assertEquals(VmCpuMemory.Problem.TOO_MANY_CPUS, form.copy(sockets = 2, cores = 16).problem(hostCpus = 16))
        assertNull(form.copy(sockets = 2, cores = 8).problem(hostCpus = 16))
        assertEquals(VmCpuMemory.Problem.MEMORY, form.copy(memoryMiB = 8).problem(null))
        assertEquals(VmCpuMemory.Problem.MIN_MEMORY, form.copy(minMemoryMiB = 9000).problem(null))
        assertEquals(VmCpuMemory.Problem.SOCKETS, form.copy(sockets = 0).problem(null))
    }

    @Test
    fun `disk operations`() {
        val add = NewVmDisk(DiskBus.SCSI, "local-lvm", 64, iothread = true, discard = true, ssd = true)
        assertNull(add.problem(vm, freeBytes = 100L shl 30))
        assertEquals(NewVmDisk.Problem.SIZE, add.problem(vm, freeBytes = 10L shl 30))
        assertEquals(NewVmDisk.Problem.STORAGE, add.copy(storage = "").problem(vm, null))
        assertEquals(mapOf("scsi1" to "local-lvm:64,iothread=1,discard=on,ssd=1", "digest" to "d1"), add.params(vm))
        // VirtIO Block has no SSD emulation; SATA has no IO thread.
        assertEquals("virtio0", add.copy(bus = DiskBus.VIRTIO).params(vm).keys.first())
        assertEquals("local-lvm:64,iothread=1,discard=on", add.copy(bus = DiskBus.VIRTIO).params(vm)["virtio0"])
        assertEquals("local-lvm:64,discard=on,ssd=1", add.copy(bus = DiskBus.SATA).params(vm)["sata0"])

        val scsi0 = vm.disk("scsi0")!!
        assertEquals(mapOf("disk" to "scsi0", "size" to "+10G", "digest" to "d1"), VmDiskOps.resize(scsi0, 10, vm.digest))
        assertEquals(mapOf("disk" to "scsi0", "storage" to "nas-nfs", "delete" to "1"), VmDiskOps.move(scsi0, "nas-nfs", true, null))
        assertEquals(mapOf("delete" to "scsi0", "digest" to "d1"), VmDiskOps.remove(scsi0, vm.digest))
        assertEquals(mapOf("scsi1" to "local-lvm:vm-100-disk-3", "digest" to "d1"), VmDiskOps.attach(vm.unused[0], DiskBus.SCSI, vm))

        val cd = vm.disk("ide2")!!
        assertEquals(mapOf("ide2" to "none,media=cdrom", "digest" to "d1"), VmDiskOps.insert(cd, null, vm.digest))
        assertEquals("local:iso/ubuntu.iso,media=cdrom", VmDiskOps.insert(cd, "local:iso/ubuntu.iso", null)["ide2"])
        assertEquals(mapOf("ide1" to "local:iso/x.iso,media=cdrom", "digest" to "d1"), VmDiskOps.addDrive(vm, "local:iso/x.iso"))
        assertEquals(mapOf("boot" to "order=ide2;scsi0", "digest" to "d1"), VmDiskOps.bootOrder(listOf("ide2", "scsi0"), vm.digest))
    }

    @Test
    fun `network device edits keep the MAC address and unknown settings`() {
        val net0 = vm.nets[0]
        val form = VmNetSettings.from(net0)
        assertEquals(emptyMap(), form.params(vm, net0))
        assertEquals(
            "e1000=BC:24:11:2A:3B:4C,bridge=vmbr1,tag=10",
            form.copy(model = "e1000", bridge = "vmbr1", tag = 10, firewall = false).value(net0),
        )
        val withQueues = vm.nets[0].copy(props = net0.props.with("queues", "4"))
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1,queues=4,rate=50", form.copy(rateMBps = 50.0).value(withQueues))
        assertEquals(mapOf("net2" to "virtio,bridge=vmbr0,firewall=1", "digest" to "d1"), VmNetSettings().params(vm, null))
        assertEquals(VmNetSettings.Problem.TAG, form.copy(tag = 5000).problem())
        assertEquals(VmNetSettings.Problem.BRIDGE, form.copy(bridge = " ").problem())
    }

    // Shape of GET /nodes/pve1/lxc/200/config on Proxmox VE 8.
    private val ct = CtHardware.fromConfig(
        config(
            """{"data":{"hostname":"pihole","cores":2,"memory":1024,"swap":512,
            "rootfs":"local-lvm:vm-200-disk-0,size=8G","mp0":"local-lvm:vm-200-disk-1,mp=/srv/data,backup=1,size=16G",
            "mp1":"/mnt/media,mp=/media","unused0":"local-lvm:vm-200-disk-2",
            "net0":"name=eth0,bridge=vmbr0,firewall=1,gw=192.168.1.1,hwaddr=BC:24:11:AA:BB:CC,ip=192.168.1.53/24,type=veth",
            "digest":"c1"}}""",
        ),
    )

    @Test
    fun `reads container resources`() {
        assertEquals(2, ct.cores)
        assertEquals(1024, ct.memoryMiB)
        assertEquals(512, ct.swapMiB)
        assertEquals("/", ct.rootfs!!.mountPath)
        assertEquals(8L shl 30, ct.rootfs!!.sizeBytes)
        assertEquals(listOf("mp0", "mp1"), ct.mountPoints.map { it.key })
        assertEquals("/srv/data", ct.mountPoints[0].mountPath)
        assertTrue(ct.mountPoints[0].backup)
        assertFalse(ct.mountPoints[1].isResizable) // bind mount
        assertEquals("local-lvm:vm-200-disk-2", ct.unused[0].volume)
        assertEquals("192.168.1.53/24", ct.nets[0].ip)
        assertEquals("mp2", ct.freeMountPoint())
        assertEquals("eth1", ct.suggestNetName())

        val res = CtResources.from(ct)
        assertEquals(emptyMap(), res.changes(ct))
        assertEquals(mapOf("delete" to "cores", "digest" to "c1"), res.copy(cores = null).changes(ct))
        assertEquals(mapOf("memory" to "2048", "swap" to "0", "digest" to "c1"), res.copy(memoryMiB = 2048, swapMiB = 0).changes(ct))
        assertEquals(CtResources.Problem.CORES, res.copy(cores = 9).problem(hostCpus = 8))
    }

    @Test
    fun `container volumes and mount points`() {
        val mp = NewMountPoint("local-lvm", 32, "/var/lib/data", backup = true)
        assertNull(mp.problem(ct, freeBytes = null))
        assertEquals(mapOf("mp2" to "local-lvm:32,mp=/var/lib/data,backup=1", "digest" to "c1"), mp.params(ct))
        assertEquals(NewMountPoint.Problem.PATH_IN_USE, mp.copy(path = "/srv/data/").problem(ct, null))
        assertEquals(NewMountPoint.Problem.PATH, mp.copy(path = "/").problem(ct, null))
        assertEquals(NewMountPoint.Problem.PATH, mp.copy(path = "data").problem(ct, null))
        assertEquals(NewMountPoint.Problem.PATH, mp.copy(path = "/a/../b").problem(ct, null))
        assertEquals(mapOf("disk" to "rootfs", "size" to "+4G", "digest" to "c1"), CtVolumeOps.resize(ct.rootfs!!, 4, ct.digest))
        assertEquals(mapOf("volume" to "mp0", "storage" to "nas-nfs", "delete" to "0"), CtVolumeOps.move(ct.mountPoints[0], "nas-nfs", false, null))
        assertEquals(mapOf("delete" to "unused0"), CtVolumeOps.remove(ct.unused[0], null))
    }

    @Test
    fun `container network edits`() {
        val net0 = ct.nets[0]
        val form = CtNetSettings.from(net0)
        assertEquals(CtNetSettings.Ipv4Mode.STATIC, form.ipv4)
        assertEquals("192.168.1.1", form.gateway)
        assertEquals(CtNetSettings.Ipv6Mode.NONE, form.ipv6)
        assertEquals(emptyMap(), form.params(ct, net0))
        assertEquals(
            "name=eth0,bridge=vmbr0,firewall=1,hwaddr=BC:24:11:AA:BB:CC,ip=dhcp,type=veth,ip6=auto",
            form.copy(ipv4 = CtNetSettings.Ipv4Mode.DHCP, ipv6 = CtNetSettings.Ipv6Mode.SLAAC).value(net0),
        )
        assertEquals(
            mapOf("net1" to "name=eth1,bridge=vmbr0,firewall=1,ip=dhcp,type=veth", "digest" to "c1"),
            CtNetSettings(name = ct.suggestNetName()).params(ct, null),
        )
        assertEquals(CtNetSettings.Problem.NAME_IN_USE, CtNetSettings(name = "eth0").problem(ct, null))
        assertNull(CtNetSettings(name = "eth0").problem(ct, net0))
        assertEquals(CtNetSettings.Problem.IP, form.copy(ip = "192.168.1.300/24").problem(ct, net0))
        assertEquals(CtNetSettings.Problem.IP, form.copy(ip = "192.168.1.5").problem(ct, net0))
        assertEquals(CtNetSettings.Problem.GATEWAY, form.copy(gateway = "gw").problem(ct, net0))
        assertEquals(CtNetSettings.Problem.IP6, form.copy(ipv6 = CtNetSettings.Ipv6Mode.STATIC, ip6 = "fd00::1").problem(ct, net0))
        assertNull(form.copy(ipv6 = CtNetSettings.Ipv6Mode.STATIC, ip6 = "fd00::10/64", gateway6 = "fd00::1").problem(ct, net0))
    }

    @Test
    fun `ip address checks`() {
        listOf("10.0.0.1", "255.255.255.255", "0.0.0.0").forEach { assertTrue(IpAddress.isIpv4(it), it) }
        listOf("10.0.0", "256.1.1.1", "01.2.3.4", "a.b.c.d", "1.2.3.4.5", "").forEach { assertFalse(IpAddress.isIpv4(it), it) }
        listOf("::1", "fd00::1", "2001:db8:0:0:0:0:0:1", "::", "fe80::1:2", "::ffff:192.0.2.1").forEach { assertTrue(IpAddress.isIpv6(it), it) }
        listOf("1::2::3", "2001:db8", "12345::1", "g::1", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9").forEach { assertFalse(IpAddress.isIpv6(it), it) }
        assertTrue(IpAddress.isIpv4Cidr("10.0.0.5/24"))
        assertFalse(IpAddress.isIpv4Cidr("10.0.0.5/33"))
        assertTrue(IpAddress.isIpv6Cidr("2001:db8::5/64"))
        assertFalse(IpAddress.isIpv6Cidr("2001:db8::5/129"))
    }

    @Test
    fun `bridges and pending changes from JSON`() {
        val nets = ProxmoxJson.decodeFromString<ApiEnvelope<List<NodeNetwork>>>(
            """{"data":[{"iface":"vmbr0","type":"bridge","active":1,"bridge_vlan_aware":1,"comments":"LAN\n"},
            {"iface":"eno1","type":"eth","active":1},{"iface":"vmbr1","type":"OVSBridge"}]}""",
        ).data!!
        assertEquals(listOf("vmbr0", "vmbr1"), nets.filter { it.isBridge }.map { it.iface })
        assertTrue(nets[0].vlanAware)

        val pending = ProxmoxJson.decodeFromString<ApiEnvelope<List<PendingEntry>>>(
            """{"data":[{"key":"memory","value":2048,"pending":4096},{"key":"cores","value":2},
            {"key":"net1","value":"virtio,bridge=vmbr1","delete":1},{"key":"digest","value":"x"}]}""",
        ).data!!
        assertEquals(setOf("memory", "net1"), PendingEntry.keys(pending))
    }
}
