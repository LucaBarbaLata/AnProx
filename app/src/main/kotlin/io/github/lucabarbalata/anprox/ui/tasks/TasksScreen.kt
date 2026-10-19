package io.github.lucabarbalata.anprox.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.component.color
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.TaskState
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.formatDuration
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TasksScreen(
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    viewModel: TasksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val labels = mapOf(
        TaskFilter.ALL to stringResource(R.string.tasks_filter_all),
        TaskFilter.RUNNING to stringResource(R.string.tasks_filter_running, state.runningCount),
        TaskFilter.FAILED to stringResource(R.string.tasks_filter_failed, state.failedCount),
    )
    AnProxScaffold(title = stringResource(R.string.tasks_title)) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SegmentedPicker(
                    options = TaskFilter.entries,
                    selected = state.filter,
                    label = { labels.getValue(it) },
                    onSelect = viewModel::setFilter,
                )
            }
            state.errors.forEach { (server, error) ->
                item { ErrorBanner("$server: ${error.asString()}") }
            }
            if (!state.loaded) {
                item { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (state.items.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.TaskAlt,
                        title = stringResource(R.string.tasks_empty_title),
                        body = stringResource(R.string.tasks_empty_body),
                    )
                }
            }
            items(state.items, key = { it.serverId + it.task.upid }) { item ->
                TaskRow(item, state.showServerNames) {
                    // A Backup Server's API addresses its only node as localhost, whatever the UPID says.
                    onTask(item.serverId, if (item.product == ProxmoxProduct.PBS) "localhost" else item.task.node, item.task.upid)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(item: TaskItem, showServer: Boolean, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    val t = item.task
    val (tone, icon) = when (t.state) {
        TaskState.RUNNING -> Tone.INFO to null
        TaskState.OK -> Tone.SUCCESS to Icons.Outlined.CheckCircle
        TaskState.WARNING -> Tone.WARNING to Icons.Outlined.ReportProblem
        TaskState.FAILED -> Tone.DANGER to Icons.Outlined.ErrorOutline
    }
    GlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon == null) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tone.color())
            } else {
                Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(TaskTypes.label(t.type), t.id?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(if (showServer) item.serverName else null, t.node, t.user).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (t.state == TaskState.FAILED || t.state == TaskState.WARNING) {
                    Text(t.status.orEmpty(), style = MaterialTheme.typography.bodySmall, color = tone.color(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(relativeTime(t.starttime * 1000), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                t.endtime?.let { end ->
                    Text(formatDuration(end - t.starttime), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }
        }
    }
}
