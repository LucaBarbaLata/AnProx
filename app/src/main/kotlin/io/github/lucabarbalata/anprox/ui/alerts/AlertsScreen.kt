package io.github.lucabarbalata.anprox.ui.alerts

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.alerts.AlertNotifier
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.alerts.AlertRun
import io.github.lucabarbalata.anprox.model.alerts.AlertSettings
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val PERCENT_STEP = 5

/**
 * Background alerts (questionnaire Q26/Q27): on/off, how often, which kinds, the limits, and
 * what the latest check found. On Android 13+ switching alerts on asks for permission to post
 * notifications.
 */
@Composable
fun AlertsScreen(onBack: () -> Unit, viewModel: AlertsViewModel = koinViewModel(), notifier: AlertNotifier = koinInject()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val s = state.settings
    var allowed by remember { mutableStateOf(notifier.allowed()) }
    LifecycleResumeEffect(Unit) {
        allowed = notifier.allowed()
        onPauseOrDispose { }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = notifier.allowed() }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
    val intervalLabels = AlertSettings.INTERVALS.associateWith { minutes ->
        if (minutes < 60) stringResource(R.string.alerts_every_minutes, minutes) else stringResource(R.string.alerts_every_hours, minutes / 60)
    }

    AnProxScaffold(title = stringResource(R.string.alerts_title), onBack = onBack) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard {
                    SwitchRow(
                        title = stringResource(R.string.alerts_enabled),
                        description = stringResource(R.string.alerts_enabled_description),
                        checked = s.enabled,
                        onCheckedChange = { on ->
                            viewModel.update { copy(enabled = on) }
                            if (on && !allowed) requestNotifications()
                        },
                    )
                    if (!allowed && (s.enabled || state.taskNotifications)) {
                        Spacer(Modifier.height(8.dp))
                        // After a refusal Android won't ask again; its settings page is the way back.
                        ErrorBanner(
                            stringResource(R.string.alerts_notifications_off),
                            actionLabel = stringResource(R.string.alerts_notifications_allow),
                            onAction = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestNotifications() else openNotificationSettings() },
                            tone = Tone.WARNING,
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.alerts_interval)) }
            item {
                GlassCard {
                    SegmentedPicker(
                        AlertSettings.INTERVALS,
                        AlertSettings.INTERVALS.firstOrNull { it == s.intervalMinutes } ?: AlertSettings.INTERVALS.first(),
                        { intervalLabels.getValue(it) },
                        { minutes -> viewModel.update { copy(intervalMinutes = minutes) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    FormHint(stringResource(R.string.alerts_interval_hint))
                }
            }
            item { SectionHeader(stringResource(R.string.alerts_kinds)) }
            item {
                GlassCard {
                    SwitchRow(
                        title = stringResource(R.string.alerts_down),
                        description = stringResource(R.string.alerts_down_description),
                        checked = s.down,
                        onCheckedChange = { on -> viewModel.update { copy(down = on) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.alerts_failures),
                        description = stringResource(R.string.alerts_failures_description),
                        checked = s.failures,
                        onCheckedChange = { on -> viewModel.update { copy(failures = on) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.alerts_thresholds),
                        description = stringResource(R.string.alerts_thresholds_description),
                        checked = s.thresholds,
                        onCheckedChange = { on -> viewModel.update { copy(thresholds = on) } },
                    )
                    if (s.thresholds) {
                        Spacer(Modifier.height(8.dp))
                        PercentStepper(stringResource(R.string.alerts_cpu_limit), s.cpuPercent) { p -> viewModel.update { copy(cpuPercent = p) } }
                        PercentStepper(stringResource(R.string.alerts_memory_limit), s.memoryPercent) { p -> viewModel.update { copy(memoryPercent = p) } }
                        PercentStepper(stringResource(R.string.alerts_storage_limit), s.storagePercent) { p -> viewModel.update { copy(storagePercent = p) } }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.alerts_tasks)) }
            item {
                GlassCard {
                    SwitchRow(
                        title = stringResource(R.string.alerts_task_notifications),
                        description = stringResource(R.string.alerts_task_notifications_description),
                        checked = state.taskNotifications,
                        onCheckedChange = { on ->
                            viewModel.setTaskNotifications(on)
                            if (on && !allowed) requestNotifications()
                        },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.alerts_last_check)) }
            item { LastCheckCard(state.lastRun, state.checking, viewModel::checkNow) }
            item { FormHint(stringResource(R.string.alerts_footnote)) }
        }
    }
}

/** A percentage between [AlertSettings.PERCENT_RANGE] in steps of five. */
@Composable
private fun PercentStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    val range = AlertSettings.PERCENT_RANGE
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = AnProxTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - PERCENT_STEP).coerceIn(range)) }, enabled = value > range.first) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.alerts_lower, label))
        }
        Text(
            stringResource(R.string.alerts_percent, value),
            style = MaterialTheme.typography.titleMedium,
            color = AnProxTheme.colors.textPrimary,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange((value + PERCENT_STEP).coerceIn(range)) }, enabled = value < range.last) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.alerts_raise, label))
        }
    }
}

@Composable
private fun LastCheckCard(run: AlertRun?, checking: Boolean, onCheckNow: () -> Unit) {
    val context = LocalContext.current
    GlassCard {
        if (run == null) {
            FormHint(stringResource(R.string.alerts_never_checked))
        } else {
            val time = DateUtils.formatDateTime(
                context,
                run.atMillis,
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
            )
            FormLabel(time)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.alerts_last_summary, run.checked, run.alerts),
                style = MaterialTheme.typography.bodyMedium,
                color = AnProxTheme.colors.textSecondary,
            )
            if (run.unreachable.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(stringResource(R.string.alerts_unreachable, run.unreachable.joinToString(", ")), tone = Tone.WARNING)
            }
            if (run.signInNeeded.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(stringResource(R.string.alerts_sign_in_needed, run.signInNeeded.joinToString(", ")), tone = Tone.WARNING)
            }
        }
        Spacer(Modifier.height(12.dp))
        AnProxButton(
            text = stringResource(R.string.alerts_check_now),
            onClick = onCheckNow,
            style = ButtonStyle.SECONDARY,
            icon = Icons.Outlined.Refresh,
            loading = checking,
            enabled = !checking,
        )
    }
}
