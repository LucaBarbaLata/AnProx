package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.SnapshotName
import io.github.lucabarbalata.anprox.model.pve.SnapshotTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotTest {

    @Test
    fun `parses a VM snapshot list including the current pseudo-entry`() {
        // Shape of GET /nodes/pve1/qemu/100/snapshot on PVE 8/9.
        val json = """
            {"data":[
              {"name":"before-upgrade","description":"Before apt full-upgrade\n","snaptime":1728900000,"vmstate":1},
              {"name":"after-upgrade","parent":"before-upgrade","snaptime":1728990000,"description":""},
              {"name":"current","parent":"after-upgrade","description":"You are here!","running":1,"digest":"abc"}
            ]}
        """.trimIndent()
        val list = ProxmoxJson.decodeFromString<ApiEnvelope<List<GuestSnapshot>>>(json).data!!

        assertEquals(3, list.size)
        assertTrue(list[0].vmstate)
        assertNull(list[0].parent)
        assertFalse(list[1].vmstate)
        assertTrue(list[2].isCurrent)
        assertTrue(list[2].running)
        assertNull(list[2].snaptime)
    }

    @Test
    fun `tree is depth-first, oldest first, with current last among its siblings`() {
        val list = listOf(
            GuestSnapshot("current", parent = "a"),
            GuestSnapshot("b", parent = "a", snaptime = 300),
            GuestSnapshot("a", snaptime = 100),
            GuestSnapshot("c", parent = "b", snaptime = 400),
            GuestSnapshot("z", snaptime = 50),
        )
        val rows = SnapshotTree.flatten(list).map { it.snapshot.name to it.depth }
        assertEquals(listOf("z" to 0, "a" to 0, "b" to 1, "c" to 2, "current" to 1), rows)
    }

    @Test
    fun `missing parents become roots and cycles do not hang`() {
        val list = listOf(
            GuestSnapshot("orphan", parent = "deleted", snaptime = 10),
            GuestSnapshot("x", parent = "y", snaptime = 20),
            GuestSnapshot("y", parent = "x", snaptime = 30),
        )
        val rows = SnapshotTree.flatten(list)
        assertEquals(3, rows.size)
        assertEquals("orphan" to 0, rows[0].snapshot.name to rows[0].depth)
    }

    @Test
    fun `snapshot names follow Proxmox rules`() {
        assertNull(SnapshotName.validate("before-upgrade"))
        assertNull(SnapshotName.validate("Snap_2"))
        assertEquals(SnapshotName.Problem.EMPTY, SnapshotName.validate(""))
        assertEquals(SnapshotName.Problem.FORMAT, SnapshotName.validate("a"))
        assertEquals(SnapshotName.Problem.FORMAT, SnapshotName.validate("1st"))
        assertEquals(SnapshotName.Problem.FORMAT, SnapshotName.validate("has space"))
        assertEquals(SnapshotName.Problem.FORMAT, SnapshotName.validate("dot.ted"))
        assertEquals(SnapshotName.Problem.TOO_LONG, SnapshotName.validate("a".repeat(41)))
        assertEquals(SnapshotName.Problem.RESERVED, SnapshotName.validate("Current"))
        assertEquals(SnapshotName.Problem.DUPLICATE, SnapshotName.validate("daily", listOf("daily")))
    }

    @Test
    fun `suggested names are valid`() {
        val name = SnapshotName.suggest(2026, 10, 4, 9, 5)
        assertEquals("snap-20261004-0905", name)
        assertNull(SnapshotName.validate(name))
    }
}
