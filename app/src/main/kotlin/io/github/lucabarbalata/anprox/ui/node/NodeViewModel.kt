package io.github.lucabarbalata.anprox.ui.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.NodePowerAction
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

private data class NodeUi(val confirm: NodePowerAction? = null, val busy: NodePowerAction? = null, val message: UiText? = null)

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
}
