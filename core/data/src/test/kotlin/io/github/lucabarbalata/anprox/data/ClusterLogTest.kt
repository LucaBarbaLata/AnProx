package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.ClusterLogRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.ClusterLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Datacenter → Cluster log against the mock. */
class ClusterLogTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    @Test
    fun `the log comes oldest first with tasks, errors and warnings`() = runBlocking {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)

        val log = ClusterLogRepository(hub).log("pve", max = 500)
        assertTrue(log.zipWithNext().all { (a, b) -> a.time <= b.time })
        assertTrue(log.any { it.isError && it.tag == "corosync" })
        assertTrue(log.any { it.isWarning })
        assertTrue(log.any { "end task" in it.msg && it.msg.endsWith("migration aborted: target node pve3 is offline") && it.isError })
        assertEquals(3, ClusterLogRepository(hub).log("pve", max = 3).size)

        val failed = log.first { "authentication failure" in it.msg }
        assertTrue(failed.matches("AUTHENTICATION"))
        assertTrue(failed.matches("pve2"))
        assertFalse(failed.matches("corosync"))
        Unit
    }

    @Test
    fun `entries parse from Proxmox's JSON`() {
        // Shape of GET /cluster/log on PVE 8 (numbers as numbers).
        val json = """[{"uid":5,"time":1760850000,"pri":6,"tag":"pvedaemon","pid":2290,"node":"pve1","user":"root@pam","msg":"<root@pam> starting task UPID:pve1:x"}]"""
        val entry = ProxmoxJson.decodeFromString<List<ClusterLogEntry>>(json).single()
        assertEquals(5L, entry.uid)
        assertEquals("pvedaemon", entry.tag)
        assertFalse(entry.isError)
    }
}
