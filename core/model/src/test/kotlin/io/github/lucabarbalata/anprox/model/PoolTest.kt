package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.PoolChange
import io.github.lucabarbalata.anprox.model.pve.PoolDetail
import io.github.lucabarbalata.anprox.model.pve.PoolIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoolTest {

    @Test
    fun `members by kind, storages once`() {
        val pool = ProxmoxJson.decodeFromString<ApiEnvelope<List<PoolDetail>>>(
            """{"data":[{"poolid":"prod","comment":"Production","members":[
                 {"id":"qemu/101","type":"qemu","vmid":101,"node":"pve1","name":"db","status":"running","template":0},
                 {"id":"storage/pve1/nas-nfs","type":"storage","node":"pve1","storage":"nas-nfs","status":"available"},
                 {"id":"qemu/100","type":"qemu","vmid":100,"node":"pve1","name":"web","status":"running"},
                 {"id":"storage/pve2/nas-nfs","type":"storage","node":"pve2","storage":"nas-nfs"},
                 {"id":"lxc/201","type":"lxc","vmid":201,"node":"pve2","name":"nginx-proxy"}]}]}""",
        ).data!!.single()
        assertEquals(listOf(100, 101, 201), pool.guests.map { it.vmid })
        assertEquals(listOf("nas-nfs"), pool.storages)
        assertFalse(pool.isEmpty)
    }

    @Test
    fun `changes and names`() {
        assertEquals(mapOf("vms" to "100,103", "storage" to "local"), PoolChange(setOf(103, 100), setOf("local")).params())
        assertEquals(mapOf("vms" to "104", "delete" to "1"), PoolChange(setOf(104), remove = true).params())
        assertTrue(PoolIds.isValid("prod-2.k8s_a"))
        assertFalse(PoolIds.isValid("my pool"))
        assertFalse(PoolIds.isValid(""))
    }
}
