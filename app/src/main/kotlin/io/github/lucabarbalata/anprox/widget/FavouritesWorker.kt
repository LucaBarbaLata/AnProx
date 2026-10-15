package io.github.lucabarbalata.anprox.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.lucabarbalata.anprox.data.favourites.FavouriteRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.favourites.FavouriteRef
import io.github.lucabarbalata.anprox.model.favourites.FavouritesSnapshot
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.storage.WidgetConfirm
import io.github.lucabarbalata.anprox.storage.WidgetStore
import io.github.lucabarbalata.anprox.ui.common.resolve
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * The favourites widget's background work (questionnaire Q28): reads every favourite's state,
 * runs a power action and waits for its task, or clears a button that waited too long for its
 * second tap. The widget is redrawn after each.
 */
class FavouritesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val repository: FavouriteRepository by inject()
    private val guests: GuestRepository by inject()
    private val settings: SettingsRepository by inject()
    private val store: WidgetStore by inject()

    override suspend fun doWork(): Result {
        when (inputData.getString(KEY_MODE)) {
            MODE_POWER -> power()
            MODE_EXPIRE -> store.expireConfirm(System.currentTimeMillis())
            else -> refresh()
        }
        FavouritesWidget.redraw(applicationContext)
        return Result.success()
    }

    private suspend fun refresh() {
        val refs = settings.settings.first().favourites.mapNotNull { FavouriteRef.parse(it) }
        store.setFavourites(FavouritesSnapshot(System.currentTimeMillis(), repository.statuses(refs)))
    }

    private suspend fun power() {
        val key = inputData.getString(KEY_GUEST) ?: return
        val ref = FavouriteRef.parse(key) ?: return
        val action = PowerAction.entries.firstOrNull { it.name == inputData.getString(KEY_ACTION) } ?: return
        var error: String? = null
        try {
            val (guest, upid) = repository.power(ref, action)
            // A live task notification follows it too; the widget waits for the end to show the new state.
            withTimeoutOrNull(WAIT_MILLIS) { guests.awaitTask(guest.serverId, guest.node, upid) }
        } catch (e: ApiException) {
            error = e.toUiText().resolve(applicationContext)
        } finally {
            store.setBusy(key, false)
        }
        refresh()
        error?.let { store.setError(it) }
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_GUEST = "guest"
        private const val KEY_ACTION = "action"
        private const val MODE_REFRESH = "refresh"
        private const val MODE_POWER = "power"
        private const val MODE_EXPIRE = "expire"
        private const val WAIT_MILLIS = 3 * 60_000L

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private suspend fun hasWidgets(context: Context): Boolean =
            runCatching { GlanceAppWidgetManager(context).getGlanceIds(FavouritesWidget::class.java).isNotEmpty() }.getOrDefault(false)

        /** Reads the favourites again, if a favourites widget is on the home screen. */
        suspend fun refreshIfShown(context: Context) {
            if (hasWidgets(context)) enqueueRefresh(context)
        }

        fun enqueueRefresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<FavouritesWorker>()
                .setConstraints(online)
                .setInputData(workDataOf(KEY_MODE to MODE_REFRESH))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("favourites-refresh", ExistingWorkPolicy.REPLACE, request)
        }

        fun power(context: Context, key: String, action: PowerAction) {
            val request = OneTimeWorkRequestBuilder<FavouritesWorker>()
                .setConstraints(online)
                .setInputData(workDataOf(KEY_MODE to MODE_POWER, KEY_GUEST to key, KEY_ACTION to action.name))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("favourites-power-$key", ExistingWorkPolicy.KEEP, request)
        }

        /** Puts the button back once the second tap didn't come. */
        fun expireConfirm(context: Context) {
            val request = OneTimeWorkRequestBuilder<FavouritesWorker>()
                .setInitialDelay(WidgetConfirm.WINDOW_MILLIS + 500, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MODE to MODE_EXPIRE))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("favourites-expire", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
