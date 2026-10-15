package io.github.lucabarbalata.anprox

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.lucabarbalata.anprox.alerts.AlertNotifier
import io.github.lucabarbalata.anprox.alerts.AlertScheduler
import io.github.lucabarbalata.anprox.alerts.TaskTracker
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.security.AppLock
import io.github.lucabarbalata.anprox.di.appModule
import io.github.lucabarbalata.anprox.diagnostics.CrashReporter
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.widget.StatusWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named

class AnProxApplication : Application() {

    private val hub: ServerHub by inject()
    private val crashReporter: CrashReporter by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@AnProxApplication)
            modules(appModule)
        }
        crashReporter.install()
        watchNetwork()
        watchAppLock()
        watchAlerts()
    }

    /** Keeps the background checks (questionnaire Q26) in step with the alert settings. */
    private fun watchAlerts() {
        get<AlertNotifier>().createChannels()
        get<TaskTracker>().createChannels()
        val scheduler: AlertScheduler = get()
        val settings: SettingsRepository = get()
        get<CoroutineScope>(named("appScope")).launch {
            settings.settings
                .map { it.alerts }
                .distinctUntilChanged()
                .collect {
                    scheduler.apply(it)
                    // The widget says when it isn't being kept up to date.
                    StatusWidget.refresh(this@AnProxApplication)
                }
        }
    }

    /**
     * Feeds the app lock (questionnaire Q11) its settings and the app's background/foreground
     * changes. ProcessLifecycleOwner ignores rotations and other configuration changes.
     */
    private fun watchAppLock() {
        val appLock: AppLock = get()
        val settings: SettingsRepository = get()
        get<CoroutineScope>(named("appScope")).launch {
            settings.settings
                .map { it.appLock to it.lockAfterMillis }
                .distinctUntilChanged()
                .collect { (enabled, lockAfter) -> appLock.configure(enabled, lockAfter) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> appLock.onBackground()
                    Lifecycle.Event.ON_START -> appLock.onForeground()
                    else -> Unit
                }
            },
        )
    }

    /**
     * When the phone switches network (Wi-Fi ↔ mobile ↔ VPN), forget which endpoint worked last
     * so each server tries its primary (usually LAN) address first again (questionnaire Q14).
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = hub.onNetworkChanged()
                override fun onLost(network: Network) = hub.onNetworkChanged()
            },
        )
    }
}
