package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.HaGroup
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaStatusEntry

/**
 * The HA manager: resources (seeded from guests flagged `ha`), groups, and a status that names
 * pve1 the master. Changes take effect at once; the real CRM needs a few seconds.
 */
internal class MockHa(guests: List<MockState.Guest>) {
    private val resources = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private var version = 1

    private val groupMap = linkedMapOf(
        "prefer-pve1" to linkedMapOf("nodes" to "pve1:2,pve2:1", "comment" to "Run on pve1 when it is up"),
    )
    private var groupVersion = 1

    /** Groups as Proxmox VE 8 has them (the mock answers like 8: no rules). */
    val groups: List<HaGroup>
        get() = groupMap.map { (g, o) ->
            HaGroup(g, o["nodes"], o["comment"], restricted = o["restricted"] == "1", nofailback = o["nofailback"] == "1", digest = "grp%04d".format(groupVersion))
        }

    fun createGroup(p: Map<String, String>, nodeExists: (String) -> Boolean): String? {
        val name = p["group"].orEmpty()
        if (!Regex("^[A-Za-z][A-Za-z0-9\\-_.]*[A-Za-z0-9]$").matches(name)) return "group: invalid format"
        if (name in groupMap) return "ha group '$name' already defined"
        checkNodes(p["nodes"], nodeExists)?.let { return it }
        groupMap[name] = LinkedHashMap(p.filterKeys { it != "group" && it != "type" })
        groupVersion++
        return null
    }

    fun updateGroup(name: String, p: Map<String, String>, nodeExists: (String) -> Boolean): String? {
        val o = groupMap[name] ?: return "no such ha group '$name'"
        p["digest"]?.let { if (it != "grp%04d".format(groupVersion)) return "detected modified configuration - file changed by other user? Try again." }
        p["nodes"]?.let { checkNodes(it, nodeExists)?.let { e -> return e } }
        p.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> o[k] = v }
        p["delete"].orEmpty().split(',').filter { it.isNotBlank() }.forEach { o.remove(it) }
        groupVersion++
        return null
    }

    fun deleteGroup(name: String): String? {
        if (resources.values.any { it["group"] == name }) return "ha group '$name' is used by a resource"
        groupMap.remove(name) ?: return "no such ha group '$name'"
        groupVersion++
        return null
    }

    private fun checkNodes(nodes: String?, nodeExists: (String) -> Boolean): String? {
        val list = nodes.orEmpty().split(',').map { it.substringBefore(':').trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return "nodes: property is missing and it is not optional"
        list.firstOrNull { !nodeExists(it) }?.let { return "no such node '$it'" }
        return null
    }

    init {
        guests.filter { it.ha }.forEach { g ->
            resources[sid(g)] = linkedMapOf("state" to "started", "max_restart" to "1", "max_relocate" to "1")
        }
        resources["vm:100"]?.set("group", "prefer-pve1")
    }

    private val digest get() = "ha%04d".format(version)

    fun sid(g: MockState.Guest) = "${if (g.type == "qemu") "vm" else "ct"}:${g.vmid}"

    fun state(g: MockState.Guest): String? = resources[sid(g)]?.get("state")

    fun list(): List<HaResource> = resources.map { (sid, o) ->
        HaResource(
            sid = sid, type = sid.substringBefore(':'), state = o["state"], group = o["group"],
            maxRestart = o["max_restart"]?.toIntOrNull(), maxRelocate = o["max_relocate"]?.toIntOrNull(),
            comment = o["comment"], digest = digest,
        )
    }

    fun add(params: Map<String, String>, guestExists: (String) -> Boolean): String? {
        val sid = params["sid"].orEmpty()
        if (!guestExists(sid)) return "no such resource '$sid'"
        if (sid in resources) return "resource ID '$sid' already defined"
        validate(params)?.let { return it }
        resources[sid] = LinkedHashMap(params.filterKeys { it != "sid" })
        version++
        return null
    }

    fun update(sid: String, params: Map<String, String>): String? {
        val o = resources[sid] ?: return "no such resource '$sid'"
        params["digest"]?.let { if (it != digest) return "detected modified configuration - file changed by other user? Try again." }
        validate(params)?.let { return it }
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) -> o[k] = v }
        params["delete"].orEmpty().split(',').filter { it.isNotBlank() }.forEach { o.remove(it) }
        version++
        return null
    }

    fun remove(sid: String): String? {
        resources.remove(sid) ?: return "no such resource '$sid'"
        version++
        return null
    }

    private fun validate(p: Map<String, String>): String? {
        p["state"]?.let { if (it !in listOf("started", "stopped", "disabled", "ignored", "enabled")) return "state: value '$it' does not have a value in the enumeration" }
        p["group"]?.let { g -> if (g.isNotEmpty() && groups.none { it.group == g }) return "group '$g' does not exist" }
        return null
    }

    fun status(nodes: List<MockState.Node>, guests: List<MockState.Guest>): List<HaStatusEntry> = buildList {
        add(HaStatusEntry("quorum", "quorum", status = "OK", node = "pve1"))
        add(HaStatusEntry("master", "master", status = "pve1 (active)", node = "pve1"))
        nodes.forEach { n ->
            add(HaStatusEntry("lrm:${n.name}", "lrm", status = if (n.online) "${n.name} (active)" else "${n.name} (old timestamp - dead?)", node = n.name))
        }
        resources.forEach { (sid, o) ->
            val g = guests.firstOrNull { sid(it) == sid }
            add(HaStatusEntry("service:$sid", "service", status = o["state"], node = g?.node, sid = sid, state = o["state"], crmState = o["state"], requestState = o["state"]))
        }
    }
}
