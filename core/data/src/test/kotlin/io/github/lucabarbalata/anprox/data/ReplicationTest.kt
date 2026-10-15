package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.ReplicationRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.ReplicationJobForm
import io.github.lucabarbalata.anprox.model.pve.ReplicationState
import io.github.lucabarbalata.anprox.model.pve.ReplicationStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Storage replication against the mock (questionnaire Q21). */
class ReplicationTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(): ReplicationRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return ReplicationRepository(hub)
    }

    @Test
    fun `jobs with the runs their guests' nodes report`() = runBlocking {
        val overview = repo().overview("pve")
        assertEquals(listOf("103-0", "201-0"), overview.jobs.map { it.id })
        assertEquals(listOf("pve1", "pve2"), overview.onlineNodes)
        val synced = overview.jobs[0]
        assertEquals(ReplicationState.OK, ReplicationStatus.state(synced, overview.status[synced.id]))
        val failing = overview.jobs[1]
        assertEquals("*/30", failing.schedule)
        assertEquals(ReplicationState.FAILED, ReplicationStatus.state(failing, overview.status[failing.id]))
        assertEquals(3, overview.status.getValue(failing.id).failCount)
        Unit
    }

    @Test
    fun `create, edit, switch off, run now and delete`() = runBlocking {
        val repo = repo()
        var overview = repo.overview("pve")
        // win11 (102) lives on pve2.
        repo.create("pve", ReplicationJobForm(guest = 102, target = "pve1", schedule = "*/2:00", rate = "50", comment = "desktop"), overview.jobs)
        overview = repo.overview("pve")
        val job = overview.jobs.single { it.guest == 102 }
        assertEquals("102-0", job.id)
        assertEquals(50.0, job.rate)
        assertEquals(ReplicationState.NEVER_RUN, ReplicationStatus.state(job, overview.status[job.id]))

        // A second job of the same guest to the same node is refused by Proxmox too.
        assertFailsWith<ApiException> { repo.create("pve", ReplicationJobForm(guest = 102, target = "pve1"), emptyList()) }

        repo.update("pve", job, ReplicationJobForm.from(job).copy(rate = "", comment = ""))
        repo.setEnabled("pve", job, false)
        val edited = repo.overview("pve").jobs.single { it.id == job.id }
        assertEquals(null, edited.rate)
        assertEquals(null, edited.comment)
        assertFalse(edited.enabled)
        // The digest changed since, so the old copy of the job is refused.
        assertFailsWith<ApiException> { repo.update("pve", job, ReplicationJobForm.from(job)) }
        repo.setEnabled("pve", edited, true)

        repo.runNow("pve", "pve2", job)
        overview = repo.overview("pve")
        assertEquals(ReplicationState.OK, ReplicationStatus.state(overview.jobs.single { it.id == job.id }, overview.status[job.id]))
        assertTrue(repo.log("pve", "pve2", job).any { "end replication job" in it.t })

        repo.delete("pve", job, keepCopies = false)
        assertTrue(repo.overview("pve").jobs.none { it.id == job.id })
        Unit
    }

    @Test
    fun `a run to the offline node fails again`() = runBlocking {
        val repo = repo()
        val failing = repo.overview("pve").jobs.single { it.id == "201-0" }
        repo.runNow("pve", "pve2", failing)
        val status = repo.overview("pve").status.getValue("201-0")
        assertEquals(4, status.failCount)
        assertTrue(status.error.orEmpty().contains("exit code 255"))
        assertEquals(3, repo.nextRuns("pve", "*/15").size)
        Unit
    }
}
