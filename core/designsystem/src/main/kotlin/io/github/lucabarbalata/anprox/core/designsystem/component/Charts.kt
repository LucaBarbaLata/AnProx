package io.github.lucabarbalata.anprox.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import kotlin.math.roundToInt

/** One series of a [ChartCard]. [values] line up with the card's times; `null` leaves a gap. */
@Immutable
data class ChartLine(val label: String, val values: List<Double?>, val color: Color)

/**
 * A glass card with a line chart: gridlines labelled at 0, half and [yMax], soft gradient fills,
 * gaps where data is missing, and touch scrubbing (tap or drag to read the values at a moment;
 * tap the same spot again to go back to the latest values).
 *
 * @param times sample times in Unix seconds, evenly spaced, oldest first.
 * @param formatValue formats axis labels and readouts (`null` → "—").
 * @param formatAxisTime short labels under the chart (first, middle, last sample).
 * @param formatPointTime the selected sample's time in the header.
 * @param description what TalkBack reads for the chart, e.g. "CPU, now 12 percent, peak 40 percent".
 * @param noDataText shown over the chart when no line has any value.
 */
@Composable
fun ChartCard(
    title: String,
    times: List<Long>,
    lines: List<ChartLine>,
    yMax: Double,
    formatValue: (Double?) -> String,
    formatAxisTime: (Long) -> String,
    formatPointTime: (Long) -> String,
    description: String,
    noDataText: String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 132.dp,
) {
    val c = AnProxTheme.colors
    var selected by remember(times) { mutableStateOf<Int?>(null) }
    val index = selected?.takeIf { it in times.indices }
    val hasData = lines.any { line -> line.values.any { it != null } }

    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            if (index != null) {
                Text(formatPointTime(times[index]), style = MaterialTheme.typography.labelMedium, color = c.accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Readout: the selected sample, or each line's latest value.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            lines.forEach { line ->
                val value = if (index != null) line.values.getOrNull(index) else line.values.lastOrNull { it != null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(line.color))
                    Spacer(Modifier.width(6.dp))
                    Text("${line.label} ${formatValue(value)}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(contentAlignment = Alignment.Center) {
            LineChartCanvas(
                times = times,
                lines = lines,
                yMax = if (yMax > 0) yMax else 1.0,
                formatValue = formatValue,
                formatAxisTime = formatAxisTime,
                selected = index,
                onSelect = { selected = it },
                description = description,
                modifier = Modifier.fillMaxWidth().height(chartHeight),
            )
            if (!hasData) {
                Text(noDataText, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
    }
}

/** Plot edges in pixels, written while drawing and read by the touch handlers. */
private class PlotBounds {
    var left = 0f
    var right = 0f

    fun indexAt(x: Float, count: Int): Int? {
        if (count == 0) return null
        if (count == 1 || right <= left) return 0
        return (((x - left) / (right - left)) * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }
}

@Composable
private fun LineChartCanvas(
    times: List<Long>,
    lines: List<ChartLine>,
    yMax: Double,
    formatValue: (Double?) -> String,
    formatAxisTime: (Long) -> String,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier,
) {
    val c = AnProxTheme.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = c.textTertiary)
    val bounds = remember { PlotBounds() }
    val count = times.size
    // The touch handlers outlive recompositions: read the latest selection and callback.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(count) {
                detectTapGestures { pos ->
                    val i = bounds.indexAt(pos.x, count)
                    currentOnSelect(if (i == currentSelected) null else i)
                }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures(onDragStart = { pos -> currentOnSelect(bounds.indexAt(pos.x, count)) }) { change, _ ->
                    currentOnSelect(bounds.indexAt(change.position.x, count))
                }
            },
    ) {
        val gap = 8.dp.toPx()
        val gridLabels = listOf(0.0, 0.5, 1.0).map { f -> f to measurer.measure(formatValue(yMax * f), axisStyle) }
        val labelWidth = gridLabels.maxOf { it.second.size.width } + gap
        val textHeight = gridLabels.first().second.size.height
        val top = textHeight / 2f
        val bottom = size.height - textHeight - gap / 2
        val left = labelWidth
        val right = size.width
        bounds.left = left
        bounds.right = right
        val plotHeight = bottom - top

        fun x(i: Int): Float = if (count <= 1) (left + right) / 2 else left + (right - left) * i / (count - 1)
        fun y(v: Double): Float = bottom - (v / yMax).coerceIn(0.0, 1.05).toFloat() * plotHeight

        // Gridlines with their values on the left.
        gridLabels.forEach { (fraction, label) ->
            val gy = bottom - fraction.toFloat() * plotHeight
            drawLine(c.hairline, Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx())
            drawText(label, topLeft = Offset(left - gap - label.size.width, gy - label.size.height / 2f))
        }

        // Each line: one filled, stroked path per run of samples without gaps.
        lines.forEach { line ->
            runsWithoutGaps(line.values).forEach { run ->
                if (run.size == 1) {
                    drawCircle(line.color, 2.5.dp.toPx(), Offset(x(run.first()), y(line.values[run.first()]!!)))
                    return@forEach
                }
                val stroke = Path()
                val fill = Path()
                run.forEachIndexed { k, i ->
                    val px = x(i)
                    val py = y(line.values[i]!!)
                    if (k == 0) {
                        stroke.moveTo(px, py)
                        fill.moveTo(px, bottom)
                        fill.lineTo(px, py)
                    } else {
                        stroke.lineTo(px, py)
                        fill.lineTo(px, py)
                    }
                }
                fill.lineTo(x(run.last()), bottom)
                fill.close()
                drawPath(fill, Brush.verticalGradient(listOf(line.color.copy(alpha = 0.26f), Color.Transparent), startY = top, endY = bottom))
                drawPath(stroke, line.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        // Time labels: first, middle and last sample.
        if (count > 0) {
            val labelY = bottom + gap / 2
            val first = measurer.measure(formatAxisTime(times.first()), axisStyle)
            drawText(first, topLeft = Offset(left, labelY))
            if (count > 1) {
                val last = measurer.measure(formatAxisTime(times.last()), axisStyle)
                drawText(last, topLeft = Offset(right - last.size.width, labelY))
            }
            if (count > 2) {
                val mid = measurer.measure(formatAxisTime(times[count / 2]), axisStyle)
                drawText(mid, topLeft = Offset(x(count / 2) - mid.size.width / 2f, labelY))
            }
        }

        // The selected moment: a vertical rule and a dot on every line.
        if (selected != null) {
            val sx = x(selected)
            drawLine(c.textTertiary, Offset(sx, top), Offset(sx, bottom), strokeWidth = 1.dp.toPx())
            lines.forEach { line ->
                val v = line.values.getOrNull(selected) ?: return@forEach
                drawCircle(c.surfaceSolid, 5.dp.toPx(), Offset(sx, y(v)))
                drawCircle(line.color, 3.5.dp.toPx(), Offset(sx, y(v)))
            }
        }
    }
}

/** Index runs of consecutive non-null values: [1, null, 2, 3] → [[0], [2, 3]]. */
private fun runsWithoutGaps(values: List<Double?>): List<List<Int>> {
    val runs = ArrayList<List<Int>>()
    var current = ArrayList<Int>()
    values.forEachIndexed { i, v ->
        if (v != null && !v.isNaN()) {
            current.add(i)
        } else if (current.isNotEmpty()) {
            runs.add(current)
            current = ArrayList()
        }
    }
    if (current.isNotEmpty()) runs.add(current)
    return runs
}
