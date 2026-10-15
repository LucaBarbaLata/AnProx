package io.github.lucabarbalata.anprox.alerts

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.model.alerts.AlertRun
import io.github.lucabarbalata.anprox.model.link.AppLink
import io.github.lucabarbalata.anprox.storage.AlertStore
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.ui.navigation.AppLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Quick Settings tile (questionnaire Q28): what the last background check found, at a glance.
 * Active while background alerts are on. Tapping it opens the dashboard.
 */
class StatusTileService : TileService(), KoinComponent {
    private val settings: SettingsRepository by inject()
    private val store: AlertStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(settings.settings, store.lastRun) { s, run -> s.alerts.enabled to run }
                .collect { (enabled, run) -> show(enabled, run) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(enabled: Boolean, run: AlertRun?) {
        val tile = qsTile ?: return
        val status = when {
            !enabled -> getString(R.string.tile_alerts_off)
            run == null -> getString(R.string.tile_no_check)
            run.unreachable.isNotEmpty() -> getString(R.string.tile_unreachable, run.unreachable.size)
            run.alerts > 0 -> getString(R.string.tile_new_alerts, run.alerts)
            else -> getString(R.string.tile_checked, DateUtils.formatDateTime(this, run.atMillis, DateUtils.FORMAT_SHOW_TIME))
        }
        tile.label = getString(R.string.tile_label)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = status
        }
        tile.contentDescription = getString(R.string.tile_description, status)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openDashboard() } else openDashboard()
    }

    private fun openDashboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(AppLinks.pendingIntent(this, AppLink.Dashboard))
        } else {
            startActivityAndCollapseLegacy(AppLinks.intent(this, AppLink.Dashboard))
        }
    }

    /** Before Android 14 the tile can only start an activity with an Intent. */
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startActivityAndCollapseLegacy(intent: Intent) = startActivityAndCollapse(intent)

    companion object {
        /** Asks Android to refresh the tile (after a check), if the user has added it. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, StatusTileService::class.java)) }
        }
    }
}
