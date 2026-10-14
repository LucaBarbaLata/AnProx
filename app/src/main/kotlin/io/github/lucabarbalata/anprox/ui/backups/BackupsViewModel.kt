package io.github.lucabarbalata.anprox.ui.backups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.backup.BackupRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
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

/** What the backups screen shows: one guest's backups, or everything on one storage. */
sealed interface BackupScope {
    val serverId: String

    /** Where requests (and restores) go. */
    val node: String

    data class Guest(val ref: GuestRef, val name: String) : BackupScope {
        override val serverId: String get() = ref.serverId
        override val node: String get() = ref.node
    }

    data class Storage(override val serverId: String, override val node: String, val storage: String) : BackupScope
}

enum class BackupOp { BACKUP, RESTORE }

data class BackupsState(
    val scope: BackupScope,
    val loading: Boolean = true,
    val backups: List<StorageContentItem> = emptyList(),
    /** Storages that couldn't be listed (e.g. an unreachable Backup Server). */
    val failedStorages: List<String> = emptyList(),
    val error: UiText? = null,
    /** Guest scope: where a new backup can go. Loaded when the backup dialog opens. */
    val storages: List<NodeStorage>? = null,
    val showBackupDialog: Boolean = false,
    /** The backup whose restore dialog is open. */
    val restoring: StorageContentItem? = null,
    /** Suggested id for "restore as new". */
    val nextVmid: Int? = null,
    /** Waiting for the type-to-confirm step before replacing a guest. */
    val confirmReplace: StorageContentItem? = null,
    val busy: BackupOp? = null,
    val result: TaskResult? = null,
)

/** Back up now, list backups and restore them (questionnaire Q20). */
class BackupsViewModel(
    private val scope: BackupScope,
    private val backups: BackupRepository,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(BackupsState(scope))
    val state: StateFlow<BackupsState> = ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val listing = when (scope) {
                    is BackupScope.Guest -> backups.backups(scope.ref)
                    is BackupScope.Storage -> backups.storageBackups(scope.serverId, scope.node, scope.storage)
                }
                ui.update {
                    it.copy(loading = false, backups = listing.backups, failedStorages = listing.failed.keys.sorted(), error = null)
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    // ------------------------------------------------------------------ back up now

    fun openBackup() {
        ui.update { it.copy(showBackupDialog = true) }
        if (ui.value.storages != null) return
        viewModelScope.launch {
            try {
                val storages = backups.backupStorages(scope.serverId, scope.node)
                ui.update { it.copy(storages = storages) }
            } catch (e: ApiException) {
                ui.update { it.copy(showBackupDialog = false, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun dismissBackup() = ui.update { it.copy(showBackupDialog = false) }

    fun backupNow(storage: NodeStorage, mode: BackupMode, notes: String) {
        val guest = scope as? BackupScope.Guest ?: return
        ui.update { it.copy(showBackupDialog = false) }
        run(BackupOp.BACKUP) { backups.backupNow(guest.ref, storage, mode, notes) }
    }

    // ------------------------------------------------------------------ restore

    fun openRestore(item: StorageContentItem) {
        ui.update { it.copy(restoring = item, nextVmid = null) }
        viewModelScope.launch {
            try {
                val id = backups.nextVmid(scope.serverId)
                ui.update { it.copy(nextVmid = id) }
            } catch (e: ApiException) {
                ui.update { it.copy(restoring = null, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun dismissRestore() = ui.update { it.copy(restoring = null) }

    fun restoreAsNew(item: StorageContentItem, vmid: Int) {
        ui.update { it.copy(restoring = null) }
        run(BackupOp.RESTORE) { backups.restore(scope.serverId, scope.node, item, vmid, replace = false) }
    }

    /** Replacing a guest is a type-to-confirm action (Q12): ask first. */
    fun requestReplace(item: StorageContentItem) = ui.update { it.copy(restoring = null, confirmReplace = item) }

    fun dismissReplace() = ui.update { it.copy(confirmReplace = null) }

    fun confirmReplace() {
        val item = ui.value.confirmReplace ?: return
        val vmid = replaceTarget(item) ?: return
        ui.update { it.copy(confirmReplace = null) }
        run(BackupOp.RESTORE) { backups.restore(scope.serverId, scope.node, item, vmid, replace = true) }
    }

    /** The guest a "replace" restore overwrites: the scope's guest, or the one the backup came from. */
    fun replaceTarget(item: StorageContentItem): Int? = when (scope) {
        is BackupScope.Guest -> scope.ref.vmid
        is BackupScope.Storage -> item.vmid
    }

    fun consumeResult() = ui.update { it.copy(result = null) }

    /** Starts the task, waits for it, reports the outcome and reloads the list. */
    private fun run(op: BackupOp, start: suspend () -> String) {
        if (ui.value.busy != null) return
        ui.update { it.copy(busy = op) }
        viewModelScope.launch {
            val result = try {
                val upid = start()
                val task = guests.awaitTask(scope.serverId, Upid.parse(upid)?.node ?: scope.node, upid)
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
