package io.github.lucabarbalata.anprox.ui.snapshots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.SnapshotName
import io.github.lucabarbalata.anprox.model.pve.SnapshotRow
import io.github.lucabarbalata.anprox.model.pve.SnapshotTree
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

enum class SnapshotAction(val danger: DangerLevel) {
    CREATE(DangerLevel.SAFE),
    /** Discards everything since the snapshot: the user types the snapshot name (Q12). */
    ROLLBACK(DangerLevel.TYPE_TO_CONFIRM),
    /** Only removes the saved state; the guest itself is unaffected. */
    DELETE(DangerLevel.CONFIRM),
}

data class SnapshotOp(val action: SnapshotAction, val name: String)

data class SnapshotsState(
    val ref: GuestRef,
    val guestName: String,
    val loading: Boolean = true,
    val rows: List<SnapshotRow> = emptyList(),
    val error: UiText? = null,
    /** The operation whose task is running (one at a time, like Proxmox's guest lock). */
    val busy: SnapshotOp? = null,
    /** Rollback or delete waiting for confirmation. */
    val confirm: SnapshotOp? = null,
    val showCreate: Boolean = false,
    /** One-shot result shown as a snackbar. */
    val result: TaskResult? = null,
) {
    val snapshotNames: List<String> get() = rows.map { it.snapshot.name }.filterNot { it == GuestSnapshot.CURRENT }
    val isEmpty: Boolean get() = !loading && error == null && snapshotNames.isEmpty()

    /** Saving RAM needs a running VM; Proxmox reports that on the `current` entry. */
    val canSaveRam: Boolean
        get() = ref.type == GuestType.QEMU && rows.any { it.snapshot.isCurrent && it.snapshot.running }
}

/** Snapshots of one VM or container (questionnaire Q18): list as a tree, take, roll back, delete. */
class SnapshotsViewModel(
    private val ref: GuestRef,
    guestName: String,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(SnapshotsState(ref, guestName))
    val state: StateFlow<SnapshotsState> = ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val rows = SnapshotTree.flatten(guests.snapshots(ref))
                ui.update { it.copy(loading = false, rows = rows, error = null) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    /** A valid name to start from, e.g. `snap-20261014-1530`. */
    fun suggestedName(): String {
        val now = LocalDateTime.now()
        return SnapshotName.suggest(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
    }

    fun nameProblem(name: String): SnapshotName.Problem? = SnapshotName.validate(name.trim(), ui.value.snapshotNames)

    fun openCreate() = ui.update { it.copy(showCreate = true) }

    fun dismissCreate() = ui.update { it.copy(showCreate = false) }

    fun create(name: String, description: String, includeRam: Boolean) {
        val trimmed = name.trim()
        if (nameProblem(trimmed) != null) return
        ui.update { it.copy(showCreate = false) }
        run(SnapshotOp(SnapshotAction.CREATE, trimmed)) {
            guests.createSnapshot(ref, trimmed, description, includeRam && ui.value.canSaveRam)
        }
    }

    fun requestRollback(name: String) = ui.update { it.copy(confirm = SnapshotOp(SnapshotAction.ROLLBACK, name)) }

    fun requestDelete(name: String) = ui.update { it.copy(confirm = SnapshotOp(SnapshotAction.DELETE, name)) }

    fun dismissConfirm() = ui.update { it.copy(confirm = null) }

    fun confirm() {
        val op = ui.value.confirm ?: return
        ui.update { it.copy(confirm = null) }
        when (op.action) {
            SnapshotAction.ROLLBACK -> run(op) { guests.rollbackSnapshot(ref, op.name) }
            SnapshotAction.DELETE -> run(op) { guests.deleteSnapshot(ref, op.name) }
            SnapshotAction.CREATE -> Unit
        }
    }

    fun consumeResult() = ui.update { it.copy(result = null) }

    /** Starts the task, waits for it, reports the outcome and reloads the list. */
    private fun run(op: SnapshotOp, start: suspend () -> String) {
        if (ui.value.busy != null) return
        ui.update { it.copy(busy = op) }
        viewModelScope.launch {
            val result = try {
                val upid = start()
                val task = guests.awaitTask(ref.serverId, ref.node, upid)
                val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
                if (task.succeeded) {
                    TaskResult(UiText.res(R.string.guest_task_ok, label), upid, ok = true)
                } else {
                    TaskResult(UiText.res(R.string.guest_task_failed, label, task.exitstatus.orEmpty()), upid, ok = false)
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = null, result = result) }
            refresh()
        }
    }
}
