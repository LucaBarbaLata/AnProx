package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
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

/** Reading and saving guest options against the mock (questionnaire Q19). */
class GuestOptionsTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val web = GuestRef("pve", "pve1", GuestType.QEMU, 100)
    private val pihole = GuestRef("pve", "pve1", GuestType.LXC, 200)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(user: String = "root", realm: String = "pam"): GuestRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return GuestRepository(hub)
    }

    @Test
    fun `reads and saves VM options`() = runBlocking {
        val guests = repo()
        val old = guests.options(web)
        assertEquals("web", old.name)
        assertEquals(listOf("prod", "web"), old.tags)
        assertEquals(true, old.agent)

        guests.saveOptions(web, old, old.copy(name = "web-01", onBoot = true, description = "Front end", tags = listOf("prod")))
        val saved = guests.options(web)
        assertEquals("web-01", saved.name)
        assertTrue(saved.onBoot)
        assertEquals("Front end", saved.description)
        assertEquals(listOf("prod"), saved.tags)

        // Clearing text fields deletes them.
        guests.saveOptions(web, saved, saved.copy(description = "", tags = emptyList()))
        val cleared = guests.options(web)
        assertEquals("", cleared.description)
        assertEquals(emptyList(), cleared.tags)
    }

    @Test
    fun `a stale digest is refused`() = runBlocking {
        val guests = repo()
        val loaded = guests.options(pihole)
        guests.saveOptions(pihole, loaded, loaded.copy(onBoot = true))          // someone else's change
        val error = assertFailsWith<ApiException.Server> {
            guests.saveOptions(pihole, loaded, loaded.copy(name = "dns"))        // still the old digest
        }
        assertTrue("modified" in error.message.orEmpty())
        Unit
    }

    @Test
    fun `read-only users can't change options`() = runBlocking {
        val guests = repo(user = "auditor", realm = "pve")
        val old = guests.options(web)
        assertFailsWith<ApiException.Forbidden> { guests.saveOptions(web, old, old.copy(protection = true)) }
        Unit
    }
}
