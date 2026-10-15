package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.console.ConsoleEvent
import io.github.lucabarbalata.anprox.data.console.ConsoleRepository
import io.github.lucabarbalata.anprox.data.console.ConsoleSession
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.mock.vncAuthResponse
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Consoles through the app's own connection (questionnaire Q15): ticket from termproxy/vncproxy,
 * then the WebSocket, over HTTPS with a self-signed certificate and a reverse-proxy sub-path.
 */
class ConsoleTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, pathPrefix = "/pve").start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun consoles(user: String = "root", realm: String = "pam"): ConsoleRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return ConsoleRepository(hub)
    }

    /** Collects terminal output until it contains [text]; fails on errors or after 10 s. */
    private suspend fun Flow<ConsoleEvent>.awaitText(text: String): String = withTimeout(10_000) {
        val seen = StringBuilder()
        first { event ->
            when (event) {
                is ConsoleEvent.Output -> seen.append(String(event.bytes))
                is ConsoleEvent.Failed -> error("console failed: ${event.message}")
                is ConsoleEvent.Closed -> error("console closed: ${event.reason}; output so far: $seen")
                ConsoleEvent.Connected -> Unit
            }
            text in seen
        }
        seen.toString()
    }

    private suspend fun ConsoleSession.awaitConnected() = withTimeout(10_000) {
        val event = events.first { it !is ConsoleEvent.Output }
        assertEquals(ConsoleEvent.Connected, event, "expected Connected, got $event")
    }

    @Test
    fun `container terminal accepts the ticket, echoes input and takes resizes`() = runBlocking {
        val session = consoles().open("pve", ConsoleTarget(ConsoleKind.CONTAINER, "pve2", 201, "nginx-proxy"))
        session.awaitConnected()
        session.events.awaitText("root@nginx-proxy:~# ")
        session.resize(100, 30)
        session.sendInput("uptime\r")
        assertTrue("uptime\r\nroot@nginx-proxy:~# " in session.events.awaitText("uptime\r\n"))
        session.sendInput("\u001b")
        session.events.awaitText("^[")
        session.close()
    }

    @Test
    fun `node shell`() = runBlocking {
        val session = consoles().open("pve", ConsoleTarget(ConsoleKind.NODE, "pve1"))
        session.awaitConnected()
        assertTrue("mock shell on pve1" in session.events.awaitText("root@pve1:~# "))
        session.close()
    }

    @Test
    fun `VM screen speaks RFB with the generated VNC password`() = runBlocking {
        val session = consoles().open("pve", ConsoleTarget(ConsoleKind.VM, "pve2", 103, "k8s-master"))
        val password = assertNotNull(session.vncPassword)
        session.awaitConnected()
        session.events.awaitText("RFB 003.008\n")
        // Play noVNC's part of the handshake: version, VNC authentication, ClientInit.
        session.sendBinary("RFB 003.008\n".toByteArray())
        val types = (session.events.first { it is ConsoleEvent.Output } as ConsoleEvent.Output).bytes
        assertEquals(listOf<Byte>(1, 2), types.toList()) // VNC authentication only
        session.sendBinary(byteArrayOf(2))
        val challenge = (session.events.first { it is ConsoleEvent.Output } as ConsoleEvent.Output).bytes
        session.sendBinary(vncAuthResponse(password, challenge))
        val result = (session.events.first { it is ConsoleEvent.Output } as ConsoleEvent.Output).bytes
        assertEquals(listOf<Byte>(0, 0, 0, 0), result.toList()) // authenticated
        session.sendBinary(byteArrayOf(1))
        session.events.awaitText("k8s-master") // ServerInit carries the desktop name
        session.close()
    }

    @Test
    fun `stopped guests and read-only users are refused`() = runBlocking {
        val stopped = assertFailsWith<ApiException.Server> {
            consoles().open("pve", ConsoleTarget(ConsoleKind.VM, "pve2", 102, "win11"))
        }
        assertTrue("not running" in stopped.message.orEmpty())
        assertFailsWith<ApiException.Forbidden> {
            consoles(user = "auditor", realm = "pve").open("pve", ConsoleTarget(ConsoleKind.NODE, "pve1"))
        }
        Unit
    }
}
