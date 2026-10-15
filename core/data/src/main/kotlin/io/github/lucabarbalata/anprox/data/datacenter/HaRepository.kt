package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.HaGroup
import io.github.lucabarbalata.anprox.model.pve.HaGroupForm
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaResourceForm
import io.github.lucabarbalata.anprox.model.pve.HaRule
import io.github.lucabarbalata.anprox.model.pve.HaRuleForm
import io.github.lucabarbalata.anprox.model.pve.HaState
import io.github.lucabarbalata.anprox.model.pve.HaStatusEntry
import io.github.lucabarbalata.anprox.network.ApiException

/**
 * High availability (questionnaire Q21): which guests the HA manager keeps running, its view
 * of the cluster, and moving a managed guest.
 */
class HaRepository(private val hub: ServerHub) {

    suspend fun resources(serverId: String): List<HaResource> =
        hub.use(serverId) { it.pve { haResources() } }.sortedBy { it.vmid ?: Int.MAX_VALUE }

    suspend fun status(serverId: String): List<HaStatusEntry> = hub.use(serverId) { it.pve { haStatus() } }

    /** Guests and nodes, to name resources and offer guests and migration targets. */
    suspend fun clusterResources(serverId: String): List<ClusterResource> = hub.use(serverId) { it.pve { clusterResources(null) } }

    /** Groups exist on Proxmox VE 8; null on a version without them. */
    suspend fun groups(serverId: String): List<HaGroup>? = try {
        hub.use(serverId) { it.pve { haGroups() } }.sortedBy { it.group }
    } catch (_: ApiException) {
        null
    }

    /** Rules exist from Proxmox VE 9; null on older versions. */
    suspend fun rules(serverId: String): List<HaRule>? = try {
        hub.use(serverId) { it.pve { haRules() } }.sortedBy { it.rule }
    } catch (_: ApiException) {
        null
    }

    suspend fun createGroup(serverId: String, form: HaGroupForm) {
        hub.use(serverId) { it.pveNoData { createHaGroup(form.createParams()) } }
    }

    suspend fun updateGroup(serverId: String, group: HaGroup, form: HaGroupForm) {
        hub.use(serverId) { it.pveNoData { updateHaGroup(group.group, form.updateParams(group)) } }
    }

    suspend fun deleteGroup(serverId: String, group: HaGroup) {
        hub.use(serverId) { it.pveNoData { deleteHaGroup(group.group) } }
    }

    suspend fun createRule(serverId: String, form: HaRuleForm) {
        hub.use(serverId) { it.pveNoData { createHaRule(form.createParams()) } }
    }

    suspend fun updateRule(serverId: String, rule: HaRule, form: HaRuleForm) {
        hub.use(serverId) { it.pveNoData { updateHaRule(rule.rule, form.updateParams(rule)) } }
    }

    suspend fun deleteRule(serverId: String, rule: HaRule) {
        hub.use(serverId) { it.pveNoData { deleteHaRule(rule.rule) } }
    }

    suspend fun add(serverId: String, sid: String, form: HaResourceForm) {
        hub.use(serverId) { it.pveNoData { addHaResource(form.createParams(sid)) } }
    }

    suspend fun update(serverId: String, resource: HaResource, form: HaResourceForm) {
        hub.use(serverId) { it.pveNoData { updateHaResource(resource.sid, form.updateParams(resource)) } }
    }

    suspend fun setState(serverId: String, resource: HaResource, state: HaState) {
        hub.use(serverId) { it.pveNoData { updateHaResource(resource.sid, mapOf("state" to state.apiName)) } }
    }

    suspend fun migrate(serverId: String, resource: HaResource, node: String) {
        hub.use(serverId) { it.pveNoData { migrateHaResource(resource.sid, node) } }
    }

    /** Stops managing the guest; the guest itself keeps its state. */
    suspend fun remove(serverId: String, resource: HaResource) {
        hub.use(serverId) { it.pveNoData { removeHaResource(resource.sid) } }
    }
}
