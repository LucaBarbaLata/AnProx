package io.github.lucabarbalata.anprox.data.backupserver

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.json.objects
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.pbs.BackupGroup
import io.github.lucabarbalata.anprox.model.pbs.BackupSnapshot
import io.github.lucabarbalata.anprox.model.pbs.CatalogEntry
import io.github.lucabarbalata.anprox.model.pbs.DatastoreInfo
import io.github.lucabarbalata.anprox.model.pbs.DatastoreMaintenance
import io.github.lucabarbalata.anprox.model.pbs.DatastoreStatus
import io.github.lucabarbalata.anprox.model.pbs.DatastoreUsage
import io.github.lucabarbalata.anprox.model.pbs.GcStatus
import io.github.lucabarbalata.anprox.model.pbs.KeepOptions
import io.github.lucabarbalata.anprox.model.pbs.PbsJob
import io.github.lucabarbalata.anprox.model.pbs.PbsJobKind
import io.github.lucabarbalata.anprox.model.pbs.PbsNamespace
import io.github.lucabarbalata.anprox.model.pbs.PbsNodeStatus
import io.github.lucabarbalata.anprox.model.pbs.PbsRemote
import io.github.lucabarbalata.anprox.model.pbs.PbsRemoteForm
import io.github.lucabarbalata.anprox.model.pbs.PruneItem
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** A datastore with its usage, for the list. */
data class DatastoreSummary(val info: DatastoreInfo, val usage: DatastoreUsage?)

/**
 * Proxmox Backup Server (questionnaire Q20, milestone M8): datastore content and maintenance,
 * file restore from a snapshot's catalog, jobs, remotes and the node's status. [ns] is a
 * namespace path, "" for the root.
 */
class PbsRepository(private val hub: ServerHub) {

    private fun ns(ns: String): Map<String, String> = if (ns.isEmpty()) emptyMap() else mapOf("ns" to ns)

    // ---- datastores

    suspend fun datastores(serverId: String): List<DatastoreSummary> {
        val list = hub.use(serverId) { it.pbs { datastores() } }
        // Usage needs Datastore.Audit; without it the list still shows.
        val usage = runCatching { hub.use(serverId) { it.pbs { datastoreUsage() } } }.getOrDefault(emptyList()).associateBy { it.store }
        return list.sortedBy { it.store }.map { DatastoreSummary(it, usage[it.store]) }
    }

    suspend fun status(serverId: String, store: String): DatastoreStatus = DatastoreStatus.fromJson(hub.use(serverId) { it.pbs { datastoreStatus(store) } })

    /** The last garbage collection; older servers only report it inside the status. */
    suspend fun gc(serverId: String, store: String): GcStatus? = try {
        GcStatus.fromJson(hub.use(serverId) { it.pbs { gcStatus(store) } })
    } catch (_: ApiException.NotFound) {
        null
    }

    suspend fun startGc(serverId: String, store: String): String = hub.use(serverId) { it.pbs { startGc(store) } }

    suspend fun maintenance(serverId: String, store: String): DatastoreMaintenance =
        DatastoreMaintenance.parse(hub.use(serverId) { it.pbs { datastoreConfig(store) } }.str("maintenance-mode"))

    /** [type] `read-only` or `offline`; null ends maintenance. */
    suspend fun setMaintenance(serverId: String, store: String, type: String?, message: String) {
        val params = if (type == null) mapOf("delete" to "maintenance-mode") else mapOf("maintenance-mode" to DatastoreMaintenance.value(type, message))
        hub.use(serverId) { it.pbsNoData { updateDatastore(store, params) } }
    }

    // ---- namespaces

    /** All namespaces, the root ("") first and each below its parent. */
    suspend fun namespaces(serverId: String, store: String): List<PbsNamespace> =
        (hub.use(serverId) { it.pbs { namespaces(store) } }.objects().map(PbsNamespace::fromJson) + PbsNamespace(""))
            .distinctBy { it.ns }.sortedBy { it.ns.split('/').joinToString("\u0000") }

    suspend fun createNamespace(serverId: String, store: String, parent: String, name: String) {
        hub.use(serverId) { it.pbsNoData { createNamespace(store, name.trim(), parent.ifEmpty { null }) } }
    }

    /** [deleteGroups] also removes the backups in it; otherwise only an empty namespace can go. */
    suspend fun deleteNamespace(serverId: String, store: String, ns: String, deleteGroups: Boolean) {
        hub.use(serverId) { it.pbsNoData { deleteNamespace(store, ns, if (deleteGroups) 1 else 0) } }
    }

    // ---- groups and snapshots

    suspend fun groups(serverId: String, store: String, ns: String): List<BackupGroup> =
        hub.use(serverId) { it.pbs { groups(store, ns(ns)) } }.objects().map(BackupGroup::fromJson)
            .sortedWith(compareBy({ it.type }, { it.id.toIntOrNull() ?: Int.MAX_VALUE }, { it.id }))

    /** Newest first. */
    suspend fun snapshots(serverId: String, store: String, ns: String, group: BackupGroup): List<BackupSnapshot> =
        hub.use(serverId) { it.pbs { snapshots(store, ns(ns) + mapOf("backup-type" to group.type, "backup-id" to group.id)) } }
            .objects().map(BackupSnapshot::fromJson).sortedByDescending { it.time }

    suspend fun setNotes(serverId: String, store: String, ns: String, snapshot: BackupSnapshot, notes: String) {
        hub.use(serverId) { it.pbsNoData { setNotes(store, ns(ns) + snapshot.params() + ("notes" to notes)) } }
    }

    suspend fun setProtected(serverId: String, store: String, ns: String, snapshot: BackupSnapshot, protected: Boolean) {
        hub.use(serverId) { it.pbsNoData { setProtected(store, ns(ns) + snapshot.params() + ("protected" to if (protected) "1" else "0")) } }
    }

    suspend fun deleteSnapshot(serverId: String, store: String, ns: String, snapshot: BackupSnapshot) {
        hub.use(serverId) { it.pbsNoData { deleteSnapshot(store, ns(ns) + snapshot.params()) } }
    }

    suspend fun deleteGroup(serverId: String, store: String, ns: String, group: BackupGroup) {
        hub.use(serverId) { it.pbsNoData { deleteGroup(store, ns(ns) + mapOf("backup-type" to group.type, "backup-id" to group.id)) } }
    }

    /** Verifies [snapshot], or [group], or everything in [ns]; returns the task's UPID. */
    suspend fun verify(serverId: String, store: String, ns: String, group: BackupGroup? = null, snapshot: BackupSnapshot? = null, ignoreVerified: Boolean = false): String {
        val params = ns(ns) + when {
            snapshot != null -> snapshot.params()
            group != null -> mapOf("backup-type" to group.type, "backup-id" to group.id)
            else -> emptyMap()
        } + if (ignoreVerified) mapOf("ignore-verified" to "1") else emptyMap()
        return hub.use(serverId) { it.pbs { verify(store, params) } }
    }

    /** Prunes [group] by [keep]; a [dryRun] only reports which snapshots it would keep. */
    suspend fun prune(serverId: String, store: String, ns: String, group: BackupGroup, keep: KeepOptions, dryRun: Boolean): List<PruneItem> {
        val params = ns(ns) + mapOf("backup-type" to group.type, "backup-id" to group.id, "dry-run" to if (dryRun) "1" else "0") + keep.params()
        return hub.use(serverId) { it.pbs { prune(store, params) } }.objects().map(PruneItem::fromJson).sortedByDescending { it.time }
    }

    // ---- file restore

    /** Entries under [filepath] (from a [CatalogEntry], or [CatalogEntry.ROOT]). */
    suspend fun catalog(serverId: String, store: String, ns: String, snapshot: BackupSnapshot, filepath: String = CatalogEntry.ROOT): List<CatalogEntry> =
        hub.use(serverId) { it.pbs { catalog(store, ns(ns) + snapshot.params() + ("filepath" to filepath)) } }
            .objects().map(CatalogEntry::fromJson).sortedWith(compareBy({ !it.browsable }, { it.name.lowercase() }))

    /**
     * Writes [entry] to [sink]: a file as it is, a directory as a zip. Returns the bytes written.
     * The caller owns [sink].
     */
    suspend fun download(serverId: String, store: String, ns: String, snapshot: BackupSnapshot, entry: CatalogEntry, sink: OutputStream): Long {
        val body = hub.use(serverId) { it.pbsRaw { downloadFile(store, ns(ns) + snapshot.params() + ("filepath" to entry.filepath)) } }
        return withContext(Dispatchers.IO) {
            try {
                body.use { it.byteStream().copyTo(sink) }
            } catch (e: IOException) {
                throw ApiException.Server(0, e.message ?: "download interrupted")
            }
        }
    }

    // ---- jobs and remotes

    /** Sync, verify and prune jobs; kinds the user may not read are left out. */
    suspend fun jobs(serverId: String): List<PbsJob> = PbsJobKind.entries.flatMap { kind ->
        try {
            hub.use(serverId) { it.pbs { jobs(kind.apiName) } }.objects().map { PbsJob.fromJson(kind, it) }
        } catch (_: ApiException.Forbidden) {
            emptyList()
        }
    }.sortedWith(compareBy({ it.kind }, { it.id }))

    suspend fun runJob(serverId: String, job: PbsJob): String = hub.use(serverId) { it.pbs { runJob(job.kind.apiName, job.id) } }

    suspend fun setPruneJobEnabled(serverId: String, job: PbsJob, enabled: Boolean) {
        val params = if (enabled) mapOf("delete" to "disable") else mapOf("disable" to "1")
        hub.use(serverId) { it.pbsNoData { updatePruneJob(job.id, params) } }
    }

    suspend fun remotes(serverId: String): List<PbsRemote> =
        hub.use(serverId) { it.pbs { remotes() } }.objects().map(PbsRemote::fromJson).sortedBy { it.name }

    suspend fun createRemote(serverId: String, form: PbsRemoteForm) {
        hub.use(serverId) { it.pbsNoData { createRemote(form.params()) } }
    }

    suspend fun deleteRemote(serverId: String, name: String) {
        hub.use(serverId) { it.pbsNoData { deleteRemote(name) } }
    }

    // ---- the node

    suspend fun nodeStatus(serverId: String): PbsNodeStatus = PbsNodeStatus.fromJson(hub.use(serverId) { it.pbs { nodeStatus(NODE) } })

    companion object {
        /** A Backup Server is one node, always called this in the API. */
        const val NODE = "localhost"
    }
}
