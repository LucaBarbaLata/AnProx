package io.github.lucabarbalata.anprox.di

import io.github.lucabarbalata.anprox.BuildConfig
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.metrics.MetricsRepository
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.data.security.AppLock
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.store.ProfileStore
import io.github.lucabarbalata.anprox.data.store.SecretStore
import io.github.lucabarbalata.anprox.diagnostics.CrashReporter
import io.github.lucabarbalata.anprox.network.ProxmoxConnection
import io.github.lucabarbalata.anprox.storage.DataStoreProfileStore
import io.github.lucabarbalata.anprox.storage.KeystoreSecretStore
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.ui.dashboard.DashboardViewModel
import io.github.lucabarbalata.anprox.ui.guest.GuestViewModel
import io.github.lucabarbalata.anprox.ui.login.LoginViewModel
import io.github.lucabarbalata.anprox.ui.metrics.PerformanceViewModel
import io.github.lucabarbalata.anprox.ui.node.NodeViewModel
import io.github.lucabarbalata.anprox.ui.resources.ResourcesViewModel
import io.github.lucabarbalata.anprox.ui.servers.ServerEditorViewModel
import io.github.lucabarbalata.anprox.ui.servers.ServersViewModel
import io.github.lucabarbalata.anprox.ui.settings.SettingsViewModel
import io.github.lucabarbalata.anprox.ui.snapshots.SnapshotsViewModel
import io.github.lucabarbalata.anprox.ui.tasks.TaskLogViewModel
import io.github.lucabarbalata.anprox.ui.tasks.TasksViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin dependency graph. `single` = one instance for the whole app, `viewModel` = one per
 * screen (scoped to its navigation entry). Parameters such as a server id are passed with
 * `koinViewModel { parametersOf(...) }`. See docs/architecture.md → "Dependency injection".
 */
val appModule = module {
    single<CoroutineScope>(named("appScope")) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // Storage (Android implementations of the :core:data interfaces)
    single<ProfileStore> { DataStoreProfileStore(androidContext()) }
    single<SecretStore> { KeystoreSecretStore(androidContext()) }
    single { SettingsRepository(androidContext()) }
    single { CrashReporter(androidContext()) }
    single { AppLock() }

    // Networking: one shared OkHttp pool for all servers.
    single { OkHttpClient() }
    single {
        val base: OkHttpClient = get()
        ServerHub(
            profileStore = get(),
            secretStore = get(),
            scope = get(named("appScope")),
            connectionFactory = { profile ->
                ProxmoxConnection(profile, baseClient = base, userAgent = "AnProx/${BuildConfig.VERSION_NAME}")
            },
        )
    }
    single { SnapshotRepository(get()) }
    single { GuestRepository(get()) }
    single { MetricsRepository(get()) }

    // Screens
    viewModel { ServersViewModel(get()) }
    // "" = create a new server
    viewModel { (serverId: String) -> ServerEditorViewModel(serverId.ifEmpty { null }, get()) }
    viewModel { (serverId: String) -> LoginViewModel(serverId, get()) }
    viewModel { DashboardViewModel(get(), get(), get()) }
    viewModel { ResourcesViewModel(get(), get(), get()) }
    viewModel { (ref: GuestRef) -> GuestViewModel(ref, get(), get()) }
    viewModel { (ref: GuestRef, name: String) -> SnapshotsViewModel(ref, name, get()) }
    viewModel { (source: MetricsSource) -> PerformanceViewModel(source, get()) }
    viewModel { (serverId: String, node: String) -> NodeViewModel(serverId, node, get(), get()) }
    viewModel { TasksViewModel(get()) }
    viewModel { (serverId: String, node: String, upid: String) -> TaskLogViewModel(serverId, node, upid, get()) }
    viewModel { SettingsViewModel(get()) }
}
