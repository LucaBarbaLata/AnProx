package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.BackupJobRepository
import io.github.lucabarbalata.anprox.model.pve.BackupJob
import io.github.lucabarbalata.anprox.model.pve.BackupJobForm
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ResourcePool
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.StorageConfig
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupJobEditorState(
    val serverId: String,
    /** Null for a new job. */
    val jobId: String?,
    val loading: Boolean = true,
    val error: UiText? = null,
    val original: BackupJob? = null,
    val form: BackupJobForm = BackupJobForm(),
    val storages: List<StorageConfig> = emptyList(),
    val pools: List<ResourcePool> = emptyList(),
    /** Guests (no templates), by VMID. */
    val guests: List<ClusterResource> = emptyList(),
    val nodes: List<String> = emptyList(),
    /** The schedule's next runs (epoch seconds), as Proxmox reads it; null while unknown. */
    val nextRuns: List<Long>? = null,
    val scheduleInvalid: Boolean = false,
    val busy: Boolean = false,
    val saveError: UiText? = null,
    val confirmDelete: Boolean = false,
    /** Set once saved or deleted: the screen goes back and says so. */
    val done: UiText? = null,
) {
    val isNew: Boolean get() = jobId == null
    val problem: BackupJobForm.Problem? get() = form.problem()
    val canSave: Boolean get() = !loading && !busy && problem == null && !scheduleInvalid
}

/** Creates or edits one backup job (questionnaire Q20). */
class BackupJobEditorViewModel(
    private val serverId: String,
    jobId: String?,
    private val repo: BackupJobRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(BackupJobEditorState(serverId, jobId))
    val state: StateFlow<BackupJobEditorState> = ui.asStateFlow()
    private var preview: Job? = null

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val jobsCall = async { repo.jobs(serverId) }
                    val storagesCall = async { repo.backupStorages(serverId) }
                    val poolsCall = async { repo.pools(serverId) }
                    val resourcesCall = async { repo.resources(serverId) }
                    val storages = storagesCall.await()
                    val pools = poolsCall.await()
                    val all = resourcesCall.await()
                    val original = ui.value.jobId?.let { id -> jobsCall.await().firstOrNull { it.id == id } }
                    ui.update { s ->
                        val form = when {
                            original != null -> BackupJobForm.from(original)
                            // A new job goes to the first storage, a Backup Server if there is one.
                            else -> s.form.copy(storage = s.form.storage ?: storages.firstOrNull()?.storage)
                        }
                        s.copy(
                            loading = false,
                            original = original,
                            form = form,
                            storages = storages,
                            pools = pools,
                            guests = all.filter { it.isGuest && !it.template && it.vmid != null }.sortedBy { it.vmid },
                            nodes = all.filter { it.resourceType == ResourceType.NODE }.mapNotNull { it.node }.sorted(),
                        )
                    }
                }
                updatePreview(immediately = true)
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun edit(change: BackupJobForm.() -> BackupJobForm) {
        val before = ui.value.form.schedule
        ui.update { it.copy(form = it.form.change(), saveError = null) }
        if (ui.value.form.schedule != before) updatePreview(immediately = false)
    }

    fun toggleGuest(vmid: Int) = edit { copy(vmids = if (vmid in vmids) vmids - vmid else vmids + vmid) }

    /** Asks Proxmox when the schedule would run, after a short pause in typing. */
    private fun updatePreview(immediately: Boolean) {
        preview?.cancel()
        val schedule = ui.value.form.schedule.trim()
        if (schedule.isEmpty()) {
            ui.update { it.copy(nextRuns = null, scheduleInvalid = false) }
            return
        }
        preview = viewModelScope.launch {
            if (!immediately) delay(PREVIEW_DELAY_MILLIS)
            try {
                val runs = repo.nextRuns(serverId, schedule)
                ui.update { it.copy(nextRuns = runs, scheduleInvalid = false) }
            } catch (_: ApiException) {
                ui.update { it.copy(nextRuns = null, scheduleInvalid = true) }
            }
        }
    }

    fun save() {
        val s = ui.value
        if (!s.canSave) return
        ui.update { it.copy(busy = true, saveError = null) }
        viewModelScope.launch {
            try {
                if (s.jobId == null) repo.create(serverId, s.form) else repo.update(serverId, s.jobId, s.form)
                ui.update { it.copy(busy = false, done = UiText.res(if (s.isNew) R.string.backupjob_created else R.string.backupjob_saved)) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, saveError = e.toUiText()) }
            }
        }
    }

    fun requestDelete() {
        ui.update { it.copy(confirmDelete = true) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = false) }
    }

    fun delete() {
        val id = ui.value.jobId ?: return
        ui.update { it.copy(confirmDelete = false, busy = true) }
        viewModelScope.launch {
            try {
                repo.delete(serverId, id)
                ui.update { it.copy(busy = false, done = UiText.res(R.string.backupjob_deleted)) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, saveError = e.toUiText()) }
            }
        }
    }

    private companion object {
        const val PREVIEW_DELAY_MILLIS = 500L
    }
}
