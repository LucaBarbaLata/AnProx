package io.github.lucabarbalata.anprox.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.alerts.AlertScheduler
import io.github.lucabarbalata.anprox.model.alerts.AlertRun
import io.github.lucabarbalata.anprox.model.alerts.AlertSettings
import io.github.lucabarbalata.anprox.storage.AlertStore
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AlertsState(
    val settings: AlertSettings = AlertSettings(),
    val lastRun: AlertRun? = null,
    /** A "check now" is queued or running. */
    val checking: Boolean = false,
    val taskNotifications: Boolean = true,
)

/** Background alert settings (questionnaire Q26/Q27). [AnProxApplication] schedules the checks. */
class AlertsViewModel(
    private val settings: SettingsRepository,
    store: AlertStore,
    private val scheduler: AlertScheduler,
) : ViewModel() {

    val state: StateFlow<AlertsState> = combine(settings.settings, store.lastRun, scheduler.checking) { s, run, checking ->
        AlertsState(s.alerts, run, checking, s.taskNotifications)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsState())

    fun update(change: AlertSettings.() -> AlertSettings) {
        viewModelScope.launch { settings.setAlerts(settings.settings.first().alerts.change()) }
    }

    fun setTaskNotifications(enabled: Boolean) {
        viewModelScope.launch { settings.setTaskNotifications(enabled) }
    }

    fun checkNow() {
        scheduler.checkNow()
    }
}
