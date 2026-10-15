package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.PoolChange
import io.github.lucabarbalata.anprox.model.pve.PoolDetail
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Resource pools (Datacenter → Resource pools): groups of guests and storages, used for
 * permissions and backup jobs. Proxmox VE 8.1 moved pools to `/pools?poolid=…`; on older
 * versions each call falls back to `/pools/{poolid}`.
 */
class PoolRepository(private val hub: ServerHub) {

    /** Every pool with its members. */
    suspend fun pools(serverId: String): List<PoolDetail> = coroutineScope {
        val ids = hub.use(serverId) { it.pve { pools() } }.map { it.id }.sorted()
        ids.map { id -> async { pool(serverId, id) } }.awaitAll()
    }

    suspend fun pool(serverId: String, id: String): PoolDetail = withFallback(
        current = { hub.use(serverId) { it.pve { poolDetails(id) } }.first() },
        legacy = { hub.use(serverId) { it.pve { poolDetailLegacy(id) } }.copy(id = id) },
    )

    /** Guests and storages, to offer as new members. */
    suspend fun resources(serverId: String): List<ClusterResource> = hub.use(serverId) { it.pve { clusterResources(null) } }

    suspend fun create(serverId: String, id: String, comment: String) {
        val params = buildMap {
            put("poolid", id.trim())
            comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
        }
        hub.use(serverId) { it.pveNoData { createPool(params) } }
    }

    suspend fun setComment(serverId: String, id: String, comment: String) = update(serverId, id, mapOf("comment" to comment.trim()))

    suspend fun change(serverId: String, id: String, change: PoolChange) {
        if (!change.isEmpty) update(serverId, id, change.params())
    }

    /** Proxmox refuses to delete a pool that still has members. */
    suspend fun delete(serverId: String, id: String) = withFallback(
        current = { hub.use(serverId) { it.pveNoData { deletePool(id) } } },
        legacy = { hub.use(serverId) { it.pveNoData { deletePoolLegacy(id) } } },
    )

    private suspend fun update(serverId: String, id: String, params: Map<String, String>) = withFallback(
        current = { hub.use(serverId) { it.pveNoData { updatePool(params + ("poolid" to id)) } } },
        legacy = { hub.use(serverId) { it.pveNoData { updatePoolLegacy(id, params) } } },
    )

    /**
     * Runs [current]; if the server predates Proxmox VE 8.1 (no `PUT`/`DELETE /pools`, or no
     * `poolid` parameter on `GET /pools`), runs [legacy] instead.
     */
    private suspend fun <T> withFallback(current: suspend () -> T, legacy: suspend () -> T): T = try {
        current()
    } catch (e: ApiException) {
        val older = when (e) {
            is ApiException.Server -> e.code == 501
            is ApiException.NotFound -> true
            is ApiException.Validation -> "poolid" in e.fieldErrors
            else -> false
        }
        if (older) legacy() else throw e
    }
}
