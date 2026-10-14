package io.github.lucabarbalata.anprox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.snapshot.PbsSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotState
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DashboardTotals(
    val vmsRunning: Int = 0,
    val vmsTotal: Int = 0,
    val ctsRunning: Int = 0,
    val ctsTotal: Int = 0,
    val nodesOnline: Int = 0,
    val nodesTotal: Int = 0,
)

/** Things that need the admin's attention, shown at the top of the dashboard. */
sealed interface DashboardAlert {
    val serverId: String
    val serverName: String

    data class SignInRequired(override val serverId: String, override val serverName: String, val expired: Boolean) : DashboardAlert
    data class NoQuorum(override val serverId: String, override val serverName: String) : DashboardAlert
    data class NodeOffline(override val serverId: String, override val serverName: String, val node: String) : DashboardAlert
    data class StorageFull(override val serverId: String, override val serverName: String, val node: String?, val storage: String, val fraction: Double) : DashboardAlert
    data class DatastoreFull(override val serverId: String, override val serverName: String, val store: String, val fraction: Double) : DashboardAlert
}

data class DashboardState(
    val loaded: Boolean = false,
    val servers: List<SnapshotState> = emptyList(),
    val totals: DashboardTotals = DashboardTotals(),
    val alerts: List<DashboardAlert> = emptyList(),
    val refreshSeconds: Long = 5,
) {
    val isEmpty: Boolean get() = loaded && servers.isEmpty()
}

/** Combined overview across every saved server (questionnaire Q6). */
class DashboardViewModel(
    hub: ServerHub,
    snapshots: SnapshotRepository,
    settings: SettingsRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DashboardState> = settings.settings
        .map { it.refreshIntervalMillis }
        .distinctUntilChanged()
        .flatMapLatest { interval ->
            combine(hub.isLoaded, snapshots.snapshots(interval)) { loaded, list -> build(loaded, list, interval) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    private fun build(loaded: Boolean, list: List<SnapshotState>, interval: Long): DashboardState {
        var totals = DashboardTotals()
        val alerts = mutableListOf<DashboardAlert>()
        for (s in list) {
            val id = s.server.id
            val name = s.server.profile.name
            if (s.server.auth != AuthState.LOGGED_IN) {
                alerts += DashboardAlert.SignInRequired(id, name, expired = s.server.auth == AuthState.SESSION_EXPIRED)
            }
            when (val snap = s.snapshot) {
                is PveSnapshot -> {
                    val vms = snap.guestCounts(GuestType.QEMU)
                    val cts = snap.guestCounts(GuestType.LXC)
                    totals = totals.copy(
                        vmsRunning = totals.vmsRunning + vms.running,
                        vmsTotal = totals.vmsTotal + vms.total,
                        ctsRunning = totals.ctsRunning + cts.running,
                        ctsTotal = totals.ctsTotal + cts.total,
                        nodesOnline = totals.nodesOnline + snap.nodesOnline,
                        nodesTotal = totals.nodesTotal + snap.nodes.size,
                    )
                    if (snap.quorate == false) alerts += DashboardAlert.NoQuorum(id, name)
                    snap.nodes.filter { it.status != "online" }.forEach {
                        alerts += DashboardAlert.NodeOffline(id, name, it.node ?: it.id)
                    }
                    snap.storagesAbove(STORAGE_THRESHOLD).forEach {
                        alerts += DashboardAlert.StorageFull(id, name, if (it.shared) null else it.node, it.storage ?: it.id, it.diskFraction ?: 0.0)
                    }
                }
                is PbsSnapshot -> snap.datastores.filter { (it.fraction ?: 0.0) >= STORAGE_THRESHOLD }.forEach {
                    alerts += DashboardAlert.DatastoreFull(id, name, it.store, it.fraction ?: 0.0)
                }
                null -> Unit
            }
        }
        return DashboardState(loaded = loaded, servers = list, totals = totals, alerts = alerts, refreshSeconds = interval / 1000)
    }

    companion object {
        const val STORAGE_THRESHOLD = 0.85
    }
}
