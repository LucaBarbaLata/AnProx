package io.github.lucabarbalata.anprox.ui.metrics

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.ChartCard
import io.github.lucabarbalata.anprox.core.designsystem.component.ChartLine
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.model.format.ChartScale
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.format.SeriesStats
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.RrdTimeframe
import io.github.lucabarbalata.anprox.ui.common.asString
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.Locale

/**
 * Timeframe picker and history charts for a node, guest or storage (questionnaire Q25). Meant to
 * sit in a LazyColumn item on the node, guest and storage screens.
 */
@Composable
fun PerformanceSection(source: MetricsSource) {
    val viewModel: PerformanceViewModel = koinViewModel(key = "perf-${source.key}") { parametersOf(source) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // SegmentedPicker's label lambda isn't composable: resolve the strings first.
    val timeframeLabels = mapOf(
        RrdTimeframe.HOUR to stringResource(R.string.perf_hour),
        RrdTimeframe.DAY to stringResource(R.string.perf_day),
        RrdTimeframe.WEEK to stringResource(R.string.perf_week),
        RrdTimeframe.MONTH to stringResource(R.string.perf_month),
        RrdTimeframe.YEAR to stringResource(R.string.perf_year),
    )
    val axisTime = rememberTimeFormat(state.timeframe, detailed = false)
    val pointTime = rememberTimeFormat(state.timeframe, detailed = true)
    val noData = stringResource(R.string.chart_no_data)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentedPicker(RrdTimeframe.entries, state.timeframe, { timeframeLabels.getValue(it) }, viewModel::setTimeframe)
        state.error?.let { ErrorBanner(it.asString()) }
        if (state.loading && state.points.isEmpty()) {
            GlassCard {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent)
                }
            }
        } else if (state.points.isNotEmpty() || state.error == null) {
            val times = state.points.map { it.time }
            chartSpecs(source, state.points).forEach { spec ->
                ChartCard(
                    title = spec.title,
                    times = times,
                    lines = spec.lines,
                    yMax = spec.yMax,
                    formatValue = spec.format,
                    formatAxisTime = axisTime,
                    formatPointTime = pointTime,
                    description = describe(spec),
                    noDataText = noData,
                )
            }
        }
    }
}

private class ChartSpec(val title: String, val lines: List<ChartLine>, val yMax: Double, val format: (Double?) -> String)

private val percent: (Double?) -> String = { Formatters.percent(it) }
private val bytes: (Double?) -> String = { Formatters.bytes(it?.toLong()) }
private val rate: (Double?) -> String = { Formatters.rate(it) }
private val decimal: (Double?) -> String = { v -> if (v == null) "—" else String.format(Locale.getDefault(), "%.2f", v) }

/** Axis top for auto-scaled charts: a round number just above the highest value. */
private fun autoTop(values: List<Double?>, binary: Boolean): Double {
    val peak = values.filterNotNull().maxOrNull() ?: return 1.0
    return if (binary) ChartScale.niceCeilBytes(peak) else ChartScale.niceCeil(peak)
}

@Composable
private fun chartSpecs(source: MetricsSource, points: List<RrdPoint>): List<ChartSpec> {
    val c = AnProxTheme.colors
    val cpuTitle = stringResource(R.string.metric_cpu)
    val usage = stringResource(R.string.chart_usage)
    val memoryTitle = stringResource(R.string.chart_memory)
    val used = stringResource(R.string.chart_used)

    val netIn = points.map { it.netin }
    val netOut = points.map { it.netout }
    val network = ChartSpec(
        stringResource(R.string.chart_network),
        listOf(ChartLine(stringResource(R.string.chart_in), netIn, c.info), ChartLine(stringResource(R.string.chart_out), netOut, c.accentAlt)),
        autoTop(netIn + netOut, binary = true),
        rate,
    )

    return when (source) {
        is MetricsSource.Guest -> {
            val mem = points.map { it.mem }
            val read = points.map { it.diskread }
            val write = points.map { it.diskwrite }
            listOf(
                ChartSpec(cpuTitle, listOf(ChartLine(usage, points.map { it.cpu }, c.accent)), 1.0, percent),
                ChartSpec(
                    memoryTitle,
                    listOf(ChartLine(used, mem, c.accentAlt)),
                    points.mapNotNull { it.maxmem }.maxOrNull() ?: autoTop(mem, binary = true),
                    bytes,
                ),
                network,
                ChartSpec(
                    stringResource(R.string.chart_disk_io),
                    listOf(ChartLine(stringResource(R.string.chart_read), read, c.success), ChartLine(stringResource(R.string.chart_write), write, c.warning)),
                    autoTop(read + write, binary = true),
                    rate,
                ),
            )
        }
        is MetricsSource.Node -> {
            val load = points.map { it.loadavg }
            val mem = points.map { it.memused }
            listOf(
                ChartSpec(
                    cpuTitle,
                    listOf(ChartLine(usage, points.map { it.cpu }, c.accent), ChartLine(stringResource(R.string.chart_iowait), points.map { it.iowait }, c.warning)),
                    1.0,
                    percent,
                ),
                ChartSpec(stringResource(R.string.chart_load), listOf(ChartLine(stringResource(R.string.chart_load_line), load, c.info)), autoTop(load, binary = false), decimal),
                ChartSpec(
                    memoryTitle,
                    listOf(ChartLine(used, mem, c.accentAlt)),
                    points.mapNotNull { it.memtotal }.maxOrNull() ?: autoTop(mem, binary = true),
                    bytes,
                ),
                network,
            )
        }
        is MetricsSource.Storage -> {
            val usedSpace = points.map { it.used }
            listOf(
                ChartSpec(
                    usage,
                    listOf(ChartLine(used, usedSpace, c.accent)),
                    points.mapNotNull { it.total }.maxOrNull() ?: autoTop(usedSpace, binary = true),
                    bytes,
                ),
            )
        }
    }
}

/** What TalkBack reads for a chart: now, average and peak of every line. */
@Composable
private fun describe(spec: ChartSpec): String = spec.lines.map { line ->
    val s = SeriesStats.of(line.values)
    stringResource(R.string.chart_description, spec.title, line.label, spec.format(s.latest), spec.format(s.average), spec.format(s.peak))
}.joinToString("; ")

/** Axis labels are short (time of day, or a date); the touched sample shows date and time. */
@Composable
private fun rememberTimeFormat(timeframe: RrdTimeframe, detailed: Boolean): (Long) -> String {
    val context = LocalContext.current
    return remember(timeframe, detailed, context) {
        val date = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        val flags = when {
            timeframe == RrdTimeframe.HOUR -> DateUtils.FORMAT_SHOW_TIME
            timeframe == RrdTimeframe.DAY && !detailed -> DateUtils.FORMAT_SHOW_TIME
            timeframe == RrdTimeframe.YEAR && !detailed -> date or DateUtils.FORMAT_NO_MONTH_DAY
            timeframe == RrdTimeframe.YEAR -> date
            !detailed -> date or DateUtils.FORMAT_NO_YEAR
            else -> date or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME
        }
        val format: (Long) -> String = { seconds -> DateUtils.formatDateTime(context, seconds * 1000, flags) }
        format
    }
}
