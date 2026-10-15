package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.FirewallAlias
import io.github.lucabarbalata.anprox.model.pve.FirewallAliasForm
import io.github.lucabarbalata.anprox.model.pve.FirewallNames
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirewallObjectsTest {

    @Test
    fun `scopes know their paths and what they have`() {
        assertEquals("cluster/firewall/rules", FirewallScope.Datacenter.rulesPath)
        assertEquals("cluster/firewall/groups/web", FirewallScope.Group("web").rulesPath)
        assertFalse(FirewallScope.Group("web").hasOptions)
        assertTrue(FirewallScope.Datacenter.hasAliases)
        assertFalse(FirewallScope.Node("pve1").hasAliases)
        assertTrue(FirewallScope.Node("pve1").hasLog)
        assertTrue(FirewallScope.Guest("pve1", GuestType.LXC, 200).hasLog)
        assertFalse(FirewallScope.Datacenter.hasLog)
    }

    @Test
    fun `names and addresses`() {
        assertTrue(FirewallNames.isValidName("admin-net"))
        assertFalse(FirewallNames.isValidName("1net"))
        assertFalse(FirewallNames.isValidName("a"))
        assertTrue(FirewallNames.isAddress("10.0.0.0/24"))
        assertTrue(FirewallNames.isAddress("fd00::/64"))
        assertFalse(FirewallNames.isAddress("10.0.0.0/33"))
        assertTrue(FirewallNames.isIpSetEntry("dc/admin-net"))
        assertFalse(FirewallNames.isIpSetEntry("not an address"))
    }

    @Test
    fun `alias form`() {
        assertNull(FirewallAliasForm("nas", "10.0.0.50").problem())
        assertEquals(FirewallAliasForm.Problem.NAME, FirewallAliasForm("n", "10.0.0.50").problem())
        assertEquals(FirewallAliasForm.Problem.ADDRESS, FirewallAliasForm("nas", "nas.lan").problem())
        assertEquals(mapOf("name" to "nas", "cidr" to "10.0.0.50"), FirewallAliasForm(" nas", "10.0.0.50 ").createParams())
        val original = FirewallAlias("nas", "10.0.0.50", "Storage", "d1")
        assertEquals(
            mapOf("cidr" to "10.0.0.51", "comment" to "", "rename" to "nas2", "digest" to "d1"),
            FirewallAliasForm("nas2", "10.0.0.51", "").updateParams(original),
        )
    }
}
