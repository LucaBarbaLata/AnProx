package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.MetricServerRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.MetricServerForm
import io.github.lucabarbalata.anprox.model.pve.MetricServerType
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

/** External metric servers against the mock. */
class MetricServerTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, taskDurationMillis = 0).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun hub(user: String = "root", realm: String = "pam"): ServerHub {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return hub
    }

    @Test
    fun `add, change and delete servers of each kind`() = runBlocking {
        val repo = MetricServerRepository(hub())
        val seeded = repo.servers("pve").single()
        assertEquals("influx", seeded.id)
        assertEquals(MetricServerType.INFLUXDB, seeded.kind)
        assertFalse(seeded.disable)

        repo.create("pve", MetricServerForm().withType(MetricServerType.GRAPHITE).copy(id = "carbon", server = "graphite.lan", path = "proxmox.home"))
        repo.create(
            "pve",
            MetricServerForm().withType(MetricServerType.OPENTELEMETRY).copy(id = "otel", server = "otel.lan", headers = "Authorization: Bearer x", disabled = true),
        )
        assertEquals(listOf("carbon", "influx", "otel"), repo.servers("pve").map { it.id })
        assertTrue(repo.servers("pve").single { it.id == "otel" }.disable)

        // Switching InfluxDB to HTTP: the token is sent once and never comes back.
        val influx = repo.settings("pve", "influx")
        repo.update("pve", influx.form.withProtocol("http").copy(bucket = "proxmox", organization = "home", token = "t0ken"), influx.digest)
        val changed = repo.settings("pve", "influx")
        assertEquals("http", changed.form.protocol)
        assertEquals("8086", changed.form.port)
        assertEquals("proxmox", changed.form.bucket)
        assertEquals("", changed.form.token)

        // Emptied fields are deleted; the others stay.
        repo.update("pve", changed.form.copy(bucket = ""), changed.digest)
        assertEquals("", repo.settings("pve", "influx").form.bucket)
        assertEquals("home", repo.settings("pve", "influx").form.organization)

        // An old digest is refused, as is a second server with the same ID.
        assertFailsWith<ApiException> { repo.update("pve", changed.form, changed.digest) }
        assertFailsWith<ApiException> { repo.create("pve", MetricServerForm(id = "carbon", server = "x")) }

        // Enabled HTTP servers are tried when saved.
        val error = assertFailsWith<ApiException> { repo.create("pve", MetricServerForm(id = "down", server = "unreachable.lan").withProtocol("http")) }
        assertTrue("Connection refused" in error.message.orEmpty())

        repo.delete("pve", "carbon")
        repo.delete("pve", "otel")
        assertEquals(listOf("influx"), repo.servers("pve").map { it.id })
        Unit
    }

    @Test
    fun `an auditor can read but not change`() = runBlocking {
        val repo = MetricServerRepository(hub("auditor", "pve"))
        assertEquals(1, repo.servers("pve").size)
        assertFailsWith<ApiException.Forbidden> { repo.delete("pve", "influx") }
        Unit
    }
}
