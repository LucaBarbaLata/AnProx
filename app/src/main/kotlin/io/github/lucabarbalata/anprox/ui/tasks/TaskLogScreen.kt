package io.github.lucabarbalata.anprox.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.InfoRow
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.core.designsystem.theme.MonoStyle
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.relativeTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TaskLogScreen(
    serverId: String,
    node: String,
    upid: String,
    onBack: () -> Unit,
    viewModel: TaskLogViewModel = koinViewModel(key = "task-$serverId-$upid") { parametersOf(serverId, node, upid) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val listState = rememberLazyListState()
    // Follow the log while it grows.
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }
    val type = state.upid?.type ?: state.status?.type.orEmpty()
    AnProxScaffold(title = TaskTypes.label(type), subtitle = node, onBack = onBack) { inner ->
        LazyColumn(state = listState, contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { e -> item { ErrorBanner(e.asString()) } }
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val s = state.status
                        when {
                            s == null -> StatusPill(stringResource(R.string.status_unknown), Tone.NEUTRAL)
                            s.isRunning -> StatusPill(stringResource(R.string.task_running), Tone.INFO)
                            s.exitstatus == "OK" -> StatusPill(stringResource(R.string.task_ok), Tone.SUCCESS)
                            s.succeeded -> StatusPill(s.exitstatus.orEmpty(), Tone.WARNING)
                            else -> StatusPill(stringResource(R.string.task_failed), Tone.DANGER)
                        }
                        Spacer(Modifier.width(10.dp))
                        state.upid?.let { Text(relativeTime(it.startTimeEpochSeconds * 1000), style = MaterialTheme.typography.labelMedium, color = c.textSecondary) }
                    }
                    Spacer(Modifier.height(8.dp))
                    state.upid?.let { u ->
                        if (u.id.isNotBlank()) InfoRow(stringResource(R.string.task_target), u.id)
                        InfoRow(stringResource(R.string.task_user), u.user)
                    }
                    state.status?.exitstatus?.takeIf { it != "OK" }?.let { InfoRow(stringResource(R.string.task_exit), it) }
                    SelectionContainer {
                        Text(upid, style = MonoStyle.copy(color = c.textTertiary), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                GlassCard {
                    SelectionContainer {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            if (state.lines.isEmpty()) {
                                Text(stringResource(R.string.task_log_empty), style = MonoStyle, color = c.textTertiary)
                            }
                            state.lines.forEach { line ->
                                Text(line.t, style = MonoStyle, color = if (line.t.contains("ERROR") || line.t.startsWith("TASK ERROR")) c.danger else c.textPrimary, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
