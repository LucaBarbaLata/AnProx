package io.github.lucabarbalata.anprox.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskLogState(
    val upid: Upid?,
    val status: TaskStatus? = null,
    val lines: List<TaskLogLine> = emptyList(),
    val error: UiText? = null,
)

/** Stopping the task: waiting for confirmation, the request running, or why it failed. */
data class TaskStopState(val confirm: Boolean = false, val stopping: Boolean = false, val error: UiText? = null)

/** Follows a task's status and log, refreshing every 2 s until it finishes. */
class TaskLogViewModel(
    private val serverId: String,
    private val node: String,
    private val upid: String,
    private val guests: GuestRepository,
) : ViewModel() {
    val state: StateFlow<TaskLogState> = flow {
        var current = TaskLogState(Upid.parse(upid))
        while (true) {
            current = try {
                val status = guests.taskStatus(serverId, node, upid)
                val lines = guests.taskLog(serverId, node, upid, start = 0, limit = 5_000)
                current.copy(status = status, lines = lines, error = null)
            } catch (e: ApiException) {
                current.copy(error = e.toUiText())
            }
            emit(current)
            if (current.status?.isRunning == false) break
            delay(2_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskLogState(Upid.parse(upid)))

    private val stopUi = MutableStateFlow(TaskStopState())
    val stop: StateFlow<TaskStopState> = stopUi.asStateFlow()

    fun requestStop() = stopUi.update { it.copy(confirm = true, error = null) }

    fun dismissStop() = stopUi.update { it.copy(confirm = false) }

    /** The log keeps refreshing, so it shows the task ending ("interrupted by signal"). */
    fun stopTask() {
        stopUi.update { it.copy(confirm = false, stopping = true, error = null) }
        viewModelScope.launch {
            try {
                guests.stopTask(serverId, node, upid)
                stopUi.update { it.copy(stopping = false) }
            } catch (e: ApiException) {
                stopUi.update { it.copy(stopping = false, error = e.toUiText()) }
            }
        }
    }
}
