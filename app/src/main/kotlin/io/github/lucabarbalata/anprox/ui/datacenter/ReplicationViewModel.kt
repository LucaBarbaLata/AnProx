package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.ReplicationOverview
import io.github.lucabarbalata.anprox.data.datacenter.ReplicationRepository
import io.github.lucabarbalata.anprox.model.pve.ReplicationJob
import io.github.lucabarbalata.anprox.model.pve.ReplicationJobForm
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The job dialog: adding one ([job] null) or editing [job]. */
data class ReplicationEdit(
    val job: ReplicationJob?,
    val form: ReplicationJobForm,
    /** The schedule's next runs (epoch seconds), as Proxmox reads it; null while unknown. */
    val nextRuns: List<Long>? = null,
    val scheduleInvalid: Boolean = false,
)

/** A job's last run log, shown in a dialog. */
data class ReplicationLog(val job: ReplicationJob, val lines: List<TaskLogLine>?, val error: UiText? = null)

data class ReplicationUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val overview: ReplicationOverview? = null,
    val editing: ReplicationEdit? = null,
    val confirmDelete: ReplicationJob? = null,
    val log: ReplicationLog? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    val jobs: List<ReplicationJob> get() = overview?.jobs.orEmpty()

    /** Where the guest runs now; status and "run now" go to that node. */
    fun sourceOf(job: ReplicationJob): String? = overview?.guest(job.guest)?.node

    fun guestLabel(vmid: Int): String = overview?.guest(vmid)?.name?.let { "$vmid · $it" } ?: vmid.toString()

    val canAdd: Boolean get() = !busy && overview != null && overview.guests.isNotEmpty() && overview.onlineNodes.size > 1
}

/** Datacenter → Replication (questionnaire Q21). */
class ReplicationViewModel(
    private val serverId: String,
    private val repo: ReplicationRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(ReplicationUiState())
    val state: StateFlow<ReplicationUiState> = ui.asStateFlow()
    private var preview: Job? = null

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val overview = repo.overview(serverId)
                ui.update { it.copy(loading = false, overview = overview) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun add() {
        val overview = ui.value.overview ?: return
        val guest = overview.guests.firstOrNull()
        val target = overview.onlineNodes.firstOrNull { it != guest?.node }
        ui.update { it.copy(editing = ReplicationEdit(null, ReplicationJobForm(guest = guest?.vmid, target = target))) }
        updatePreview(immediately = true)
    }

    fun edit(job: ReplicationJob) {
        ui.update { it.copy(editing = ReplicationEdit(job, ReplicationJobForm.from(job))) }
        updatePreview(immediately = true)
    }

    fun editForm(change: ReplicationJobForm.() -> ReplicationJobForm) {
        val before = ui.value.editing?.form ?: return
        val after = before.change()
        // A new guest may live on the chosen target: pick another node.
        val fixed = if (after.guest != before.guest && after.target == ui.value.overview?.guest(after.guest ?: -1)?.node) {
            after.copy(target = ui.value.overview?.onlineNodes?.firstOrNull { it != ui.value.overview?.guest(after.guest ?: -1)?.node })
        } else {
            after
        }
        ui.update { s -> s.copy(editing = s.editing?.copy(form = fixed)) }
        if (fixed.schedule != before.schedule) updatePreview(immediately = false)
    }

    fun close() {
        preview?.cancel()
        ui.update { it.copy(editing = null) }
    }

    fun save() {
        val s = ui.value
        val edit = s.editing ?: return
        val guestNode = edit.form.guest?.let { s.overview?.guest(it)?.node }
        if (edit.scheduleInvalid || edit.form.problem(guestNode, s.jobs, creating = edit.job == null) != null) return
        close()
        run(UiText.res(if (edit.job == null) R.string.replication_added else R.string.replication_saved)) {
            if (edit.job == null) repo.create(serverId, edit.form, s.jobs) else repo.update(serverId, edit.job, edit.form)
        }
    }

    fun setEnabled(job: ReplicationJob, enabled: Boolean) {
        run(UiText.res(if (enabled) R.string.replication_switched_on else R.string.replication_switched_off)) { repo.setEnabled(serverId, job, enabled) }
    }

    fun runNow(job: ReplicationJob) {
        val node = ui.value.sourceOf(job) ?: return
        close()
        run(UiText.res(R.string.replication_run_started)) { repo.runNow(serverId, node, job) }
    }

    fun showLog(job: ReplicationJob) {
        val node = ui.value.sourceOf(job) ?: return
        ui.update { it.copy(editing = null, log = ReplicationLog(job, null)) }
        viewModelScope.launch {
            val log = try {
                ReplicationLog(job, repo.log(serverId, node, job))
            } catch (e: ApiException) {
                ReplicationLog(job, emptyList(), e.toUiText())
            }
            ui.update { s -> if (s.log?.job?.id == job.id) s.copy(log = log) else s }
        }
    }

    fun closeLog() {
        ui.update { it.copy(log = null) }
    }

    fun requestDelete(job: ReplicationJob) {
        ui.update { it.copy(editing = null, confirmDelete = job) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete(keepCopies: Boolean) {
        val job = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        run(UiText.res(R.string.replication_deleted)) { repo.delete(serverId, job, keepCopies) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    /** Asks Proxmox when the schedule would run, after a short pause in typing. */
    private fun updatePreview(immediately: Boolean) {
        preview?.cancel()
        val schedule = ui.value.editing?.form?.schedule?.trim() ?: return
        if (schedule.isEmpty()) {
            ui.update { s -> s.copy(editing = s.editing?.copy(nextRuns = null, scheduleInvalid = false)) }
            return
        }
        preview = viewModelScope.launch {
            if (!immediately) delay(PREVIEW_DELAY_MILLIS)
            val (runs, invalid) = try {
                repo.nextRuns(serverId, schedule) to false
            } catch (_: ApiException) {
                null to true
            }
            ui.update { s -> s.copy(editing = s.editing?.copy(nextRuns = runs, scheduleInvalid = invalid)) }
        }
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

    private companion object {
        const val PREVIEW_DELAY_MILLIS = 600L
    }
}
