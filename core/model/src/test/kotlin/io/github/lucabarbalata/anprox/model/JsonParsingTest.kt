package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.auth.TicketResponse
import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.AptRepositories
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.ResourceType
import io.github.lucabarbalata.anprox.model.pve.TaskState
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonParsingTest {

    @Test
    fun `cluster resources tolerate quoted numbers, int booleans and unknown keys`() {
        val json = """
            {"data":[
              {"id":"qemu/100","type":"qemu","node":"pve1","vmid":"100","name":"web","status":"running",
               "cpu":"0.25","maxcpu":4,"mem":1073741824,"maxmem":4294967296,"template":0,"tags":"prod;web",
               "brand-new-field":{"x":1}},
              {"id":"node/pve1","type":"node","node":"pve1","status":"online","cpu":0.1,"maxcpu":8},
              {"id":"storage/pve1/local","type":"storage","node":"pve1","storage":"local","shared":"1",
               "disk":10,"maxdisk":100,"status":"available"}
            ]}
        """.trimIndent()
        val rows = ProxmoxJson.decodeFromString<ApiEnvelope<List<ClusterResource>>>(json).data!!

        val vm = rows[0]
        assertEquals(100, vm.vmid)
        assertEquals(0.25, vm.cpu)
        assertFalse(vm.template)
        assertEquals(listOf("prod", "web"), vm.tagList)
        assertEquals("100 · web", vm.displayName)
        assertEquals(0.25, vm.memFraction)
        assertTrue(vm.isGuest && vm.isRunning)

        assertEquals(ResourceType.NODE, rows[1].resourceType)
        assertTrue(rows[2].shared)
        assertEquals("local", rows[2].displayName)
    }

    @Test
    fun `standard repositories - numbers from Proxmox VE, booleans from a Backup Server`() {
        // PBS 4.2 sends true/false where Proxmox VE sends 1/0; the app couldn't read it before.
        fun parse(enterprise: String, noSubscription: String): AptRepositories = ProxmoxJson.decodeFromString(
            """{"digest":"d","files":[],"errors":[],"infos":[],"standard-repos":[
                {"handle":"enterprise","name":"Enterprise",$enterprise},
                {"handle":"no-subscription","name":"No-Subscription",$noSubscription},
                {"handle":"test","name":"Test"}]}""",
        )
        for (repos in listOf(parse("\"status\":1", "\"status\":0"), parse("\"status\":true", "\"status\":false"))) {
            assertEquals(listOf(true, false, null), repos.standard.map { it.status })
            assertEquals(listOf("test"), repos.addable.map { it.handle })
        }
    }

    @Test
    fun `ticket response with NeedTFA`() {
        val json = """{"data":{"username":"root@pam","ticket":"PVE:!tfa!%7B%7D:65A1B2C3::sig","NeedTFA":1}}"""
        val t = ProxmoxJson.decodeFromString<ApiEnvelope<TicketResponse>>(json).data!!
        assertTrue(t.needTfa)
        assertEquals(null, t.csrfToken)
    }

    @Test
    fun `cluster status rows`() {
        val json = """{"data":[
            {"type":"cluster","id":"cluster","name":"homelab","nodes":3,"quorate":1,"version":7},
            {"type":"node","id":"node/pve1","name":"pve1","online":1,"local":1,"ip":"10.0.0.1","nodeid":1}
        ]}"""
        val rows = ProxmoxJson.decodeFromString<ApiEnvelope<List<ClusterStatusEntry>>>(json).data!!
        assertTrue(rows[0].quorate)
        assertTrue(rows[1].online && rows[1].local)
    }

    @Test
    fun `task state and PBS field aliases`() {
        val json = """{"data":[
            {"upid":"UPID:a","node":"pve1","type":"qmstart","id":"100","user":"root@pam","starttime":1},
            {"upid":"UPID:b","node":"pbs","worker_type":"garbage_collection","worker_id":"store1",
             "user":"root@pam","starttime":1,"endtime":2,"status":"OK"},
            {"upid":"UPID:c","node":"pve1","type":"vzdump","user":"root@pam","starttime":1,"endtime":2,"status":"WARNINGS: 2"},
            {"upid":"UPID:d","node":"pve1","type":"vzdump","user":"root@pam","starttime":1,"endtime":2,"status":"job errors"}
        ]}"""
        val rows = ProxmoxJson.decodeFromString<ApiEnvelope<List<TaskSummary>>>(json).data!!
        assertEquals(listOf(TaskState.RUNNING, TaskState.OK, TaskState.WARNING, TaskState.FAILED), rows.map { it.state })
        assertEquals("garbage_collection", rows[1].type)
        assertEquals("store1", rows[1].id)
    }
}
