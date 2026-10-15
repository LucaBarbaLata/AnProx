package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.FirewallAlias
import io.github.lucabarbalata.anprox.model.pve.FirewallAliasForm
import io.github.lucabarbalata.anprox.model.pve.FirewallIpSet
import io.github.lucabarbalata.anprox.model.pve.FirewallMacro
import io.github.lucabarbalata.anprox.model.pve.FirewallOptions
import io.github.lucabarbalata.anprox.model.pve.FirewallRule
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.IpSetEntry
import io.github.lucabarbalata.anprox.model.pve.SecurityGroup
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Firewall rules and options for the datacenter, a node or a guest (questionnaire Q24). Every
 * change sends the digest Proxmox gave with the rules, so an edit made elsewhere in the meantime
 * is refused instead of overwritten.
 */
class FirewallRepository(private val hub: ServerHub) {

    suspend fun rules(serverId: String, scope: FirewallScope): List<FirewallRule> =
        hub.use(serverId) { it.pve { firewallRules(scope.rulesPath) } }.sortedBy { it.pos }

    suspend fun create(serverId: String, scope: FirewallScope, form: FirewallRuleForm) {
        hub.use(serverId) { it.pveNoData { createFirewallRule(scope.rulesPath, form.createParams()) } }
    }

    suspend fun update(serverId: String, scope: FirewallScope, rule: FirewallRule, form: FirewallRuleForm) {
        hub.use(serverId) { it.pveNoData { updateFirewallRule(scope.rulesPath, rule.pos, form.updateParams(rule)) } }
    }

    suspend fun setEnabled(serverId: String, scope: FirewallScope, rule: FirewallRule, enabled: Boolean) {
        val params = buildMap {
            put("enable", if (enabled) "1" else "0")
            rule.digest?.let { put("digest", it) }
        }
        hub.use(serverId) { it.pveNoData { updateFirewallRule(scope.rulesPath, rule.pos, params) } }
    }

    /** Moves [rule] to position [to] (0 is applied first). */
    suspend fun move(serverId: String, scope: FirewallScope, rule: FirewallRule, to: Int) {
        val params = buildMap {
            put("moveto", to.toString())
            rule.digest?.let { put("digest", it) }
        }
        hub.use(serverId) { it.pveNoData { updateFirewallRule(scope.rulesPath, rule.pos, params) } }
    }

    suspend fun delete(serverId: String, scope: FirewallScope, rule: FirewallRule) {
        hub.use(serverId) { it.pveNoData { deleteFirewallRule(scope.rulesPath, rule.pos, rule.digest) } }
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

    // ------------------------------------------------------------------ security groups

    suspend fun createGroup(serverId: String, name: String, comment: String) {
        val params = buildMap {
            put("group", name.trim())
            comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        }
        hub.use(serverId) { it.pveNoData { createSecurityGroup(params) } }
    }

    /** Proxmox refuses while the group still has rules. */
    suspend fun deleteGroup(serverId: String, name: String) {
        hub.use(serverId) { it.pveNoData { deleteSecurityGroup(name) } }
    }

    // ------------------------------------------------------------------ aliases

    suspend fun aliases(serverId: String, scope: FirewallScope): List<FirewallAlias> =
        hub.use(serverId) { it.pve { firewallAliases(scope.path) } }.sortedBy { it.name.lowercase() }

    suspend fun createAlias(serverId: String, scope: FirewallScope, form: FirewallAliasForm) {
        hub.use(serverId) { it.pveNoData { createFirewallAlias(scope.path, form.createParams()) } }
    }

    suspend fun updateAlias(serverId: String, scope: FirewallScope, alias: FirewallAlias, form: FirewallAliasForm) {
        hub.use(serverId) { it.pveNoData { updateFirewallAlias(scope.path, alias.name, form.updateParams(alias)) } }
    }

    suspend fun deleteAlias(serverId: String, scope: FirewallScope, alias: FirewallAlias) {
        hub.use(serverId) { it.pveNoData { deleteFirewallAlias(scope.path, alias.name, alias.digest) } }
    }

    // ------------------------------------------------------------------ IP sets

    /** Every IP set with its entries. */
    suspend fun ipSets(serverId: String, scope: FirewallScope): List<Pair<FirewallIpSet, List<IpSetEntry>>> = coroutineScope {
        val sets = hub.use(serverId) { it.pve { firewallIpSets(scope.path) } }.sortedBy { it.name.lowercase() }
        sets.map { set -> async { set to hub.use(serverId) { it.pve { firewallIpSetEntries(scope.path, set.name) } }.sortedBy { it.cidr } } }.awaitAll()
    }

    suspend fun createIpSet(serverId: String, scope: FirewallScope, name: String, comment: String) {
        val params = buildMap {
            put("name", name.trim())
            comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        }
        hub.use(serverId) { it.pveNoData { createFirewallIpSet(scope.path, params) } }
    }

    /** Deletes the set and its entries. */
    suspend fun deleteIpSet(serverId: String, scope: FirewallScope, name: String) {
        hub.use(serverId) { it.pveNoData { deleteFirewallIpSet(scope.path, name, 1) } }
    }

    suspend fun addIpSetEntry(serverId: String, scope: FirewallScope, set: String, cidr: String, comment: String, nomatch: Boolean) {
        val params = buildMap {
            put("cidr", cidr.trim())
            comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
            if (nomatch) put("nomatch", "1")
        }
        hub.use(serverId) { it.pveNoData { addIpSetEntry(scope.path, set, params) } }
    }

    suspend fun removeIpSetEntry(serverId: String, scope: FirewallScope, set: String, entry: IpSetEntry) {
        hub.use(serverId) { it.pveNoData { deleteIpSetEntry(scope.path, set, entry.cidr, entry.digest) } }
    }

    // ------------------------------------------------------------------ log

    /** The last [lines] lines of a node's or guest's firewall log, oldest first. */
    suspend fun log(serverId: String, scope: FirewallScope, lines: Int = 500): List<TaskLogLine> {
        // Proxmox pages from the start of the file; the first answer tells how long it is.
        val first = hub.use(serverId) { it.pveEnvelope { firewallLog(scope.path, 0, 1) } }
        val total = first.total ?: return first.data.orEmpty()
        if (total <= 1) return first.data.orEmpty()
        return hub.use(serverId) { it.pve { firewallLog(scope.path, maxOf(0, total - lines), lines) } }
    }
}
