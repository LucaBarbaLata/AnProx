package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.console.ConsoleRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The console page a WebView opens, with the session cookie (questionnaire Q15). */
class ConsoleTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, pathPrefix = "/pve").start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun consoles(): ConsoleRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return ConsoleRepository(hub)
    }

    /** The mock's certificate is self-signed, like a fresh Proxmox install. */
    private fun fetch(url: String, cookie: String?): String {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
        val conn = URI(url).toURL().openConnection() as HttpsURLConnection
        conn.sslSocketFactory = tls.socketFactory
        conn.setHostnameVerifier { _, _ -> true }
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `a console session opens the page signed in`() = runBlocking {
        val session = consoles().open("pve", ConsoleTarget(ConsoleKind.CONTAINER, "pve2", 201, "nginx-proxy"))
        assertTrue(session.url.startsWith("${pve.baseUrl}/?console=lxc&xtermjs=1&vmid=201"), session.url)
        assertEquals(pve.baseUrl.removeSuffix("/pve"), session.origin) // cookies belong to the origin, not the sub-path
        assertTrue(session.allowInsecureTls)
        assertTrue(session.cookie.startsWith("PVEAuthCookie=PVE:root@pam:") && session.cookie.endsWith("; Path=/; Secure"))

        val page = fetch(session.url, "${session.cookieName}=${session.ticket}")
        assertTrue("Mock console: lxc 201 on pve2 (signed in)" in page, page)
        assertTrue("xterm-helper-textarea" in page)
        assertTrue("(not signed in)" in fetch(session.url, cookie = null))
    }

    @Test
    fun `node shells and VM screens`() = runBlocking {
        val repo = consoles()
        val shell = repo.open("pve", ConsoleTarget(ConsoleKind.NODE, "pve1"))
        assertTrue("Mock console: shell on pve1 (signed in)" in fetch(shell.url, "${shell.cookieName}=${shell.ticket}"))
        val vm = repo.open("pve", ConsoleTarget(ConsoleKind.VM, "pve2", 103, "k8s-master"))
        val page = fetch(vm.url, "${vm.cookieName}=${vm.ticket}")
        assertTrue("Mock console: kvm 103 on pve2 (signed in)" in page)
        assertTrue("xterm-helper-textarea" !in page) // noVNC, not a terminal
        Unit
    }
}
