package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaResourceForm
import io.github.lucabarbalata.anprox.model.pve.HaState
import io.github.lucabarbalata.anprox.model.pve.HaStatusEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class HighAvailabilityTest {

    @Test
    fun `resources and status`() {
        val resources = ProxmoxJson.decodeFromString<ApiEnvelope<List<HaResource>>>(
            """{"data":[{"sid":"vm:100","type":"vm","state":"started","group":"prefer-pve1","max_restart":1,"max_relocate":1,"digest":"h1"},
                        {"sid":"ct:201","type":"ct","state":"ignored","digest":"h1"}]}""",
        ).data!!
        assertEquals(100, resources[0].vmid)
        assertEquals(HaState.IGNORED, resources[1].haState)
        assertEquals("ct:201", HaResource.sid(GuestType.LXC, 201))

        val status = ProxmoxJson.decodeFromString<ApiEnvelope<List<HaStatusEntry>>>(
            """{"data":[{"id":"quorum","type":"quorum","status":"OK","node":"pve1","quorate":1},
                        {"id":"master","type":"master","status":"pve1 (active, Wed Oct 15 10:00:00 2026)","node":"pve1"},
                        {"id":"lrm:pve1","type":"lrm","status":"pve1 (active, Wed Oct 15 10:00:00 2026)","node":"pve1"},
                        {"id":"service:vm:100","type":"service","sid":"vm:100","node":"pve1","state":"started","crm_state":"started","request_state":"started","status":"started"}]}""",
        ).data!!
        assertEquals(listOf("quorum", "master", "lrm", "service"), status.map { it.type })
        assertEquals("vm:100", status.last().sid)
    }

    @Test
    fun `resource form`() {
        assertEquals(
            mapOf("sid" to "vm:101", "state" to "started", "max_restart" to "1", "max_relocate" to "1"),
            HaResourceForm().createParams("vm:101"),
        )
        val r = HaResource("vm:100", "vm", "started", group = "prefer-pve1", comment = "web", digest = "h1")
        assertEquals(
            mapOf("state" to "stopped", "max_restart" to "2", "max_relocate" to "1", "delete" to "group,comment", "digest" to "h1"),
            HaResourceForm.from(r).copy(state = HaState.STOPPED, group = "", comment = "", maxRestart = 2).updateParams(r),
        )
        assertEquals(false, HaResourceForm(maxRestart = 11).valid)
    }
}
