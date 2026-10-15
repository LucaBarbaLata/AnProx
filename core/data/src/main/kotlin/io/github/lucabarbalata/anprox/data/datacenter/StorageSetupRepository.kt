package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.CifsShare
import io.github.lucabarbalata.anprox.model.pve.LvmThinPool
import io.github.lucabarbalata.anprox.model.pve.LvmVolumeGroup
import io.github.lucabarbalata.anprox.model.pve.NfsExport
import io.github.lucabarbalata.anprox.model.pve.PbsDatastore
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.StorageSetupForm
import io.github.lucabarbalata.anprox.model.pve.ZfsPoolName

/**
 * An existing storage for the editor: [form] is null for a type the app can't edit in full
 * (then only removing it is offered); [raw] are its flat values with the digest.
 */
data class StorageSetup(val id: String, val type: String, val form: StorageSetupForm?, val raw: Map<String, String>)

/**
 * Datacenter → Storage → add, edit or remove a storage (milestone M4), with the node-side scans
 * that fill in NFS exports, SMB shares, Backup Server datastores, volume groups and ZFS pools.
 */
class StorageSetupRepository(private val hub: ServerHub) {

    suspend fun load(serverId: String, id: String): StorageSetup {
        val o = hub.use(serverId) { it.pve { storageConfig(id) } }
        val raw = StorageSetupForm.flatten(o)
        return StorageSetup(id, raw["type"].orEmpty(), StorageSetupForm.from(o), raw)
    }

    /** Online nodes, to limit a storage to some of them and to run scans on. */
    suspend fun onlineNodes(serverId: String): List<String> =
        hub.use(serverId) { it.pve { clusterResources("node") } }
            .filter { it.resourceType == ResourceType.NODE && it.status == "online" }
            .mapNotNull { it.node }.sorted()

    suspend fun create(serverId: String, form: StorageSetupForm) {
        hub.use(serverId) { it.pveNoData { createStorage(form.createParams()) } }
    }

    suspend fun update(serverId: String, existing: StorageSetup, form: StorageSetupForm) {
        hub.use(serverId) { it.pveNoData { updateStorage(existing.id, form.updateParams(existing.raw)) } }
    }

    /** Removes the storage from the configuration; what is stored on it is not deleted. */
    suspend fun remove(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteStorage(id) } }
    }

    suspend fun scanNfs(serverId: String, node: String, server: String): List<NfsExport> =
        hub.use(serverId) { it.pve { scanNfs(node, server.trim()) } }

    suspend fun scanCifs(serverId: String, node: String, server: String, username: String, password: String, domain: String): List<CifsShare> {
        val params = buildMap {
            put("server", server.trim())
            if (username.isNotBlank()) put("username", username.trim())
            if (password.isNotEmpty()) put("password", password)
            if (domain.isNotBlank()) put("domain", domain.trim())
        }
        return hub.use(serverId) { it.pve { scanCifs(node, params) } }
    }

    suspend fun scanPbs(serverId: String, node: String, server: String, username: String, password: String, fingerprint: String): List<PbsDatastore> {
        val params = buildMap {
            put("server", server.trim())
            put("username", username.trim())
            put("password", password)
            if (fingerprint.isNotBlank()) put("fingerprint", fingerprint.trim())
        }
        return hub.use(serverId) { it.pve { scanPbs(node, params) } }
    }

    suspend fun scanLvm(serverId: String, node: String): List<LvmVolumeGroup> = hub.use(serverId) { it.pve { scanLvm(node) } }

    suspend fun scanLvmThin(serverId: String, node: String, vg: String): List<LvmThinPool> = hub.use(serverId) { it.pve { scanLvmThin(node, vg) } }

    suspend fun scanZfs(serverId: String, node: String): List<ZfsPoolName> = hub.use(serverId) { it.pve { scanZfs(node) } }
}
