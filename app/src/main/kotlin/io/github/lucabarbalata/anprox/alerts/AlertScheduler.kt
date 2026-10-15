package io.github.lucabarbalata.anprox.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.lucabarbalata.anprox.model.alerts.AlertSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Starts and stops the periodic [AlertWorker] to match the user's alert settings. */
class AlertScheduler(context: Context) {
    private val work = WorkManager.getInstance(context.applicationContext)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun apply(settings: AlertSettings) {
        if (!settings.enabled) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val minutes = settings.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
        val request = PeriodicWorkRequestBuilder<AlertWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        // UPDATE keeps the schedule's timing when only the interval or the options change.
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Runs one check right away. */
    fun checkNow() {
        val request = OneTimeWorkRequestBuilder<AlertWorker>()
            .setConstraints(online)
            .setInputData(workDataOf(AlertWorker.KEY_MANUAL to true))
            .build()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** True while a "check now" is queued or running. */
    val checking: Flow<Boolean> = work.getWorkInfosForUniqueWorkFlow(NOW).map { infos ->
        infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }

    private companion object {
        const val PERIODIC = "alerts"
        const val NOW = "alerts-now"

        /** Android doesn't run periodic work more often than this. */
        const val MIN_INTERVAL_MINUTES = 15
    }
}
