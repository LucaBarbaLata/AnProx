package io.github.lucabarbalata.anprox.ui.metrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.metrics.MetricsRepository
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.RrdTimeframe
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class PerformanceState(
    val timeframe: RrdTimeframe = RrdTimeframe.HOUR,
    val points: List<RrdPoint> = emptyList(),
    val loading: Boolean = true,
    val error: UiText? = null,
)

/**
 * Performance history of a node or guest for the selected timeframe (questionnaire Q25).
 * Polls only while the charts are on screen: every minute for the last hour, every five
 * minutes for longer timeframes (Proxmox stores coarser samples for those anyway).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModel(
    private val source: MetricsSource,
    private val metrics: MetricsRepository,
) : ViewModel() {

    private val timeframe = MutableStateFlow(RrdTimeframe.HOUR)

    val state: StateFlow<PerformanceState> = timeframe
        .flatMapLatest { history(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerformanceState())

    fun setTimeframe(value: RrdTimeframe) {
        timeframe.value = value
    }

    private fun history(tf: RrdTimeframe): Flow<PerformanceState> = flow {
        emit(PerformanceState(tf, loading = true))
        var points = emptyList<RrdPoint>()
        while (true) {
            val next = try {
                points = metrics.history(source, tf)
                PerformanceState(tf, points, loading = false)
            } catch (e: ApiException) {
                PerformanceState(tf, points, loading = false, error = e.toUiText())
            }
            emit(next)
            delay(if (tf == RrdTimeframe.HOUR) 60_000L else 300_000L)
        }
    }
}
