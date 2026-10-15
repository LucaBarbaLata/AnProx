package io.github.lucabarbalata.anprox.di

import io.github.lucabarbalata.anprox.BuildConfig
import io.github.lucabarbalata.anprox.alerts.AlertNotifier
import io.github.lucabarbalata.anprox.alerts.AlertScheduler
import io.github.lucabarbalata.anprox.alerts.TaskTracker
import io.github.lucabarbalata.anprox.data.alerts.AlertRepository
import io.github.lucabarbalata.anprox.data.backup.BackupRepository
import io.github.lucabarbalata.anprox.data.console.ConsoleRepository
import io.github.lucabarbalata.anprox.data.datacenter.BackupJobRepository
import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.data.datacenter.HaRepository
import io.github.lucabarbalata.anprox.data.datacenter.PoolRepository
import io.github.lucabarbalata.anprox.data.datacenter.ReplicationRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageSetupRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.metrics.MetricsRepository
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
import io.github.lucabarbalata.anprox.data.security.AppLock
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.store.ProfileStore
import io.github.lucabarbalata.anprox.data.store.SecretStore
import io.github.lucabarbalata.anprox.data.transfer.ProfileTransfer
import io.github.lucabarbalata.anprox.diagnostics.CrashReporter
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.network.ProxmoxConnection
import io.github.lucabarbalata.anprox.storage.AlertStore
import io.github.lucabarbalata.anprox.storage.DataStoreProfileStore
import io.github.lucabarbalata.anprox.storage.KeystoreSecretStore
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.ui.alerts.AlertsViewModel
import io.github.lucabarbalata.anprox.ui.backups.BackupScope
import io.github.lucabarbalata.anprox.ui.backups.BackupsViewModel
import io.github.lucabarbalata.anprox.ui.cloudinit.CloudInitViewModel
import io.github.lucabarbalata.anprox.ui.console.ConsoleViewModel
import io.github.lucabarbalata.anprox.ui.create.CreateGuestViewModel
import io.github.lucabarbalata.anprox.ui.dashboard.DashboardViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.AliasesViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.BackupJobEditorViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.BackupJobsViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.FirewallLogViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.FirewallViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.HaViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.IpSetsViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.PoolViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.PoolsViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.ReplicationViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.SecurityGroupsViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.StorageContentViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.StorageEditorViewModel
import io.github.lucabarbalata.anprox.ui.datacenter.StorageListViewModel
import io.github.lucabarbalata.anprox.ui.guest.CloneViewModel
import io.github.lucabarbalata.anprox.ui.guest.GuestViewModel
import io.github.lucabarbalata.anprox.ui.guest.MigrateViewModel
import io.github.lucabarbalata.anprox.ui.guest.OptionsViewModel
import io.github.lucabarbalata.anprox.ui.hardware.HardwareViewModel
import io.github.lucabarbalata.anprox.ui.login.LoginViewModel
import io.github.lucabarbalata.anprox.ui.metrics.PerformanceViewModel
import io.github.lucabarbalata.anprox.ui.navigation.AppLinks
import io.github.lucabarbalata.anprox.ui.node.NodeViewModel
import io.github.lucabarbalata.anprox.ui.resources.ResourcesViewModel
import io.github.lucabarbalata.anprox.ui.servers.ServerEditorViewModel
import io.github.lucabarbalata.anprox.ui.servers.ServersViewModel
import io.github.lucabarbalata.anprox.ui.settings.SettingsViewModel
import io.github.lucabarbalata.anprox.ui.settings.TransferViewModel
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
    single { AppLinks() }

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
    // Live task notifications follow every task the app waits for.
    single { TaskTracker(androidContext(), get(), get(named("appScope")), get()) }
    single { GuestRepository(get(), get<TaskTracker>()) }
    single { MetricsRepository(get()) }
    single { BackupRepository(get()) }
    single { ConsoleRepository(get()) }
    single { HardwareRepository(get()) }
    single { BackupJobRepository(get()) }
    single { StorageRepository(get()) }
    single { FirewallRepository(get()) }
    single { HaRepository(get()) }
    single { ReplicationRepository(get()) }
    single { PoolRepository(get()) }
    single { StorageSetupRepository(get()) }
    single { ProfileTransfer(get(), get()) }

    // Background alerts
    single { AlertStore(androidContext()) }
    single { AlertRepository(get(), get(), get<AlertStore>()) }
    single { AlertNotifier(androidContext()) }
    single { AlertScheduler(androidContext()) }

    // Screens
    viewModel { ServersViewModel(get()) }
    // "" = create a new server
    viewModel { (serverId: String) -> ServerEditorViewModel(serverId.ifEmpty { null }, get()) }
    viewModel { (serverId: String) -> LoginViewModel(serverId, get()) }
    viewModel { DashboardViewModel(get(), get(), get()) }
    viewModel { ResourcesViewModel(get(), get(), get(), get()) }
    viewModel { (ref: GuestRef) -> GuestViewModel(ref, get(), get(), get()) }
    viewModel { (ref: GuestRef) -> OptionsViewModel(ref, get()) }
    viewModel { (ref: GuestRef) -> HardwareViewModel(ref, get(), get()) }
    viewModel { (ref: GuestRef) -> CloudInitViewModel(ref, get(), get()) }
    viewModel { (serverId: String, node: String, type: GuestType) -> CreateGuestViewModel(serverId, node, type, get(), get()) }
    viewModel { (ref: GuestRef, name: String) -> CloneViewModel(ref, name, get()) }
    viewModel { (serverId: String, target: ConsoleTarget) -> ConsoleViewModel(serverId, target, get()) }
    viewModel { (ref: GuestRef, name: String) -> MigrateViewModel(ref, name, get()) }
    viewModel { (ref: GuestRef, name: String) -> SnapshotsViewModel(ref, name, get()) }
    viewModel { (source: MetricsSource) -> PerformanceViewModel(source, get()) }
    viewModel { (scope: BackupScope) -> BackupsViewModel(scope, get(), get()) }
    viewModel { (serverId: String, node: String) -> NodeViewModel(serverId, node, get(), get()) }
    viewModel { TasksViewModel(get()) }
    viewModel { (serverId: String, node: String, upid: String) -> TaskLogViewModel(serverId, node, upid, get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { TransferViewModel(get()) }
    viewModel { AlertsViewModel(get(), get(), get()) }
    viewModel { (serverId: String) -> BackupJobsViewModel(serverId, get(), get()) }
    // "" = a new job
    viewModel { (serverId: String, jobId: String) -> BackupJobEditorViewModel(serverId, jobId.ifEmpty { null }, get()) }
    viewModel { (serverId: String) -> StorageListViewModel(serverId, get()) }
    viewModel { (serverId: String, scope: FirewallScope) -> FirewallViewModel(serverId, scope, get()) }
    viewModel { (serverId: String) -> SecurityGroupsViewModel(serverId, get()) }
    viewModel { (serverId: String, scope: FirewallScope) -> AliasesViewModel(serverId, scope, get()) }
    viewModel { (serverId: String, scope: FirewallScope) -> IpSetsViewModel(serverId, scope, get()) }
    viewModel { (serverId: String, scope: FirewallScope) -> FirewallLogViewModel(serverId, scope, get()) }
    viewModel { (serverId: String) -> HaViewModel(serverId, get()) }
    viewModel { (serverId: String) -> ReplicationViewModel(serverId, get()) }
    viewModel { (serverId: String) -> PoolsViewModel(serverId, get()) }
    viewModel { (serverId: String, poolId: String) -> PoolViewModel(serverId, poolId, get()) }
    // storageId "" adds a new storage.
    viewModel { (serverId: String, storageId: String) -> StorageEditorViewModel(serverId, storageId.ifEmpty { null }, get()) }
    viewModel { (serverId: String, node: String, storage: String) -> StorageContentViewModel(serverId, node, storage, get(), get()) }
}
