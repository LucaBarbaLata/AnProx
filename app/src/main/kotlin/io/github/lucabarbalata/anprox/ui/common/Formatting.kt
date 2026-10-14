package io.github.lucabarbalata.anprox.ui.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.model.format.DurationLabels
import io.github.lucabarbalata.anprox.model.format.Formatters

/** Localised unit suffixes for [Formatters.duration]. */
@Composable
fun durationLabels() = DurationLabels(
    days = stringResource(R.string.unit_days_short),
    hours = stringResource(R.string.unit_hours_short),
    minutes = stringResource(R.string.unit_minutes_short),
    seconds = stringResource(R.string.unit_seconds_short),
)

@Composable
fun formatDuration(seconds: Long?): String = Formatters.duration(seconds, durationLabels())

/** "3 minutes ago", localised by Android. */
fun relativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(epochMillis, nowMillis, DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** Tone + label for a Proxmox status string (guest, node or storage). */
data class StatusAppearance(val tone: Tone, val label: String)

@Composable
fun statusAppearance(status: String?, paused: Boolean = false): StatusAppearance = when {
    paused -> StatusAppearance(Tone.PAUSED, stringResource(R.string.status_paused))
    status == "running" -> StatusAppearance(Tone.SUCCESS, stringResource(R.string.status_running))
    status == "stopped" -> StatusAppearance(Tone.NEUTRAL, stringResource(R.string.status_stopped))
    status == "paused" || status == "suspended" -> StatusAppearance(Tone.PAUSED, stringResource(R.string.status_paused))
    status == "online" -> StatusAppearance(Tone.SUCCESS, stringResource(R.string.status_online))
    status == "offline" -> StatusAppearance(Tone.DANGER, stringResource(R.string.status_offline))
    status == "available" -> StatusAppearance(Tone.SUCCESS, stringResource(R.string.status_available))
    else -> StatusAppearance(Tone.WARNING, stringResource(R.string.status_unknown))
}
