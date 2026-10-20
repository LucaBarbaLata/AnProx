package io.github.lucabarbalata.anprox.ui.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.NodePowerAction
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.taskFailedText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Node-wide guest actions, each one Proxmox task. */
enum class NodeBulk(val danger: DangerLevel) {
    START_ALL(DangerLevel.CONFIRM),
    /** Stops every guest on the node, like a node shutdown does: type the node's name. */
    STOP_ALL(DangerLevel.TYPE_TO_CONFIRM),
    MIGRATE_ALL(DangerLevel.CONFIRM),
}

data class NodeState(
    val status: NodeStatus? = null,
    val error: UiText? = null,
    val guests: List<ClusterResource> = emptyList(),
    val online: Boolean = true,
    /** Reboot or shutdown waiting for confirmation. */
    val confirm: NodePowerAction? = null,
    val busy: NodePowerAction? = null,
    /** One-shot snackbar text. */
    val message: UiText? = null,
    /** Other online nodes, for Migrate all. */
    val otherNodes: List<String> = emptyList(),
    /** Bulk action whose dialog is open. */
    val bulk: NodeBulk? = null,
    /** Bulk action whose task is running. */
    val bulkBusy: NodeBulk? = null,
    /** One-shot result of a bulk task, with its log. */
    val result: TaskResult? = null,
)

private data class NodeUi(
    val confirm: NodePowerAction? = null,
    val busy: NodePowerAction? = null,
    val message: UiText? = null,
    val bulk: NodeBulk? = null,
    val bulkBusy: NodeBulk? = null,
    val result: TaskResult? = null,
)

class NodeViewModel(
    val serverId: String,
    val node: String,
    private val guests: GuestRepository,
    snapshots: SnapshotRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(NodeUi())

    val state: StateFlow<NodeState> = combine(
        guests.observeNode(serverId, node),
        snapshots.snapshot(serverId, intervalMillis = 10_000),
        ui,
    ) { polled, snap, u ->
        val pve = snap?.snapshot as? PveSnapshot
        NodeState(
            status = polled.value,
            error = polled.error?.toUiText(),
            guests = pve?.guests?.filter { it.node == node }.orEmpty(),
            online = pve?.nodes?.firstOrNull { it.node == node }?.status != "offline",
            confirm = u.confirm,
            busy = u.busy,
            message = u.message,
            otherNodes = pve?.nodes?.filter { it.node != node && it.status == "online" }?.mapNotNull { it.node }?.sorted().orEmpty(),
            bulk = u.bulk,
            bulkBusy = u.bulkBusy,
            result = u.result,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeState())

    fun requestPower(action: NodePowerAction) {
        ui.update { it.copy(confirm = action) }
    }

    fun dismissPower() {
        ui.update { it.copy(confirm = null) }
    }

    fun power() {
        val action = ui.value.confirm ?: return
        if (ui.value.busy != null) return
        ui.update { it.copy(confirm = null, busy = action) }
        viewModelScope.launch {
            val message = try {
                guests.nodePower(serverId, node, action)
                UiText.res(if (action == NodePowerAction.REBOOT) R.string.node_reboot_sent else R.string.node_shutdown_sent, node)
            } catch (e: ApiException) {
                e.toUiText()
            }
            ui.update { it.copy(busy = null, message = message) }
        }
    }

    fun consumeMessage() {
        ui.update { it.copy(message = null) }
    }

    fun requestBulk(bulk: NodeBulk) {
        ui.update { it.copy(bulk = bulk) }
    }

    fun dismissBulk() {
        ui.update { it.copy(bulk = null) }
    }

    fun startAll(force: Boolean) {
        runBulk(NodeBulk.START_ALL) { guests.startAll(serverId, node, force) }
    }

    fun stopAll() {
        runBulk(NodeBulk.STOP_ALL) { guests.stopAll(serverId, node) }
    }

    fun migrateAll(target: String, withLocalDisks: Boolean) {
        runBulk(NodeBulk.MIGRATE_ALL) { guests.migrateAll(serverId, node, target, withLocalDisks) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    /** Starts the task, follows it to the end and reports it with its log. */
    private fun runBulk(bulk: NodeBulk, start: suspend () -> String) {
        if (ui.value.bulkBusy != null) return
        ui.update { it.copy(bulk = null, bulkBusy = bulk) }
        viewModelScope.launch {
            val result = try {
                val upid = start()
                val task = guests.awaitTask(serverId, node, upid)
                val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
                if (task.succeeded) {
                    TaskResult(UiText.res(R.string.guest_task_ok, label), upid, ok = true)
                } else {
                    TaskResult(taskFailedText(label, task.exitstatus), upid, ok = false)
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(bulkBusy = null, result = result) }
        }
    }
}
