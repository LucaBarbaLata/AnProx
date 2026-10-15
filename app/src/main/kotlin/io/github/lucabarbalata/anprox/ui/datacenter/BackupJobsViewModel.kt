package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.BackupJobRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.pve.BackupJob
import io.github.lucabarbalata.anprox.model.pve.NotBackedUp
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

data class BackupJobsState(
    val serverId: String,
    val loading: Boolean = true,
    val error: UiText? = null,
    val jobs: List<BackupJob> = emptyList(),
    val notBackedUp: List<NotBackedUp>? = null,
    /** VMID → name, to describe which guests a job lists. */
    val guestNames: Map<Int, String> = emptyMap(),
    /** The job whose "run now" is running. */
    val running: String? = null,
    val confirmRun: BackupJob? = null,
    val result: TaskResult? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && jobs.isEmpty()
}

/** Datacenter → Backup jobs (questionnaire Q20). */
class BackupJobsViewModel(
    private val serverId: String,
    private val repo: BackupJobRepository,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(BackupJobsState(serverId))
    val state: StateFlow<BackupJobsState> = ui.asStateFlow()

    fun refresh() {
        ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val jobsCall = async { repo.jobs(serverId) }
                    val missingCall = async { repo.notBackedUp(serverId) }
                    val resourcesCall = async { repo.resources(serverId) }
                    val names = resourcesCall.await().filter { it.isGuest && it.vmid != null }.associate { it.vmid!! to (it.name ?: it.vmid.toString()) }
                    val jobs = jobsCall.await()
                    val missing = missingCall.await()
                    ui.update { it.copy(loading = false, error = null, jobs = jobs, notBackedUp = missing, guestNames = names) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setEnabled(job: BackupJob, enabled: Boolean) {
        ui.update { s -> s.copy(jobs = s.jobs.map { if (it.id == job.id) it.copy(enabled = enabled) else it }) }
        viewModelScope.launch {
            try {
                repo.setEnabled(serverId, job.id, enabled)
            } catch (e: ApiException) {
                ui.update { it.copy(result = TaskResult(e.toUiText(), null, ok = false)) }
            }
            refresh()
        }
    }

    fun requestRun(job: BackupJob) {
        ui.update { it.copy(confirmRun = job) }
    }

    fun dismissRun() {
        ui.update { it.copy(confirmRun = null) }
    }

    fun confirmRun() {
        val job = ui.value.confirmRun ?: return
        ui.update { it.copy(confirmRun = null, running = job.id) }
        viewModelScope.launch {
            val result = try {
                val started = repo.runNow(serverId, job)
                if (started.isEmpty()) {
                    TaskResult(UiText.res(R.string.backupjobs_nothing_to_run), null, ok = false)
                } else {
                    val ok = started.map { guests.awaitTask(serverId, it.node, it.upid).succeeded }.all { it }
                    TaskResult(UiText.res(if (ok) R.string.backupjobs_run_done else R.string.backupjobs_run_failed), started.first().upid, ok)
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(running = null, result = result) }
            refresh()
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}
