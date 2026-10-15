package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.FirewallAliasForm
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
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
import kotlin.test.assertTrue

/** Security groups, aliases, IP sets and the firewall log against the mock. */
class FirewallObjectsTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(): FirewallRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return FirewallRepository(hub)
    }

    @Test
    fun `security groups hold their own rules`() = runBlocking {
        val repo = repo()
        val web = FirewallScope.Group("webserver")
        assertEquals(listOf("HTTP", "HTTPS"), repo.rules("pve", web).map { it.macro })
        repo.createGroup("pve", "dns-server", "DNS for the LAN")
        val dns = FirewallScope.Group("dns-server")
        repo.create("pve", dns, FirewallRuleForm(macro = "DNS"))
        assertEquals(listOf("DNS"), repo.rules("pve", dns).map { it.macro })
        // Only an empty group can be deleted.
        assertFailsWith<ApiException> { repo.deleteGroup("pve", "dns-server") }
        repo.delete("pve", dns, repo.rules("pve", dns).single())
        repo.deleteGroup("pve", "dns-server")
        assertEquals(listOf("webserver"), repo.securityGroups("pve").map { it.group })
        Unit
    }

    @Test
    fun `aliases can be added, renamed and deleted`() = runBlocking {
        val repo = repo()
        val dc = FirewallScope.Datacenter
        assertEquals(listOf("admin-net", "nas"), repo.aliases("pve", dc).map { it.name })
        repo.createAlias("pve", dc, FirewallAliasForm("printer", "10.0.0.30", "Office printer"))
        val printer = repo.aliases("pve", dc).single { it.name == "printer" }
        repo.updateAlias("pve", dc, printer, FirewallAliasForm.from(printer).copy(name = "printer2", cidr = "10.0.0.31"))
        val renamed = repo.aliases("pve", dc).single { it.name == "printer2" }
        assertEquals("10.0.0.31", renamed.cidr)
        assertEquals("Office printer", renamed.comment)
        // The old copy's digest is stale.
        assertFailsWith<ApiException> { repo.deleteAlias("pve", dc, printer) }
        repo.deleteAlias("pve", dc, renamed)
        assertTrue(repo.aliases("pve", dc).none { it.name.startsWith("printer") })
        Unit
    }

    @Test
    fun `ip sets with their entries`() = runBlocking {
        val repo = repo()
        val dc = FirewallScope.Datacenter
        val sets = repo.ipSets("pve", dc)
        assertEquals(listOf("blocklist"), sets.map { it.first.name })
        assertEquals(listOf("198.51.100.7", "203.0.113.0/24"), sets.single().second.map { it.cidr })

        repo.createIpSet("pve", dc, "trusted", "VPN clients")
        repo.addIpSetEntry("pve", dc, "trusted", "10.8.0.0/24", "WireGuard", nomatch = false)
        repo.addIpSetEntry("pve", dc, "trusted", "10.8.0.66", "", nomatch = true)
        val trusted = repo.ipSets("pve", dc).single { it.first.name == "trusted" }.second
        assertEquals(listOf(false, true), trusted.map { it.nomatch })
        // A CIDR with a slash travels path-encoded.
        repo.removeIpSetEntry("pve", dc, "trusted", trusted.first { it.cidr == "10.8.0.0/24" })
        assertEquals(listOf("10.8.0.66"), repo.ipSets("pve", dc).single { it.first.name == "trusted" }.second.map { it.cidr })
        repo.deleteIpSet("pve", dc, "trusted")
        assertEquals(listOf("blocklist"), repo.ipSets("pve", dc).map { it.first.name })

        val guest = FirewallScope.Guest("pve1", GuestType.QEMU, 100)
        assertEquals(listOf("ipfilter-net0"), repo.ipSets("pve", guest).map { it.first.name })
        Unit
    }

    @Test
    fun `the log shows its newest lines`() = runBlocking {
        val repo = repo()
        val lines = repo.log("pve", FirewallScope.Node("pve1"), lines = 100)
        assertEquals(100, lines.size)
        assertEquals(640, lines.last().n)
        assertTrue(lines.all { it.t.contains("DST=10.0.0.11") })
        Unit
    }
}
