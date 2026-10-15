package io.github.lucabarbalata.anprox.ui.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.node.NodeAdminRepository
import io.github.lucabarbalata.anprox.model.pve.AptRepositories
import io.github.lucabarbalata.anprox.model.pve.AptUpdate
import io.github.lucabarbalata.anprox.model.pve.DiskStorageForm
import io.github.lucabarbalata.anprox.model.pve.DiskStorageKind
import io.github.lucabarbalata.anprox.model.pve.NetworkConfig
import io.github.lucabarbalata.anprox.model.pve.NetworkInterfaceForm
import io.github.lucabarbalata.anprox.model.pve.NetworkKind
import io.github.lucabarbalata.anprox.model.pve.NodeDisk
import io.github.lucabarbalata.anprox.model.pve.NodeDnsForm
import io.github.lucabarbalata.anprox.model.pve.NodeHosts
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeService
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import io.github.lucabarbalata.anprox.model.pve.NodeTime
import io.github.lucabarbalata.anprox.model.pve.PackageVersion
import io.github.lucabarbalata.anprox.model.pve.ServiceAction
import io.github.lucabarbalata.anprox.model.pve.SmartData
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

/** The node pages under Node → Administration (milestone M5). */
enum class NodeAdminPage { UPDATES, REPOSITORIES, SERVICES, NETWORK, DISKS, SYSTEM, JOURNAL }

// ------------------------------------------------------------------------------ updates

/** A changelog being shown; [text] is null while it loads. */
data class Changelog(val update: AptUpdate, val text: String? = null, val error: UiText? = null)

data class UpdatesState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val updates: List<AptUpdate> = emptyList(),
    val subscription: NodeSubscription? = null,
    val versions: List<PackageVersion>? = null,
    val showVersions: Boolean = false,
    val changelog: Changelog? = null,
    val refreshing: Boolean = false,
    val result: TaskResult? = null,
)

/** Node → Updates: what can be upgraded, the package index refresh, changelogs. */
class UpdatesViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: NodeAdminRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(UpdatesState())
    val state: StateFlow<UpdatesState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val updatesCall = async { repo.updates(serverId, node) }
                    val subscriptionCall = async { repo.subscription(serverId, node) }
                    val updates = updatesCall.await()
                    val subscription = subscriptionCall.await()
                    ui.update { it.copy(loading = false, updates = updates, subscription = subscription) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    /** `apt-get update`, then the list again. */
    fun refresh() {
        if (ui.value.refreshing) return
        ui.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val result = try {
                val upid = repo.refresh(serverId, node)
                val ok = guests.awaitTask(serverId, node, upid).succeeded
                TaskResult(UiText.res(if (ok) R.string.updates_refreshed else R.string.updates_refresh_failed), upid, ok)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(refreshing = false, result = result) }
            load()
        }
    }

    fun openChangelog(update: AptUpdate) {
        ui.update { it.copy(changelog = Changelog(update)) }
        viewModelScope.launch {
            val loaded = try {
                Changelog(update, repo.changelog(serverId, node, update))
            } catch (e: ApiException) {
                Changelog(update, "", e.toUiText())
            }
            ui.update { s -> if (s.changelog?.update == update) s.copy(changelog = loaded) else s }
        }
    }

    fun closeChangelog() {
        ui.update { it.copy(changelog = null) }
    }

    fun showVersions() {
        ui.update { it.copy(showVersions = true) }
        if (ui.value.versions != null) return
        viewModelScope.launch {
            val versions = try {
                repo.versions(serverId, node)
            } catch (_: ApiException) {
                emptyList()
            }
            ui.update { it.copy(versions = versions) }
        }
    }

    fun hideVersions() {
        ui.update { it.copy(showVersions = false) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ repositories

data class RepositoriesState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val repos: AptRepositories? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
)

/** Node → Repositories: APT sources, switched on and off, and Proxmox's standard ones added. */
class RepositoriesViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: NodeAdminRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(RepositoriesState())
    val state: StateFlow<RepositoriesState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val repos = repo.repositories(serverId, node)
                ui.update { it.copy(loading = false, repos = repos) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setEnabled(path: String, index: Int, enabled: Boolean) {
        val repos = ui.value.repos ?: return
        run(UiText.res(if (enabled) R.string.repos_enabled else R.string.repos_disabled)) {
            repo.setRepositoryEnabled(serverId, node, repos, path, index, enabled)
        }
    }

    fun add(handle: String, name: String) {
        val repos = ui.value.repos ?: return
        run(UiText.res(R.string.repos_added, name)) { repo.addStandardRepository(serverId, node, repos, handle) }
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

// ------------------------------------------------------------------------------ services

data class ServicesState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val services: List<NodeService> = emptyList(),
    val selected: NodeService? = null,
    /** An action that cuts the app's own connection, waiting for confirmation. */
    val confirm: Pair<NodeService, ServiceAction>? = null,
    val running: String? = null,
    val result: TaskResult? = null,
)

/** Node → Services: systemd units Proxmox manages, started, stopped, restarted. */
class ServicesViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: NodeAdminRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(ServicesState())
    val state: StateFlow<ServicesState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val services = repo.services(serverId, node)
                ui.update { it.copy(loading = false, services = services) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun select(service: NodeService) {
        ui.update { it.copy(selected = service) }
    }

    fun closeSelection() {
        ui.update { it.copy(selected = null) }
    }

    /** Stopping or restarting what serves the API (or the cluster) asks first. */
    fun request(service: NodeService, action: ServiceAction) {
        ui.update { it.copy(selected = null) }
        if (action != ServiceAction.START && service.id in CONNECTION_SERVICES) {
            ui.update { it.copy(confirm = service to action) }
        } else {
            perform(service, action)
        }
    }

    fun confirm() {
        val (service, action) = ui.value.confirm ?: return
        ui.update { it.copy(confirm = null) }
        perform(service, action)
    }

    fun dismissConfirm() {
        ui.update { it.copy(confirm = null) }
    }

    private fun perform(service: NodeService, action: ServiceAction) {
        if (ui.value.running != null) return
        ui.update { it.copy(running = service.id) }
        viewModelScope.launch {
            val result = try {
                val upid = repo.serviceAction(serverId, node, service, action)
                val ok = guests.awaitTask(serverId, node, upid).succeeded
                TaskResult(UiText.res(if (ok) R.string.services_done else R.string.services_failed, service.id), upid, ok)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(running = null, result = result) }
            load()
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    companion object {
        /** Services the app talks through, or that hold the cluster together. */
        val CONNECTION_SERVICES = setOf("pveproxy", "pvedaemon", "pve-cluster", "corosync", "sshd")
    }
}

// ------------------------------------------------------------------------------ journal

data class JournalState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val lines: List<String> = emptyList(),
    val filter: String = "",
) {
    val shown: List<String> get() = if (filter.isBlank()) lines else lines.filter { it.contains(filter.trim(), ignoreCase = true) }
}

/** Node → System log: the newest 500 journal lines. */
class JournalViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: NodeAdminRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(JournalState())
    val state: StateFlow<JournalState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val lines = repo.journal(serverId, node)
                ui.update { it.copy(loading = false, lines = lines) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setFilter(text: String) {
        ui.update { it.copy(filter = text) }
    }
}

// ------------------------------------------------------------------------------ DNS, hosts, time

data class SystemState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val dns: NodeDnsForm? = null,
    val hosts: NodeHosts? = null,
    val hostsText: String = "",
    val time: NodeTime? = null,
    val timezone: String = "",
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    val hostsChanged: Boolean get() = hosts != null && hostsText != hosts.data
    val timezoneChanged: Boolean get() = time != null && timezone.isNotBlank() && timezone != time.timezone
}

/** Node → DNS, hosts and time. */
class SystemSettingsViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: NodeAdminRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(SystemState())
    val state: StateFlow<SystemState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val dnsCall = async { repo.dns(serverId, node) }
                    val hostsCall = async { repo.hosts(serverId, node) }
                    val timeCall = async { repo.time(serverId, node) }
                    val dns = dnsCall.await()
                    val hosts = hostsCall.await()
                    val time = timeCall.await()
                    ui.update {
                        it.copy(loading = false, dns = NodeDnsForm.from(dns), hosts = hosts, hostsText = hosts.data, time = time, timezone = time.timezone)
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun editDns(change: NodeDnsForm.() -> NodeDnsForm) {
        ui.update { s -> s.copy(dns = s.dns?.change()) }
    }

    fun setServer(index: Int, value: String) = editDns { copy(servers = servers.toMutableList().also { it[index] = value }) }

    fun saveDns() {
        val form = ui.value.dns ?: return
        if (form.problem() != null) return
        run(UiText.res(R.string.system_dns_saved)) { repo.setDns(serverId, node, form) }
    }

    fun editHosts(text: String) {
        ui.update { it.copy(hostsText = text) }
    }

    fun saveHosts() {
        val s = ui.value
        val hosts = s.hosts ?: return
        if (!s.hostsChanged) return
        run(UiText.res(R.string.system_hosts_saved)) { repo.setHosts(serverId, node, hosts, s.hostsText) }
    }

    fun setTimezone(zone: String) {
        ui.update { it.copy(timezone = zone) }
    }

    fun saveTimezone() {
        val s = ui.value
        if (!s.timezoneChanged) return
        run(UiText.res(R.string.system_timezone_saved, s.timezone)) { repo.setTimezone(serverId, node, s.timezone) }
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

// ------------------------------------------------------------------------------ network

/** An interface being added ([original] null) or edited. */
data class NetworkEdit(val original: NodeNetwork?, val form: NetworkInterfaceForm)

data class NetworkState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val config: NetworkConfig? = null,
    val editing: NetworkEdit? = null,
    val showChanges: Boolean = false,
    val confirmApply: Boolean = false,
    val confirmRevert: Boolean = false,
    val confirmDelete: NodeNetwork? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    val names: List<String> get() = config?.interfaces?.map { it.iface }.orEmpty()
}

/** Node → Network: interfaces, edited into a pending configuration that is then applied or reverted. */
class NetworkViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: NodeAdminRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val config = repo.network(serverId, node)
                ui.update { it.copy(loading = false, config = config) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun add(kind: NetworkKind) {
        val name = NetworkInterfaceForm.suggestedName(kind, ui.value.names)
        ui.update { it.copy(editing = NetworkEdit(null, NetworkInterfaceForm(type = kind.apiName, name = name))) }
    }

    fun edit(iface: NodeNetwork) {
        ui.update { it.copy(editing = NetworkEdit(iface, NetworkInterfaceForm.from(iface))) }
    }

    fun editForm(change: NetworkInterfaceForm.() -> NetworkInterfaceForm) {
        ui.update { s -> s.copy(editing = s.editing?.let { it.copy(form = it.form.change()) }) }
    }

    fun close() {
        ui.update { it.copy(editing = null) }
    }

    fun save() {
        val s = ui.value
        val edit = s.editing ?: return
        val original = edit.original
        if (edit.form.problem(original == null, s.names) != null) return
        ui.update { it.copy(editing = null) }
        run(UiText.res(R.string.network_saved)) {
            if (original == null) repo.createInterface(serverId, node, edit.form) else repo.updateInterface(serverId, node, original, edit.form)
        }
    }

    fun requestDelete(iface: NodeNetwork) {
        ui.update { it.copy(editing = null, confirmDelete = iface) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val iface = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.network_deleted, iface.iface)) { repo.deleteInterface(serverId, node, iface.iface) }
    }

    fun showChanges(show: Boolean) {
        ui.update { it.copy(showChanges = show) }
    }

    fun requestApply() {
        ui.update { it.copy(confirmApply = true, showChanges = false) }
    }

    fun requestRevert() {
        ui.update { it.copy(confirmRevert = true, showChanges = false) }
    }

    fun dismissConfirm() {
        ui.update { it.copy(confirmApply = false, confirmRevert = false) }
    }

    fun apply() {
        ui.update { it.copy(confirmApply = false, busy = true) }
        viewModelScope.launch {
            val result = try {
                val upid = repo.applyNetwork(serverId, node)
                val ok = guests.awaitTask(serverId, node, upid).succeeded
                TaskResult(UiText.res(if (ok) R.string.network_applied else R.string.network_apply_failed), upid, ok)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    fun revert() {
        ui.update { it.copy(confirmRevert = false) }
        run(UiText.res(R.string.network_reverted)) { repo.revertNetwork(serverId, node) }
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

// ------------------------------------------------------------------------------ disks

/** A disk's details: SMART data once loaded. */
data class DiskDetail(val disk: NodeDisk, val smart: SmartData? = null, val error: UiText? = null)

data class DisksState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val disks: List<NodeDisk> = emptyList(),
    val detail: DiskDetail? = null,
    val creating: DiskStorageForm? = null,
    val confirmWipe: NodeDisk? = null,
    val running: String? = null,
    val result: TaskResult? = null,
) {
    val unused: List<NodeDisk> get() = disks.filter { it.unused }
}

/** Node → Disks: health, SMART, and turning unused disks into storage. */
class DisksViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: NodeAdminRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(DisksState())
    val state: StateFlow<DisksState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val disks = repo.disks(serverId, node)
                ui.update { it.copy(loading = false, disks = disks) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun open(disk: NodeDisk) {
        ui.update { it.copy(detail = DiskDetail(disk)) }
        viewModelScope.launch {
            val detail = try {
                DiskDetail(disk, repo.smart(serverId, node, disk))
            } catch (e: ApiException) {
                DiskDetail(disk, SmartData(), e.toUiText())
            }
            ui.update { s -> if (s.detail?.disk == disk) s.copy(detail = detail) else s }
        }
    }

    fun closeDetail() {
        ui.update { it.copy(detail = null) }
    }

    fun startCreate(disk: NodeDisk?) {
        ui.update { it.copy(detail = null, creating = DiskStorageForm(devices = listOfNotNull(disk?.devpath))) }
    }

    fun editCreate(change: DiskStorageForm.() -> DiskStorageForm) {
        ui.update { s -> s.copy(creating = s.creating?.change()) }
    }

    fun toggleDevice(devpath: String) = editCreate {
        copy(devices = if (devpath in devices) devices - devpath else if (kind == DiskStorageKind.ZFS) devices + devpath else listOf(devpath))
    }

    fun closeCreate() {
        ui.update { it.copy(creating = null) }
    }

    fun create() {
        val form = ui.value.creating ?: return
        if (form.problem() != null) return
        ui.update { it.copy(creating = null) }
        runTask(form.name.trim(), UiText.res(R.string.disks_created, form.name.trim())) { repo.createStorage(serverId, node, form) }
    }

    fun initGpt(disk: NodeDisk) {
        ui.update { it.copy(detail = null) }
        runTask(disk.devpath, UiText.res(R.string.disks_gpt_done, disk.devpath)) { repo.initGpt(serverId, node, disk) }
    }

    fun requestWipe(disk: NodeDisk) {
        ui.update { it.copy(detail = null, confirmWipe = disk) }
    }

    fun dismissWipe() {
        ui.update { it.copy(confirmWipe = null) }
    }

    fun wipe() {
        val disk = ui.value.confirmWipe ?: return
        ui.update { it.copy(confirmWipe = null) }
        runTask(disk.devpath, UiText.res(R.string.disks_wiped, disk.devpath)) { repo.wipe(serverId, node, disk) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun runTask(what: String, done: UiText, call: suspend () -> String) {
        if (ui.value.running != null) return
        ui.update { it.copy(running = what) }
        viewModelScope.launch {
            val result = try {
                val upid = call()
                val ok = guests.awaitTask(serverId, node, upid).succeeded
                TaskResult(if (ok) done else UiText.res(R.string.disks_failed), upid, ok)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(running = null, result = result) }
            load()
        }
    }
}

