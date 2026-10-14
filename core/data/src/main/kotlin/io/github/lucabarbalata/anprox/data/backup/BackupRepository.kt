package io.github.lucabarbalata.anprox.data.backup

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Backups found on several storages. A storage that failed (e.g. an unreachable PBS) is reported, not fatal. */
data class BackupListing(
    val backups: List<StorageContentItem>,
    val failed: Map<String, ApiException> = emptyMap(),
)

/** Backup now, browse backups and restore them (questionnaire Q20). */
class BackupRepository(private val hub: ServerHub) {

    /** Enabled, active storages on [node] that accept backups; Backup Server storages first. */
    suspend fun backupStorages(serverId: String, node: String): List<NodeStorage> =
        hub.use(serverId) { it.pve { nodeStorages(node, BACKUP, 1) } }
            .filter { it.enabled && it.active && BACKUP in it.content.split(',') }
            .sortedWith(compareBy({ !it.isPbs }, { it.storage }))

    /** Every backup of one guest that its node can see, newest first. */
    suspend fun backups(ref: GuestRef): BackupListing =
        collect(ref.serverId, ref.node, backupStorages(ref.serverId, ref.node).map { it.storage }, ref.vmid)

    /** Every backup on one storage, newest first. */
    suspend fun storageBackups(serverId: String, node: String, storage: String): BackupListing =
        collect(serverId, node, listOf(storage), vmid = null)

    private suspend fun collect(serverId: String, node: String, storages: List<String>, vmid: Int?): BackupListing = coroutineScope {
        val results = storages.map { storage ->
            async {
                storage to try {
                    Result.success(hub.use(serverId) { it.pve { storageContent(node, storage, BACKUP, vmid) } })
                } catch (e: ApiException) {
                    Result.failure(e)
                }
            }
        }.awaitAll()
        BackupListing(
            backups = results.flatMap { (_, r) -> r.getOrDefault(emptyList()) }
                .filter { it.content.isEmpty() || it.content == BACKUP }
                .distinctBy { it.volid }
                .sortedByDescending { it.ctime ?: 0 },
            failed = results.mapNotNull { (storage, r) -> (r.exceptionOrNull() as? ApiException)?.let { storage to it } }.toMap(),
        )
    }

    /**
     * Starts a backup of [ref] to [storage] and returns the task UPID. Compression only applies to
     * file storages (a Backup Server compresses and deduplicates on its own).
     */
    suspend fun backupNow(ref: GuestRef, storage: NodeStorage, mode: BackupMode, notes: String): String {
        val params = buildMap {
            put("vmid", ref.vmid.toString())
            put("storage", storage.storage)
            put("mode", mode.apiName)
            if (!storage.isPbs) put("compress", "zstd")
            if (notes.isNotBlank()) put("notes-template", notes.trim())
        }
        return hub.use(ref.serverId) { it.pve { vzdump(ref.node, params) } }
    }

    /** The lowest free VMID in the cluster. */
    suspend fun nextVmid(serverId: String): Int = hub.use(serverId) { it.pve { nextId() } }

    /**
     * Restores [backup] on [node] as guest [targetVmid] and returns the task UPID.
     *
     * - [replace] `true`: overwrites that guest (it must be stopped); Proxmox needs `force=1`.
     * - [replace] `false`: creates a new guest; `unique=1` gives it new MAC addresses so it can
     *   run next to the original.
     */
    suspend fun restore(serverId: String, node: String, backup: StorageContentItem, targetVmid: Int, replace: Boolean): String {
        val type = backup.guestType ?: throw IllegalArgumentException("Not a guest backup: ${backup.volid}")
        val params = buildMap {
            put("vmid", targetVmid.toString())
            if (replace) put("force", "1") else put("unique", "1")
            when (type) {
                GuestType.QEMU -> put("archive", backup.volid)
                GuestType.LXC -> {
                    put("ostemplate", backup.volid)
                    put("restore", "1")
                }
            }
        }
        return hub.use(serverId) {
            it.pve {
                when (type) {
                    GuestType.QEMU -> createQemu(node, params)
                    GuestType.LXC -> createLxc(node, params)
                }
            }
        }
    }

    private companion object {
        const val BACKUP = "backup"
    }
}
