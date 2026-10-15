package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.DiskBus
import io.github.lucabarbalata.anprox.model.pve.GuestOs
import io.github.lucabarbalata.anprox.model.pve.NewContainer
import io.github.lucabarbalata.anprox.model.pve.NewVm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestCreationTest {

    private val taken = setOf(100, 101, 200)

    @Test
    fun `a Linux VM with the Proxmox VE 8 defaults`() {
        val vm = NewVm(vmid = 105, name = "web2", iso = "local:iso/debian-12.iso", storage = "local-lvm", diskGiB = 20, start = true)
        assertNull(vm.problem(taken, hostCpus = 16, freeBytes = 100L shl 30))
        assertEquals(
            mapOf(
                "vmid" to "105",
                "name" to "web2",
                "ostype" to "l26",
                "sockets" to "1",
                "cores" to "2",
                "cpu" to "x86-64-v2-AES",
                "memory" to "2048",
                "scsihw" to "virtio-scsi-single",
                "scsi0" to "local-lvm:20,iothread=1,discard=on",
                "ide2" to "local:iso/debian-12.iso,media=cdrom",
                "net0" to "virtio,bridge=vmbr0,firewall=1",
                "boot" to "order=scsi0;ide2;net0",
                "agent" to "1",
                "start" to "1",
            ),
            vm.params(),
        )
        assertEquals(listOf("vmid", "name", "ostype"), vm.params().keys.take(3))
    }

    @Test
    fun `Windows gets SATA, e1000e, UEFI and a TPM unless Advanced says otherwise`() {
        val win = NewVm(vmid = 106, name = "win", os = GuestOs.WINDOWS, storage = "local-lvm", diskGiB = 64, memoryMiB = 8192, ballooning = false)
        assertEquals(DiskBus.SATA, win.diskBus)
        val p = win.params()
        assertEquals("local-lvm:64,discard=on", p["sata0"])
        assertEquals("none,media=cdrom", p["ide2"])
        assertEquals("e1000e,bridge=vmbr0,firewall=1", p["net0"])
        assertEquals("order=sata0;ide2;net0", p["boot"])
        assertEquals("ovmf", p["bios"])
        assertEquals("q35", p["machine"])
        assertEquals("local-lvm:1,efitype=4m,pre-enrolled-keys=1", p["efidisk0"])
        assertEquals("local-lvm:1,version=v2.0", p["tpmstate0"])
        assertEquals("0", p["balloon"])
        assertEquals("win11", p["ostype"])

        val tuned = win.copy(bus = DiskBus.SCSI, netModel = "virtio", vlan = 30, firewall = false).params()
        assertEquals("local-lvm:64,iothread=1,discard=on", tuned["scsi0"])
        assertEquals("virtio,bridge=vmbr0,tag=30", tuned["net0"])
        assertFalse("sata0" in tuned)

        assertFalse("bios" in NewVm(vmid = 107, storage = "s").params())
        assertTrue("bios" in NewVm(vmid = 107, storage = "s", uefi = true).params())
    }

    @Test
    fun `VM problems`() {
        val vm = NewVm(vmid = 105, storage = "local-lvm")
        assertEquals(NewVm.Problem.ID_TAKEN, vm.copy(vmid = 100).problem(taken, null, null))
        assertEquals(NewVm.Problem.ID, vm.copy(vmid = 99).problem(taken, null, null))
        assertEquals(NewVm.Problem.NAME, vm.copy(name = "bad name").problem(taken, null, null))
        assertEquals(NewVm.Problem.STORAGE, vm.copy(storage = "").problem(taken, null, null))
        assertEquals(NewVm.Problem.SIZE, vm.copy(diskGiB = 64).problem(taken, null, freeBytes = 10L shl 30))
        assertEquals(NewVm.Problem.TOO_MANY_CPUS, vm.copy(cores = 8, sockets = 2).problem(taken, hostCpus = 8, freeBytes = null))
        assertEquals(NewVm.Problem.MEMORY, vm.copy(memoryMiB = 8).problem(taken, null, null))
        assertEquals(NewVm.Problem.VLAN, vm.copy(vlan = 0).problem(taken, null, null))
        assertNull(vm.problem(taken, null, null))
    }

    @Test
    fun `a container with a password, a key and a static address`() {
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGq3l5h6W2y1t5j0Yb2c3d4e5f6g7h8i9j0k1l2m3n4 me@laptop"
        val ct = NewContainer(
            vmid = 210,
            hostname = "dns",
            template = "local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst",
            password = "secret1",
            sshKeys = "\n$key\n\n",
            storage = "local-zfs",
            diskGiB = 4,
            network = CtNetSettings(ipv4 = CtNetSettings.Ipv4Mode.STATIC, ip = "10.0.0.53/24", gateway = "10.0.0.1"),
            start = true,
        )
        assertNull(ct.problem(taken, hostCpus = 8, freeBytes = null))
        assertEquals(
            mapOf(
                "vmid" to "210",
                "hostname" to "dns",
                "ostemplate" to "local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst",
                "password" to "secret1",
                "ssh-public-keys" to key,
                "rootfs" to "local-zfs:4",
                "cores" to "1",
                "memory" to "512",
                "swap" to "512",
                "net0" to "name=eth0,bridge=vmbr0,firewall=1,ip=10.0.0.53/24,gw=10.0.0.1,type=veth",
                "unprivileged" to "1",
                "features" to "nesting=1",
                "start" to "1",
            ),
            ct.params(),
        )
    }

    @Test
    fun `container problems and template names`() {
        val ct = NewContainer(vmid = 210, template = "local:vztmpl/alpine.tar.xz", password = "secret1", storage = "local-zfs")
        assertNull(ct.problem(taken, null, null))
        assertEquals(NewContainer.Problem.ID_TAKEN, ct.copy(vmid = 200).problem(taken, null, null))
        assertEquals(NewContainer.Problem.HOSTNAME, ct.copy(hostname = "-bad").problem(taken, null, null))
        assertEquals(NewContainer.Problem.TEMPLATE, ct.copy(template = "").problem(taken, null, null))
        assertEquals(NewContainer.Problem.PASSWORD, ct.copy(password = "1234").problem(taken, null, null))
        assertEquals(NewContainer.Problem.LOGIN, ct.copy(password = "").problem(taken, null, null))
        assertEquals(NewContainer.Problem.SSH_KEY, ct.copy(sshKeys = "not a key").problem(taken, null, null))
        assertNull(ct.copy(password = "", sshKeys = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY=").problem(taken, null, null))
        assertEquals(NewContainer.Problem.CORES, ct.copy(cores = 9).problem(taken, hostCpus = 8, freeBytes = null))
        assertEquals(NewContainer.Problem.NETWORK, ct.copy(network = CtNetSettings(ipv4 = CtNetSettings.Ipv4Mode.STATIC, ip = "10.0.0.5")).problem(taken, null, null))
        assertFalse("features" in ct.copy(nesting = false).params())
        assertEquals("0", ct.copy(unprivileged = false).params()["unprivileged"])
        assertFalse("password" in ct.copy(password = "", sshKeys = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQAB").params())

        assertEquals("debian-12-standard_12.7-1_amd64", NewContainer.templateName("local:vztmpl/debian-12-standard_12.7-1_amd64.tar.zst"))
        assertEquals("alpine-3.20-default_20240908_amd64", NewContainer.templateName("nas:vztmpl/alpine-3.20-default_20240908_amd64.tar.xz"))
        assertEquals("archlinux-base_20240911-1_amd64", NewContainer.templateName("local:vztmpl/archlinux-base_20240911-1_amd64.tar.gz"))
    }
}
