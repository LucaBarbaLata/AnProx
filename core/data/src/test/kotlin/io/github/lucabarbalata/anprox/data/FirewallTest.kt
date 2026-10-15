package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
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

/** Firewall rules and options against the mock (questionnaire Q24). */
class FirewallTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val web = FirewallScope.Guest("pve1", GuestType.QEMU, 100)

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
    fun `rules at every level`() = runBlocking {
        val repo = repo()
        val dc = repo.rules("pve", FirewallScope.Datacenter)
        assertEquals(listOf("SSH", null), dc.map { it.macro })
        assertEquals("8006", dc[1].dport)
        assertEquals(listOf("group", "in"), repo.rules("pve", web).map { it.type })
        assertEquals(emptyList(), repo.rules("pve", FirewallScope.Node("pve2")))
        assertTrue(repo.macros("pve").any { it.macro == "HTTPS" })
        assertEquals(listOf("webserver"), repo.securityGroups("pve").map { it.group })
        Unit
    }

    @Test
    fun `add, edit, toggle, move and delete a rule`() = runBlocking {
        val repo = repo()
        val node = FirewallScope.Node("pve1")
        repo.create("pve", node, FirewallRuleForm(proto = "tcp", dport = "9100", comment = "node exporter"))
        repo.create("pve", node, FirewallRuleForm(macro = "Ping", action = "ACCEPT"))
        var rules = repo.rules("pve", node)
        assertEquals(listOf("Ping", null), rules.map { it.macro }) // new rules go first

        val exporter = rules[1]
        repo.update("pve", node, exporter, FirewallRuleForm.from(exporter).copy(source = "10.0.0.5", comment = ""))
        rules = repo.rules("pve", node)
        assertEquals("10.0.0.5", rules[1].source)
        assertEquals(null, rules[1].comment)

        repo.setEnabled("pve", node, rules[1], false)
        repo.move("pve", node, repo.rules("pve", node)[1], 0)
        rules = repo.rules("pve", node)
        assertEquals(listOf("9100", null), rules.map { it.dport })
        assertEquals(false, rules[0].enable)

        // A stale digest (someone else changed the rules) is refused.
        val stale = rules[0]
        repo.delete("pve", node, rules[1])
        val e = assertFailsWith<ApiException> { repo.delete("pve", node, stale) }
        assertTrue("modified" in e.message.orEmpty(), e.message)
        repo.delete("pve", node, repo.rules("pve", node)[0])
        assertEquals(emptyList(), repo.rules("pve", node))

        assertFailsWith<ApiException> { repo.create("pve", node, FirewallRuleForm(dport = "22")) } // ports without a protocol
        Unit
    }

    @Test
    fun `options`() = runBlocking {
        val repo = repo()
        val options = repo.options("pve", web)
        assertEquals(true, options.flag("enable"))
        assertEquals("DROP", options["policy_in"])
        repo.saveOptions("pve", web, options, mapOf("enable" to "0", "policy_in" to "", "ipfilter" to "1"))
        val saved = repo.options("pve", web)
        assertEquals(false, saved.flag("enable"))
        assertEquals(null, saved["policy_in"])
        assertEquals(true, saved.flag("ipfilter"))
        assertEquals(false, repo.options("pve", FirewallScope.Datacenter).flag("enable"))
        Unit
    }
}
