package io.github.lucabarbalata.anprox.ui.sdn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.ClusterNode
import io.github.lucabarbalata.anprox.data.datacenter.SdnRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.pve.IpamEntry
import io.github.lucabarbalata.anprox.model.pve.SdnController
import io.github.lucabarbalata.anprox.model.pve.SdnControllerForm
import io.github.lucabarbalata.anprox.model.pve.SdnDns
import io.github.lucabarbalata.anprox.model.pve.SdnIpam
import io.github.lucabarbalata.anprox.model.pve.SdnPluginForm
import io.github.lucabarbalata.anprox.model.pve.SdnState
import io.github.lucabarbalata.anprox.model.pve.SdnSubnet
import io.github.lucabarbalata.anprox.model.pve.SdnSubnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnVnet
import io.github.lucabarbalata.anprox.model.pve.SdnVnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnZone
import io.github.lucabarbalata.anprox.model.pve.SdnZoneForm
import io.github.lucabarbalata.anprox.model.pve.SdnZoneType
import io.github.lucabarbalata.anprox.model.pve.Upid
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

/** The zone dialog; [existing] is null while creating. */
data class ZoneDraft(val existing: SdnZone?, val form: SdnZoneForm)

/** The VNet dialog; [existing] is null while creating. */
data class VnetDraft(val existing: SdnVnet?, val form: SdnVnetForm)

/** Something to delete, after confirming. */
sealed interface SdnDelete {
    val id: String

    data class Zone(override val id: String) : SdnDelete
    data class Vnet(override val id: String) : SdnDelete
    data class Controller(override val id: String) : SdnDelete
    data class Ipam(override val id: String) : SdnDelete
    data class Dns(override val id: String) : SdnDelete
}

data class SdnUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val zones: List<SdnZone> = emptyList(),
    val vnets: List<SdnVnet> = emptyList(),
    val controllers: List<SdnController> = emptyList(),
    val ipams: List<SdnIpam> = emptyList(),
    val dns: List<SdnDns> = emptyList(),
    val nodes: List<ClusterNode> = emptyList(),
    /** Bridges of an online node, for VLAN and QinQ zones. */
    val bridges: List<String> = emptyList(),
    val zoneDraft: ZoneDraft? = null,
    val vnetDraft: VnetDraft? = null,
    val controllerDraft: SdnControllerForm? = null,
    val pluginDraft: SdnPluginForm? = null,
    val confirmDelete: SdnDelete? = null,
    val confirmApply: Boolean = false,
    val confirmDiscard: Boolean = false,
    /** Applying: the task is running. */
    val applying: Boolean = false,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    /** How many zones, VNets and controllers differ from what the nodes run. */
    val pendingCount: Int get() = (zones.map { it.state } + vnets.map { it.state } + controllers.map { it.state }).count { it != null }

    fun zoneType(zone: String): SdnZoneType? = zones.firstOrNull { it.id == zone }?.zoneType
}

/** Datacenter → SDN: zones, VNets, controllers, IPAMs, DNS, and applying pending changes. */
class SdnViewModel(
    private val serverId: String,
    private val repo: SdnRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(SdnUiState())
    val state: StateFlow<SdnUiState> = ui.asStateFlow()

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val zones = async { repo.zones(serverId) }
                    val vnets = async { repo.vnets(serverId) }
                    val controllers = async { repo.controllers(serverId) }
                    val ipams = async { repo.ipams(serverId) }
                    val dns = async { repo.dnsServers(serverId) }
                    val nodes = async { repo.nodes(serverId) }
                    val n = nodes.await()
                    // Bridges are only a convenience for the zone form.
                    val bridges = n.firstOrNull { it.online }?.let { node -> runCatching { repo.bridges(serverId, node.name) }.getOrNull() }.orEmpty()
                    val z = zones.await()
                    val v = vnets.await()
                    val c = controllers.await()
                    val i = ipams.await()
                    val d = dns.await()
                    ui.update { it.copy(loading = false, zones = z, vnets = v, controllers = c, ipams = i, dns = d, nodes = n, bridges = bridges) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    // ---- zones

    fun startZone() {
        ui.update { it.copy(zoneDraft = ZoneDraft(null, SdnZoneForm(bridge = it.bridges.firstOrNull().orEmpty()))) }
    }

    fun openZone(zone: SdnZone) {
        ui.update { it.copy(zoneDraft = ZoneDraft(zone, SdnZoneForm.from(zone))) }
    }

    fun editZone(change: SdnZoneForm.() -> SdnZoneForm) {
        ui.update { s -> s.copy(zoneDraft = s.zoneDraft?.let { d -> d.copy(form = d.form.change()) }) }
    }

    fun closeZone() {
        ui.update { it.copy(zoneDraft = null) }
    }

    fun saveZone() {
        val draft = ui.value.zoneDraft ?: return
        val existing = draft.existing
        if (draft.form.problem(existing == null) != null) return
        ui.update { it.copy(zoneDraft = null) }
        if (existing == null) {
            run(UiText.res(R.string.sdn_saved_pending)) { repo.createZone(serverId, draft.form) }
        } else {
            run(UiText.res(R.string.sdn_saved_pending)) { repo.updateZone(serverId, existing, draft.form) }
        }
    }

    // ---- VNets

    fun startVnet() {
        ui.update { s -> s.copy(vnetDraft = VnetDraft(null, SdnVnetForm(zone = s.zones.firstOrNull { it.state != SdnState.DELETED }?.id.orEmpty()))) }
    }

    fun editVnet(change: SdnVnetForm.() -> SdnVnetForm) {
        ui.update { s -> s.copy(vnetDraft = s.vnetDraft?.let { d -> d.copy(form = d.form.change()) }) }
    }

    fun closeVnet() {
        ui.update { it.copy(vnetDraft = null) }
    }

    fun saveVnet() {
        val s = ui.value
        val draft = s.vnetDraft ?: return
        if (draft.form.problem(draft.existing == null, s.zoneType(draft.form.zone)) != null) return
        ui.update { it.copy(vnetDraft = null) }
        val existing = draft.existing
        if (existing == null) {
            run(UiText.res(R.string.sdn_saved_pending)) { repo.createVnet(serverId, draft.form) }
        } else {
            run(UiText.res(R.string.sdn_saved_pending)) { repo.updateVnet(serverId, existing, draft.form) }
        }
    }

    // ---- controllers, IPAM, DNS

    fun startController() {
        ui.update { s -> s.copy(controllerDraft = SdnControllerForm(node = s.nodes.firstOrNull()?.name.orEmpty())) }
    }

    fun editController(change: SdnControllerForm.() -> SdnControllerForm) {
        ui.update { s -> s.copy(controllerDraft = s.controllerDraft?.change()) }
    }

    fun closeController() {
        ui.update { it.copy(controllerDraft = null) }
    }

    fun saveController() {
        val form = ui.value.controllerDraft ?: return
        if (form.problem() != null) return
        ui.update { it.copy(controllerDraft = null) }
        run(UiText.res(R.string.sdn_saved_pending)) { repo.createController(serverId, form) }
    }

    /** [type] `netbox`, `phpipam` or `powerdns`. */
    fun startPlugin(type: String) {
        ui.update { it.copy(pluginDraft = SdnPluginForm(type)) }
    }

    fun editPlugin(change: SdnPluginForm.() -> SdnPluginForm) {
        ui.update { s -> s.copy(pluginDraft = s.pluginDraft?.change()) }
    }

    fun closePlugin() {
        ui.update { it.copy(pluginDraft = null) }
    }

    fun savePlugin() {
        val form = ui.value.pluginDraft ?: return
        if (!form.valid) return
        ui.update { it.copy(pluginDraft = null) }
        run(UiText.res(R.string.sdn_added, form.id.trim())) { repo.createPlugin(serverId, form) }
    }

    // ---- deleting, applying

    fun requestDelete(target: SdnDelete) {
        ui.update { it.copy(zoneDraft = null, vnetDraft = null, confirmDelete = target) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val target = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        val done = when (target) {
            is SdnDelete.Ipam, is SdnDelete.Dns -> UiText.res(R.string.sdn_deleted, target.id)
            else -> UiText.res(R.string.sdn_deleted_pending, target.id)
        }
        run(done) {
            when (target) {
                is SdnDelete.Zone -> repo.deleteZone(serverId, target.id)
                is SdnDelete.Vnet -> repo.deleteVnet(serverId, target.id)
                is SdnDelete.Controller -> repo.deleteController(serverId, target.id)
                is SdnDelete.Ipam -> repo.deleteIpam(serverId, target.id)
                is SdnDelete.Dns -> repo.deleteDns(serverId, target.id)
            }
        }
    }

    fun requestApply() {
        ui.update { it.copy(confirmApply = true) }
    }

    fun dismissApply() {
        ui.update { it.copy(confirmApply = false) }
    }

    fun apply() {
        if (ui.value.applying) return
        ui.update { it.copy(confirmApply = false, applying = true) }
        viewModelScope.launch {
            val result = try {
                val upid = repo.apply(serverId)
                val node = Upid.parse(upid)?.node ?: ui.value.nodes.firstOrNull { it.online }?.name.orEmpty()
                val ok = guests.awaitTask(serverId, node, upid).succeeded
                TaskResult(UiText.res(if (ok) R.string.sdn_applied else R.string.sdn_apply_failed), upid, ok)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(applying = false, result = result) }
            load()
        }
    }

    fun requestDiscard() {
        ui.update { it.copy(confirmDiscard = true) }
    }

    fun dismissDiscard() {
        ui.update { it.copy(confirmDiscard = false) }
    }

    fun discard() {
        ui.update { it.copy(confirmDiscard = false) }
        run(UiText.res(R.string.sdn_discarded)) { repo.discard(serverId) }
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

/** The subnet dialog; [existing] is null while creating. */
data class SubnetDraft(val existing: SdnSubnet?, val form: SdnSubnetForm)

data class SdnVnetUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val vnet: SdnVnet? = null,
    val zones: List<SdnZone> = emptyList(),
    val subnets: List<SdnSubnet> = emptyList(),
    val vnetDraft: SdnVnetForm? = null,
    val subnetDraft: SubnetDraft? = null,
    val confirmDeleteSubnet: SdnSubnet? = null,
    val confirmDeleteVnet: Boolean = false,
    val busy: Boolean = false,
    val result: TaskResult? = null,
    /** The VNet was deleted: the screen closes. */
    val deleted: Boolean = false,
) {
    val zoneType: SdnZoneType? get() = vnet?.let { v -> zones.firstOrNull { it.id == v.zone }?.zoneType }
}

/** Datacenter → SDN → a VNet: its settings and subnets. */
class SdnVnetViewModel(
    private val serverId: String,
    private val vnetId: String,
    private val repo: SdnRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(SdnVnetUiState())
    val state: StateFlow<SdnVnetUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val vnets = async { repo.vnets(serverId) }
                    val zones = async { repo.zones(serverId) }
                    val subnets = async { repo.subnets(serverId, vnetId) }
                    val vnet = vnets.await().firstOrNull { it.id == vnetId } ?: throw ApiException.NotFound(vnetId)
                    val z = zones.await()
                    val s = subnets.await()
                    ui.update { it.copy(loading = false, vnet = vnet, zones = z, subnets = s) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun editVnet() {
        ui.update { s -> s.copy(vnetDraft = s.vnet?.let(SdnVnetForm::from)) }
    }

    fun changeVnet(change: SdnVnetForm.() -> SdnVnetForm) {
        ui.update { s -> s.copy(vnetDraft = s.vnetDraft?.change()) }
    }

    fun closeVnet() {
        ui.update { it.copy(vnetDraft = null) }
    }

    fun saveVnet() {
        val s = ui.value
        val form = s.vnetDraft ?: return
        val vnet = s.vnet ?: return
        if (form.problem(creating = false, zoneType = s.zones.firstOrNull { it.id == form.zone }?.zoneType) != null) return
        ui.update { it.copy(vnetDraft = null) }
        run(UiText.res(R.string.sdn_saved_pending)) { repo.updateVnet(serverId, vnet, form) }
    }

    fun startSubnet() {
        ui.update { it.copy(subnetDraft = SubnetDraft(null, SdnSubnetForm())) }
    }

    fun openSubnet(subnet: SdnSubnet) {
        ui.update { it.copy(subnetDraft = SubnetDraft(subnet, SdnSubnetForm.from(subnet))) }
    }

    fun editSubnet(change: SdnSubnetForm.() -> SdnSubnetForm) {
        ui.update { s -> s.copy(subnetDraft = s.subnetDraft?.let { d -> d.copy(form = d.form.change()) }) }
    }

    fun closeSubnet() {
        ui.update { it.copy(subnetDraft = null) }
    }

    fun saveSubnet() {
        val draft = ui.value.subnetDraft ?: return
        val existing = draft.existing
        if (draft.form.problem(existing == null) != null) return
        ui.update { it.copy(subnetDraft = null) }
        if (existing == null) {
            run(UiText.res(R.string.sdn_saved_pending)) { repo.createSubnet(serverId, vnetId, draft.form) }
        } else {
            run(UiText.res(R.string.sdn_saved_pending)) { repo.updateSubnet(serverId, existing, draft.form) }
        }
    }

    fun requestDeleteSubnet(subnet: SdnSubnet) {
        ui.update { it.copy(subnetDraft = null, confirmDeleteSubnet = subnet) }
    }

    fun dismissDeleteSubnet() {
        ui.update { it.copy(confirmDeleteSubnet = null) }
    }

    fun deleteSubnet() {
        val subnet = ui.value.confirmDeleteSubnet ?: return
        ui.update { it.copy(confirmDeleteSubnet = null) }
        run(UiText.res(R.string.sdn_deleted_pending, subnet.cidr)) { repo.deleteSubnet(serverId, subnet) }
    }

    fun requestDeleteVnet() {
        ui.update { it.copy(confirmDeleteVnet = true) }
    }

    fun dismissDeleteVnet() {
        ui.update { it.copy(confirmDeleteVnet = false) }
    }

    fun deleteVnet() {
        ui.update { it.copy(confirmDeleteVnet = false, busy = true) }
        viewModelScope.launch {
            try {
                repo.deleteVnet(serverId, vnetId)
                ui.update { it.copy(busy = false, deleted = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
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

data class IpamUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val entries: List<IpamEntry> = emptyList(),
    val filter: String = "",
) {
    val shown: List<IpamEntry>
        get() = filter.trim().takeIf { it.isNotEmpty() }?.let { f ->
            entries.filter { e -> listOfNotNull(e.ip, e.hostname, e.mac, e.vnet, e.vmid?.toString()).any { it.contains(f, ignoreCase = true) } }
        } ?: entries
}

/** Datacenter → SDN → an IPAM: the addresses it handed out. */
class SdnIpamViewModel(
    private val serverId: String,
    private val ipam: String,
    private val repo: SdnRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(IpamUiState())
    val state: StateFlow<IpamUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = repo.ipamEntries(serverId, ipam)
                ui.update { it.copy(loading = false, entries = entries) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setFilter(text: String) {
        ui.update { it.copy(filter = text) }
    }
}
