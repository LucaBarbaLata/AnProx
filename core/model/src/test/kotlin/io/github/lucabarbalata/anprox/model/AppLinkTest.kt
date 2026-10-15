package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.link.AppLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLinkTest {

    @Test
    fun `links round-trip, including UPIDs and odd names`() {
        val links = listOf(
            AppLink.Dashboard,
            AppLink.Resources,
            AppLink.Tasks,
            AppLink.Alerts,
            AppLink.Guest("3f2c-…", "pve 1", "lxc", 200),
            AppLink.Node("srv", "pve-2"),
            AppLink.Task("srv", "pve1", "UPID:pve1:00001234:0000ABCD:6710F00D:qmstart:100:root@pam:"),
        )
        links.forEach { assertEquals(it, AppLink.parse(it.toUri()), it.toUri()) }
        assertEquals("anprox://guest?server=srv&node=pve1&type=qemu&vmid=100", AppLink.Guest("srv", "pve1", "qemu", 100).toUri())
    }

    @Test
    fun `incomplete or foreign links are ignored`() {
        assertNull(AppLink.parse(null))
        assertNull(AppLink.parse("https://example.com/guest?server=a&node=b&type=qemu&vmid=1"))
        assertNull(AppLink.parse("anprox://guest?server=a&node=b&type=qemu"))
        assertNull(AppLink.parse("anprox://guest?server=a&node=b&type=vm&vmid=1"))
        assertNull(AppLink.parse("anprox://task?server=a&node=b"))
        assertNull(AppLink.parse("anprox://somewhere"))
        assertNull(AppLink.parse("anprox://guest?server=a&node=b&type=qemu&vmid=x"))
        assertNull(AppLink.parse("not a uri at all %%"))
    }
}
