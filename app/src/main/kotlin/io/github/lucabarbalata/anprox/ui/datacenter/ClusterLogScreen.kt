package io.github.lucabarbalata.anprox.ui.datacenter

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.datacenter.ClusterLogRepository
import io.github.lucabarbalata.anprox.model.pve.ClusterLogEntry
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class ClusterLogState(
    val loading: Boolean = true,
    val entries: List<ClusterLogEntry> = emptyList(),
    val error: UiText? = null,
    val filter: String = "",
) {
    val shown: List<ClusterLogEntry> get() = entries.filter { it.matches(filter) }
}

/** Datacenter → Cluster log: what every node logged, newest at the bottom. */
class ClusterLogViewModel(private val serverId: String, private val repo: ClusterLogRepository) : ViewModel() {
    private val ui = MutableStateFlow(ClusterLogState())
    val state: StateFlow<ClusterLogState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = repo.log(serverId)
                ui.update { it.copy(loading = false, entries = entries) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setFilter(text: String) {
        ui.update { it.copy(filter = text) }
    }
}

@Composable
fun ClusterLogScreen(serverId: String, onBack: () -> Unit) {
    val viewModel: ClusterLogViewModel = koinViewModel(key = "clusterlog-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = AnProxTheme.colors
    val list = rememberLazyListState()
    val shown = state.shown
    // Start at the newest entry, like a log.
    LaunchedEffect(state.entries.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size + 1) }
    AnProxScaffold(
        title = stringResource(R.string.clusterlog_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(state = list, contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { GlassTextField(value = state.filter, onValueChange = viewModel::setFilter, label = stringResource(R.string.firewall_log_filter)) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            when {
                state.loading && state.entries.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
                state.error == null && shown.isEmpty() -> item {
                    EmptyState(Icons.Outlined.History, stringResource(R.string.clusterlog_empty), stringResource(R.string.clusterlog_empty_body))
                }
                else -> items(shown, key = { "${it.uid}-${it.time}-${it.node}" }) { entry -> LogEntry(entry) }
            }
        }
    }
}

@Composable
private fun LogEntry(entry: ClusterLogEntry) {
    val c = AnProxTheme.colors
    val context = LocalContext.current
    val time = remember(entry.time) {
        DateUtils.formatDateTime(context, entry.time * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    }
    GlassCard {
        Column {
            Text(
                listOf(time, entry.node, entry.tag, entry.user).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                entry.msg,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    entry.isError -> c.danger
                    entry.isWarning -> c.warning
                    else -> c.textPrimary
                },
            )
        }
    }
}
