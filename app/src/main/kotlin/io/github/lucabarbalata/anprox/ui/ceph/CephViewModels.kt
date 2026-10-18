package io.github.lucabarbalata.anprox.ui.ceph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.CephAction
import io.github.lucabarbalata.anprox.data.datacenter.CephRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.pve.CephConfigEntry
import io.github.lucabarbalata.anprox.model.pve.CephFlag
import io.github.lucabarbalata.anprox.model.pve.CephFs
import io.github.lucabarbalata.anprox.model.pve.CephFsForm
import io.github.lucabarbalata.anprox.model.pve.CephOsd
import io.github.lucabarbalata.anprox.model.pve.CephOsdForm
import io.github.lucabarbalata.anprox.model.pve.CephOsdTree
import io.github.lucabarbalata.anprox.model.pve.CephOverview
import io.github.lucabarbalata.anprox.model.pve.CephPool
import io.github.lucabarbalata.anprox.model.pve.CephPoolForm
import io.github.lucabarbalata.anprox.model.pve.CephService
import io.github.lucabarbalata.anprox.model.pve.CephServiceType
import io.github.lucabarbalata.anprox.model.pve.NodeDisk
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs one Ceph change at a time: a Proxmox task (waited for) or a plain call, then reports the
 * outcome and reloads. Shared by the Ceph screens' ViewModels.
 */
internal class CephChanges(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val guests: GuestRepository,
    private val busy: (UiText?) -> Unit,
    private val report: (TaskResult) -> Unit,
    private val reload: () -> Unit,
) {
    private var running = false

    /** [start] returns the task's UPID; [node] is used when the UPID can't be read. */
    fun task(node: String, working: UiText, ok: UiText, failed: UiText, start: suspend () -> String) {
        if (running) return
        running = true
        busy(working)
        scope.launch {
            val result = try {
                val upid = start()
                val succeeded = guests.awaitTask(serverId, Upid.parse(upid)?.node ?: node, upid).succeeded
                TaskResult(if (succeeded) ok else failed, upid, succeeded)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            running = false
            busy(null)
            report(result)
            reload()
        }
    }

    fun call(done: UiText, action: suspend () -> Unit) {
        if (running) return
        running = true
        busy(UiText.res(R.string.ceph_working))
        scope.launch {
            val result = try {
                action()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            running = false
            busy(null)
            report(result)
            reload()
        }
    }
}

/** Ceph screens load from any online node; the first one, unless it can't answer. */
private suspend fun CephRepository.readNode(serverId: String): String =
    onlineNodes(serverId).firstOrNull() ?: throw ApiException.NotFound("no node is online")

private fun ApiException.cephUiText(): UiText =
    if (CephRepository.notSetUp(this)) UiText.res(R.string.ceph_not_set_up_body) else toUiText()

// ------------------------------------------------------------------------------ overview

data class CephUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    /** The cluster has no Ceph (not installed or not initialised). */
    val notSetUp: Boolean = false,
    val node: String? = null,
    val overview: CephOverview? = null,
    val flags: List<CephFlag> = emptyList(),
    /** A flag change waiting for confirmation (the ones that stop I/O or OSDs). */
    val confirmFlag: CephFlag? = null,
    val busy: UiText? = null,
    val result: TaskResult? = null,
)

/** Datacenter → Ceph: health, capacity, daemons and the OSD map flags. */
class CephViewModel(
    private val serverId: String,
    private val repo: CephRepository,
    guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(CephUiState())
    val state: StateFlow<CephUiState> = ui.asStateFlow()
    private val changes = CephChanges(viewModelScope, serverId, guests, { b -> ui.update { it.copy(busy = b) } }, { r -> ui.update { it.copy(result = r) } }, ::load)

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val node = repo.readNode(serverId)
                coroutineScope {
                    val overviewCall = async { repo.overview(serverId, node) }
                    val flagsCall = async { repo.flags(serverId) }
                    val overview = overviewCall.await()
                    val flags = flagsCall.await()
                    ui.update { it.copy(loading = false, notSetUp = false, node = node, overview = overview, flags = flags) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, notSetUp = CephRepository.notSetUp(e), error = if (CephRepository.notSetUp(e)) null else e.toUiText()) }
            }
        }
    }

    fun setFlag(flag: CephFlag, value: Boolean) {
        if (value && flag.name in CONFIRM) {
            ui.update { it.copy(confirmFlag = flag) }
            return
        }
        changes.call(UiText.res(if (value) R.string.ceph_flag_set else R.string.ceph_flag_unset, flag.name)) { repo.setFlag(serverId, flag.name, value) }
    }

    fun confirmFlag() {
        val flag = ui.value.confirmFlag ?: return
        ui.update { it.copy(confirmFlag = null) }
        changes.call(UiText.res(R.string.ceph_flag_set, flag.name)) { repo.setFlag(serverId, flag.name, true) }
    }

    fun dismissFlag() {
        ui.update { it.copy(confirmFlag = null) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    companion object {
        /** Flags that stop client I/O or keep OSDs from running. */
        val CONFIRM = setOf("pause", "noup", "nodown")
    }
}

// ------------------------------------------------------------------------------ OSDs

/** What the OSD dialog offers for [osd]. */
data class OsdDestroy(val osd: CephOsd, val cleanup: Boolean = true)

data class CephOsdsUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val node: String? = null,
    val nodes: List<String> = emptyList(),
    val tree: CephOsdTree = CephOsdTree(),
    val selected: CephOsd? = null,
    val destroying: OsdDestroy? = null,
    val creating: CephOsdForm? = null,
    /** Unused disks of [creating]'s node; null while loading. */
    val disks: List<NodeDisk>? = null,
    val disksError: UiText? = null,
    val busy: UiText? = null,
    val result: TaskResult? = null,
)

/** Datacenter → Ceph → OSDs: by host; in/out, scrub, start/stop, create and destroy. */
class CephOsdsViewModel(
    private val serverId: String,
    private val repo: CephRepository,
    guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(CephOsdsUiState())
    val state: StateFlow<CephOsdsUiState> = ui.asStateFlow()
    private val changes = CephChanges(viewModelScope, serverId, guests, { b -> ui.update { it.copy(busy = b) } }, { r -> ui.update { it.copy(result = r) } }, ::load)

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val nodes = repo.onlineNodes(serverId)
                val node = nodes.firstOrNull() ?: throw ApiException.NotFound("no node is online")
                val tree = repo.osds(serverId, node)
                ui.update { s -> s.copy(loading = false, node = node, nodes = nodes, tree = tree, selected = s.selected?.let { old -> tree.osds.firstOrNull { it.id == old.id } }) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.cephUiText()) }
            }
        }
    }

    fun select(osd: CephOsd?) {
        ui.update { it.copy(selected = osd) }
    }

    fun setIn(osd: CephOsd, inCluster: Boolean) {
        val node = ui.value.node ?: return
        ui.update { it.copy(selected = null) }
        changes.call(UiText.res(if (inCluster) R.string.ceph_osd_marked_in else R.string.ceph_osd_marked_out, osd.name)) { repo.setIn(serverId, node, osd, inCluster) }
    }

    fun scrub(osd: CephOsd, deep: Boolean) {
        val node = ui.value.node ?: return
        ui.update { it.copy(selected = null) }
        changes.call(UiText.res(if (deep) R.string.ceph_osd_deep_scrub_started else R.string.ceph_osd_scrub_started, osd.name)) { repo.scrub(serverId, node, osd, deep) }
    }

    fun service(osd: CephOsd, action: CephAction) {
        val host = osd.host ?: return
        ui.update { it.copy(selected = null) }
        changes.task(host, UiText.res(R.string.ceph_working), UiText.res(R.string.ceph_service_done, osd.name), UiText.res(R.string.ceph_service_failed, osd.name)) {
            repo.serviceAction(serverId, host, osd.service, action)
        }
    }

    fun requestDestroy(osd: CephOsd) {
        ui.update { it.copy(selected = null, destroying = OsdDestroy(osd)) }
    }

    fun setCleanup(cleanup: Boolean) {
        ui.update { s -> s.copy(destroying = s.destroying?.copy(cleanup = cleanup)) }
    }

    fun dismissDestroy() {
        ui.update { it.copy(destroying = null) }
    }

    fun destroy() {
        val d = ui.value.destroying ?: return
        ui.update { it.copy(destroying = null) }
        changes.task(d.osd.host.orEmpty(), UiText.res(R.string.ceph_osd_destroying, d.osd.name), UiText.res(R.string.ceph_osd_destroyed, d.osd.name), UiText.res(R.string.ceph_task_failed)) {
            repo.destroyOsd(serverId, d.osd, d.cleanup)
        }
    }

    fun startCreate() {
        val node = ui.value.node ?: return
        ui.update { it.copy(creating = CephOsdForm(node = node)) }
        loadDisks(node)
    }

    fun editCreate(change: CephOsdForm.() -> CephOsdForm) {
        val before = ui.value.creating ?: return
        val after = before.change()
        ui.update { it.copy(creating = after) }
        if (after.node != before.node) loadDisks(after.node)
    }

    private fun loadDisks(node: String) {
        ui.update { it.copy(disks = null, disksError = null) }
        viewModelScope.launch {
            try {
                val disks = repo.unusedDisks(serverId, node)
                ui.update { s ->
                    val form = s.creating
                    if (form != null && form.node == node) s.copy(disks = disks, creating = form.copy(device = disks.firstOrNull()?.devpath.orEmpty(), dbDevice = "")) else s
                }
            } catch (e: ApiException) {
                ui.update { s -> if (s.creating?.node == node) s.copy(disks = emptyList(), disksError = e.toUiText()) else s }
            }
        }
    }

    fun closeCreate() {
        ui.update { it.copy(creating = null) }
    }

    fun create() {
        val form = ui.value.creating ?: return
        if (!form.valid) return
        ui.update { it.copy(creating = null) }
        changes.task(form.node, UiText.res(R.string.ceph_osd_creating, form.device), UiText.res(R.string.ceph_osd_created), UiText.res(R.string.ceph_task_failed)) {
            repo.createOsd(serverId, form)
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ pools

/** The pool dialog; [existing] is null while creating. */
data class PoolDraft(val existing: CephPool?, val form: CephPoolForm)

/** Deleting a pool: type its name; optionally remove the Proxmox storages that use it. */
data class PoolDelete(val pool: CephPool, val removeStorages: Boolean = true)

data class CephPoolsUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val node: String? = null,
    val pools: List<CephPool> = emptyList(),
    val rules: List<String> = emptyList(),
    val draft: PoolDraft? = null,
    val deleting: PoolDelete? = null,
    val busy: UiText? = null,
    val result: TaskResult? = null,
) {
    fun nameTaken(name: String): Boolean = pools.any { it.name == name.trim() }
}

/** Datacenter → Ceph → Pools. */
class CephPoolsViewModel(
    private val serverId: String,
    private val repo: CephRepository,
    guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(CephPoolsUiState())
    val state: StateFlow<CephPoolsUiState> = ui.asStateFlow()
    private val changes = CephChanges(viewModelScope, serverId, guests, { b -> ui.update { it.copy(busy = b) } }, { r -> ui.update { it.copy(result = r) } }, ::load)

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val node = repo.readNode(serverId)
                coroutineScope {
                    val poolsCall = async { repo.pools(serverId, node) }
                    val rulesCall = async { runCatching { repo.rules(serverId, node) }.getOrDefault(emptyList()) }
                    val pools = poolsCall.await()
                    val rules = rulesCall.await()
                    ui.update { it.copy(loading = false, node = node, pools = pools, rules = rules) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.cephUiText()) }
            }
        }
    }

    fun startCreate() {
        ui.update { it.copy(draft = PoolDraft(null, CephPoolForm())) }
    }

    fun open(pool: CephPool) {
        ui.update { it.copy(draft = PoolDraft(pool, CephPoolForm.from(pool))) }
    }

    fun edit(change: CephPoolForm.() -> CephPoolForm) {
        ui.update { s -> s.copy(draft = s.draft?.let { d -> d.copy(form = d.form.change()) }) }
    }

    fun closeDraft() {
        ui.update { it.copy(draft = null) }
    }

    fun save() {
        val s = ui.value
        val draft = s.draft ?: return
        val node = s.node ?: return
        val creating = draft.existing == null
        if (draft.form.problem(creating) != null || (creating && s.nameTaken(draft.form.name))) return
        ui.update { it.copy(draft = null) }
        val name = draft.form.name.trim()
        val existing = draft.existing
        if (existing == null) {
            changes.task(node, UiText.res(R.string.ceph_pool_creating, name), UiText.res(R.string.ceph_pool_created, name), UiText.res(R.string.ceph_task_failed)) {
                repo.createPool(serverId, node, draft.form)
            }
        } else {
            if (draft.form.updateParams(existing).isEmpty()) {
                ui.update { it.copy(result = TaskResult(UiText.res(R.string.ceph_pool_unchanged), null, ok = true)) }
                return
            }
            changes.task(node, UiText.res(R.string.ceph_working), UiText.res(R.string.ceph_pool_saved, name), UiText.res(R.string.ceph_task_failed)) {
                checkNotNull(repo.updatePool(serverId, node, existing, draft.form))
            }
        }
    }

    fun requestDelete(pool: CephPool) {
        ui.update { it.copy(draft = null, deleting = PoolDelete(pool)) }
    }

    fun setRemoveStorages(remove: Boolean) {
        ui.update { s -> s.copy(deleting = s.deleting?.copy(removeStorages = remove)) }
    }

    fun dismissDelete() {
        ui.update { it.copy(deleting = null) }
    }

    fun delete() {
        val d = ui.value.deleting ?: return
        val node = ui.value.node ?: return
        ui.update { it.copy(deleting = null) }
        changes.task(node, UiText.res(R.string.ceph_pool_deleting, d.pool.name), UiText.res(R.string.ceph_pool_deleted, d.pool.name), UiText.res(R.string.ceph_task_failed)) {
            repo.deletePool(serverId, node, d.pool.name, d.removeStorages)
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ monitors, managers, MDS, CephFS

data class CephServicesUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val node: String? = null,
    val nodes: List<String> = emptyList(),
    val monitors: List<CephService> = emptyList(),
    val managers: List<CephService> = emptyList(),
    val metadataServers: List<CephService> = emptyList(),
    val filesystems: List<CephFs> = emptyList(),
    val selected: CephService? = null,
    val confirmDestroy: CephService? = null,
    /** Adding a daemon of this type: pick the node. */
    val adding: CephServiceType? = null,
    val fsDraft: CephFsForm? = null,
    val busy: UiText? = null,
    val result: TaskResult? = null,
) {
    fun ofType(type: CephServiceType): List<CephService> = when (type) {
        CephServiceType.MON -> monitors
        CephServiceType.MGR -> managers
        CephServiceType.MDS -> metadataServers
    }

    /** Online nodes that don't run a [type] daemon yet. */
    fun freeNodes(type: CephServiceType): List<String> = nodes.filter { n -> ofType(type).none { it.name == n || it.host == n } }
}

/** Datacenter → Ceph → Monitors, managers, metadata servers and CephFS. */
class CephServicesViewModel(
    private val serverId: String,
    private val repo: CephRepository,
    guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(CephServicesUiState())
    val state: StateFlow<CephServicesUiState> = ui.asStateFlow()
    private val changes = CephChanges(viewModelScope, serverId, guests, { b -> ui.update { it.copy(busy = b) } }, { r -> ui.update { it.copy(result = r) } }, ::load)

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val nodes = repo.onlineNodes(serverId)
                val node = nodes.firstOrNull() ?: throw ApiException.NotFound("no node is online")
                coroutineScope {
                    val mon = async { repo.services(serverId, node, CephServiceType.MON) }
                    val mgr = async { repo.services(serverId, node, CephServiceType.MGR) }
                    val mds = async { repo.services(serverId, node, CephServiceType.MDS) }
                    val fs = async { repo.filesystems(serverId, node) }
                    val monitors = mon.await()
                    val managers = mgr.await()
                    val metadataServers = mds.await()
                    val filesystems = fs.await()
                    ui.update {
                        it.copy(
                            loading = false, node = node, nodes = nodes, monitors = monitors, managers = managers,
                            metadataServers = metadataServers, filesystems = filesystems,
                        )
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.cephUiText()) }
            }
        }
    }

    fun select(service: CephService?) {
        ui.update { it.copy(selected = service) }
    }

    fun action(service: CephService, action: CephAction) {
        val host = service.host ?: service.name
        ui.update { it.copy(selected = null) }
        changes.task(host, UiText.res(R.string.ceph_working), UiText.res(R.string.ceph_service_done, service.service), UiText.res(R.string.ceph_service_failed, service.service)) {
            repo.serviceAction(serverId, host, service.service, action)
        }
    }

    fun requestDestroy(service: CephService) {
        ui.update { it.copy(selected = null, confirmDestroy = service) }
    }

    fun dismissDestroy() {
        ui.update { it.copy(confirmDestroy = null) }
    }

    fun destroy() {
        val service = ui.value.confirmDestroy ?: return
        ui.update { it.copy(confirmDestroy = null) }
        changes.task(service.host ?: service.name, UiText.res(R.string.ceph_working), UiText.res(R.string.ceph_service_removed, service.service), UiText.res(R.string.ceph_task_failed)) {
            repo.destroyService(serverId, service)
        }
    }

    fun startAdd(type: CephServiceType) {
        ui.update { it.copy(adding = type) }
    }

    fun closeAdd() {
        ui.update { it.copy(adding = null) }
    }

    fun add(node: String) {
        val type = ui.value.adding ?: return
        ui.update { it.copy(adding = null) }
        changes.task(node, UiText.res(R.string.ceph_working), UiText.res(R.string.ceph_service_added, "${type.apiName}.$node"), UiText.res(R.string.ceph_task_failed)) {
            repo.createService(serverId, node, type)
        }
    }

    fun startFs() {
        ui.update { it.copy(fsDraft = CephFsForm()) }
    }

    fun editFs(change: CephFsForm.() -> CephFsForm) {
        ui.update { s -> s.copy(fsDraft = s.fsDraft?.change()) }
    }

    fun closeFs() {
        ui.update { it.copy(fsDraft = null) }
    }

    fun createFs() {
        val form = ui.value.fsDraft ?: return
        val node = ui.value.node ?: return
        if (!form.valid) return
        ui.update { it.copy(fsDraft = null) }
        changes.task(node, UiText.res(R.string.ceph_fs_creating, form.name.trim()), UiText.res(R.string.ceph_fs_created, form.name.trim()), UiText.res(R.string.ceph_task_failed)) {
            repo.createFs(serverId, node, form)
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ configuration, CRUSH map, log

enum class CephText { CONFIG, CRUSH, LOG }

data class CephTextUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val lines: List<String> = emptyList(),
    /** The configuration database (CONFIG only). */
    val database: List<CephConfigEntry> = emptyList(),
    val filter: String = "",
) {
    val shown: List<String> get() = if (filter.isBlank()) lines else lines.filter { it.contains(filter.trim(), ignoreCase = true) }
}

/** Datacenter → Ceph → Configuration, CRUSH map or Log: text to read. */
class CephTextViewModel(
    private val serverId: String,
    private val kind: CephText,
    private val repo: CephRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(CephTextUiState())
    val state: StateFlow<CephTextUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val node = repo.readNode(serverId)
                when (kind) {
                    CephText.CONFIG -> coroutineScope {
                        val raw = async { repo.config(serverId, node) }
                        val db = async { runCatching { repo.configDb(serverId, node) }.getOrDefault(emptyList()) }
                        val lines = raw.await().lines()
                        val database = db.await()
                        ui.update { it.copy(loading = false, lines = lines, database = database) }
                    }
                    CephText.CRUSH -> {
                        val lines = repo.crushMap(serverId, node).lines()
                        ui.update { it.copy(loading = false, lines = lines) }
                    }
                    CephText.LOG -> {
                        val lines = repo.log(serverId, node)
                        ui.update { it.copy(loading = false, lines = lines) }
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.cephUiText()) }
            }
        }
    }

    fun setFilter(text: String) {
        ui.update { it.copy(filter = text) }
    }
}
