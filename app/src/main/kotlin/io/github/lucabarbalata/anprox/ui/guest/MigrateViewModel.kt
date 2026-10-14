package io.github.lucabarbalata.anprox.ui.guest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.MigrationCheck
import io.github.lucabarbalata.anprox.model.pve.MigrationPlan
import io.github.lucabarbalata.anprox.model.pve.MigrationTarget
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MigrateState(
    val ref: GuestRef,
    val guestName: String,
    val loading: Boolean = true,
    val error: UiText? = null,
    val running: Boolean = false,
    val haManaged: Boolean = false,
    val check: MigrationCheck? = null,
    val targets: List<MigrationTarget> = emptyList(),
    val target: String? = null,
    val confirm: Boolean = false,
    val busy: Boolean = false,
    /** One-shot failure shown as a snackbar (success navigates to the guest on its new node). */
    val result: TaskResult? = null,
    /** Set once the guest has moved. */
    val moved: GuestRef? = null,
) {
    val canMigrate: Boolean
        get() = !loading && !busy && error == null && targets.any { it.node == target && it.allowed }
}

/** Move a VM or container to another node of its cluster (questionnaire Q18). */
class MigrateViewModel(
    private val ref: GuestRef,
    guestName: String,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(MigrateState(ref, guestName))
    val state: StateFlow<MigrateState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                // coroutineScope: a failed request is thrown here instead of cancelling viewModelScope's job.
                val (s, nodes, check) = coroutineScope {
                    val status = async { guests.status(ref) }
                    val nodes = async { guests.clusterNodes(ref.serverId) }
                    val check = async { guests.migrationCheck(ref) }
                    Triple(status.await(), nodes.await(), check.await())
                }
                val targets = MigrationPlan.targets(ref.node, nodes, check)
                ui.update {
                    it.copy(
                        loading = false,
                        running = s.isRunning,
                        haManaged = s.ha?.managed == true,
                        check = check,
                        targets = targets,
                        target = it.target?.takeIf { t -> targets.any { x -> x.node == t && x.allowed } }
                            ?: targets.firstOrNull { x -> x.allowed }?.node,
                    )
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun select(node: String) {
        ui.update { it.copy(target = node) }
    }

    fun requestMigrate() {
        if (ui.value.canMigrate) ui.update { it.copy(confirm = true) }
    }

    fun dismissConfirm() {
        ui.update { it.copy(confirm = false) }
    }

    fun migrate() {
        val s = ui.value
        val target = s.target ?: return
        if (!s.canMigrate) return
        ui.update { it.copy(confirm = false, busy = true) }
        viewModelScope.launch {
            try {
                val upid = guests.migrate(ref, target, s.running, s.check)
                val task = guests.awaitTask(ref.serverId, ref.node, upid)
                if (task.succeeded) {
                    ui.update { it.copy(busy = false, moved = ref.copy(node = target)) }
                } else {
                    val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
                    val result = TaskResult(UiText.res(R.string.guest_task_failed, label, task.exitstatus.orEmpty()), upid, ok = false)
                    ui.update { it.copy(busy = false, result = result) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }
}
