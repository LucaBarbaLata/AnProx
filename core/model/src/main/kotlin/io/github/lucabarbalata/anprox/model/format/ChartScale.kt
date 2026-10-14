package io.github.lucabarbalata.anprox.model.format

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Axis maths for charts: round tops so the labels read well. */
object ChartScale {
    private val STEPS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)

    /** The smallest of 1, 2, 2.5, 5 × 10ⁿ that is ≥ [value]. Zero, negative or NaN → 1. */
    fun niceCeil(value: Double): Double {
        if (value.isNaN() || value <= 0) return 1.0
        val magnitude = 10.0.pow(floor(log10(value)))
        val step = STEPS.first { it * magnitude >= value * (1 - EPSILON) }
        return step * magnitude
    }

    /**
     * Like [niceCeil] but in binary units, for bytes and bytes per second: 3.1 GB → 5 GiB, so the
     * axis label reads "5 GiB" instead of "2.89 GiB".
     */
    fun niceCeilBytes(value: Double): Double {
        if (value.isNaN() || value <= 0) return 1.0
        var unit = 1.0
        while (value / unit >= 1024) unit *= 1024
        return niceCeil(value / unit) * unit
    }

    private const val EPSILON = 1e-9
}

/** Latest, average and peak of a series that may have gaps (`null`). */
data class SeriesStats(val latest: Double?, val average: Double?, val peak: Double?) {
    companion object {
        fun of(values: List<Double?>): SeriesStats {
            val present = values.filterNotNull().filterNot { it.isNaN() }
            return SeriesStats(
                latest = values.lastOrNull { it != null && !it.isNaN() },
                average = if (present.isEmpty()) null else present.average(),
                peak = present.maxOrNull(),
            )
        }
    }
}
