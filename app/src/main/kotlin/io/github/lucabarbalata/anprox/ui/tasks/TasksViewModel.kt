package io.github.lucabarbalata.anprox.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.pve.TaskState
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class TaskFilter { ALL, RUNNING, FAILED }

data class TaskItem(
    val serverId: String,
    val serverName: String,
    val product: ProxmoxProduct,
    val task: TaskSummary,
)

data class TasksState(
    val loaded: Boolean = false,
    val items: List<TaskItem> = emptyList(),
    val filter: TaskFilter = TaskFilter.ALL,
    val errors: List<Pair<String, UiText>> = emptyList(),
    val showServerNames: Boolean = false,
    val runningCount: Int = 0,
    val failedCount: Int = 0,
)

/** Recent tasks of all servers merged into one timeline. */
class TasksViewModel(snapshots: SnapshotRepository) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter.ALL)

    val state: StateFlow<TasksState> = combine(snapshots.tasks(), filter) { perServer, f ->
        val all = perServer
            .flatMap { s -> s.tasks.map { TaskItem(s.server.id, s.server.profile.name, s.server.profile.product, it) } }
            .distinctBy { it.serverId + it.task.upid }
            .sortedByDescending { it.task.starttime }
        TasksState(
            loaded = true,
            items = all.filter {
                when (f) {
                    TaskFilter.ALL -> true
                    TaskFilter.RUNNING -> it.task.state == TaskState.RUNNING
                    TaskFilter.FAILED -> it.task.state == TaskState.FAILED
                }
            },
            filter = f,
            errors = perServer.mapNotNull { s -> s.error?.let { s.server.profile.name to it.toUiText() } },
            showServerNames = perServer.size > 1,
            runningCount = all.count { it.task.state == TaskState.RUNNING },
            failedCount = all.count { it.task.state == TaskState.FAILED },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksState())

    fun setFilter(f: TaskFilter) {
        filter.value = f
    }
}
