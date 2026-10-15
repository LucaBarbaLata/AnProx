package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.FirewallOptions
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FirewallTest {

    // Shape of GET /nodes/pve1/qemu/100/firewall/rules on Proxmox VE 8.2, trimmed.
    private val rules = ProxmoxJson.decodeFromString<ApiEnvelope<List<FirewallRule>>>(
        """{"data":[
            {"pos":0,"type":"in","action":"ACCEPT","macro":"SSH","enable":1,"source":"+trusted","log":"nolog","digest":"d1"},
            {"pos":1,"type":"in","action":"ACCEPT","proto":"tcp","dport":443,"enable":1,"comment":"web","digest":"d1"},
            {"pos":2,"type":"group","action":"webserver","enable":0,"digest":"d1"}
        ]}""",
    ).data!!

    @Test
    fun `scopes and rules`() {
        assertEquals("cluster/firewall", FirewallScope.Datacenter.path)
        assertEquals("nodes/pve1/firewall", FirewallScope.Node("pve1").path)
        assertEquals("nodes/pve2/lxc/201/firewall", FirewallScope.Guest("pve2", GuestType.LXC, 201).path)
        assertEquals("SSH", rules[0].macro)
        assertEquals("443", rules[1].dport) // a number in the JSON
        assertEquals(false, rules[2].enable)
    }

    @Test
    fun `rule form`() {
        val form = FirewallRuleForm(proto = "tcp", dport = "80,443", source = "10.0.0.0/24", comment = "web")
        assertNull(form.problem())
        assertEquals(
            mapOf("type" to "in", "action" to "ACCEPT", "enable" to "1", "proto" to "tcp", "source" to "10.0.0.0/24", "dport" to "80,443", "comment" to "web"),
            form.createParams(),
        )
        assertEquals(FirewallRuleForm.Problem.PORTS_NEED_PROTOCOL, FirewallRuleForm(dport = "22").problem())
        assertNull(FirewallRuleForm(dport = "22", macro = "SSH").problem())
        assertEquals(FirewallRuleForm.Problem.PORT, FirewallRuleForm(proto = "tcp", dport = "22 23").problem())
        assertNull(FirewallRuleForm(proto = "udp", dport = "6000:6010,ssh").problem())
        assertEquals(FirewallRuleForm.Problem.GROUP, FirewallRuleForm(type = "group", action = "").problem())

        // Editing the HTTPS rule into a plain SSH macro rule deletes what no longer applies.
        val edited = FirewallRuleForm.from(rules[1]).copy(proto = "", dport = "", macro = "SSH", comment = "")
        assertEquals(
            mapOf("type" to "in", "action" to "ACCEPT", "enable" to "1", "macro" to "SSH", "delete" to "proto,dport,comment", "digest" to "d1"),
            edited.updateParams(rules[1]),
        )
        // A group rule carries only the group.
        assertEquals(mapOf("type" to "group", "action" to "webserver", "enable" to "0"), FirewallRuleForm.from(rules[2]).createParams())
    }

    @Test
    fun `options changes`() {
        val o = FirewallOptions.fromJson(
            ProxmoxJson.decodeFromString<ApiEnvelope<JsonObject>>("""{"data":{"enable":1,"policy_in":"DROP","dhcp":1,"digest":"o1"}}""").data!!,
        )
        assertEquals(true, o.flag("enable"))
        assertEquals(false, o.flag("macfilter"))
        assertEquals(true, o.flag("macfilter", default = true))
        assertEquals(emptyMap(), o.changes(mapOf("enable" to "1", "policy_in" to "DROP")))
        assertEquals(
            mapOf("enable" to "0", "policy_out" to "REJECT", "delete" to "policy_in", "digest" to "o1"),
            o.changes(mapOf("enable" to "0", "policy_in" to "", "policy_out" to "REJECT")),
        )
    }
}
