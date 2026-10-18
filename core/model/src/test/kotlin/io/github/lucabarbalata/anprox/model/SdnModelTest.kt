package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.Ipv4
import io.github.lucabarbalata.anprox.model.pve.SdnControllerForm
import io.github.lucabarbalata.anprox.model.pve.SdnPluginForm
import io.github.lucabarbalata.anprox.model.pve.SdnState
import io.github.lucabarbalata.anprox.model.pve.SdnSubnet
import io.github.lucabarbalata.anprox.model.pve.SdnSubnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnVnet
import io.github.lucabarbalata.anprox.model.pve.SdnVnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnZone
import io.github.lucabarbalata.anprox.model.pve.SdnZoneForm
import io.github.lucabarbalata.anprox.model.pve.SdnZoneType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdnModelTest {
    private fun json(text: String): JsonObject = ProxmoxJson.parseToJsonElement(text).jsonObject

    @Test
    fun `pending changes are merged the way they will be applied`() {
        // A changed zone: running values at the top, what changes under "pending".
        val changed = SdnZone.fromJson(json("""{"zone":"vlans","type":"vlan","bridge":"vmbr0","mtu":1500,"state":"changed","pending":{"bridge":"vmbr1","mtu":"deleted"}}"""))
        assertEquals(SdnState.CHANGED, changed.state)
        assertEquals("vmbr1", changed.bridge)
        assertNull(changed.mtu)
        // A new VNet: only the id and type at the top.
        val new = SdnVnet.fromJson(json("""{"vnet":"iot","type":"vnet","state":"new","pending":{"zone":"vlans","tag":40,"alias":"IoT"}}"""))
        assertEquals(SdnState.NEW, new.state)
        assertEquals("vlans", new.zone)
        assertEquals(40, new.tag)
        val deleted = SdnVnet.fromJson(json("""{"vnet":"old","zone":"lan","state":"deleted"}"""))
        assertEquals(SdnState.DELETED, deleted.state)
        assertNull(SdnVnet.fromJson(json("""{"vnet":"lab","zone":"lan"}""")).state)
    }

    @Test
    fun `zone forms check each type`() {
        assertEquals(SdnZoneForm.Problem.ID, SdnZoneForm(id = "toolongname").problem(creating = true))
        assertEquals(SdnZoneForm.Problem.ID, SdnZoneForm(id = "1abc").problem(creating = true))
        assertEquals(SdnZoneForm.Problem.BRIDGE, SdnZoneForm(SdnZoneType.VLAN, id = "v1").problem(creating = true))
        assertEquals(SdnZoneForm.Problem.TAG, SdnZoneForm(SdnZoneType.QINQ, id = "q1", bridge = "vmbr0").problem(creating = true))
        assertEquals(SdnZoneForm.Problem.PEERS, SdnZoneForm(SdnZoneType.VXLAN, id = "x1", peers = "10.0.0.1, pve2").problem(creating = true))
        assertEquals(SdnZoneForm.Problem.CONTROLLER, SdnZoneForm(SdnZoneType.EVPN, id = "e1").problem(creating = true))
        assertEquals(SdnZoneForm.Problem.MTU, SdnZoneForm(id = "s1", mtu = "100").problem(creating = true))
        val vxlan = SdnZoneForm(SdnZoneType.VXLAN, id = "x1", peers = "10.0.0.1 10.0.0.2", mtu = "1450", nodes = setOf("pve2", "pve1"))
        assertNull(vxlan.problem(creating = true))
        assertEquals(
            mapOf("peers" to "10.0.0.1,10.0.0.2", "mtu" to "1450", "nodes" to "pve1,pve2", "ipam" to "pve", "zone" to "x1", "type" to "vxlan"),
            vxlan.createParams(),
        )
        val zone = SdnZone("lan", "simple", mtu = 1500, dhcp = "dnsmasq", nodes = listOf("pve1"))
        val edit = SdnZoneForm.from(zone).copy(mtu = "", dhcp = false, nodes = emptySet())
        assertEquals("mtu,nodes,dhcp", edit.updateParams(zone)["delete"])
    }

    @Test
    fun `vnet tags depend on the zone type`() {
        assertEquals(SdnVnetForm.Problem.TAG, SdnVnetForm(id = "v1", zone = "vlans").problem(true, SdnZoneType.VLAN))
        assertEquals(SdnVnetForm.Problem.TAG, SdnVnetForm(id = "v1", zone = "vlans", tag = "4095").problem(true, SdnZoneType.VLAN))
        assertNull(SdnVnetForm(id = "v1", zone = "evpn1", tag = "100000").problem(true, SdnZoneType.EVPN))
        assertEquals(SdnVnetForm.Problem.TAG_NOT_ALLOWED, SdnVnetForm(id = "v1", zone = "lan", tag = "5").problem(true, SdnZoneType.SIMPLE))
        assertEquals(SdnVnetForm.Problem.ZONE, SdnVnetForm(id = "v1").problem(true, null))
        val vnet = SdnVnet("dmz", "vlans", tag = 30, alias = "DMZ", vlanAware = true)
        assertEquals(mapOf("zone" to "vlans", "tag" to "30", "delete" to "alias,vlanaware"), SdnVnetForm.from(vnet).copy(alias = "", vlanAware = false).updateParams(vnet))
    }

    @Test
    fun `subnets - ids, gateways inside, one DHCP range`() {
        assertEquals("10.10.0.0/24", SdnSubnet.cidrOf("lan-10.10.0.0-24"))
        assertEquals("fd00::/64", SdnSubnet.cidrOf("lan-fd00::-64"))
        val s = SdnSubnet.fromJson(json("""{"subnet":"lan-10.10.0.0-24","vnet":"lab","gateway":"10.10.0.1","snat":1,"dhcp-range":["start-address=10.10.0.100,end-address=10.10.0.200"]}"""))
        assertEquals("10.10.0.0/24", s.cidr)
        assertTrue(s.snat)
        val form = SdnSubnetForm.from(s)
        assertEquals("10.10.0.100" to "10.10.0.200", form.dhcpStart to form.dhcpEnd)
        assertNull(form.problem(creating = false))
        assertEquals(SdnSubnetForm.Problem.GATEWAY, form.copy(gateway = "10.10.1.1").problem(creating = false))
        assertEquals(SdnSubnetForm.Problem.DHCP_RANGE, form.copy(dhcpEnd = "10.10.0.50").problem(creating = false))
        assertEquals(SdnSubnetForm.Problem.DHCP_RANGE, form.copy(dhcpEnd = "").problem(creating = false))
        assertEquals(SdnSubnetForm.Problem.CIDR, SdnSubnetForm(cidr = "10.10.0.0").problem(creating = true))
        assertNull(SdnSubnetForm(cidr = "fd00::/64", gateway = "fd00::1").problem(creating = true))
        assertEquals(
            mapOf("gateway" to "10.10.0.1", "snat" to "1", "dhcp-range" to "start-address=10.10.0.100,end-address=10.10.0.200", "subnet" to "10.10.0.0/24", "type" to "subnet"),
            form.createParams(),
        )
        assertTrue(Ipv4.inside("192.168.30.200", "192.168.30.0/24"))
        assertFalse(Ipv4.inside("192.168.31.1", "192.168.30.0/23".replace("23", "24")))
        assertTrue(Ipv4.inside("10.1.2.3", "0.0.0.0/0"))
    }

    @Test
    fun `controllers and plugins`() {
        assertEquals(SdnControllerForm.Problem.PEERS, SdnControllerForm(id = "evpn2").problem())
        assertEquals(SdnControllerForm.Problem.NODE, SdnControllerForm(type = "bgp", id = "bgp1").problem())
        assertNull(SdnControllerForm(id = "evpn2", peers = "10.0.0.11,10.0.0.12").problem())
        val netbox = SdnPluginForm("netbox", id = "netbox", url = "https://netbox.lan/api", token = "abc")
        assertTrue(netbox.valid)
        assertEquals(mapOf("ipam" to "netbox", "type" to "netbox", "url" to "https://netbox.lan/api", "token" to "abc"), netbox.createParams())
        assertFalse(SdnPluginForm("phpipam", id = "php", url = "https://ipam.lan/api/app", token = "t").valid) // needs a section
        assertEquals("key", SdnPluginForm("powerdns", id = "pdns", url = "http://pdns:8081/api/v1/servers/localhost", token = "k").createParams().keys.elementAt(3))
    }
}
