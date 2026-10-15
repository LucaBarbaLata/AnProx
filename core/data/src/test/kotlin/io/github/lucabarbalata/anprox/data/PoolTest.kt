package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.PoolRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.PoolChange
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

/** Resource pools against the mock. */
class PoolTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(): PoolRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return PoolRepository(hub)
    }

    @Test
    fun `pools with their guests and storages`() = runBlocking {
        val repo = repo()
        val pools = repo.pools("pve")
        assertEquals(listOf("lab", "prod"), pools.map { it.id })
        val prod = pools.single { it.id == "prod" }
        assertEquals("Production", prod.comment)
        assertEquals(listOf(100, 101, 201), prod.guests.map { it.vmid })
        assertEquals(listOf("nas-nfs"), prod.storages)
        // Guests carry their pool in the cluster resources.
        assertEquals("prod", repo.resources("pve").single { it.vmid == 100 }.pool)
        Unit
    }

    @Test
    fun `create, fill, empty and delete a pool`() = runBlocking {
        val repo = repo()
        repo.create("pve", "k8s", "Kubernetes")
        repo.change("pve", "k8s", PoolChange(vmids = setOf(103), storages = setOf("local-zfs")))
        var k8s = repo.pool("pve", "k8s")
        assertEquals(listOf(103), k8s.guests.map { it.vmid })
        assertEquals(listOf("local-zfs"), k8s.storages)

        // A guest is in one pool at most.
        assertFailsWith<ApiException> { repo.change("pve", "k8s", PoolChange(vmids = setOf(100))) }
        // Only an empty pool can be deleted.
        assertFailsWith<ApiException> { repo.delete("pve", "k8s") }

        repo.setComment("pve", "k8s", "Cluster nodes")
        repo.change("pve", "k8s", PoolChange(vmids = setOf(103), storages = setOf("local-zfs"), remove = true))
        k8s = repo.pool("pve", "k8s")
        assertEquals("Cluster nodes", k8s.comment)
        assertTrue(k8s.isEmpty)
        repo.delete("pve", "k8s")
        assertTrue(repo.pools("pve").none { it.id == "k8s" })
        Unit
    }
}
