package io.github.lucabarbalata.anprox.ui.backupserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.backupserver.DatastoreSummary
import io.github.lucabarbalata.anprox.data.backupserver.PbsRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pbs.BackupGroup
import io.github.lucabarbalata.anprox.model.pbs.BackupSnapshot
import io.github.lucabarbalata.anprox.model.pbs.CatalogEntry
import io.github.lucabarbalata.anprox.model.pbs.DatastoreMaintenance
import io.github.lucabarbalata.anprox.model.pbs.DatastoreStatus
import io.github.lucabarbalata.anprox.model.pbs.GcStatus
import io.github.lucabarbalata.anprox.model.pbs.KeepOptions
import io.github.lucabarbalata.anprox.model.pbs.PbsJob
import io.github.lucabarbalata.anprox.model.pbs.PbsNamespace
import io.github.lucabarbalata.anprox.model.pbs.PbsNodeStatus
import io.github.lucabarbalata.anprox.model.pbs.PbsRemote
import io.github.lucabarbalata.anprox.model.pbs.PbsRemoteForm
import io.github.lucabarbalata.anprox.model.pbs.PruneItem
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** Runs one Backup Server change at a time (a task waited for, or a plain call), reports it and reloads. */
internal class PbsChanges(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val guests: GuestRepository,
    private val busy: (UiText?) -> Unit,
    private val report: (TaskResult) -> Unit,
    private val reload: () -> Unit,
) {
    private var running = false

    fun task(working: UiText, ok: UiText, failed: UiText, start: suspend () -> String) {
        if (running) return
        running = true
        busy(working)
        scope.launch {
            val result = try {
                val upid = start()
                val succeeded = guests.awaitTask(serverId, PbsRepository.NODE, upid).succeeded
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
        busy(UiText.res(R.string.pbs_working))
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

// ------------------------------------------------------------------------------ the server

data class BackupServerUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val status: PbsNodeStatus? = null,
    val statusError: UiText? = null,
    val datastores: List<DatastoreSummary> = emptyList(),
)

/** A Backup Server: its node and datastores, and the way to jobs, remotes and node administration. */
class BackupServerViewModel(private val serverId: String, private val repo: PbsRepository) : ViewModel() {
    private val ui = MutableStateFlow(BackupServerUiState())
    val state: StateFlow<BackupServerUiState> = ui.asStateFlow()

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val datastores = repo.datastores(serverId)
                ui.update { it.copy(loading = false, datastores = datastores) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
        // The node's status needs Sys.Audit; without it only that card is missing.
        viewModelScope.launch {
            try {
                val status = repo.nodeStatus(serverId)
                ui.update { it.copy(status = status, statusError = null) }
            } catch (e: ApiException) {
                ui.update { it.copy(statusError = e.toUiText()) }
            }
        }
    }
}

// ------------------------------------------------------------------------------ one datastore

data class MaintenanceDraft(val type: String?, val message: String = "")

data class DatastoreUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val status: DatastoreStatus? = null,
    val gc: GcStatus? = null,
    val maintenance: DatastoreMaintenance = DatastoreMaintenance(),
    val namespaces: List<PbsNamespace> = listOf(PbsNamespace("")),
    /** The namespace shown; "" is the root. */
    val ns: String = "",
    val groups: List<BackupGroup> = emptyList(),
    val groupsLoading: Boolean = true,
    val newNamespace: String? = null,
    val confirmDeleteNamespace: Boolean = false,
    val deleteGroupsToo: Boolean = false,
    val maintenanceDraft: MaintenanceDraft? = null,
    val confirmVerify: Boolean = false,
    val busy: UiText? = null,
    val result: TaskResult? = null,
)

/** Datacenter of a Backup Server → a datastore: usage, garbage collection, maintenance, namespaces and backup groups. */
class DatastoreViewModel(
    private val serverId: String,
    private val store: String,
    private val repo: PbsRepository,
    guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(DatastoreUiState())
    val state: StateFlow<DatastoreUiState> = ui.asStateFlow()
    private val changes = PbsChanges(viewModelScope, serverId, guests, { b -> ui.update { it.copy(busy = b) } }, { r -> ui.update { it.copy(result = r) } }, ::load)

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val status = async { repo.status(serverId, store) }
                    val gc = async { runCatching { repo.gc(serverId, store) }.getOrNull() }
                    val maintenance = async { runCatching { repo.maintenance(serverId, store) }.getOrDefault(DatastoreMaintenance()) }
                    val namespaces = async { runCatching { repo.namespaces(serverId, store) }.getOrDefault(listOf(PbsNamespace(""))) }
                    val st = status.await()
                    val g = gc.await() ?: st.gc
                    val m = maintenance.await()
                    val n = namespaces.await()
                    ui.update { s -> s.copy(loading = false, status = st, gc = g, maintenance = m, namespaces = n, ns = s.ns.takeIf { ns -> n.any { it.ns == ns } } ?: "") }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
            loadGroups()
        }
    }

    private suspend fun loadGroups() {
        val ns = ui.value.ns
        ui.update { it.copy(groupsLoading = true) }
        try {
            val groups = repo.groups(serverId, store, ns)
            ui.update { s -> if (s.ns == ns) s.copy(groupsLoading = false, groups = groups) else s }
        } catch (e: ApiException) {
            ui.update { s -> if (s.ns == ns) s.copy(groupsLoading = false, groups = emptyList(), error = s.error ?: e.toUiText()) else s }
        }
    }

    fun selectNamespace(ns: String) {
        if (ns == ui.value.ns) return
        ui.update { it.copy(ns = ns, groups = emptyList()) }
        viewModelScope.launch { loadGroups() }
    }

    fun startGc() {
        changes.task(UiText.res(R.string.pbs_gc_running), UiText.res(R.string.pbs_gc_done), UiText.res(R.string.pbs_task_failed)) { repo.startGc(serverId, store) }
    }

    fun requestVerify() {
        ui.update { it.copy(confirmVerify = true) }
    }

    fun dismissVerify() {
        ui.update { it.copy(confirmVerify = false) }
    }

    /** Verifies everything in the namespace shown; [onlyNew] skips snapshots verified before. */
    fun verify(onlyNew: Boolean) {
        val ns = ui.value.ns
        ui.update { it.copy(confirmVerify = false) }
        changes.task(UiText.res(R.string.pbs_verify_running), UiText.res(R.string.pbs_verify_done), UiText.res(R.string.pbs_verify_failed)) {
            repo.verify(serverId, store, ns, ignoreVerified = onlyNew)
        }
    }

    fun startMaintenance() {
        ui.update { it.copy(maintenanceDraft = MaintenanceDraft(it.maintenance.type, it.maintenance.message.orEmpty())) }
    }

    fun editMaintenance(change: MaintenanceDraft.() -> MaintenanceDraft) {
        ui.update { s -> s.copy(maintenanceDraft = s.maintenanceDraft?.change()) }
    }

    fun closeMaintenance() {
        ui.update { it.copy(maintenanceDraft = null) }
    }

    fun saveMaintenance() {
        val draft = ui.value.maintenanceDraft ?: return
        ui.update { it.copy(maintenanceDraft = null) }
        val done = if (draft.type == null) UiText.res(R.string.pbs_maintenance_off) else UiText.res(R.string.pbs_maintenance_on, draft.type)
        changes.call(done) { repo.setMaintenance(serverId, store, draft.type, draft.message) }
    }

    fun startNamespace() {
        ui.update { it.copy(newNamespace = "") }
    }

    fun editNamespace(name: String) {
        ui.update { it.copy(newNamespace = name) }
    }

    fun closeNamespace() {
        ui.update { it.copy(newNamespace = null) }
    }

    fun createNamespace() {
        val s = ui.value
        val name = s.newNamespace?.trim() ?: return
        if (!PbsNamespace.NAME.matches(name)) return
        ui.update { it.copy(newNamespace = null) }
        val parent = s.ns
        changes.call(UiText.res(R.string.pbs_namespace_created, if (parent.isEmpty()) name else "$parent/$name")) { repo.createNamespace(serverId, store, parent, name) }
    }

    fun requestDeleteNamespace() {
        ui.update { it.copy(confirmDeleteNamespace = true, deleteGroupsToo = false) }
    }

    fun setDeleteGroupsToo(value: Boolean) {
        ui.update { it.copy(deleteGroupsToo = value) }
    }

    fun dismissDeleteNamespace() {
        ui.update { it.copy(confirmDeleteNamespace = false) }
    }

    fun deleteNamespace() {
        val s = ui.value
        if (s.ns.isEmpty()) return
        val ns = s.ns
        ui.update { it.copy(confirmDeleteNamespace = false, ns = PbsNamespace(ns).parent) }
        changes.call(UiText.res(R.string.pbs_namespace_deleted, ns)) { repo.deleteNamespace(serverId, store, ns, s.deleteGroupsToo) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ one backup group

/** The prune dialog: the rules, and what a dry run says they'd keep. */
data class PruneDraft(val keep: KeepOptions = KeepOptions(last = "3"), val preview: List<PruneItem>? = null, val previewing: Boolean = false, val error: UiText? = null) {
    val removes: Int get() = preview?.count { !it.keep } ?: 0
}

data class BackupGroupUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val snapshots: List<BackupSnapshot> = emptyList(),
    val selected: BackupSnapshot? = null,
    val notes: String? = null,
    val confirmForget: BackupSnapshot? = null,
    val prune: PruneDraft? = null,
    val confirmDeleteGroup: Boolean = false,
    val busy: UiText? = null,
    val result: TaskResult? = null,
    /** The group was deleted: the screen closes. */
    val deleted: Boolean = false,
)

/** The snapshots of one guest or host: notes, protection, verify, forget, prune, file restore. */
class BackupGroupViewModel(
    private val serverId: String,
    private val store: String,
    private val ns: String,
    private val group: BackupGroup,
    private val repo: PbsRepository,
    guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(BackupGroupUiState())
    val state: StateFlow<BackupGroupUiState> = ui.asStateFlow()
    private val changes = PbsChanges(viewModelScope, serverId, guests, { b -> ui.update { it.copy(busy = b) } }, { r -> ui.update { it.copy(result = r) } }, ::load)

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshots = repo.snapshots(serverId, store, ns, group)
                ui.update { s -> s.copy(loading = false, snapshots = snapshots, selected = s.selected?.let { old -> snapshots.firstOrNull { it.time == old.time } }) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun select(snapshot: BackupSnapshot?) {
        ui.update { it.copy(selected = snapshot, notes = null) }
    }

    fun editNotes() {
        ui.update { it.copy(notes = it.selected?.comment.orEmpty()) }
    }

    fun changeNotes(text: String) {
        ui.update { it.copy(notes = text) }
    }

    fun saveNotes() {
        val s = ui.value
        val snapshot = s.selected ?: return
        val notes = s.notes ?: return
        ui.update { it.copy(notes = null) }
        changes.call(UiText.res(R.string.pbs_notes_saved)) { repo.setNotes(serverId, store, ns, snapshot, notes) }
    }

    fun setProtected(snapshot: BackupSnapshot, protected: Boolean) {
        changes.call(UiText.res(if (protected) R.string.pbs_protected_on else R.string.pbs_protected_off)) { repo.setProtected(serverId, store, ns, snapshot, protected) }
    }

    fun verify(snapshot: BackupSnapshot?) {
        ui.update { it.copy(selected = null) }
        changes.task(UiText.res(R.string.pbs_verify_running), UiText.res(R.string.pbs_verify_done), UiText.res(R.string.pbs_verify_failed)) {
            repo.verify(serverId, store, ns, group = group, snapshot = snapshot)
        }
    }

    fun requestForget(snapshot: BackupSnapshot) {
        ui.update { it.copy(selected = null, confirmForget = snapshot) }
    }

    fun dismissForget() {
        ui.update { it.copy(confirmForget = null) }
    }

    fun forget() {
        val snapshot = ui.value.confirmForget ?: return
        ui.update { it.copy(confirmForget = null) }
        changes.call(UiText.res(R.string.pbs_snapshot_forgotten)) { repo.deleteSnapshot(serverId, store, ns, snapshot) }
    }

    fun startPrune() {
        ui.update { it.copy(prune = PruneDraft()) }
        preview()
    }

    fun editPrune(change: KeepOptions.() -> KeepOptions) {
        ui.update { s -> s.copy(prune = s.prune?.let { p -> p.copy(keep = p.keep.change(), preview = null, error = null) }) }
    }

    /** A dry run with the current rules. */
    fun preview() {
        val draft = ui.value.prune ?: return
        if (!draft.keep.valid) return
        ui.update { s -> s.copy(prune = s.prune?.copy(previewing = true, error = null)) }
        viewModelScope.launch {
            val next = try {
                draft.copy(preview = repo.prune(serverId, store, ns, group, draft.keep, dryRun = true), previewing = false)
            } catch (e: ApiException) {
                draft.copy(previewing = false, error = e.toUiText())
            }
            ui.update { s -> if (s.prune?.keep == draft.keep) s.copy(prune = next) else s }
        }
    }

    fun closePrune() {
        ui.update { it.copy(prune = null) }
    }

    fun prune() {
        val draft = ui.value.prune ?: return
        if (!draft.keep.valid) return
        ui.update { it.copy(prune = null) }
        changes.call(UiText.res(R.string.pbs_pruned)) { repo.prune(serverId, store, ns, group, draft.keep, dryRun = false) }
    }

    fun requestDeleteGroup() {
        ui.update { it.copy(confirmDeleteGroup = true) }
    }

    fun dismissDeleteGroup() {
        ui.update { it.copy(confirmDeleteGroup = false) }
    }

    fun deleteGroup() {
        ui.update { it.copy(confirmDeleteGroup = false, busy = UiText.res(R.string.pbs_working)) }
        viewModelScope.launch {
            try {
                repo.deleteGroup(serverId, store, ns, group)
                ui.update { it.copy(busy = null, deleted = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = null, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ file restore

data class FileRestoreUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    /** The folders opened, from the top; empty at the top. */
    val path: List<CatalogEntry> = emptyList(),
    val entries: List<CatalogEntry> = emptyList(),
    /** Waiting for the user to pick where to save it. */
    val saving: CatalogEntry? = null,
    val downloading: CatalogEntry? = null,
    val result: TaskResult? = null,
)

/** A snapshot's files, from its catalog: browse folders, save a file or a folder (as a zip). */
class FileRestoreViewModel(
    private val serverId: String,
    private val store: String,
    private val ns: String,
    private val snapshot: BackupSnapshot,
    private val repo: PbsRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(FileRestoreUiState())
    val state: StateFlow<FileRestoreUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        val at = ui.value.path.lastOrNull()
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = repo.catalog(serverId, store, ns, snapshot, at?.filepath ?: CatalogEntry.ROOT)
                ui.update { s -> if (s.path.lastOrNull() == at) s.copy(loading = false, entries = entries) else s }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun open(entry: CatalogEntry) {
        if (!entry.browsable) return
        ui.update { it.copy(path = it.path + entry, entries = emptyList()) }
        load()
    }

    /** One level up; false at the top (the screen closes then). */
    fun up(): Boolean {
        if (ui.value.path.isEmpty()) return false
        ui.update { it.copy(path = it.path.dropLast(1), entries = emptyList()) }
        load()
        return true
    }

    /** Goes back to the folder at [depth] (0 = the top). */
    fun jump(depth: Int) {
        if (depth >= ui.value.path.size) return
        ui.update { it.copy(path = it.path.take(depth), entries = emptyList()) }
        load()
    }

    fun requestSave(entry: CatalogEntry) {
        ui.update { it.copy(saving = entry) }
    }

    fun cancelSave() {
        ui.update { it.copy(saving = null) }
    }

    /** Writes the chosen entry to what [open] gives (the file the user picked). */
    fun save(open: () -> OutputStream?) {
        val entry = ui.value.saving ?: return
        ui.update { it.copy(saving = null, downloading = entry) }
        viewModelScope.launch {
            val result = try {
                val bytes = withContext(Dispatchers.IO) {
                    (open() ?: throw IOException("can't write there")).use { out -> repo.download(serverId, store, ns, snapshot, entry, out) }
                }
                TaskResult(UiText.res(R.string.pbs_file_saved, entry.name, Formatters.bytes(bytes)), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            } catch (e: IOException) {
                TaskResult(UiText.res(R.string.pbs_file_save_failed, e.message.orEmpty()), null, ok = false)
            }
            ui.update { it.copy(downloading = null, result = result) }
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

// ------------------------------------------------------------------------------ jobs and remotes

data class PbsJobsUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val jobs: List<PbsJob> = emptyList(),
    val selected: PbsJob? = null,
    val busy: UiText? = null,
    val result: TaskResult? = null,
)

/** A Backup Server's sync, verify and prune jobs: when they last ran and how, run one now. */
class PbsJobsViewModel(
    private val serverId: String,
    private val repo: PbsRepository,
    guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(PbsJobsUiState())
    val state: StateFlow<PbsJobsUiState> = ui.asStateFlow()
    private val changes = PbsChanges(viewModelScope, serverId, guests, { b -> ui.update { it.copy(busy = b) } }, { r -> ui.update { it.copy(result = r) } }, ::load)

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val jobs = repo.jobs(serverId)
                ui.update { it.copy(loading = false, jobs = jobs) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun select(job: PbsJob?) {
        ui.update { it.copy(selected = job) }
    }

    fun run(job: PbsJob) {
        ui.update { it.copy(selected = null) }
        changes.task(UiText.res(R.string.pbs_job_running, job.id), UiText.res(R.string.pbs_job_done, job.id), UiText.res(R.string.pbs_job_failed, job.id)) { repo.runJob(serverId, job) }
    }

    fun setEnabled(job: PbsJob, enabled: Boolean) {
        ui.update { it.copy(selected = null) }
        changes.call(UiText.res(if (enabled) R.string.pbs_job_enabled else R.string.pbs_job_disabled, job.id)) { repo.setPruneJobEnabled(serverId, job, enabled) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}

data class PbsRemotesUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val remotes: List<PbsRemote> = emptyList(),
    val adding: PbsRemoteForm? = null,
    val confirmDelete: PbsRemote? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
)

/** The Backup Servers this one syncs from. */
class PbsRemotesViewModel(private val serverId: String, private val repo: PbsRepository) : ViewModel() {
    private val ui = MutableStateFlow(PbsRemotesUiState())
    val state: StateFlow<PbsRemotesUiState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val remotes = repo.remotes(serverId)
                ui.update { it.copy(loading = false, remotes = remotes) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startAdd() {
        ui.update { it.copy(adding = PbsRemoteForm()) }
    }

    fun edit(change: PbsRemoteForm.() -> PbsRemoteForm) {
        ui.update { s -> s.copy(adding = s.adding?.change()) }
    }

    fun closeAdd() {
        ui.update { it.copy(adding = null) }
    }

    fun add() {
        val form = ui.value.adding ?: return
        if (form.problem() != null) return
        ui.update { it.copy(adding = null) }
        run(UiText.res(R.string.pbs_remote_added, form.name.trim())) { repo.createRemote(serverId, form) }
    }

    fun requestDelete(remote: PbsRemote) {
        ui.update { it.copy(confirmDelete = remote) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val remote = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.pbs_remote_deleted, remote.name)) { repo.deleteRemote(serverId, remote.name) }
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
