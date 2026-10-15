package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.alerts.Alert
import io.github.lucabarbalata.anprox.model.alerts.Alert.Kind
import io.github.lucabarbalata.anprox.model.alerts.AlertEngine
import io.github.lucabarbalata.anprox.model.alerts.AlertSettings
import io.github.lucabarbalata.anprox.model.alerts.FinishedTask
import io.github.lucabarbalata.anprox.model.alerts.GuestSeen
import io.github.lucabarbalata.anprox.model.alerts.Observation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertsTest {

    private val settings = AlertSettings(enabled = true)

    private val healthy = Observation(
        time = 1_000,
        nodes = mapOf("pve1" to true, "pve2" to true),
        runningGuests = mapOf(100 to GuestSeen(100, "web", "pve1"), 200 to GuestSeen(200, "pihole", "pve2", "lxc")),
        guestIds = setOf(100, 101, 200),
        quorate = true,
        nodeCpu = mapOf("pve1" to 0.20, "pve2" to 0.30),
        nodeMemory = mapOf("pve1" to 0.50, "pve2" to 0.60),
        storages = mapOf("pve1/local" to 0.40),
        tasks = listOf(FinishedTask("UPID:old", "vzdump", null, "pve1", 900, "job errors")),
        ceph = "HEALTH_OK",
    )

    private fun kinds(alerts: List<Alert>) = alerts.map { it.kind }

    @Test
    fun `first check reports current problems but not old tasks`() {
        val (alerts, memory) = AlertEngine.evaluate(null, healthy, settings)
        assertEquals(emptyList(), alerts)
        assertEquals(mapOf(100 to "web", 200 to "pihole"), memory.runningGuests)

        val bad = healthy.copy(nodes = mapOf("pve1" to true, "pve2" to false), quorate = false, storages = mapOf("pve1/local" to 0.95))
        val (first, _) = AlertEngine.evaluate(null, bad, settings)
        assertEquals(listOf(Kind.NODE_DOWN, Kind.QUORUM_LOST, Kind.STORAGE_HIGH), kinds(first))
        assertEquals("pve2", first[0].subject)
        assertEquals(95, first[2].percent)
    }

    @Test
    fun `transitions are reported once`() {
        val (_, m1) = AlertEngine.evaluate(null, healthy, settings)
        val down = healthy.copy(
            time = 2_000,
            nodes = mapOf("pve1" to true, "pve2" to false),
            quorate = false,
            runningGuests = mapOf(100 to GuestSeen(100, "web", "pve1")), // pihole was on pve2
        )
        val (a2, m2) = AlertEngine.evaluate(m1, down, settings)
        assertEquals(listOf(Kind.NODE_DOWN, Kind.QUORUM_LOST), kinds(a2))
        // The guest on the node that went down is covered by the node alert.
        assertTrue(a2.none { it.kind == Kind.GUEST_STOPPED })
        // Same state again: nothing new.
        val (a3, _) = AlertEngine.evaluate(m2, down.copy(time = 3_000), settings)
        assertEquals(emptyList(), a3)
    }

    @Test
    fun `a guest that stops is reported unless a task stopped it`() {
        val (_, m1) = AlertEngine.evaluate(null, healthy, settings)
        val crashed = healthy.copy(time = 2_000, runningGuests = mapOf(200 to GuestSeen(200, "pihole", "pve2", "lxc")))
        val (a2, _) = AlertEngine.evaluate(m1, crashed, settings)
        assertEquals(listOf(Alert(Kind.GUEST_STOPPED, "web", detail = "100", node = "pve1", guestType = "qemu")), a2)
        val ctDown = healthy.copy(time = 2_000, runningGuests = mapOf(100 to GuestSeen(100, "web", "pve1")))
        assertEquals("lxc", AlertEngine.evaluate(m1, ctDown, settings).first.single().guestType)

        val shutDown = crashed.copy(tasks = listOf(FinishedTask("UPID:x", "qmshutdown", "100", "pve1", 1_500, "OK")))
        assertEquals(emptyList(), AlertEngine.evaluate(m1, shutDown, settings).first)

        // Destroyed or moved elsewhere (no longer in the guest list): not "stopped".
        val gone = crashed.copy(guestIds = setOf(101, 200))
        assertEquals(emptyList(), AlertEngine.evaluate(m1, gone, settings).first)

        // A shutdown task still running.
        assertEquals(emptyList(), AlertEngine.evaluate(m1, crashed.copy(busyGuests = setOf(100)), settings).first)
    }

    @Test
    fun `new tasks are picked by the server's clock`() {
        // The phone's clock is an hour behind the server's.
        val (_, m1) = AlertEngine.evaluate(null, healthy.copy(time = 1_000 - 3_600), settings)
        assertEquals(900L, m1.lastTaskEnd)
        val failed = FinishedTask("UPID:f", "qmstart", "101", "pve1", 950, "start failed")
        val (a2, m2) = AlertEngine.evaluate(m1, healthy.copy(time = 2_000 - 3_600, tasks = listOf(failed) + healthy.tasks), settings)
        assertEquals(listOf("UPID:f"), a2.map { it.subject })
        assertEquals(950L, m2.lastTaskEnd)
        // Not reported twice; with no tasks at all the last end time is kept.
        assertEquals(emptyList(), AlertEngine.evaluate(m2, healthy.copy(time = 3_000 - 3_600, tasks = listOf(failed)), settings).first)
        assertEquals(950L, AlertEngine.evaluate(m2, healthy.copy(tasks = emptyList()), settings).second.lastTaskEnd)
    }

    @Test
    fun `failed tasks and backups since the last check`() {
        val (_, m1) = AlertEngine.evaluate(null, healthy, settings)
        val later = healthy.copy(
            time = 2_000,
            tasks = listOf(
                FinishedTask("UPID:b", "vzdump", null, "pve1", 1_800, "job errors"),
                FinishedTask("UPID:w", "vzdump", null, "pve2", 1_700, "WARNINGS: 1"),
                FinishedTask("UPID:t", "qmstart", "101", "pve1", 1_600, "start failed: no space"),
                FinishedTask("UPID:ok", "qmstart", "100", "pve1", 1_500, "OK"),
                FinishedTask("UPID:old", "vzdump", null, "pve1", 900, "job errors"),
            ),
        )
        val (alerts, _) = AlertEngine.evaluate(m1, later, settings)
        assertEquals(listOf(Kind.TASK_FAILED, Kind.BACKUP_FAILED), kinds(alerts)) // oldest first, warnings are not failures
        assertEquals("UPID:t", alerts[0].subject)
        assertEquals("start failed: no space", alerts[0].detail)
        assertEquals(emptyList(), AlertEngine.evaluate(m1, later, settings.copy(failures = false)).first)
    }

    @Test
    fun `thresholds alert on crossing, with hysteresis`() {
        val (_, m1) = AlertEngine.evaluate(null, healthy, settings)
        val hot = healthy.copy(time = 2_000, nodeCpu = mapOf("pve1" to 0.95, "pve2" to 0.30))
        val (a2, m2) = AlertEngine.evaluate(m1, hot, settings)
        assertEquals(listOf(Alert(Kind.CPU_HIGH, "pve1", percent = 95)), a2)
        // Dips just under the limit and comes back: no new alert.
        val (a3, m3) = AlertEngine.evaluate(m2, hot.copy(time = 3_000, nodeCpu = mapOf("pve1" to 0.88)), settings)
        assertEquals(emptyList(), a3)
        val (a4, m4) = AlertEngine.evaluate(m3, hot.copy(time = 4_000), settings)
        assertEquals(emptyList(), a4)
        // Clearly back to normal, then high again: a new alert.
        val (_, m5) = AlertEngine.evaluate(m4, hot.copy(time = 5_000, nodeCpu = mapOf("pve1" to 0.50)), settings)
        assertEquals(listOf(Kind.CPU_HIGH), kinds(AlertEngine.evaluate(m5, hot.copy(time = 6_000), settings).first))
        assertEquals(emptyList(), AlertEngine.evaluate(m1, hot, settings.copy(cpuPercent = 96)).first)
    }

    @Test
    fun `ceph health changes`() {
        val (_, m1) = AlertEngine.evaluate(null, healthy, settings)
        val (warn, m2) = AlertEngine.evaluate(m1, healthy.copy(time = 2_000, ceph = "HEALTH_WARN"), settings)
        assertEquals(listOf(Kind.CEPH_WARN), kinds(warn))
        val (err, m3) = AlertEngine.evaluate(m2, healthy.copy(time = 3_000, ceph = "HEALTH_ERR"), settings)
        assertEquals(listOf(Kind.CEPH_ERR), kinds(err))
        // Getting better (ERR → WARN) is not an alert.
        assertEquals(emptyList(), AlertEngine.evaluate(m3, healthy.copy(time = 4_000, ceph = "HEALTH_WARN"), settings).first)
        assertEquals(emptyList(), AlertEngine.evaluate(m1, healthy.copy(ceph = "HEALTH_ERR"), settings.copy(thresholds = false)).first)
    }
}
