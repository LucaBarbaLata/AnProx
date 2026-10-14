package io.github.lucabarbalata.anprox.model.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Locale-aware number formatting shared by every screen. Units (KiB, GiB, %) are universal;
 * words (days, ago, …) come from Android string resources via [DurationLabels] so the app stays
 * translation-ready (questionnaire Q16).
 */
object Formatters {
    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

    /** 1536 → "1.5 KiB". Uses binary (IEC) units, like the Proxmox web UI. */
    fun bytes(value: Long?, locale: Locale = Locale.getDefault()): String {
        if (value == null) return "—"
        var v = abs(value.toDouble())
        var unit = 0
        while (v >= 1024 && unit < UNITS.lastIndex) {
            v /= 1024
            unit++
        }
        val sign = if (value < 0) "-" else ""
        val number = when {
            unit == 0 -> v.toLong().toString()
            v >= 100 -> String.format(locale, "%.0f", v)
            v >= 10 -> String.format(locale, "%.1f", v)
            else -> String.format(locale, "%.2f", v)
        }
        return "$sign$number ${UNITS[unit]}"
    }

    /** "12.3 GiB / 64 GiB". */
    fun usage(used: Long?, total: Long?, locale: Locale = Locale.getDefault()): String =
        "${bytes(used, locale)} / ${bytes(total, locale)}"

    /** 0.4231 → "42%"; values under 10% keep one decimal ("4.2%"). */
    fun percent(fraction: Double?, locale: Locale = Locale.getDefault()): String {
        if (fraction == null || fraction.isNaN()) return "—"
        val p = fraction * 100
        return if (p > 0 && p < 10) String.format(locale, "%.1f%%", p) else "${p.roundToInt()}%"
    }

    /** Bytes per second → "12.4 MiB/s". */
    fun rate(bytesPerSecond: Double?, locale: Locale = Locale.getDefault()): String =
        if (bytesPerSecond == null) "—" else "${bytes(bytesPerSecond.toLong(), locale)}/s"

    /** 93784 → "1d 2h" — the two most significant units. */
    fun duration(seconds: Long?, labels: DurationLabels = DurationLabels.English): String {
        if (seconds == null || seconds < 0) return "—"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        val s = seconds % 60
        return when {
            d > 0 -> "${d}${labels.days} ${h}${labels.hours}"
            h > 0 -> "${h}${labels.hours} ${m}${labels.minutes}"
            m > 0 -> "${m}${labels.minutes} ${s}${labels.seconds}"
            else -> "${s}${labels.seconds}"
        }
    }
}

/** Unit suffixes for [Formatters.duration]; Android passes localised ones from strings.xml. */
data class DurationLabels(val days: String, val hours: String, val minutes: String, val seconds: String) {
    companion object {
        val English = DurationLabels("d", "h", "m", "s")
    }
}
