package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.CloudInit
import io.github.lucabarbalata.anprox.model.pve.CloudInitForm
import io.github.lucabarbalata.anprox.model.pve.CloudInitIp
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudInitTest {

    private fun config(json: String) = ProxmoxJson.decodeFromString<ApiEnvelope<JsonObject>>(json).data!!

    private val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGq3l5h6W2y1t5j0Yb2c3d4e5f6g7h8i9j0k1l2m3n4+/= me@laptop"

    // Shape of GET /nodes/pve1/qemu/103/config on Proxmox VE 8.2 for a cloud-init VM, trimmed.
    private val json = """{"data":{"name":"k8s","ide0":"local-lvm:vm-103-cloudinit,media=cdrom","scsi0":"local-lvm:vm-103-disk-0,size=32G",
        "net0":"virtio=BC:24:11:00:00:01,bridge=vmbr0","net1":"virtio=BC:24:11:00:00:02,bridge=vmbr1",
        "ciuser":"ubuntu","cipassword":"**********",
        "sshkeys":"ssh-ed25519%20AAAAC3NzaC1lZDI1NTE5AAAAIGq3l5h6W2y1t5j0Yb2c3d4e5f6g7h8i9j0k1l2m3n4%2B%2F%3D%20me%40laptop%0A",
        "ipconfig0":"ip=10.0.0.103/24,gw=10.0.0.1,ip6=auto","nameserver":"1.1.1.1 9.9.9.9","searchdomain":"lab","ciupgrade":0,"digest":"ci1"}}"""

    @Test
    fun `reads cloud-init settings`() {
        val ci = CloudInit.fromConfig(config(json))
        assertEquals("ide0", ci.drive)
        assertEquals("ubuntu", ci.user)
        assertTrue(ci.passwordSet)
        assertEquals(key, ci.sshKeys)
        assertEquals(setOf(0), ci.ipconfigs.keys)
        val ip = ci.ipconfigs.getValue(0)
        assertEquals(CtNetSettings.Ipv4Mode.STATIC, ip.ipv4)
        assertEquals("10.0.0.103/24", ip.ip)
        assertEquals("10.0.0.1", ip.gateway)
        assertEquals(CtNetSettings.Ipv6Mode.SLAAC, ip.ipv6)
        assertEquals("1.1.1.1 9.9.9.9", ci.nameserver)
        assertEquals(false, ci.upgrade)

        val none = CloudInit.fromConfig(config("""{"data":{"scsi0":"local-lvm:vm-1-disk-0,size=8G","digest":"d"}}"""))
        assertNull(none.drive)
        assertNull(none.upgrade)
        assertEquals(false, none.passwordSet)
    }

    @Test
    fun `ssh keys round-trip through Proxmox's URL encoding`() {
        val two = "$key\nssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ== work"
        assertEquals(two, CloudInit.decodeKeys(CloudInit.encodeKeys("  $key \n\n ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ== work")))
        assertTrue(" " !in CloudInit.encodeKeys(key) && "+" !in CloudInit.encodeKeys(key).replace("%2B", ""))
    }

    @Test
    fun `form changes`() {
        val ci = CloudInit.fromConfig(config(json))
        val hw = VmHardware.fromConfig(config(json))
        val form = CloudInitForm.from(ci, hw.nets)
        // net1 has no ipconfig yet: it shows as "none".
        assertEquals(CtNetSettings.Ipv4Mode.NONE, form.ipconfigs.getValue(1).ipv4)
        assertEquals(emptyMap(), form.changes(ci))
        assertNull(form.problem())

        val changed = form.copy(
            user = "admin",
            password = "s3cret",
            ipconfigs = form.ipconfigs + (1 to CloudInitIp()) + (0 to CloudInitIp(ipv4 = CtNetSettings.Ipv4Mode.DHCP)),
            nameserver = "",
            upgrade = true,
        )
        assertEquals(
            mapOf(
                "ciuser" to "admin",
                "cipassword" to "s3cret",
                "ipconfig0" to "ip=dhcp",
                "ipconfig1" to "ip=dhcp",
                "ciupgrade" to "1",
                "delete" to "nameserver",
                "digest" to "ci1",
            ),
            changed.changes(ci),
        )
        assertEquals(mapOf("delete" to "cipassword,sshkeys", "digest" to "ci1"), form.copy(clearPassword = true, sshKeys = " ").changes(ci))
        assertEquals("ssh-ed25519%20AAAA%20x", form.copy(sshKeys = "ssh-ed25519 AAAA x").changes(ci)["sshkeys"])

        assertEquals(CloudInitForm.Problem.USER, form.copy(user = "Bad User").problem())
        assertEquals(CloudInitForm.Problem.SSH_KEY, form.copy(sshKeys = "nope").problem())
        assertEquals(CloudInitForm.Problem.DNS, form.copy(nameserver = "dns.example").problem())
        val badIp = form.copy(ipconfigs = form.ipconfigs + (1 to CloudInitIp(ipv4 = CtNetSettings.Ipv4Mode.STATIC, ip = "10.0.0.9")))
        assertEquals(CloudInitForm.Problem.IP, badIp.problem())
        assertEquals(1, badIp.badIp)
    }

    @Test
    fun `adding a cloud-init drive`() {
        val hw = VmHardware.fromConfig(config("""{"data":{"scsi0":"local-lvm:vm-1-disk-0,size=8G","ide2":"none,media=cdrom","digest":"d"}}"""))
        assertEquals(mapOf("ide0" to "local-lvm:cloudinit", "digest" to "d"), CloudInitForm.addDrive(hw, "local-lvm"))
        assertEquals("ip=10.0.0.5/24,gw=10.0.0.1,ip6=fd00::5/64", CloudInitIp(CtNetSettings.Ipv4Mode.STATIC, "10.0.0.5/24", "10.0.0.1", CtNetSettings.Ipv6Mode.STATIC, "fd00::5/64").value())
        assertNull(CloudInitIp(ipv4 = CtNetSettings.Ipv4Mode.NONE).value())
    }
}
