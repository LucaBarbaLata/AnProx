package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.GuestOptions
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.StartupOrder
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestOptionsTest {

    private fun config(json: String) = ProxmoxJson.decodeFromString<ApiEnvelope<JsonObject>>(json).data!!

    @Test
    fun `reads VM options including a property-string agent`() {
        // Shape of GET /nodes/pve1/qemu/100/config (PVE 8), trimmed.
        val o = GuestOptions.fromConfig(
            GuestType.QEMU,
            config("""{"data":{"name":"web","onboot":1,"protection":0,"description":"Front end\nnginx","tags":"prod;web",
                "agent":"enabled=1,fstrim_cloned_disks=1","cores":4,"memory":"8192","digest":"abc123"}}"""),
        )
        assertEquals("web", o.name)
        assertTrue(o.onBoot)
        assertEquals(false, o.protection)
        assertEquals("Front end\nnginx", o.description)
        assertEquals(listOf("prod", "web"), o.tags)
        assertEquals(true, o.agent)
        assertEquals("abc123", o.digest)

        assertEquals(true, GuestOptions.fromConfig(GuestType.QEMU, config("""{"data":{"agent":"1"}}""")).agent)
        assertEquals(false, GuestOptions.fromConfig(GuestType.QEMU, config("""{"data":{"agent":"0,fstrim_cloned_disks=1"}}""")).agent)
        assertEquals(false, GuestOptions.fromConfig(GuestType.QEMU, config("""{"data":{}}""")).agent)
    }

    @Test
    fun `containers use hostname and have no agent`() {
        val o = GuestOptions.fromConfig(GuestType.LXC, config("""{"data":{"hostname":"pihole","onboot":"1","digest":"d"}}"""))
        assertEquals("pihole", o.name)
        assertTrue(o.onBoot)
        assertNull(o.agent)
    }

    @Test
    fun `changes contain only what changed, deletions and the digest`() {
        val old = GuestOptions(name = "web", description = "old notes", tags = listOf("prod"), agent = false, digest = "d1")
        assertEquals(emptyMap(), GuestOptions.changes(GuestType.QEMU, old, old))

        val new = old.copy(name = "web-01", description = "", tags = listOf("prod", "web"), onBoot = true, agent = true)
        assertEquals(
            mapOf("name" to "web-01", "tags" to "prod;web", "onboot" to "1", "agent" to "1", "delete" to "description", "digest" to "d1"),
            GuestOptions.changes(GuestType.QEMU, old, new),
        )
        assertEquals(
            mapOf("hostname" to "ct-01", "digest" to "d1"),
            GuestOptions.changes(GuestType.LXC, old.copy(agent = null), old.copy(name = "ct-01", agent = null)),
        )
    }

    @Test
    fun `start order reads both spellings and is written only when changed`() {
        assertEquals(StartupOrder(order = 2, up = 30, down = 60), StartupOrder.parse("order=2,up=30,down=60"))
        assertEquals(StartupOrder(order = 3, up = 10), StartupOrder.parse("3,up=10"))
        assertEquals(StartupOrder(down = 120), StartupOrder.parse("down=120,bogus=1"))
        assertTrue(StartupOrder.parse(null).isDefault)
        assertEquals("order=1,down=90", StartupOrder(order = 1, down = 90).toApi())

        val old = GuestOptions(name = "db", startup = StartupOrder(order = 1, up = 30), digest = "d1")
        assertEquals(
            mapOf("startup" to "order=1,up=45", "digest" to "d1"),
            GuestOptions.changes(GuestType.QEMU, old, old.copy(startup = StartupOrder(order = 1, up = 45))),
        )
        assertEquals(
            mapOf("delete" to "startup", "digest" to "d1"),
            GuestOptions.changes(GuestType.QEMU, old, old.copy(startup = StartupOrder())),
        )
    }

    @Test
    fun `names and tags follow Proxmox rules`() {
        assertNull(GuestOptions(name = "web-01.lab").problem())
        assertEquals(GuestOptions.Problem.NAME, GuestOptions(name = "web_01").problem())
        assertEquals(GuestOptions.Problem.NAME, GuestOptions(name = "-web").problem())
        assertEquals(GuestOptions.Problem.TAG, GuestOptions(name = "ok", tags = listOf("a/b")).problem())
        assertEquals(listOf("prod", "web", "db"), GuestOptions.parseTags("prod; web,db  web"))
    }
}
