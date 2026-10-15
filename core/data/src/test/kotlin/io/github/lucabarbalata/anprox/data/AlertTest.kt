package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.alerts.AlertRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryAlertMemoryStore
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.alerts.Alert.Kind
import io.github.lucabarbalata.anprox.model.alerts.AlertSettings
import io.github.lucabarbalata.anprox.model.alerts.ServerSummary
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Background alert checks against the mock PVE cluster and Backup Server (questionnaire Q26/Q27). */
class AlertTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val pbs = MockProxmoxServer(ProxmoxProduct.PBS).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = AlertSettings(enabled = true)
    private var now = System.currentTimeMillis() / 1000

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
        pbs.stop()
    }

    private class Setup(val alerts: AlertRepository, val guests: GuestRepository)

    private suspend fun setup(): Setup {
        fun profile(id: String, name: String, product: ProxmoxProduct, url: String) = ServerProfile(
            id = id, name = name, product = product, endpoints = listOf(Endpoint(url)),
            username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(
            InMemoryProfileStore(
                listOf(
                    profile("pve", "Homelab", ProxmoxProduct.PVE, pve.baseUrl),
                    profile("pbs", "Backups", ProxmoxProduct.PBS, pbs.baseUrl),
                ),
            ),
            InMemorySecretStore(),
            scope,
        )
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        hub.login("pbs", "anprox", remember = false)
        return Setup(AlertRepository(hub, SnapshotRepository(hub), InMemoryAlertMemoryStore()) { now }, GuestRepository(hub))
    }

    private fun AlertRepository.kinds() = runBlocking { check(settings) }.alerts.map { "${it.serverId}:${it.alert.kind}:${it.alert.subject}" }

    @Test
    fun `the first check reports what is wrong right now, but no old tasks`() = runBlocking {
        val s = setup()
        val first = s.alerts.check(settings)
        assertEquals(2, first.checked)
        assertEquals(
            // Servers in list order ("Backups" before "Homelab").
            listOf("pbs:STORAGE_HIGH:store2", "pve:NODE_DOWN:pve3", "pve:STORAGE_HIGH:pve1/local-zfs"),
            first.alerts.map { "${it.serverId}:${it.alert.kind}:${it.alert.subject}" },
        )
        assertEquals(92, first.alerts[2].alert.percent)
        assertEquals("Homelab", first.alerts[1].serverName)
        // For the widget: pve3 offline, 5 of 8 guests running (the template doesn't count),
        // and two current problems on each server's side.
        assertEquals(
            listOf(
                ServerSummary("Backups", backupServer = true, storages = 2, problems = 1),
                // Storages: local and local-zfs on the two online nodes, plus the shared nas-nfs and pbs.
                ServerSummary("Homelab", nodesOnline = 2, nodes = 3, guestsRunning = 5, guests = 8, storages = 6, problems = 2),
            ),
            first.servers,
        )
        // The same state again: nothing new.
        now += 900
        assertEquals(emptyList(), s.alerts.kinds())
        Unit
    }

    @Test
    fun `a crash, failed tasks and Ceph health are reported once`() = runBlocking {
        val s = setup()
        s.alerts.check(settings)
        pve.state.crash(101)
        pve.state.finishTask("pve2", "qmstart", "102", "start failed: QEMU exited with code 1")
        pbs.state.finishTask("pbs", "verificationjob", "store1:v-daily", "verification failed - 2 chunks bad")
        pve.state.cephHealth = "HEALTH_WARN"
        now += 900
        val alerts = s.alerts.kinds()
        assertTrue("pve:GUEST_STOPPED:db" in alerts, alerts.toString())
        assertTrue(alerts.any { it.startsWith("pve:TASK_FAILED:UPID:pve2:") }, alerts.toString())
        assertTrue(alerts.any { it.startsWith("pbs:BACKUP_FAILED:UPID:pbs:") }, alerts.toString())
        assertTrue("pve:CEPH_WARN:ceph" in alerts, alerts.toString())
        assertEquals(4, alerts.size, alerts.toString())
        now += 900
        assertEquals(emptyList(), s.alerts.kinds())
        Unit
    }

    @Test
    fun `stopping a guest yourself is not an alert`() = runBlocking {
        val s = setup()
        s.alerts.check(settings)
        val web = GuestRef("pve", "pve1", GuestType.QEMU, 100)
        val upid = s.guests.power(web, PowerAction.STOP)
        assertTrue(s.guests.awaitTask("pve", "pve1", upid).succeeded)
        now += 900
        assertEquals(emptyList(), s.alerts.kinds())
        Unit
    }

    @Test
    fun `a session that stops working is reported once`() = runBlocking {
        val s = setup()
        s.alerts.check(settings)
        pve.state.ageSessions(3 * 60 * 60 * 1000L) // no remembered password to sign in again with
        now += 900
        val first = s.alerts.check(settings)
        assertEquals(listOf("Homelab"), first.signInNeeded)
        assertEquals(1, first.checked)
        now += 900
        assertEquals(emptyList(), s.alerts.check(settings).signInNeeded)
        Unit
    }

    @Test
    fun `an unreachable server is listed, and old memory is ignored`() = runBlocking {
        val s = setup()
        s.alerts.check(settings)
        // Twelve hours later the first-check problems are reported again.
        now += 13 * 3600
        assertTrue("pve:NODE_DOWN:pve3" in s.alerts.kinds())
        pbs.stop()
        val check = s.alerts.check(settings)
        assertEquals(1, check.checked)
        assertEquals(listOf("Backups"), check.unreachable)
        assertEquals(ServerSummary("Backups", reachable = false, backupServer = true), check.servers.first())
        Unit
    }
}
