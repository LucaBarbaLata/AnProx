package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageSetupRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.StorageKind
import io.github.lucabarbalata.anprox.model.pve.StorageSetupForm
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
import kotlin.test.assertTrue

/** Adding, editing and removing storages against the mock. */
class StorageSetupTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
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
    fun `scans find exports, shares, datastores, volume groups and pools`() = runBlocking {
        val repo = StorageSetupRepository(hub())
        assertEquals(listOf("pve1", "pve2"), repo.onlineNodes("pve"))
        assertEquals(listOf("/export/proxmox", "/export/media"), repo.scanNfs("pve", "pve1", "10.0.0.50").map { it.path })
        assertFailsWith<ApiException> { repo.scanNfs("pve", "pve1", "10.9.9.9") }
        assertEquals(listOf("media", "backups"), repo.scanCifs("pve", "pve1", "10.0.0.50", "me", "pw", "").map { it.share })
        assertEquals(listOf("store1", "store2"), repo.scanPbs("pve", "pve1", "10.0.0.60", "root@pam", "anprox", "").map { it.store })
        assertFailsWith<ApiException> { repo.scanPbs("pve", "pve1", "10.0.0.60", "root@pam", "wrong", "") }
        assertEquals(listOf("pve", "data"), repo.scanLvm("pve", "pve1").map { it.vg })
        assertEquals(listOf("data"), repo.scanLvmThin("pve", "pve1", "pve").map { it.lv })
        assertEquals(listOf("rpool", "tank"), repo.scanZfs("pve", "pve2").map { it.pool })
        Unit
    }

    @Test
    fun `add an NFS storage, edit it, remove it`() = runBlocking {
        val hub = hub()
        val repo = StorageSetupRepository(hub)
        val storages = StorageRepository(hub)
        val form = StorageSetupForm().withKind(StorageKind.NFS).copy(id = "media", server = "10.0.0.50", export = "/export/media", content = setOf("iso", "vztmpl"), nodes = setOf("pve1"))
        repo.create("pve", form)
        val added = storages.storages("pve").single { it.config.storage == "media" }
        assertTrue(added.config.shared)
        assertEquals(listOf("pve1"), added.nodes.map { it.node })

        val existing = repo.load("pve", "media")
        val loaded = assertNotNull(existing.form)
        assertEquals("/export/media", loaded.export)
        repo.update("pve", existing, loaded.copy(content = setOf("iso"), nodes = emptySet(), enabled = false))
        val edited = storages.storages("pve").single { it.config.storage == "media" }
        assertEquals("iso", edited.config.content)
        assertTrue(edited.config.disable)
        assertEquals(listOf("pve1", "pve2", "pve3"), edited.nodes.map { it.node })
        // The digest changed since: the old copy is refused.
        assertFailsWith<ApiException> { repo.update("pve", existing, loaded) }

        repo.remove("pve", "media")
        assertFalse(storages.storages("pve").any { it.config.storage == "media" })
        Unit
    }

    @Test
    fun `a Backup Server storage needs the right password`() = runBlocking {
        val repo = StorageSetupRepository(hub())
        val form = StorageSetupForm().withKind(StorageKind.PBS).copy(id = "offsite", server = "10.0.0.60", datastore = "store2", username = "root@pam", password = "nope")
        assertFailsWith<ApiException> { repo.create("pve", form) }
        repo.create("pve", form.copy(password = "anprox"))
        val pbs = repo.load("pve", "offsite")
        assertEquals("store2", pbs.form?.datastore)
        assertEquals("", pbs.form?.password)
        Unit
    }
}
