package io.github.lucabarbalata.anprox.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.lucabarbalata.anprox.data.alerts.AlertRepository
import io.github.lucabarbalata.anprox.model.alerts.AlertRun
import io.github.lucabarbalata.anprox.storage.AlertStore
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.widget.FavouritesWorker
import io.github.lucabarbalata.anprox.widget.StatusWidget
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * One background check (questionnaire Q26): asks every signed-in server how it is doing,
 * notifies about what changed and records a summary for the alert settings screen.
 * [AlertScheduler] runs it every few minutes while alerts are on.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val repository: AlertRepository by inject()
    private val settings: SettingsRepository by inject()
    private val store: AlertStore by inject()
    private val notifier: AlertNotifier by inject()

    override suspend fun doWork(): Result {
        val alertSettings = settings.settings.first().alerts
        // "Check now" works while alerts are off too, so the user can try it out first.
        if (!alertSettings.enabled && !inputData.getBoolean(KEY_MANUAL, false)) return Result.success()
        val check = repository.check(alertSettings)
        notifier.show(check)
        store.setLastRun(
            AlertRun(
                atMillis = System.currentTimeMillis(),
                checked = check.checked,
                alerts = check.alerts.size,
                unreachable = check.unreachable,
                signInNeeded = check.signInNeeded,
                servers = check.servers,
            ),
        )
        StatusTileService.refresh(applicationContext)
        StatusWidget.refresh(applicationContext)
        FavouritesWorker.refreshIfShown(applicationContext)
        // A server that couldn't be reached is tried again at the next interval, not sooner.
        return Result.success()
    }

    companion object {
        const val KEY_MANUAL = "manual"
    }
}
