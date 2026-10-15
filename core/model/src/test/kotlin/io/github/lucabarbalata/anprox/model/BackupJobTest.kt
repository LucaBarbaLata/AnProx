package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.BackupJob
import io.github.lucabarbalata.anprox.model.pve.BackupJobForm
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.BackupSelection
import io.github.lucabarbalata.anprox.model.pve.Retention
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupJobTest {

    private fun jobs(json: String) = ProxmoxJson.decodeFromString<ApiEnvelope<List<JsonObject>>>(json).data!!.map { BackupJob.fromJson(it) }

    // Shape of GET /cluster/backup on Proxmox VE 8.2, trimmed. Newer versions send prune-backups as an object.
    private val json = """{"data":[
        {"id":"backup-1a2b","type":"vzdump","schedule":"21:00","enabled":1,"storage":"pbs","all":1,"mode":"snapshot",
         "prune-backups":{"keep-daily":"7","keep-weekly":"4"},"notes-template":"{{guestname}}","next-run":1760554800},
        {"id":"backup-3c4d","type":"vzdump","schedule":"sun 01:00","enabled":0,"storage":"local","vmid":"201,200","mode":"stop",
         "compress":"zstd","prune-backups":"keep-last=3","comment":"web tier","node":"pve2"},
        {"id":"backup-5e6f","type":"vzdump","schedule":"mon..fri 22:00","storage":"pbs","all":1,"exclude":"104","mode":"suspend"},
        {"id":"backup-7a8b","type":"vzdump","schedule":"0/6:00","storage":"pbs","pool":"prod","prune-backups":"keep-all=1"}
    ]}"""

    @Test
    fun `reads backup jobs`() {
        val (daily, weekly, weekdays, pool) = jobs(json)
        assertEquals(BackupSelection.ALL, daily.selection)
        assertEquals(Retention(daily = 7, weekly = 4), daily.retention)
        assertEquals(1760554800L, daily.nextRun)
        assertEquals(true, daily.enabled)

        assertEquals(false, weekly.enabled)
        assertEquals(BackupSelection.INCLUDE, weekly.selection)
        assertEquals(listOf(201, 200), weekly.vmids)
        assertEquals(BackupMode.STOP, weekly.mode)
        assertEquals("pve2", weekly.node)
        assertEquals(Retention(last = 3), weekly.retention)

        assertEquals(true, weekdays.enabled) // absent means enabled
        assertEquals(BackupSelection.EXCLUDE, weekdays.selection)
        assertEquals(listOf(104), weekdays.vmids)
        assertNull(weekdays.retention)

        assertEquals(BackupSelection.POOL, pool.selection)
        assertEquals("prod", pool.pool)
        assertEquals(Retention(keepAll = true), pool.retention)
    }

    @Test
    fun `form parameters`() {
        val form = BackupJobForm(storage = "pbs", retention = Retention(daily = 7, weekly = 4))
        assertNull(form.problem())
        assertEquals(
            mapOf(
                "storage" to "pbs", "mode" to "snapshot", "compress" to "zstd", "all" to "1",
                "prune-backups" to "keep-daily=7,keep-weekly=4", "notes-template" to "{{guestname}}",
                "schedule" to "21:00", "enabled" to "1",
            ),
            form.createParams(),
        )

        val weekly = jobs(json)[1]
        val edited = BackupJobForm.from(weekly).copy(selection = BackupSelection.ALL, node = null, comment = "")
        assertEquals(
            mapOf(
                "storage" to "local", "mode" to "stop", "compress" to "zstd", "all" to "1",
                "prune-backups" to "keep-last=3", "schedule" to "sun 01:00", "enabled" to "0",
                "delete" to "vmid,exclude,pool,node,notes-template,comment",
            ),
            edited.updateParams(),
        )
        val include = BackupJobForm.from(weekly).updateParams()
        assertEquals("200,201", include["vmid"])
        assertEquals("0", include["all"])
        assertEquals("pve2", include["node"])
    }

    @Test
    fun `form problems`() {
        val ok = BackupJobForm(storage = "pbs")
        assertEquals(BackupJobForm.Problem.SCHEDULE, ok.copy(schedule = " ").problem())
        assertEquals(BackupJobForm.Problem.STORAGE, ok.copy(storage = null).problem())
        assertEquals(BackupJobForm.Problem.NO_GUESTS, ok.copy(selection = BackupSelection.INCLUDE).problem())
        assertEquals(BackupJobForm.Problem.NO_GUESTS, ok.copy(selection = BackupSelection.EXCLUDE).problem())
        assertEquals(BackupJobForm.Problem.POOL, ok.copy(selection = BackupSelection.POOL).problem())
        assertEquals(BackupJobForm.Problem.RETENTION, ok.copy(retention = Retention()).problem())
        assertNull(ok.copy(selection = BackupSelection.INCLUDE, vmids = setOf(100)).problem())
    }
}
