package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.HaRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.HaResource
import io.github.lucabarbalata.anprox.model.pve.HaGroupForm
import io.github.lucabarbalata.anprox.model.pve.HaResourceForm
import io.github.lucabarbalata.anprox.model.pve.HaState
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

/** High availability against the mock (questionnaire Q21). */
class HaTest {
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
    fun `status, resources and groups`() = runBlocking {
        val ha = HaRepository(hub())
        assertEquals(listOf("vm:100", "vm:101"), ha.resources("pve").map { it.sid })
        assertEquals("prefer-pve1", ha.resources("pve").first().group)
        assertEquals(listOf("prefer-pve1"), ha.groups("pve")!!.map { it.group })
        val status = ha.status("pve")
        assertEquals("OK", status.first { it.type == "quorum" }.status)
        assertEquals(listOf("lrm:pve1", "lrm:pve2", "lrm:pve3"), status.filter { it.type == "lrm" }.map { it.id })
        assertEquals(2, status.count { it.type == "service" })
        Unit
    }

    @Test
    fun `add, change, move and remove a guest`() = runBlocking {
        val hub = hub()
        val ha = HaRepository(hub)
        val sid = HaResource.sid(GuestType.LXC, 201)
        ha.add("pve", sid, HaResourceForm(state = HaState.STARTED, maxRestart = 2))
        var r = ha.resources("pve").first { it.sid == sid }
        assertEquals(2, r.maxRestart)
        assertFailsWith<ApiException> { ha.add("pve", sid, HaResourceForm()) } // already managed

        ha.update("pve", r, HaResourceForm.from(r).copy(group = "prefer-pve1", comment = "proxy"))
        r = ha.resources("pve").first { it.sid == sid }
        assertEquals("prefer-pve1", r.group)
        ha.setState("pve", r, HaState.IGNORED)
        assertEquals(HaState.IGNORED, ha.resources("pve").first { it.sid == sid }.haState)

        // nginx-proxy (201) lives on pve2; the HA manager moves it to pve1.
        ha.migrate("pve", r, "pve1")
        assertEquals("pve1", hub.use("pve") { it.pve { clusterResources("vm") } }.first { it.vmid == 201 }.node)
        assertFailsWith<ApiException> { ha.migrate("pve", r, "pve3") } // offline

        ha.remove("pve", ha.resources("pve").first { it.sid == sid })
        assertEquals(listOf("vm:100", "vm:101"), ha.resources("pve").map { it.sid })
        Unit
    }

    @Test
    fun `groups can be added, edited and deleted, rules need Proxmox VE 9`() = runBlocking {
        val ha = HaRepository(hub())
        assertEquals(null, ha.rules("pve"))
        ha.createGroup("pve", HaGroupForm("pve2-first", mapOf("pve2" to 2, "pve1" to null), restricted = true))
        val group = ha.groups("pve")!!.single { it.group == "pve2-first" }
        assertEquals("pve1,pve2:2", group.nodes)
        assertTrue(group.restricted)
        ha.updateGroup("pve", group, HaGroupForm.from(group).copy(nofailback = true, comment = "Prefer pve2"))
        val edited = ha.groups("pve")!!.single { it.group == "pve2-first" }
        assertTrue(edited.nofailback)
        assertEquals("Prefer pve2", edited.comment)
        // A group a resource uses can't go.
        assertFailsWith<ApiException> { ha.deleteGroup("pve", ha.groups("pve")!!.single { it.group == "prefer-pve1" }) }
        ha.deleteGroup("pve", edited)
        assertEquals(listOf("prefer-pve1"), ha.groups("pve")!!.map { it.group })
        Unit
    }
}
