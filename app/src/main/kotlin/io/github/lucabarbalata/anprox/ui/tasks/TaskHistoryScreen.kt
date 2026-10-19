package io.github.lucabarbalata.anprox.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class TaskHistoryState(
    val loading: Boolean = true,
    val tasks: List<TaskSummary> = emptyList(),
    val errorsOnly: Boolean = false,
    /** The last page was full, so there may be older tasks. */
    val hasMore: Boolean = false,
    val error: UiText? = null,
)

/** Node → Task history: everything the node ran, page by page (the Tasks tab shows the newest only). */
class TaskHistoryViewModel(
    private val serverId: String,
    private val node: String,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(TaskHistoryState())
    val state: StateFlow<TaskHistoryState> = ui.asStateFlow()
    private var loading: Job? = null

    init {
        reload()
    }

    fun reload() = load(append = false)

    fun loadMore() {
        if (!ui.value.loading && ui.value.hasMore) load(append = true)
    }

    fun setErrorsOnly(errorsOnly: Boolean) {
        if (errorsOnly == ui.value.errorsOnly) return
        ui.update { it.copy(errorsOnly = errorsOnly, tasks = emptyList()) }
        reload()
    }

    private fun load(append: Boolean) {
        loading?.cancel()
        val start = if (append) ui.value.tasks.size else 0
        val errorsOnly = ui.value.errorsOnly
        ui.update { it.copy(loading = true, error = null) }
        loading = viewModelScope.launch {
            try {
                val page = guests.taskHistory(serverId, node, start, PAGE, errorsOnly)
                ui.update { s -> s.copy(loading = false, tasks = if (append) s.tasks + page else page, hasMore = page.size == PAGE) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    private companion object {
        const val PAGE = 50
    }
}

@Composable
fun TaskHistoryScreen(
    serverId: String,
    node: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: TaskHistoryViewModel = koinViewModel(key = "taskhistory-$serverId-$node") { parametersOf(serverId, node) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val all = stringResource(R.string.taskhistory_all)
    val failed = stringResource(R.string.taskhistory_failed)
    AnProxScaffold(
        title = stringResource(R.string.taskhistory_title),
        subtitle = node,
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::reload, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ChoiceChips(listOf(false, true), state.errorsOnly, { if (it) failed else all }, viewModel::setErrorsOnly)
            }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::reload) }
            }
            items(state.tasks, key = { it.upid }) { t ->
                TaskSummaryRow(t, t.user, onClick = { onTask(serverId, t.node.ifEmpty { node }, t.upid) })
            }
            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                state.error == null && state.tasks.isEmpty() -> item {
                    EmptyState(Icons.Outlined.History, stringResource(R.string.taskhistory_empty), stringResource(R.string.taskhistory_empty_body))
                }
                state.hasMore -> item {
                    AnProxButton(
                        stringResource(R.string.taskhistory_more),
                        onClick = viewModel::loadMore,
                        style = ButtonStyle.SECONDARY,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
