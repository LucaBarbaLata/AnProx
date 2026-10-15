package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.HaRepository
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.HaGroup
import io.github.lucabarbalata.anprox.model.pve.HaGroupForm
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaResourceForm
import io.github.lucabarbalata.anprox.model.pve.HaRule
import io.github.lucabarbalata.anprox.model.pve.HaRuleForm
import io.github.lucabarbalata.anprox.model.pve.HaStatusEntry
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The dialog for a resource: adding one ([resource] null, [sid] picked in the dialog) or editing it. */
data class HaEdit(val resource: HaResource?, val sid: String?, val form: HaResourceForm, val migrateTo: String? = null)

/** A group (Proxmox VE 8) being added ([group] null) or edited. */
data class HaGroupEdit(val group: HaGroup?, val form: HaGroupForm)

/** A rule (Proxmox VE 9) being added ([rule] null) or edited. */
data class HaRuleEdit(val rule: HaRule?, val form: HaRuleForm)

data class HaUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val status: List<HaStatusEntry> = emptyList(),
    val resources: List<HaResource> = emptyList(),
    val groups: List<HaGroup> = emptyList(),
    /** False on Proxmox VE 9, which replaced groups with rules. */
    val groupsSupported: Boolean = false,
    /** Null before Proxmox VE 9. */
    val rules: List<HaRule>? = null,
    val editingGroup: HaGroupEdit? = null,
    val editingRule: HaRuleEdit? = null,
    /** Guests (no templates), to name resources and offer new ones. */
    val guests: List<ClusterResource> = emptyList(),
    val onlineNodes: List<String> = emptyList(),
    val editing: HaEdit? = null,
    val allNodes: List<String> = emptyList(),
    val confirmRemove: HaResource? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    fun guestFor(sid: String): ClusterResource? = guests.firstOrNull { HaResource.sid(if (it.type == "lxc") GuestType.LXC else GuestType.QEMU, it.vmid ?: -1) == sid }

    /** Guests not managed by HA yet. */
    val candidates: List<ClusterResource>
        get() = guests.filter { g -> resources.none { it.sid == HaResource.sid(if (g.type == "lxc") GuestType.LXC else GuestType.QEMU, g.vmid ?: -1) } }
}

/** Datacenter → High availability (questionnaire Q21). */
class HaViewModel(
    private val serverId: String,
    private val repo: HaRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(HaUiState())
    val state: StateFlow<HaUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val statusCall = async { repo.status(serverId) }
                    val resourcesCall = async { repo.resources(serverId) }
                    val groupsCall = async { repo.groups(serverId) }
                    val rulesCall = async { repo.rules(serverId) }
                    val allCall = async { repo.clusterResources(serverId) }
                    val status = statusCall.await()
                    val resources = resourcesCall.await()
                    val groups = groupsCall.await()
                    val rules = rulesCall.await()
                    val all = allCall.await()
                    ui.update {
                        it.copy(
                            loading = false,
                            status = status,
                            resources = resources,
                            groups = groups.orEmpty(),
                            groupsSupported = groups != null,
                            rules = rules,
                            allNodes = all.filter { r -> r.resourceType == ResourceType.NODE }.mapNotNull { r -> r.node }.sorted(),
                            guests = all.filter { r -> r.isGuest && !r.template && r.vmid != null }.sortedBy { r -> r.vmid },
                            onlineNodes = all.filter { r -> r.resourceType == ResourceType.NODE && r.status == "online" }.mapNotNull { r -> r.node }.sorted(),
                        )
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun add() {
        val first = ui.value.candidates.firstOrNull()
        val sid = first?.let { HaResource.sid(if (it.type == "lxc") GuestType.LXC else GuestType.QEMU, it.vmid ?: -1) }
        ui.update { it.copy(editing = HaEdit(null, sid, HaResourceForm())) }
    }

    fun edit(resource: HaResource) {
        ui.update { it.copy(editing = HaEdit(resource, resource.sid, HaResourceForm.from(resource))) }
    }

    fun pickGuest(sid: String) {
        ui.update { s -> s.copy(editing = s.editing?.copy(sid = sid)) }
    }

    fun editForm(change: HaResourceForm.() -> HaResourceForm) {
        ui.update { s -> s.copy(editing = s.editing?.let { it.copy(form = it.form.change()) }) }
    }

    fun close() {
        ui.update { it.copy(editing = null) }
    }

    fun save() {
        val edit = ui.value.editing ?: return
        val sid = edit.sid ?: return
        if (!edit.form.valid) return
        ui.update { it.copy(editing = null) }
        run(UiText.res(if (edit.resource == null) R.string.ha_added else R.string.ha_saved)) {
            if (edit.resource == null) repo.add(serverId, sid, edit.form) else repo.update(serverId, edit.resource, edit.form)
        }
    }

    fun migrate(resource: HaResource, node: String) {
        ui.update { it.copy(editing = null) }
        run(UiText.res(R.string.ha_migrating, node)) { repo.migrate(serverId, resource, node) }
    }

    fun requestRemove(resource: HaResource) {
        ui.update { it.copy(editing = null, confirmRemove = resource) }
    }

    fun dismissRemove() {
        ui.update { it.copy(confirmRemove = null) }
    }

    fun remove() {
        val resource = ui.value.confirmRemove ?: return
        ui.update { it.copy(confirmRemove = null) }
        run(UiText.res(R.string.ha_removed)) { repo.remove(serverId, resource) }
    }

    // ------------------------------------------------------------------ groups (Proxmox VE 8)

    fun addGroup() {
        ui.update { it.copy(editingGroup = HaGroupEdit(null, HaGroupForm())) }
    }

    fun editGroup(group: HaGroup) {
        ui.update { it.copy(editingGroup = HaGroupEdit(group, HaGroupForm.from(group))) }
    }

    fun editGroupForm(change: HaGroupForm.() -> HaGroupForm) {
        ui.update { s -> s.copy(editingGroup = s.editingGroup?.let { it.copy(form = it.form.change()) }) }
    }

    fun closeGroup() {
        ui.update { it.copy(editingGroup = null) }
    }

    fun saveGroup() {
        val edit = ui.value.editingGroup ?: return
        if (!edit.form.valid) return
        ui.update { it.copy(editingGroup = null) }
        val group = edit.group
        run(UiText.res(R.string.ha_group_saved)) {
            if (group == null) repo.createGroup(serverId, edit.form) else repo.updateGroup(serverId, group, edit.form)
        }
    }

    fun deleteGroup() {
        val group = ui.value.editingGroup?.group ?: return
        ui.update { it.copy(editingGroup = null) }
        run(UiText.res(R.string.ha_group_deleted)) { repo.deleteGroup(serverId, group) }
    }

    // ------------------------------------------------------------------ rules (Proxmox VE 9)

    fun addRule() {
        ui.update { it.copy(editingRule = HaRuleEdit(null, HaRuleForm())) }
    }

    fun editRule(rule: HaRule) {
        ui.update { it.copy(editingRule = HaRuleEdit(rule, HaRuleForm.from(rule))) }
    }

    fun editRuleForm(change: HaRuleForm.() -> HaRuleForm) {
        ui.update { s -> s.copy(editingRule = s.editingRule?.let { it.copy(form = it.form.change()) }) }
    }

    fun closeRule() {
        ui.update { it.copy(editingRule = null) }
    }

    fun saveRule() {
        val edit = ui.value.editingRule ?: return
        if (!edit.form.valid) return
        ui.update { it.copy(editingRule = null) }
        val rule = edit.rule
        run(UiText.res(R.string.ha_rule_saved)) {
            if (rule == null) repo.createRule(serverId, edit.form) else repo.updateRule(serverId, rule, edit.form)
        }
    }

    fun deleteRule() {
        val rule = ui.value.editingRule?.rule ?: return
        ui.update { it.copy(editingRule = null) }
        run(UiText.res(R.string.ha_rule_deleted)) { repo.deleteRule(serverId, rule) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun run(done: UiText, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}
