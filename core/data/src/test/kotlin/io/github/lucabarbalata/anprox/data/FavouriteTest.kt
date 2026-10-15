package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.favourites.FavouriteRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.favourites.FavouriteRef
import io.github.lucabarbalata.anprox.model.favourites.FavouriteState
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Favourite guests for the widget, against the mock. */
class FavouriteTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private fun profile(id: String, name: String) = ServerProfile(
        id = id, name = name, product = ProxmoxProduct.PVE,
        endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
    )

    private suspend fun repo(): FavouriteRepository {
        val hub = ServerHub(InMemoryProfileStore(listOf(profile("pve", "Homelab"), profile("other", "Office"))), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return FavouriteRepository(hub, GuestRepository(hub))
    }

    private fun ref(vmid: Int, type: GuestType = GuestType.QEMU, server: String = "pve") = FavouriteRef(server, type, vmid)

    @Test
    fun `each favourite with its state, ordered by name`() = runBlocking {
        val list = repo().statuses(listOf(ref(100), ref(102), ref(9000), ref(200, GuestType.LXC), ref(999), ref(100, server = "other"), ref(1, server = "gone")))
        assertEquals(listOf("100", "999", "pihole", "ubuntu-2404-template", "web", "win11"), list.map { it.title })
        val web = list.single { it.vmid == 100 && it.serverId == "pve" }
        assertEquals(FavouriteState.RUNNING, web.state)
        assertEquals("pve1", web.node)
        assertEquals(PowerAction.SHUTDOWN, web.action)
        assertEquals(PowerAction.START, list.single { it.vmid == 102 }.action)
        // A template can't be started.
        assertNull(list.single { it.vmid == 9000 }.action)
        assertEquals(FavouriteState.GONE, list.single { it.vmid == 999 }.state)
        // Not signed in to the second server; the third was removed from the app.
        val other = list.single { it.serverId == "other" }
        assertEquals(FavouriteState.SIGNED_OUT, other.state)
        assertEquals("Office", other.serverName)
        assertTrue(list.none { it.serverId == "gone" })
        Unit
    }

    @Test
    fun `power acts on the node the guest is on now`() = runBlocking {
        val repo = repo()
        val (guest, upid) = repo.power(ref(102), PowerAction.START)
        assertEquals("pve2", guest.node)
        assertTrue(upid.startsWith("UPID:pve2:"))
        assertFailsWith<ApiException.NotFound> { repo.power(ref(999), PowerAction.START) }
        Unit
    }

    @Test
    fun `keys round-trip`() {
        val ref = FavouriteRef("a|b", GuestType.LXC, 200)
        assertEquals("a|b|lxc|200", ref.key)
        assertEquals(ref, FavouriteRef.parse(ref.key))
        assertNull(FavouriteRef.parse("pve|disk|1"))
        assertNull(FavouriteRef.parse("|qemu|1"))
        assertNull(FavouriteRef.parse("pve|qemu|x"))
    }
}
