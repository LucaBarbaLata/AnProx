package io.github.lucabarbalata.anprox.data.snapshot

import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.hub.ServerState
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Live data for the dashboard, resource list and task list. Each flow polls only while it is
 * collected (i.e. while a screen is visible), so the app costs nothing in the background.
 */
class SnapshotRepository(
    private val hub: ServerHub,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    /** One [SnapshotState] per saved server, refreshed every [intervalMillis]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Flow<List<SnapshotState>> =
        hub.servers
            .flatMapLatest { servers ->
                if (servers.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(servers.map { poll(it, intervalMillis) }) { it.toList() }
                }
            }

    /** Snapshot of a single server. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshot(serverId: String, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Flow<SnapshotState?> =
        hub.servers
            .flatMapLatest { servers ->
                val server = servers.firstOrNull { it.id == serverId }
                if (server == null) flowOf(null) else poll(server, intervalMillis)
            }

    private fun poll(server: ServerState, intervalMillis: Long): Flow<SnapshotState> = flow {
        if (server.auth != AuthState.LOGGED_IN) {
            emit(SnapshotState(server, null, ApiException.Unauthorized("Not logged in"), null))
            return@flow
        }
        // Emit a "loading" state right away so combined views don't wait for the slowest server
        // (and don't mistake "not fetched yet" for "empty").
        emit(SnapshotState(server, null, null, null))
        var last: ServerSnapshot? = null
        var updatedAt: Long? = null
        while (true) {
            try {
                last = fetch(server)
                updatedAt = clockMillis()
                emit(SnapshotState(server, last, null, updatedAt))
            } catch (e: ApiException) {
                emit(SnapshotState(server, last, e, updatedAt))
            }
            delay(intervalMillis)
        }
    }

    /** One-shot fetch (pull-to-refresh, tests). */
    suspend fun fetch(server: ServerState): ServerSnapshot = hub.use(server.id) { conn ->
        coroutineScope {
            when (server.profile.product) {
                ProxmoxProduct.PVE -> {
                    val status = async { conn.pve { clusterStatus() } }
                    val resources = async { conn.pve { clusterResources(null) } }
                    val version = async { runCatching { conn.version().version }.getOrNull() }
                    val cluster = status.await().firstOrNull { it.type == "cluster" }
                    PveSnapshot(
                        clusterName = cluster?.name,
                        quorate = cluster?.quorate,
                        nodeStatus = status.await().filter { it.type == "node" },
                        resources = resources.await(),
                        version = version.await(),
                    )
                }
                ProxmoxProduct.PBS -> {
                    val usage = async { conn.pbs { datastoreUsage() } }
                    val tasks = async { conn.pbs { tasks(TASK_LIMIT) } }
                    val version = async { runCatching { conn.version().version }.getOrNull() }
                    PbsSnapshot(usage.await(), tasks.await(), version.await())
                }
            }
        }
    }

    /** Recent tasks of every logged-in server, newest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun tasks(intervalMillis: Long = TASK_INTERVAL_MILLIS): Flow<List<ServerTasks>> =
        hub.servers.flatMapLatest { servers ->
            val active = servers.filter { it.auth == AuthState.LOGGED_IN }
            if (active.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(active.map { pollTasks(it, intervalMillis) }) { it.toList() }
            }
        }

    private fun pollTasks(server: ServerState, intervalMillis: Long): Flow<ServerTasks> = flow {
        var last: List<TaskSummary> = emptyList()
        while (true) {
            val error = try {
                last = hub.use(server.id) { conn ->
                    when (server.profile.product) {
                        ProxmoxProduct.PVE -> conn.pve { clusterTasks() }
                        ProxmoxProduct.PBS -> conn.pbs { tasks(TASK_LIMIT) }
                    }
                }.sortedByDescending { it.starttime }
                null
            } catch (e: ApiException) {
                e
            }
            emit(ServerTasks(server, last, error))
            delay(intervalMillis)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 5_000L
        const val TASK_INTERVAL_MILLIS = 10_000L
        const val TASK_LIMIT = 50
    }
}

data class ServerTasks(
    val server: ServerState,
    val tasks: List<TaskSummary>,
    val error: ApiException?,
)
