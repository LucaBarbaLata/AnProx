package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ReplicationJob
import io.github.lucabarbalata.anprox.model.pve.ReplicationJobForm
import io.github.lucabarbalata.anprox.model.pve.ReplicationStatus
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Jobs, their last runs (by job id; missing when the guest's node didn't answer), the guests
 * (no templates) and the online nodes.
 */
data class ReplicationOverview(
    val jobs: List<ReplicationJob>,
    val status: Map<String, ReplicationStatus>,
    val guests: List<ClusterResource>,
    val onlineNodes: List<String>,
) {
    fun guest(vmid: Int): ClusterResource? = guests.firstOrNull { it.vmid == vmid }
}

/**
 * Storage replication (questionnaire Q21): a guest's local ZFS disks copied to another node on
 * a schedule, so it can be started or migrated there quickly.
 */
class ReplicationRepository(private val hub: ServerHub) {

    suspend fun overview(serverId: String): ReplicationOverview = coroutineScope {
        val jobsCall = async { hub.use(serverId) { it.pve { replicationJobs() } }.map(ReplicationJob::fromJson) }
        val resources = hub.use(serverId) { it.pve { clusterResources(null) } }
        val jobs = jobsCall.await()
        val guests = resources.filter { it.isGuest && !it.template && it.vmid != null }.sortedBy { it.vmid }
        val online = resources.filter { it.resourceType == ResourceType.NODE && it.status == "online" }.mapNotNull { it.node }.sorted()
        // Each node reports the jobs of the guests it runs.
        val sources = jobs.mapNotNull { job -> guests.firstOrNull { it.vmid == job.guest }?.node }.distinct().filter { it in online }
        val status = sources.map { node ->
            async {
                try {
                    hub.use(serverId) { it.pve { replicationStatus(node) } }.map(ReplicationStatus::fromJson)
                } catch (_: ApiException) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().associateBy { it.id }
        ReplicationOverview(jobs.sortedBy { it.id }, status, guests, online)
    }

    suspend fun create(serverId: String, form: ReplicationJobForm, jobs: List<ReplicationJob>) {
        hub.use(serverId) { it.pveNoData { createReplicationJob(form.createParams(jobs)) } }
    }

    suspend fun update(serverId: String, job: ReplicationJob, form: ReplicationJobForm) {
        hub.use(serverId) { it.pveNoData { updateReplicationJob(job.id, form.updateParams(job)) } }
    }

    suspend fun setEnabled(serverId: String, job: ReplicationJob, enabled: Boolean) {
        val params = if (enabled) mapOf("delete" to "disable") else mapOf("disable" to "1")
        hub.use(serverId) { it.pveNoData { updateReplicationJob(job.id, params) } }
    }

    /** Proxmox removes the copies on the target at the job's next run, unless [keepCopies]. */
    suspend fun delete(serverId: String, job: ReplicationJob, keepCopies: Boolean) {
        hub.use(serverId) { it.pveNoData { deleteReplicationJob(job.id, if (keepCopies) 1 else null) } }
    }

    /** Starts the job within a minute; [node] is where the guest runs. */
    suspend fun runNow(serverId: String, node: String, job: ReplicationJob) {
        hub.use(serverId) { it.pveNoData { runReplicationNow(node, job.id) } }
    }

    suspend fun log(serverId: String, node: String, job: ReplicationJob): List<TaskLogLine> =
        hub.use(serverId) { it.pve { replicationLog(node, job.id) } }

    /** The next [count] runs of [schedule] in epoch seconds; throws for a schedule Proxmox can't read. */
    suspend fun nextRuns(serverId: String, schedule: String, count: Int = 3): List<Long> =
        hub.use(serverId) { it.pve { analyzeSchedule(schedule.trim(), count) } }.map { it.timestamp }
}
