package io.github.lucabarbalata.anprox.network

import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.OpenIdCallback
import io.github.lucabarbalata.anprox.model.auth.TfaKind
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.TaskState
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProxmoxConnectionTest {
    private val servers = mutableListOf<MockProxmoxServer>()

    private fun mock(product: ProxmoxProduct = ProxmoxProduct.PVE, pathPrefix: String = "") =
        MockProxmoxServer(product, pathPrefix = pathPrefix).start().also { servers += it }

    @AfterTest
    fun stopServers() = servers.forEach { it.stop() }

    private fun profile(
        vararg urls: String,
        user: String = "root",
        realm: String = "pam",
        insecure: Boolean = true,
        product: ProxmoxProduct = ProxmoxProduct.PVE,
        headers: Map<String, String> = emptyMap(),
    ) = ServerProfile(
        id = "test",
        name = "Test",
        product = product,
        endpoints = urls.map { Endpoint(it, headers = headers) },
        username = user,
        realm = realm,
        allowInsecureTls = insecure,
    )

    private suspend fun ProxmoxConnection.loginOk(password: String = "anprox") =
        assertIs<LoginResult.Success>(login(password)).session

    @Test
    fun `self-signed certificate is rejected unless insecure is allowed`() = runBlocking {
        val server = mock()
        val strict = ProxmoxConnection(profile(server.baseUrl, insecure = false))
        val e = assertFailsWith<ApiException.UntrustedCertificate> { strict.realms() }
        assertEquals(server.baseUrl, e.url)

        val lenient = ProxmoxConnection(profile(server.baseUrl, insecure = true))
        assertTrue(lenient.realms().any { it.realm == "pam" })
    }

    @Test
    fun `password login then authenticated calls`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl))
        val session = conn.loginOk()
        assertEquals("root@pam", session.username)
        assertEquals("homelab", session.clusterName)

        val resources = conn.pve { clusterResources(null) }
        assertTrue(resources.any { it.id == "qemu/100" })
        assertTrue(resources.any { it.id == "node/pve1" })
        assertEquals(9, conn.pve { clusterResources("vm") }.size)
        assertEquals("9", conn.version().version.substringBefore('.'))
    }

    @Test
    fun `OpenID Connect sign-in on both products, then the ticket renews`() = runBlocking {
        for (product in ProxmoxProduct.entries) {
            val server = mock(product)
            var now = 1_000_000L
            val conn = ProxmoxConnection(profile(server.baseUrl, realm = "sso", product = product), clock = { now })
            conn.realms()                                       // the login screen has talked to it
            val redirect = conn.openIdRedirectUrl()
            assertEquals(server.baseUrl, redirect)              // no sub-path, so the origin is the address
            val authUrl = conn.openIdAuthUrl("sso", redirect)
            assertTrue(authUrl.startsWith(server.baseUrl + "/mock-idp/authorize?"), authUrl)

            val back = assertIs<OpenIdCallback.Code>(OpenIdCallback.parse(server.state.openIdBrowse(authUrl)!!, redirect))
            val session = conn.openIdLogin(back.code, back.state, redirect)
            assertEquals("sso-user@sso", session.username)
            assertTrue(conn.version().version.isNotEmpty())

            // Tickets of an OpenID account renew like any other (it has no password to fall back on).
            now += 3_700
            assertNotEquals(session.ticket, conn.renewSession().ticket)

            // A code works once.
            assertFailsWith<ApiException.Unauthorized> { conn.openIdLogin(back.code, back.state, redirect) }
        }
    }

    @Test
    fun `OpenID Connect needs an OpenID realm and the same redirect address`() = runBlocking {
        val server = mock(pathPrefix = "/pve")
        val conn = ProxmoxConnection(profile(server.baseUrl))
        conn.realms()
        // Behind a reverse proxy's sub-path the web interface still sends the bare origin.
        val redirect = conn.openIdRedirectUrl()
        assertEquals(server.baseUrl.removeSuffix("/pve"), redirect)
        assertFailsWith<ApiException> { conn.openIdAuthUrl("pam", redirect) }

        val authUrl = conn.openIdAuthUrl("sso", redirect)
        val back = assertIs<OpenIdCallback.Code>(OpenIdCallback.parse(server.state.openIdBrowse(authUrl)!!, redirect))
        assertFailsWith<ApiException.Unauthorized> { conn.openIdLogin(back.code, back.state, "https://elsewhere.example.com") }
        assertFailsWith<ApiException.Unauthorized> { conn.openIdLogin(back.code, back.state, redirect) }  // the code is spent
        Unit
    }

    @Test
    fun `wrong password is Unauthorized`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl))
        val e = assertFailsWith<ApiException.Unauthorized> { conn.login("nope") }
        assertTrue(e.message!!.contains("authentication failure"))
    }

    @Test
    fun `calls without login are Unauthorized`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl))
        assertFailsWith<ApiException.Unauthorized> { conn.pve { clusterStatus() } }
        Unit
    }

    @Test
    fun `two factor with TOTP`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl, user = "admin", realm = "pve"))
        val tfa = assertIs<LoginResult.NeedsTfa>(conn.login("anprox"))
        assertEquals(listOf(TfaKind.TOTP, TfaKind.RECOVERY), tfa.challenge.availableKinds)
        assertEquals(2, tfa.challenge.recoveryKeysLeft)

        assertFailsWith<ApiException.Unauthorized> { conn.completeTfa(tfa.partialTicket, TfaKind.TOTP, "000000") }

        val again = assertIs<LoginResult.NeedsTfa>(conn.login("anprox"))
        val session = conn.completeTfa(again.partialTicket, TfaKind.TOTP, "123456")
        assertEquals("admin@pve", session.username)
        assertTrue(conn.pve { clusterStatus() }.isNotEmpty())
    }

    @Test
    fun `recovery keys work once`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl, user = "admin", realm = "pve"))
        val first = assertIs<LoginResult.NeedsTfa>(conn.login("anprox"))
        conn.completeTfa(first.partialTicket, TfaKind.RECOVERY, "aaaa-bbbb-cccc")
        val second = assertIs<LoginResult.NeedsTfa>(conn.login("anprox"))
        assertEquals(1, second.challenge.recoveryKeysLeft)
        assertFailsWith<ApiException.Unauthorized> { conn.completeTfa(second.partialTicket, TfaKind.RECOVERY, "aaaa-bbbb-cccc") }
        Unit
    }

    @Test
    fun `fails over to the next endpoint and remembers it`() = runBlocking {
        val server = mock()
        val dead = "https://127.0.0.1:1"
        val conn = ProxmoxConnection(profile(dead, server.baseUrl))
        conn.loginOk()
        assertEquals(server.baseUrl, conn.endpoints.active?.url)
        assertEquals(server.baseUrl, conn.endpoints.candidates().first().url)
        assertTrue(conn.pve { clusterStatus() }.isNotEmpty())
    }

    @Test
    fun `all endpoints down is Unreachable with every attempt listed`() = runBlocking {
        val conn = ProxmoxConnection(profile("https://127.0.0.1:1", "https://127.0.0.1:2"))
        val e = assertFailsWith<ApiException.Unreachable> { conn.realms() }
        assertEquals(listOf("https://127.0.0.1:1", "https://127.0.0.1:2"), e.attempts.map { it.url })
    }

    @Test
    fun `reverse proxy sub-path and custom headers`() = runBlocking {
        val server = mock(pathPrefix = "/pve")
        val conn = ProxmoxConnection(profile(server.baseUrl, headers = mapOf("X-Proxy-Token" to "s3cret")))
        conn.loginOk()
        assertTrue(conn.pve { clusterStatus() }.isNotEmpty())
        assertEquals("s3cret", server.lastRequestHeaders?.get("X-Proxy-Token"))
    }

    @Test
    fun `power action creates a task and changes state (CSRF accepted)`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl))
        conn.loginOk()
        val upid = conn.pve { guestPower("pve2", GuestType.QEMU.apiName, 102, PowerAction.START.apiPath, emptyMap()) }
        assertTrue(upid.startsWith("UPID:pve2:"))
        val result = conn.awaitPveTask("pve2", upid, pollMillis = 10)
        assertTrue(result.succeeded)
        assertEquals("running", conn.pve { guestStatus("pve2", "qemu", 102) }.status)
        val tasks = conn.pve { clusterTasks() }
        assertEquals(TaskState.OK, tasks.first { it.upid == upid }.state)
        assertTrue(conn.pve { taskLog("pve2", upid, 0, 50) }.last().t.contains("TASK OK"))
    }

    @Test
    fun `server errors carry the Proxmox reason`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl))
        conn.loginOk()
        val e = assertFailsWith<ApiException.Server> {
            conn.pve { guestPower("pve1", "qemu", 100, "start", emptyMap()) }
        }
        assertEquals(500, e.code)
        assertEquals("VM 100 already running", e.message)
    }

    @Test
    fun `read-only user gets Forbidden on power actions`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl, user = "auditor", realm = "pve"))
        conn.loginOk()
        assertTrue(conn.pve { clusterResources(null) }.isNotEmpty())
        val e = assertFailsWith<ApiException.Forbidden> { conn.pve { guestPower("pve1", "qemu", 100, "stop", emptyMap()) } }
        assertTrue(e.message!!.contains("VM.PowerMgmt"))
    }

    @Test
    fun `tickets older than an hour are renewed transparently`() = runBlocking {
        val server = mock()
        var now = 1_000_000L
        val conn = ProxmoxConnection(profile(server.baseUrl), clock = { now })
        val first = conn.loginOk()
        now += 3_700
        assertTrue(conn.pve { clusterStatus() }.isNotEmpty())
        val renewed = conn.session!!
        assertNotEquals(first.ticket, renewed.ticket)
        assertEquals(now, renewed.issuedAtEpochSeconds)
    }

    @Test
    fun `ticket expired on the server is Unauthorized`() = runBlocking {
        val server = mock()
        val conn = ProxmoxConnection(profile(server.baseUrl))
        conn.loginOk()
        server.state.ageSessions(3 * 60 * 60 * 1000L)
        assertFailsWith<ApiException.Unauthorized> { conn.pve { clusterStatus() } }
        Unit
    }

    @Test
    fun `restore ignores sessions that are already expired`() = runBlocking {
        val server = mock()
        var now = 10_000L
        val conn = ProxmoxConnection(profile(server.baseUrl), clock = { now })
        val s = conn.loginOk()
        val other = ProxmoxConnection(profile(server.baseUrl), clock = { now + 3 * 3600 })
        other.restore(s)
        assertEquals(null, other.session)
        now += 60
        val fresh = ProxmoxConnection(profile(server.baseUrl), clock = { now })
        fresh.restore(s)
        assertTrue(fresh.pve { clusterStatus() }.isNotEmpty())
    }

    @Test
    fun `backup server login and datastores`() = runBlocking {
        val server = mock(ProxmoxProduct.PBS)
        val conn = ProxmoxConnection(profile(server.baseUrl, product = ProxmoxProduct.PBS))
        val session = conn.loginOk()
        assertTrue(session.ticket.startsWith("PBS:"))
        val usage = conn.pbs { datastoreUsage() }
        assertEquals(listOf("store1", "store2"), usage.map { it.store })
        val tasks = conn.pbs { tasks(20) }
        assertTrue(tasks.any { it.type == "garbage_collection" })
        assertEquals("4", conn.version().version.substringBefore('.'))
    }
}
