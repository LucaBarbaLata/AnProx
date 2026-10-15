package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.NetworkInterfaceForm
import io.github.lucabarbalata.anprox.model.pve.NetworkKind
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkSetupTest {
    private val existing = listOf("eno1", "eno2", "vmbr0", "vmbr1")

    @Test
    fun `new interfaces are checked like Proxmox`() {
        val bridge = NetworkInterfaceForm(name = "vmbr2", cidr = "192.168.50.1/24", bridgePorts = "eno2")
        assertNull(bridge.problem(creating = true, existing))
        assertEquals(NetworkInterfaceForm.Problem.NAME_TAKEN, bridge.copy(name = "vmbr1").problem(true, existing))
        assertEquals(NetworkInterfaceForm.Problem.NAME, bridge.copy(name = "2br").problem(true, existing))
        assertEquals(NetworkInterfaceForm.Problem.ADDRESS, bridge.copy(cidr = "192.168.50.1").problem(true, existing))
        assertEquals(NetworkInterfaceForm.Problem.GATEWAY, bridge.copy(cidr = "", gateway = "192.168.50.254").problem(true, existing))
        assertEquals(NetworkInterfaceForm.Problem.MTU, bridge.copy(mtu = "100").problem(true, existing))
        val bond = NetworkInterfaceForm(type = "bond", name = "bond0")
        assertEquals(NetworkInterfaceForm.Problem.SLAVES, bond.problem(true, existing))
        val vlan = NetworkInterfaceForm(type = "vlan", name = "vlan20")
        assertEquals(NetworkInterfaceForm.Problem.VLAN, vlan.problem(true, existing))
        assertNull(vlan.copy(vlanRawDevice = "vmbr0", vlanId = "20").problem(true, existing))
        // The parent and tag can be in the name instead.
        assertNull(vlan.copy(name = "eno1.30").problem(true, existing))
        assertEquals("vmbr2", NetworkInterfaceForm.suggestedName(NetworkKind.BRIDGE, existing))
        assertEquals("bond0", NetworkInterfaceForm.suggestedName(NetworkKind.BOND, existing))
    }

    @Test
    fun `parameters by type`() {
        assertEquals(
            mapOf("iface" to "vmbr2", "type" to "bridge", "cidr" to "192.168.50.1/24", "autostart" to "1", "bridge_ports" to "eno2 eno3", "bridge_vlan_aware" to "0"),
            NetworkInterfaceForm(name = "vmbr2", cidr = "192.168.50.1/24", bridgePorts = "eno2, eno3").createParams(),
        )
        assertEquals(
            mapOf("iface" to "bond0", "type" to "bond", "autostart" to "1", "slaves" to "eno1 eno2", "bond_mode" to "802.3ad", "bond_xmit_hash_policy" to "layer3+4"),
            NetworkInterfaceForm(type = "bond", name = "bond0", slaves = "eno1 eno2", bondMode = "802.3ad", hashPolicy = "layer3+4").createParams(),
        )
        assertEquals(
            mapOf("iface" to "eno1.30", "type" to "vlan", "autostart" to "1"),
            NetworkInterfaceForm(type = "vlan", name = "eno1.30").createParams(),
        )
        val original = NodeNetwork("vmbr0", "bridge", cidr = "10.0.0.11/24", gateway = "10.0.0.1", comments = "LAN", bridgePorts = "eno1", vlanAware = true, autostart = true)
        val form = NetworkInterfaceForm.from(original)
        assertEquals(
            mapOf("type" to "bridge", "cidr" to "10.0.0.12/24", "autostart" to "1", "bridge_ports" to "eno1", "bridge_vlan_aware" to "1", "delete" to "gateway,comments"),
            form.copy(cidr = "10.0.0.12/24", gateway = "", comment = "").updateParams(original),
        )
    }
}
