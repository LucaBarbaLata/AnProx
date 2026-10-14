package io.github.lucabarbalata.anprox.ui.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NodeState(
    val status: NodeStatus? = null,
    val error: UiText? = null,
    val guests: List<ClusterResource> = emptyList(),
    val online: Boolean = true,
)

class NodeViewModel(
    val serverId: String,
    val node: String,
    guests: GuestRepository,
    snapshots: SnapshotRepository,
) : ViewModel() {
    val state: StateFlow<NodeState> = combine(
        guests.observeNode(serverId, node),
        snapshots.snapshot(serverId, intervalMillis = 10_000),
    ) { polled, snap ->
        val pve = snap?.snapshot as? PveSnapshot
        NodeState(
            status = polled.value,
            error = polled.error?.toUiText(),
            guests = pve?.guests?.filter { it.node == node }.orEmpty(),
            online = pve?.nodes?.firstOrNull { it.node == node }?.status != "offline",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NodeState())
}
