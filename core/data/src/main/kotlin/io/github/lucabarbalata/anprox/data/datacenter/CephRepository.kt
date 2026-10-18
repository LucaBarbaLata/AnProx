package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.json.objects
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.pve.CephConfigEntry
import io.github.lucabarbalata.anprox.model.pve.CephFlag
import io.github.lucabarbalata.anprox.model.pve.CephFs
import io.github.lucabarbalata.anprox.model.pve.CephFsForm
import io.github.lucabarbalata.anprox.model.pve.CephOsd
import io.github.lucabarbalata.anprox.model.pve.CephOsdForm
import io.github.lucabarbalata.anprox.model.pve.CephOsdTree
import io.github.lucabarbalata.anprox.model.pve.CephOverview
import io.github.lucabarbalata.anprox.model.pve.CephPool
import io.github.lucabarbalata.anprox.model.pve.CephPoolForm
import io.github.lucabarbalata.anprox.model.pve.CephService
import io.github.lucabarbalata.anprox.model.pve.CephServiceType
import io.github.lucabarbalata.anprox.model.pve.NodeDisk
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.cephNotSetUp
import io.github.lucabarbalata.anprox.network.ApiException

/** Start, stop or restart a Ceph daemon. */
enum class CephAction(val apiName: String) { START("start"), STOP("stop"), RESTART("restart") }

/**
 * Datacenter → Ceph (questionnaire Q21, milestone M6). Ceph is cluster-wide, but Proxmox serves
 * it per node: reads go to [node] (any online node), changes to an OSD or daemon go to its host.
 */
class CephRepository(private val hub: ServerHub) {

    suspend fun onlineNodes(serverId: String): List<String> =
        hub.use(serverId) { it.pve { clusterResources("node") } }
            .filter { it.resourceType == ResourceType.NODE && it.status == "online" }
            .mapNotNull { it.node }.sorted()

    suspend fun overview(serverId: String, node: String): CephOverview = CephOverview.fromJson(hub.use(serverId) { it.pve { cephOverview(node) } })

    // ---- OSDs

    suspend fun osds(serverId: String, node: String): CephOsdTree = CephOsdTree.fromJson(hub.use(serverId) { it.pve { cephOsds(node) } })

    suspend fun setIn(serverId: String, node: String, osd: CephOsd, inCluster: Boolean) {
        hub.use(serverId) { it.pveNoData { if (inCluster) cephOsdIn(node, osd.id) else cephOsdOut(node, osd.id) } }
    }

    suspend fun scrub(serverId: String, node: String, osd: CephOsd, deep: Boolean) {
        hub.use(serverId) { it.pveNoData { cephOsdScrub(node, osd.id, if (deep) 1 else 0) } }
    }

    /** Disks of [node] nothing uses yet: where an OSD can go. */
    suspend fun unusedDisks(serverId: String, node: String): List<NodeDisk> =
        hub.use(serverId) { it.pve { disks(node) } }.filter { it.unused }.sortedBy { it.devpath }

    suspend fun createOsd(serverId: String, form: CephOsdForm): String = hub.use(serverId) { it.pve { createCephOsd(form.node, form.params()) } }

    /** Runs on the OSD's host. [cleanup] also wipes its disk. */
    suspend fun destroyOsd(serverId: String, osd: CephOsd, cleanup: Boolean): String =
        hub.use(serverId) { it.pve { destroyCephOsd(osd.host.orEmpty(), osd.id, if (cleanup) 1 else 0) } }

    // ---- pools

    suspend fun pools(serverId: String, node: String): List<CephPool> =
        hub.use(serverId) { it.pve { cephPools(node) } }.objects().map(CephPool::fromJson).sortedWith(compareBy({ it.internal }, { it.name }))

    suspend fun rules(serverId: String, node: String): List<String> =
        hub.use(serverId) { it.pve { cephRules(node) } }.objects().mapNotNull { it.str("name") }.sorted()

    suspend fun createPool(serverId: String, node: String, form: CephPoolForm): String = hub.use(serverId) { it.pve { createCephPool(node, form.createParams()) } }

    /** Null when nothing changed. */
    suspend fun updatePool(serverId: String, node: String, pool: CephPool, form: CephPoolForm): String? {
        val params = form.updateParams(pool).takeIf { it.isNotEmpty() } ?: return null
        return hub.use(serverId) { it.pve { updateCephPool(node, pool.name, params) } }
    }

    suspend fun deletePool(serverId: String, node: String, pool: String, removeStorages: Boolean): String =
        hub.use(serverId) { it.pve { deleteCephPool(node, pool, if (removeStorages) 1 else 0) } }

    // ---- monitors, managers, metadata servers, CephFS

    suspend fun services(serverId: String, node: String, type: CephServiceType): List<CephService> =
        hub.use(serverId) { it.pve { cephServices(node, type.apiName) } }.objects()
            .map { CephService.fromJson(type, it) }.sortedBy { it.name }

    /** Adds a [type] daemon on [node], named after the node as Proxmox does. */
    suspend fun createService(serverId: String, node: String, type: CephServiceType): String =
        hub.use(serverId) { it.pve { createCephService(node, type.apiName, node) } }

    suspend fun destroyService(serverId: String, service: CephService): String =
        hub.use(serverId) { it.pve { destroyCephService(service.host ?: service.name, service.type.apiName, service.name) } }

    /** [host] is where the daemon runs; [service] e.g. `osd.3` or `mon.pve1`. */
    suspend fun serviceAction(serverId: String, host: String, service: String, action: CephAction): String =
        hub.use(serverId) { it.pve { cephServiceAction(host, action.apiName, service) } }

    suspend fun filesystems(serverId: String, node: String): List<CephFs> =
        hub.use(serverId) { it.pve { cephFs(node) } }.objects().map(CephFs::fromJson).sortedBy { it.name }

    suspend fun createFs(serverId: String, node: String, form: CephFsForm): String =
        hub.use(serverId) { it.pve { createCephFs(node, form.name.trim(), form.params()) } }

    // ---- flags, configuration, CRUSH map, log

    /** The common maintenance flags first, the rest by name. */
    suspend fun flags(serverId: String): List<CephFlag> =
        hub.use(serverId) { it.pve { cephFlags() } }.objects().map(CephFlag::fromJson)
            .sortedWith(compareBy({ CephFlag.COMMON.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))

    suspend fun setFlag(serverId: String, flag: String, value: Boolean) {
        hub.use(serverId) { it.pveNoData { setCephFlag(flag, if (value) 1 else 0) } }
    }

    suspend fun config(serverId: String, node: String): String = hub.use(serverId) { it.pve { cephConfig(node) } }

    suspend fun configDb(serverId: String, node: String): List<CephConfigEntry> =
        hub.use(serverId) { it.pve { cephConfigDb(node) } }.objects().map(CephConfigEntry::fromJson)

    suspend fun crushMap(serverId: String, node: String): String = hub.use(serverId) { it.pve { cephCrush(node) } }

    suspend fun log(serverId: String, node: String, lines: Int = 500): List<String> =
        hub.use(serverId) { it.pve { cephLog(node, lines) } }.sortedBy { it.n }.map { it.t }

    companion object {
        /** True when [e] means the cluster has no Ceph (not installed or not initialised). */
        fun notSetUp(e: ApiException): Boolean = e is ApiException.Server && cephNotSetUp(e.message)
    }
}
