package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.BackupJobRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.BackupJobForm
import io.github.lucabarbalata.anprox.model.pve.BackupSelection
import io.github.lucabarbalata.anprox.model.pve.Retention
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Datacenter → Backup jobs against the mock (questionnaire Q20). */
class BackupJobTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun hub(user: String = "root", realm: String = "pam"): ServerHub {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return hub
    }

    @Test
    fun `lists jobs and the guests none of them covers`() = runBlocking {
        val repo = BackupJobRepository(hub())
        val (daily, weekly) = repo.jobs("pve")
        assertEquals(BackupSelection.ALL, daily.selection)
        assertEquals(Retention(daily = 7, weekly = 4), daily.retention)
        assertTrue(daily.nextRun != null)
        assertEquals(false, weekly.enabled)
        assertEquals(listOf(200, 201), weekly.vmids)
        // The daily job covers everything.
        assertEquals(emptyList(), repo.notBackedUp("pve"))

        repo.setEnabled("pve", daily.id, false)
        assertEquals(false, repo.jobs("pve").first().enabled)
        // Disabled jobs don't count (the weekly one is disabled too).
        assertEquals(listOf(100, 101, 102, 103, 104, 200, 201, 202), repo.notBackedUp("pve").map { it.vmid })
        Unit
    }

    @Test
    fun `create, edit and delete a job`() = runBlocking {
        val repo = BackupJobRepository(hub())
        assertEquals(listOf("pbs", "local", "nas-nfs"), repo.backupStorages("pve").map { it.storage })
        assertEquals(listOf("lab", "prod"), repo.pools("pve").map { it.id })

        repo.create("pve", BackupJobForm(schedule = "mon..fri 22:30", storage = "nas-nfs", selection = BackupSelection.POOL, pool = "prod"))
        val created = repo.jobs("pve").last()
        assertEquals("mon..fri 22:30", created.schedule)
        assertEquals("prod", created.pool)

        repo.update("pve", created.id, BackupJobForm.from(created).copy(selection = BackupSelection.EXCLUDE, vmids = setOf(104), retention = Retention(last = 2)))
        val edited = repo.jobs("pve").last()
        assertEquals(BackupSelection.EXCLUDE, edited.selection)
        assertEquals(listOf(104), edited.vmids)
        assertEquals(null, edited.pool)
        assertEquals(Retention(last = 2), edited.retention)

        val bad = assertFailsWith<ApiException> { repo.create("pve", BackupJobForm(schedule = "whenever", storage = "pbs")) }
        assertTrue("calendar event" in bad.message.orEmpty(), bad.message)

        repo.delete("pve", created.id)
        assertEquals(2, repo.jobs("pve").size)
        Unit
    }

    @Test
    fun `previews a schedule`() = runBlocking {
        val repo = BackupJobRepository(hub())
        val runs = repo.nextRuns("pve", "21:00")
        assertEquals(3, runs.size)
        assertTrue(runs.zipWithNext().all { (a, b) -> b - a == 24 * 3600L })
        assertTrue(runs.all { (it / 60) % (24 * 60) == 21 * 60L }) // 21:00 UTC in the mock
        assertEquals(4, repo.nextRuns("pve", "0/6:00", 4).zipWithNext().count { (a, b) -> b - a == 6 * 3600L } + 1)
        assertFailsWith<ApiException> { repo.nextRuns("pve", "every tuesday") }
        Unit
    }

    @Test
    fun `run now starts a backup on each node the job covers`() = runBlocking {
        val hub = hub()
        val repo = BackupJobRepository(hub)
        val guests = GuestRepository(hub)
        val (daily, weekly) = repo.jobs("pve")
        val started = repo.runNow("pve", daily)
        assertEquals(listOf("pve1", "pve2"), started.map { it.node }) // pve3 is offline
        started.forEach { assertTrue(guests.awaitTask("pve", it.node, it.upid).succeeded) }

        // The weekly job lists two containers on pve1 and pve2 but is limited to pve1.
        assertEquals(listOf("pve1"), repo.runNow("pve", weekly).map { it.node })
        Unit
    }
}
