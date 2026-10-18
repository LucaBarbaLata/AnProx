package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.CephFlag
import io.github.lucabarbalata.anprox.model.pve.CephOsdForm
import io.github.lucabarbalata.anprox.model.pve.CephOsdTree
import io.github.lucabarbalata.anprox.model.pve.CephOverview
import io.github.lucabarbalata.anprox.model.pve.CephPool
import io.github.lucabarbalata.anprox.model.pve.CephPoolForm
import io.github.lucabarbalata.anprox.model.pve.CephService
import io.github.lucabarbalata.anprox.model.pve.CephServiceType
import io.github.lucabarbalata.anprox.model.pve.cephNotSetUp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CephModelTest {
    private fun json(text: String): JsonObject = ProxmoxJson.parseToJsonElement(text).jsonObject

    @Test
    fun `status of a Reef cluster with a warning`() {
        val s = CephOverview.fromJson(
            json(
                """
                {"fsid":"x","health":{"status":"HEALTH_WARN","checks":{
                   "OSDMAP_FLAGS":{"severity":"HEALTH_WARN","summary":{"message":"noout flag(s) set","count":1},"muted":false},
                   "MON_DISK_CRIT":{"severity":"HEALTH_ERR","summary":{"message":"mon pve1 is very low on available space","count":1},
                     "detail":[{"message":"mon.pve1 has 3% avail"}]}}},
                 "quorum_names":["pve1","pve2"],"monmap":{"epoch":3,"num_mons":3},
                 "osdmap":{"epoch":120,"num_osds":6,"num_up_osds":5,"num_in_osds":6},
                 "pgmap":{"pgs_by_state":[{"state_name":"active+undersized+degraded","count":12},{"state_name":"active+clean","count":117}],
                   "num_pgs":129,"bytes_used":1000,"bytes_avail":3000,"bytes_total":4000,"read_bytes_sec":2048,"write_op_per_sec":7},
                 "mgrmap":{"available":true,"num_standbys":1},"fsmap":{"up":1,"up:standby":1}}
                """,
            ),
        )
        assertEquals("HEALTH_WARN", s.health)
        assertEquals(listOf("MON_DISK_CRIT", "OSDMAP_FLAGS"), s.checks.map { it.id }) // errors first
        assertEquals(listOf("mon.pve1 has 3% avail"), s.checks.first().details)
        assertEquals(3, s.monitors)
        assertEquals(listOf("pve1", "pve2"), s.quorum)
        assertEquals(5, s.osdsUp)
        assertEquals("active+clean" to 117, s.pgStates.first())
        assertEquals(12, s.pgsNotClean)
        assertEquals(0.25, s.usedFraction)
        assertEquals(2048, s.readBytesPerSec)
        assertEquals(7, s.writeOpsPerSec)
        assertTrue(s.managerAvailable)
        assertEquals(1, s.mdsStandby)
    }

    @Test
    fun `status before Quincy nests the OSD map`() {
        val s = CephOverview.fromJson(json("""{"health":{"status":"HEALTH_OK","checks":{}},"osdmap":{"osdmap":{"num_osds":3,"num_up_osds":3,"num_in_osds":3}}}"""))
        assertEquals(3, s.osdsUp)
        assertTrue(s.checks.isEmpty())
        assertNull(s.usedFraction)
    }

    @Test
    fun `the OSD tree flattens to hosts`() {
        val tree = CephOsdTree.fromJson(
            json(
                """
                {"flags":"noout,noscrub","root":{"leaf":0,"children":[{"id":-1,"name":"default","type":"root","children":[
                  {"id":-3,"name":"pve2","type":"host","version":"18.2.4","children":[
                    {"id":3,"name":"osd.3","type":"osd","status":"down","in":0,"device_class":"hdd","percent_used":51.2,"host":"pve2"},
                    {"id":2,"name":"osd.2","type":"osd","status":"up","in":1,"device_class":"hdd","commit_latency_ms":9}]},
                  {"id":-2,"name":"pve1","type":"host","children":[{"id":0,"name":"osd.0","type":"osd","status":"up","in":1}]}]}]}}
                """,
            ),
        )
        assertEquals(listOf("pve1", "pve2"), tree.hosts.map { it.name })
        assertEquals(listOf(2, 3), tree.hosts.last().osds.map { it.id })
        val osd3 = tree.osds.single { it.id == 3 }
        assertTrue(osd3.canDestroy)
        assertEquals("osd.3", osd3.service)
        assertEquals("pve2", tree.osds.single { it.id == 2 }.host) // from its bucket
        assertFalse(tree.osds.single { it.id == 2 }.canDestroy)
        assertEquals(listOf("noout", "noscrub"), tree.flags)
    }

    @Test
    fun `pool forms check like Proxmox and send only changes`() {
        assertEquals(CephPoolForm.Problem.NAME, CephPoolForm(name = "my pool").problem(creating = true))
        assertEquals(CephPoolForm.Problem.MIN_SIZE, CephPoolForm(name = "p", size = "2", minSize = "3").problem(creating = true))
        assertEquals(CephPoolForm.Problem.SIZE, CephPoolForm(name = "p", size = "9").problem(creating = true))
        assertEquals(CephPoolForm.Problem.PG_NUM, CephPoolForm(name = "p", pgNum = "0").problem(creating = true))
        val form = CephPoolForm(name = "vm-pool")
        assertNull(form.problem(creating = true))
        assertFalse(form.risky)
        assertTrue(form.copy(minSize = "1").risky)
        assertTrue(form.copy(pgNum = "100").pgNotPowerOfTwo)
        assertEquals(
            mapOf("name" to "vm-pool", "size" to "3", "min_size" to "2", "pg_autoscale_mode" to "on", "pg_num" to "128", "application" to "rbd", "add_storages" to "1"),
            form.createParams(),
        )
        val pool = CephPool.fromJson(
            json("""{"pool":2,"pool_name":"vm-pool","size":3,"min_size":2,"pg_num":128,"pg_autoscale_mode":"on","crush_rule_name":"replicated_rule","percent_used":0.21,"application_metadata":{"rbd":{}}}"""),
        )
        assertEquals(listOf("rbd"), pool.applications)
        assertFalse(pool.internal)
        assertTrue(CephPool(".mgr").internal)
        val edit = CephPoolForm.from(pool)
        assertEquals(emptyMap(), edit.updateParams(pool))
        assertEquals(mapOf("min_size" to "1", "pg_num" to "64"), edit.copy(minSize = "1", pgNum = "64").updateParams(pool))
    }

    @Test
    fun `osd form, services, flags and errors that mean no Ceph`() {
        assertFalse(CephOsdForm(node = "pve1", device = "/dev/sdb", dbDevice = "/dev/sdb").valid)
        assertEquals(
            mapOf("dev" to "/dev/sdb", "crush-device-class" to "ssd", "encrypted" to "1"),
            CephOsdForm(node = "pve1", device = "/dev/sdb", deviceClass = "ssd", encrypted = true).params(),
        )
        val mon = CephService.fromJson(CephServiceType.MON, json("""{"name":"pve3","host":"pve3","quorum":0,"state":"stopped"}"""))
        assertFalse(mon.running)
        assertEquals("mon.pve3", mon.service)
        assertTrue(CephService.fromJson(CephServiceType.MDS, json("""{"name":"pve1","state":"up:standby"}""")).running)
        assertEquals("noout", CephFlag.COMMON.first())
        assertTrue(cephNotSetUp("binary not installed: /usr/bin/ceph-mon"))
        assertTrue(cephNotSetUp("rados_connect failed - No such file or directory"))
        assertFalse(cephNotSetUp("Connection refused"))
    }
}
