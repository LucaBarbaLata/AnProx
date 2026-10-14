package io.github.lucabarbalata.anprox

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.di.appModule
import io.github.lucabarbalata.anprox.diagnostics.CrashReporter
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

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
