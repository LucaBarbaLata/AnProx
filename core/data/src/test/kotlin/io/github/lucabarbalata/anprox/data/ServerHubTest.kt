package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.snapshot.PbsSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.OpenIdCallback
import io.github.lucabarbalata.anprox.model.auth.TfaKind
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerHubTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val pbs = MockProxmoxServer(ProxmoxProduct.PBS).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
        pbs.stop()
    }

    private fun pveProfile(user: String = "root", realm: String = "pam") = ServerProfile(
        id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
        endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
    )

    private fun pbsProfile() = ServerProfile(
        id = "pbs", name = "Backups", product = ProxmoxProduct.PBS,
        endpoints = listOf(Endpoint(pbs.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true, sortOrder = 1,
    )

    private suspend fun hubWith(vararg profiles: ServerProfile, secrets: InMemorySecretStore = InMemorySecretStore()): ServerHub {
        val hub = ServerHub(InMemoryProfileStore(profiles.toList()), secrets, scope)
        hub.awaitLoaded()
        return hub
    }

    @Test
    fun `login marks server logged in and persists the session`() = runBlocking {
        val secrets = InMemorySecretStore()
        val hub = hubWith(pveProfile(), secrets = secrets)
        assertEquals(AuthState.LOGGED_OUT, hub.server("pve")!!.auth)

        assertIs<LoginResult.Success>(hub.login("pve", "anprox", remember = false))
        assertEquals(AuthState.LOGGED_IN, hub.server("pve")!!.auth)
        // Right away, without waiting for the `servers` flow to catch up.
        assertEquals(AuthState.LOGGED_IN, hub.serversNow().single().auth)
        withTimeout(2_000) { while (secrets.session("pve") == null) kotlinx.coroutines.delay(10) }
        assertEquals(null, secrets.password("pve"))

        // A new hub (= app restart) restores the session without a password.
        val restarted = hubWith(pveProfile(), secrets = secrets)
        assertEquals(AuthState.LOGGED_IN, restarted.server("pve")!!.auth)
        assertTrue(restarted.use("pve") { it.pve { clusterStatus() } }.isNotEmpty())
    }

    @Test
    fun `OpenID Connect sign-in takes the account the identity provider names`() = runBlocking {
        val secrets = InMemorySecretStore()
        val hub = hubWith(pveProfile(realm = "sso"), secrets = secrets)
        hub.realms("pve")
        val start = hub.openIdStart("pve", "sso")
        val back = assertIs<OpenIdCallback.Code>(OpenIdCallback.parse(pve.state.openIdBrowse(start.authUrl)!!, start.redirectUrl))

        hub.openIdLogin("pve", back.code, back.state, start.redirectUrl)
        val server = hub.server("pve")!!
        assertEquals(AuthState.LOGGED_IN, server.auth)
        assertEquals("sso-user", server.profile.username)
        assertEquals("sso", server.profile.realm)
        assertEquals(null, secrets.password("pve"))
        // The profile changed user, so the hub made a new connection: it carries the session on.
        assertTrue(hub.use("pve") { it.pve { clusterStatus() } }.isNotEmpty())
        assertEquals("sso-user@sso", secrets.session("pve")!!.username)
    }

    @Test
    fun `expired session is renewed silently with a remembered password`() = runBlocking {
        val hub = hubWith(pveProfile())
        hub.login("pve", "anprox", remember = true)
        pve.state.ageSessions(3 * 60 * 60 * 1000L)
        assertTrue(hub.use("pve") { it.pve { clusterStatus() } }.isNotEmpty())
        assertEquals(AuthState.LOGGED_IN, hub.server("pve")!!.auth)
    }

    @Test
    fun `expired session without a password marks the server expired`() = runBlocking {
        val hub = hubWith(pveProfile())
        hub.login("pve", "anprox", remember = false)
        pve.state.ageSessions(3 * 60 * 60 * 1000L)
        assertFailsWith<ApiException.Unauthorized> { hub.use("pve") { it.pve { clusterStatus() } } }
        assertEquals(AuthState.SESSION_EXPIRED, hub.server("pve")!!.auth)
    }

    @Test
    fun `two-factor accounts cannot re-login silently`() = runBlocking {
        val hub = hubWith(pveProfile(user = "admin", realm = "pve"))
        val tfa = assertIs<LoginResult.NeedsTfa>(hub.login("pve", "anprox", remember = true))
        hub.completeTfa("pve", tfa.partialTicket, TfaKind.TOTP, "123456")
        assertEquals(AuthState.LOGGED_IN, hub.server("pve")!!.auth)
        pve.state.ageSessions(3 * 60 * 60 * 1000L)
        assertFailsWith<ApiException.Unauthorized> { hub.use("pve") { it.pve { clusterStatus() } } }
        assertEquals(AuthState.SESSION_EXPIRED, hub.server("pve")!!.auth)
    }

    @Test
    fun `combined snapshots for a PVE cluster and a PBS`() = runBlocking {
        val hub = hubWith(pveProfile(), pbsProfile())
        hub.login("pve", "anprox", remember = false)
        hub.login("pbs", "anprox", remember = false)
        val repo = SnapshotRepository(hub)
        val states = withTimeout(10_000) {
            repo.snapshots(intervalMillis = 60_000).first { list -> list.size == 2 && list.all { it.snapshot != null } }
        }
        val cluster = assertIs<PveSnapshot>(states[0].snapshot)
        assertEquals("homelab", cluster.clusterName)
        assertEquals(true, cluster.quorate)
        assertEquals(2, cluster.nodesOnline)
        assertEquals(3, cluster.nodes.size)
        assertEquals(3, cluster.guestCounts(GuestType.QEMU).running)
        assertEquals(1, cluster.templateCount)
        // Shared storages (nas-nfs, pbs) appear once, local ones once per node.
        assertEquals(3 * 2 + 2, cluster.storages.size)
        assertTrue(cluster.storagesAbove(0.85).any { it.storage == "local-zfs" && it.node == "pve1" })
        assertNotNull(cluster.cpuFraction)

        val backups = assertIs<PbsSnapshot>(states[1].snapshot)
        assertEquals(2, backups.datastores.size)
    }

    @Test
    fun `logged in servers report loading before their first snapshot`() = runBlocking {
        val hub = hubWith(pveProfile())
        hub.login("pve", "anprox", remember = false)
        // `servers` is derived on the hub's own coroutine, so the login reaches it a moment later.
        // Collecting before that would see the logged-out state (found as a CI flake).
        withTimeout(5_000) { hub.servers.first { it.single().auth == AuthState.LOGGED_IN } }
        val first = withTimeout(5_000) { SnapshotRepository(hub).snapshots(intervalMillis = 60_000).first { it.isNotEmpty() } }.single()
        assertTrue(first.isLoading)
        Unit
    }

    @Test
    fun `logged out servers produce an error state instead of polling`() = runBlocking {
        val hub = hubWith(pveProfile())
        val state = withTimeout(5_000) { SnapshotRepository(hub).snapshots().first { it.isNotEmpty() } }.single()
        assertIs<ApiException.Unauthorized>(state.error)
        Unit
    }

    @Test
    fun `power action through the guest repository`() = runBlocking {
        val hub = hubWith(pveProfile())
        hub.login("pve", "anprox", remember = false)
        val guests = GuestRepository(hub)
        val ref = GuestRef("pve", "pve2", GuestType.LXC, 202)
        val upid = guests.power(ref, PowerAction.START)
        assertTrue(guests.awaitTask("pve", "pve2", upid).succeeded)
        val status = withTimeout(5_000) { guests.observeStatus(ref).first { it.value != null } }
        assertEquals("running", status.value!!.status)
        assertFailsWith<IllegalArgumentException> { guests.power(ref, PowerAction.RESET) }
        Unit
    }

    @Test
    fun `deleting a server clears its secrets`() = runBlocking {
        val secrets = InMemorySecretStore()
        val hub = hubWith(pveProfile(), secrets = secrets)
        hub.login("pve", "anprox", remember = true)
        assertEquals("anprox", secrets.password("pve"))
        hub.delete("pve")
        assertEquals(null, secrets.password("pve"))
        withTimeout(2_000) { hub.servers.first { it.isEmpty() } }
        Unit
    }
}
