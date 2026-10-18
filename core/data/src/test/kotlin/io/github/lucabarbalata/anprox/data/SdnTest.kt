package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.SdnRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.SdnControllerForm
import io.github.lucabarbalata.anprox.model.pve.SdnPluginForm
import io.github.lucabarbalata.anprox.model.pve.SdnState
import io.github.lucabarbalata.anprox.model.pve.SdnSubnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnVnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnZoneForm
import io.github.lucabarbalata.anprox.model.pve.SdnZoneType
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SDN (M6) against the mock: pending changes, apply and discard. */
class SdnTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE, taskDurationMillis = 0).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hub: ServerHub

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(): SdnRepository {
        val profile = ServerProfile(
            id = "pve", name = "pve", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return SdnRepository(hub)
    }

    @Test
    fun `the mock's network, with one VNet waiting to be applied`() = runBlocking {
        val repo = repo()
        assertEquals(listOf("lan", "vlans"), repo.zones("pve").map { it.id })
        assertEquals("dnsmasq", repo.zones("pve").single { it.id == "lan" }.dhcp)
        val vnets = repo.vnets("pve")
        assertEquals(listOf("lab", "dmz", "iot"), vnets.map { it.id })
        val iot = vnets.single { it.id == "iot" }
        assertEquals(SdnState.NEW, iot.state)
        assertEquals("vlans" to 40, iot.zone to iot.tag)
        val lab = repo.subnets("pve", "lab").single()
        assertEquals("10.10.0.0/24", lab.cidr)
        assertEquals(listOf("start-address=10.10.0.100,end-address=10.10.0.200"), lab.dhcpRanges)
        assertEquals(listOf("evpn1"), repo.controllers("pve").map { it.id })
        assertEquals(listOf("pve"), repo.ipams("pve").map { it.id })
        assertTrue(repo.dnsServers("pve").isEmpty())
        assertTrue(repo.ipamEntries("pve", "pve").any { it.hostname == "pihole" && it.vmid == 200 })
        assertEquals(listOf("vmbr0", "vmbr1"), repo.bridges("pve", "pve1").take(2))

        GuestRepository(hub).awaitTask("pve", "pve1", repo.apply("pve"))
        assertNull(repo.vnets("pve").single { it.id == "iot" }.state)
        Unit
    }

    @Test
    fun `zones, VNets and subnets change as pending until applied`() = runBlocking {
        val repo = repo()
        repo.createZone("pve", SdnZoneForm(SdnZoneType.VXLAN, id = "overlay", peers = "10.0.0.11,10.0.0.12", mtu = "1450"))
        assertFailsWith<ApiException> { repo.createZone("pve", SdnZoneForm(SdnZoneType.VLAN, id = "bad")) } // no bridge
        assertEquals(SdnState.NEW, repo.zones("pve").single { it.id == "overlay" }.state)
        repo.createVnet("pve", SdnVnetForm(id = "tenant1", zone = "overlay", tag = "100100"))
        assertFailsWith<ApiException> { repo.createVnet("pve", SdnVnetForm(id = "tenant2", zone = "lan", tag = "5")) } // simple zones take no tag
        repo.createSubnet("pve", "tenant1", SdnSubnetForm(cidr = "172.16.0.0/24", gateway = "172.16.0.1"))
        val subnet = repo.subnets("pve", "tenant1").single()
        assertEquals("overlay-172.16.0.0-24", subnet.id)
        repo.updateSubnet("pve", subnet, SdnSubnetForm.from(subnet).copy(snat = true))
        assertTrue(repo.subnets("pve", "tenant1").single().snat)

        // A VNet with subnets, or a zone with VNets, can't go.
        assertFailsWith<ApiException> { repo.deleteVnet("pve", "tenant1") }
        assertFailsWith<ApiException> { repo.deleteZone("pve", "overlay") }

        // Changing something already running shows as changed.
        val dmz = repo.vnets("pve").single { it.id == "dmz" }
        repo.updateVnet("pve", dmz, SdnVnetForm.from(dmz).copy(tag = "31"))
        val changed = repo.vnets("pve").single { it.id == "dmz" }
        assertEquals(SdnState.CHANGED, changed.state)
        assertEquals(31, changed.tag)

        GuestRepository(hub).awaitTask("pve", "pve1", repo.apply("pve"))
        assertTrue(repo.zones("pve").all { it.state == null })
        assertTrue(repo.vnets("pve").all { it.state == null })

        // Removing is pending too, until applied.
        repo.deleteSubnet("pve", repo.subnets("pve", "tenant1").single())
        repo.deleteVnet("pve", "tenant1")
        assertEquals(SdnState.DELETED, repo.vnets("pve").single { it.id == "tenant1" }.state)
        Unit
    }

    @Test
    fun `discarding pending changes, controllers, IPAM and DNS`() = runBlocking {
        val repo = repo()
        repo.discard("pve")
        assertFalse(repo.vnets("pve").any { it.id == "iot" })

        repo.createController("pve", SdnControllerForm(id = "evpn2", asn = "65001", peers = "10.0.0.11"))
        repo.createZone("pve", SdnZoneForm(SdnZoneType.EVPN, id = "tenants", controller = "evpn2", vrfVxlan = "4000"))
        assertFailsWith<ApiException> { repo.deleteController("pve", "evpn2") } // used by the zone
        repo.deleteZone("pve", "tenants")
        repo.deleteController("pve", "evpn2")
        assertFalse(repo.controllers("pve").any { it.id == "evpn2" })

        repo.createPlugin("pve", SdnPluginForm("netbox", id = "netbox", url = "https://netbox.lan/api", token = "secret"))
        repo.createPlugin("pve", SdnPluginForm("powerdns", id = "pdns", url = "http://10.0.0.53:8081/api/v1/servers/localhost", token = "key"))
        assertEquals(listOf("pve", "netbox"), repo.ipams("pve").map { it.id })
        assertEquals("https://netbox.lan/api", repo.ipams("pve").single { it.id == "netbox" }.url)
        assertEquals(listOf("pdns"), repo.dnsServers("pve").map { it.id })
        assertFailsWith<ApiException> { repo.deleteIpam("pve", "pve") } // the zones use it
        repo.deleteIpam("pve", "netbox")
        repo.deleteDns("pve", "pdns")
        assertEquals(listOf("pve"), repo.ipams("pve").map { it.id })
        Unit
    }
}
