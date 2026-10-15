package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.FirewallMacro
import io.github.lucabarbalata.anprox.model.pve.FirewallOptions
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup

/**
 * Firewall rules and options for the datacenter, a node or a guest (questionnaire Q24). Every
 * change sends the digest Proxmox gave with the rules, so an edit made elsewhere in the meantime
 * is refused instead of overwritten.
 */
class FirewallRepository(private val hub: ServerHub) {

    suspend fun rules(serverId: String, scope: FirewallScope): List<FirewallRule> =
        hub.use(serverId) { it.pve { firewallRules(scope.path) } }.sortedBy { it.pos }

    suspend fun create(serverId: String, scope: FirewallScope, form: FirewallRuleForm) {
        hub.use(serverId) { it.pveNoData { createFirewallRule(scope.path, form.createParams()) } }
    }

    suspend fun update(serverId: String, scope: FirewallScope, rule: FirewallRule, form: FirewallRuleForm) {
        hub.use(serverId) { it.pveNoData { updateFirewallRule(scope.path, rule.pos, form.updateParams(rule)) } }
    }

    suspend fun setEnabled(serverId: String, scope: FirewallScope, rule: FirewallRule, enabled: Boolean) {
        val params = buildMap {
            put("enable", if (enabled) "1" else "0")
            rule.digest?.let { put("digest", it) }
        }
        hub.use(serverId) { it.pveNoData { updateFirewallRule(scope.path, rule.pos, params) } }
    }

    /** Moves [rule] to position [to] (0 is applied first). */
    suspend fun move(serverId: String, scope: FirewallScope, rule: FirewallRule, to: Int) {
        val params = buildMap {
            put("moveto", to.toString())
            rule.digest?.let { put("digest", it) }
        }
        hub.use(serverId) { it.pveNoData { updateFirewallRule(scope.path, rule.pos, params) } }
    }

    suspend fun delete(serverId: String, scope: FirewallScope, rule: FirewallRule) {
        hub.use(serverId) { it.pveNoData { deleteFirewallRule(scope.path, rule.pos, rule.digest) } }
    }

    suspend fun options(serverId: String, scope: FirewallScope): FirewallOptions =
        FirewallOptions.fromJson(hub.use(serverId) { it.pve { firewallOptions(scope.path) } })

    /** Saves the options that differ from [original]; nothing is sent when nothing changed. */
    suspend fun saveOptions(serverId: String, scope: FirewallScope, original: FirewallOptions, edited: Map<String, String>) {
        val params = original.changes(edited)
        if (params.isEmpty()) return
        hub.use(serverId) { it.pveNoData { updateFirewallOptions(scope.path, params) } }
    }

    suspend fun macros(serverId: String): List<FirewallMacro> =
        hub.use(serverId) { it.pve { firewallMacros() } }.sortedBy { it.macro.lowercase() }

    suspend fun securityGroups(serverId: String): List<SecurityGroup> =
        hub.use(serverId) { it.pve { securityGroups() } }.sortedBy { it.group }
}
