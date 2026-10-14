package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.CloneRequest
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.MigrationCheck
import io.github.lucabarbalata.anprox.model.pve.MigrationPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloneMigrateTest {

    @Test
    fun `clone form sends only what Proxmox needs`() {
        val vm = CloneRequest(newId = 110, name = " web-clone ", targetNode = "pve1", full = true, storage = "local-zfs")
        assertEquals(
            mapOf("newid" to "110", "name" to "web-clone", "full" to "1", "storage" to "local-zfs"),
            vm.params(GuestType.QEMU, sourceNode = "pve1"), // same node: no target
        )
        val linkedCt = CloneRequest(newId = 210, name = "", targetNode = "pve2", full = false, storage = "local-zfs")
        assertEquals(
            mapOf("newid" to "210", "target" to "pve2", "full" to "0"), // no name; linked clones keep the storage
            linkedCt.params(GuestType.LXC, sourceNode = "pve1"),
        )
        assertEquals("hostname", CloneRequest(211, "ct").params(GuestType.LXC, "pve1").keys.elementAt(1))
    }

    @Test
    fun `clone problems`() {
        assertEquals(CloneRequest.Problem.ID, CloneRequest(99, "a").problem(isTemplate = false))
        assertEquals(CloneRequest.Problem.NAME, CloneRequest(120, "web server").problem(isTemplate = false))
        assertEquals(CloneRequest.Problem.LINKED_NEEDS_TEMPLATE, CloneRequest(120, "a", full = false).problem(isTemplate = false))
        assertNull(CloneRequest(120, "a", full = false).problem(isTemplate = true))
        assertNull(CloneRequest(120, "").problem(isTemplate = false)) // Proxmox picks "Copy-of-VM-…"
        assertEquals("web-clone", CloneRequest.suggestName("web"))
        assertEquals("", CloneRequest.suggestName("my vm"))
    }

    @Test
    fun `migration targets and parameters`() {
        // Shape of GET /nodes/pve1/qemu/101/migrate for a stopped VM on PVE 8.
        val check = ProxmoxJson.decodeFromString<ApiEnvelope<MigrationCheck>>(
            """{"data":{"running":0,"allowed_nodes":["pve2"],
                "not_allowed_nodes":{"pve4":{"unavailable_storages":["local-zfs"]}},
                "local_disks":[{"volid":"local-zfs:vm-101-disk-0","drivename":"scsi0","size":274877906944,"is_unused":0,"cdrom":0},
                               {"volid":"local:iso/debian.iso","drivename":"ide2","cdrom":1},
                               {"volid":"local-zfs:vm-101-disk-1","is_unused":1}],
                "local_resources":[],"mapped-resources":[]}}""",
        ).data!!
        assertFalse(check.running)
        assertEquals(listOf("local-zfs:vm-101-disk-0"), check.disksToCopy.map { it.volid })

        val targets = MigrationPlan.targets("pve1", mapOf("pve1" to true, "pve2" to true, "pve3" to false, "pve4" to true), check)
        assertEquals(listOf("pve2", "pve3", "pve4"), targets.map { it.node })
        assertTrue(targets[0].allowed)
        assertFalse(targets[1].allowed) // offline
        assertEquals(listOf("local-zfs"), targets[2].missing)

        assertEquals(mapOf("target" to "pve2"), MigrationPlan.params(GuestType.QEMU, "pve2", running = false, check = check))
        assertEquals(
            mapOf("target" to "pve2", "online" to "1", "with-local-disks" to "1"),
            MigrationPlan.params(GuestType.QEMU, "pve2", running = true, check = check),
        )
        assertEquals(
            mapOf("target" to "pve2", "restart" to "1", "timeout" to "180"),
            MigrationPlan.params(GuestType.LXC, "pve2", running = true, check = null),
        )
    }
}
