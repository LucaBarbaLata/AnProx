package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.RealmSync
import io.github.lucabarbalata.anprox.model.pve.RealmSyncJobForm
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scheduled directory syncs against the mock. */
class RealmSyncJobTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, taskDurationMillis = 0).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun hub(): ServerHub {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return hub
    }

    @Test
    fun `add, change and delete a scheduled sync`() = runBlocking {
        val repo = AccessRepository(hub())
        assertTrue(repo.syncJobs("pve").isEmpty())

        repo.createSyncJob("pve", RealmSyncJobForm(id = "nightly", realm = "corp", comment = "AD users", sync = RealmSync(scope = "users", removeVanished = setOf("entry"), dryRun = false)))
        val job = repo.syncJobs("pve").single()
        assertEquals("corp", job.realm)
        assertEquals("02:00", job.schedule)
        assertEquals(setOf("entry"), job.sync.removeVanished)
        assertNotNull(job.nextRun)

        // Switched off and the comment emptied: the job no longer has a next run.
        repo.updateSyncJob("pve", RealmSyncJobForm.from(job).copy(enabled = false, comment = "", schedule = "sun 02:00"))
        val changed = repo.syncJob("pve", "nightly")
        assertFalse(changed.enabled)
        assertNull(changed.comment)
        assertEquals("sun 02:00", changed.schedule)
        assertNull(changed.nextRun)

        // Only directories can be synced, and IDs are unique.
        assertFailsWith<ApiException> { repo.createSyncJob("pve", RealmSyncJobForm(id = "local", realm = "pve")) }
        assertFailsWith<ApiException> { repo.createSyncJob("pve", RealmSyncJobForm(id = "nightly", realm = "corp")) }

        repo.deleteSyncJob("pve", "nightly")
        assertTrue(repo.syncJobs("pve").isEmpty())
        Unit
    }
}
