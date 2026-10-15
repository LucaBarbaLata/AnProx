package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.PoolDetail
import io.github.lucabarbalata.anprox.model.pve.PoolMember
import io.github.lucabarbalata.anprox.model.pve.ResourcePool

/**
 * Resource pools: `prod` (web, db, nginx-proxy and the NFS storage) and `lab` (lab-vm). A guest
 * belongs to one pool at most; only an empty pool can be deleted, as in Proxmox.
 */
internal class MockPools {
    private class Pool(var comment: String?, val vms: MutableSet<Int>, val storages: MutableSet<String>)

    private val pools = linkedMapOf(
        "prod" to Pool("Production", mutableSetOf(100, 101, 201), mutableSetOf("nas-nfs")),
        "lab" to Pool(null, mutableSetOf(104), mutableSetOf()),
    )

    fun list(): List<ResourcePool> = pools.map { (id, p) -> ResourcePool(id, p.comment) }

    fun members(id: String): Set<Int> = pools[id]?.vms.orEmpty()

    fun poolOf(vmid: Int): String? = pools.entries.firstOrNull { vmid in it.value.vms }?.key

    fun detail(id: String, guests: List<MockState.Guest>, storages: List<MockState.Storage>): PoolDetail? {
        val p = pools[id] ?: return null
        val members = guests.filter { it.vmid in p.vms }.sortedBy { it.vmid }.map { g ->
            PoolMember("${g.type}/${g.vmid}", g.type, vmid = g.vmid, node = g.node, name = g.name, status = g.status)
        } + storages.filter { it.id in p.storages }.map { s ->
            PoolMember("storage/${s.node}/${s.id}", "storage", node = s.node, storage = s.id, status = "available")
        }
        return PoolDetail(id, p.comment, members)
    }

    fun create(params: Map<String, String>): String? {
        val id = params["poolid"].orEmpty()
        if (!Regex("^[A-Za-z0-9.\\-_]+$").matches(id)) return "poolid: invalid format - invalid pool ID '$id'"
        if (id in pools) return "pool '$id' already exists"
        pools[id] = Pool(params["comment"]?.takeIf { it.isNotBlank() }, mutableSetOf(), mutableSetOf())
        return null
    }

    fun update(id: String, params: Map<String, String>, guestExists: (Int) -> Boolean, storageExists: (String) -> Boolean): String? {
        val p = pools[id] ?: return "pool '$id' does not exist"
        val vms = params["vms"].orEmpty().split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }
        val storages = params["storage"].orEmpty().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val remove = params["delete"] == "1"
        vms.forEach { vmid ->
            if (!guestExists(vmid)) return "no such VMID '$vmid'"
            if (remove) {
                if (vmid !in p.vms) return "VM $vmid is not a pool member"
            } else {
                val other = poolOf(vmid)
                if (other != null && other != id && params["allow-move"] != "1") return "VM $vmid belongs already to pool '$other'"
            }
        }
        storages.forEach { s ->
            if (!storageExists(s)) return "no such storage '$s'"
            if (remove && s !in p.storages) return "storage '$s' is not a pool member"
        }
        params["comment"]?.let { p.comment = it.takeIf { c -> c.isNotBlank() } }
        if (remove) {
            p.vms.removeAll(vms.toSet())
            p.storages.removeAll(storages.toSet())
        } else {
            vms.forEach { vmid -> pools.values.forEach { it.vms.remove(vmid) } }
            p.vms.addAll(vms)
            p.storages.addAll(storages)
        }
        return null
    }

    fun delete(id: String): String? {
        val p = pools[id] ?: return "pool '$id' does not exist"
        if (p.vms.isNotEmpty() || p.storages.isNotEmpty()) return "pool '$id' is not empty"
        pools.remove(id)
        return null
    }
}
