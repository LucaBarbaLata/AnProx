package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.ReplicationJob
import io.github.lucabarbalata.anprox.model.pve.ReplicationJobForm
import io.github.lucabarbalata.anprox.model.pve.ReplicationState
import io.github.lucabarbalata.anprox.model.pve.ReplicationStatus
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplicationTest {

    private fun jobs(json: String) =
        ProxmoxJson.decodeFromString<ApiEnvelope<List<JsonObject>>>(json).data!!.map(ReplicationJob::fromJson)

    @Test
    fun `jobs with numbers or strings, defaults and flags`() {
        val jobs = jobs(
            """{"data":[{"id":"103-0","guest":103,"jobnum":0,"target":"pve1","type":"local","schedule":"*/30","rate":"25","comment":"k8s","digest":"d1"},
                        {"id":"200-1","guest":"200","jobnum":"1","target":"pve2","type":"local","disable":1,"digest":"d1"},
                        {"id":"201-0","target":"pve1","type":"local","remove_job":"full","rate":12.5}]}""",
        )
        assertEquals(listOf(103, 200, 201), jobs.map { it.guest })
        assertEquals(listOf(0, 1, 0), jobs.map { it.jobNumber })
        assertEquals("*/30", jobs[0].schedule)
        assertEquals(ReplicationJob.DEFAULT_SCHEDULE, jobs[1].schedule)
        assertEquals(25.0, jobs[0].rate)
        assertEquals(12.5, jobs[2].rate)
        assertTrue(jobs[0].enabled)
        assertFalse(jobs[1].enabled)
        assertTrue(jobs[2].removing)
    }

    @Test
    fun `status and the state shown for a job`() {
        val rows = ProxmoxJson.decodeFromString<ApiEnvelope<List<JsonObject>>>(
            """{"data":[{"id":"103-0","guest":103,"last_sync":1760500000,"last_try":1760500000,"next_sync":1760501800,"duration":3.41,"fail_count":0},
                        {"id":"200-1","last_sync":0,"last_try":1760500000,"fail_count":2,"error":"no replicable volumes"},
                        {"id":"201-0","last_sync":0,"pid":4242}]}""",
        ).data!!.map(ReplicationStatus::fromJson)
        assertEquals(1760500000L, rows[0].lastSync)
        assertNull(rows[1].lastSync)
        assertEquals(2, rows[1].failCount)
        assertTrue(rows[2].running)

        val job = jobs("""{"data":[{"id":"103-0","target":"pve1"}]}""").single()
        assertEquals(ReplicationState.OK, ReplicationStatus.state(job, rows[0]))
        assertEquals(ReplicationState.FAILED, ReplicationStatus.state(job, rows[1]))
        assertEquals(ReplicationState.SYNCING, ReplicationStatus.state(job, rows[2]))
        assertEquals(ReplicationState.NEVER_RUN, ReplicationStatus.state(job, null))
        assertEquals(ReplicationState.DISABLED, ReplicationStatus.state(job.copy(enabled = false), rows[0]))
        assertEquals(ReplicationState.REMOVING, ReplicationStatus.state(job.copy(removing = true), rows[2]))
    }

    @Test
    fun `the form checks what Proxmox would refuse`() {
        val existing = jobs("""{"data":[{"id":"103-0","guest":103,"jobnum":0,"target":"pve1"}]}""")
        val form = ReplicationJobForm(guest = 103, target = "pve3")
        assertNull(form.problem("pve2", existing, creating = true))
        assertEquals(ReplicationJobForm.Problem.GUEST, ReplicationJobForm().problem(null, existing, true))
        assertEquals(ReplicationJobForm.Problem.TARGET, form.copy(target = null).problem("pve2", existing, true))
        assertEquals(ReplicationJobForm.Problem.SAME_NODE, form.copy(target = "pve2").problem("pve2", existing, true))
        assertEquals(ReplicationJobForm.Problem.DUPLICATE, form.copy(target = "pve1").problem("pve2", existing, true))
        assertNull(form.copy(target = "pve1").problem("pve2", existing, creating = false))
        assertEquals(ReplicationJobForm.Problem.SCHEDULE, form.copy(schedule = " ").problem("pve2", existing, true))
        assertEquals(ReplicationJobForm.Problem.RATE, form.copy(rate = "0.5").problem("pve2", existing, true))
        assertEquals(ReplicationJobForm.Problem.RATE, form.copy(rate = "fast").problem("pve2", existing, true))
        assertNull(form.copy(rate = "12,5").problem("pve2", existing, true))
    }

    @Test
    fun `create takes the next free job number, update deletes what was cleared`() {
        val existing = jobs(
            """{"data":[{"id":"103-0","guest":103,"jobnum":0,"target":"pve1"},
                        {"id":"103-2","guest":103,"jobnum":2,"target":"pve3"}]}""",
        )
        assertEquals(1, ReplicationJobForm.nextJobNumber(103, existing))
        assertEquals(0, ReplicationJobForm.nextJobNumber(200, existing))
        assertEquals(
            mapOf("id" to "103-1", "type" to "local", "target" to "pve4", "schedule" to "*/30", "rate" to "10", "disable" to "1"),
            ReplicationJobForm(guest = 103, target = "pve4", schedule = "*/30", rate = "10", enabled = false).createParams(existing),
        )

        val original = jobs("""{"data":[{"id":"103-0","guest":103,"target":"pve1","rate":"10","comment":"old","disable":1,"digest":"d7"}]}""").single()
        val form = ReplicationJobForm.from(original)
        assertEquals("10", form.rate)
        assertEquals(
            mapOf("schedule" to "*/15", "delete" to "rate,comment,disable", "digest" to "d7"),
            form.copy(rate = "", comment = "", enabled = true).updateParams(original),
        )
        assertEquals(
            mapOf("schedule" to "22:00", "rate" to "12.5", "comment" to "old", "disable" to "1", "digest" to "d7"),
            form.copy(schedule = "22:00", rate = "12.5").updateParams(original),
        )
    }
}
