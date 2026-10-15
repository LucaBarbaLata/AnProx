package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.BackupJob
import io.github.lucabarbalata.anprox.model.pve.BackupJobForm
import io.github.lucabarbalata.anprox.model.pve.BackupSelection
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.NotBackedUp
import io.github.lucabarbalata.anprox.model.pve.ResourcePool
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.StorageConfig

/** A task a backup job started on one node. */
data class StartedBackup(val node: String, val upid: String)

/**
 * Datacenter → Backup (questionnaire Q20): scheduled backup jobs, which guests no job covers,
 * and running a job now.
 */
class BackupJobRepository(private val hub: ServerHub) {

    suspend fun jobs(serverId: String): List<BackupJob> =
        hub.use(serverId) { it.pve { backupJobs() } }.map(BackupJob::fromJson)

    suspend fun create(serverId: String, form: BackupJobForm) {
        hub.use(serverId) { it.pveNoData { createBackupJob(form.createParams()) } }
    }

    suspend fun update(serverId: String, id: String, form: BackupJobForm) {
        hub.use(serverId) { it.pveNoData { updateBackupJob(id, form.updateParams()) } }
    }

    suspend fun setEnabled(serverId: String, id: String, enabled: Boolean) {
        hub.use(serverId) { it.pveNoData { updateBackupJob(id, mapOf("enabled" to if (enabled) "1" else "0")) } }
    }

    suspend fun delete(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteBackupJob(id) } }
    }

    /** Guests that no enabled job backs up. */
    suspend fun notBackedUp(serverId: String): List<NotBackedUp> =
        hub.use(serverId) { it.pve { notBackedUp() } }.sortedBy { it.vmid }

    /**
     * The next [count] runs of [schedule], in epoch seconds, as Proxmox reads it. Throws
     * [io.github.lucabarbalata.anprox.network.ApiException] for a schedule it can't parse.
     */
    suspend fun nextRuns(serverId: String, schedule: String, count: Int = 3): List<Long> =
        hub.use(serverId) { it.pve { analyzeSchedule(schedule.trim(), count) } }.map { it.timestamp }

    /** Storages that can hold backups, Backup Servers first. */
    suspend fun backupStorages(serverId: String): List<StorageConfig> =
        hub.use(serverId) { it.pve { storageConfigs() } }.filter { it.holdsBackups }
            .sortedWith(compareBy({ !it.isPbs }, { it.storage }))

    suspend fun pools(serverId: String): List<ResourcePool> =
        hub.use(serverId) { it.pve { pools() } }.sortedBy { it.id }

    /** Guests and nodes, for picking what a job covers. */
    suspend fun resources(serverId: String): List<ClusterResource> =
        hub.use(serverId) { it.pve { clusterResources(null) } }

    /**
     * Runs [job] now, like the web interface's "Run now": one backup task on each node it
     * covers (only the job's node, if it has one; otherwise every online node).
     */
    suspend fun runNow(serverId: String, job: BackupJob): List<StartedBackup> {
        val resources = resources(serverId)
        val nodes = job.node?.let(::listOf)
            ?: resources.filter { it.resourceType == ResourceType.NODE && it.status == "online" }.mapNotNull { it.node }.sorted()
        val base = BackupJobForm.from(job).commonParams()
        return nodes.mapNotNull { node ->
            val params = if (job.selection == BackupSelection.INCLUDE) {
                // vzdump on a node only backs up guests that live there.
                val here = resources.filter { it.isGuest && it.node == node && it.vmid in job.vmids }.mapNotNull { it.vmid }.sorted()
                if (here.isEmpty()) return@mapNotNull null
                base + ("vmid" to here.joinToString(","))
            } else {
                base
            }
            StartedBackup(node, hub.use(serverId) { it.pve { vzdump(node, params) } })
        }
    }
}
