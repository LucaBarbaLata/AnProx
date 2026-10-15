package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleProxy
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.SpiceFile
import io.github.lucabarbalata.anprox.model.pve.TermProxyProtocol
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleTest {

    @Test
    fun `console endpoints match the Proxmox API`() {
        val vm = ConsoleTarget(ConsoleKind.VM, "pve1", 100, "web")
        assertEquals("nodes/pve1/qemu/100/vncproxy", vm.proxyPath)
        assertEquals("nodes/pve1/qemu/100/vncwebsocket", vm.websocketPath)
        val ct = ConsoleTarget(ConsoleKind.CONTAINER, "pve2", 200, "pihole")
        assertEquals("nodes/pve2/lxc/200/termproxy", ct.proxyPath)
        assertEquals("nodes/pve2/lxc/200/vncwebsocket", ct.websocketPath)
        val node = ConsoleTarget(ConsoleKind.NODE, "pve1")
        assertEquals("nodes/pve1/termproxy", node.proxyPath)
        assertEquals("nodes/pve1/vncwebsocket", node.websocketPath)
    }

    @Test
    fun `proxy answers with the port as a number or a string`() {
        val numeric = ProxmoxJson.decodeFromString<ApiEnvelope<ConsoleProxy>>(
            """{"data":{"port":5900,"ticket":"PVEVNC:abc","user":"root@pam","upid":"UPID:x","cert":"-----BEGIN"}}""",
        ).data!!
        assertEquals("5900", numeric.portNumber)
        assertEquals("PVEVNC:abc", numeric.vncPassword) // no generated password: the ticket is the password
        val quoted = ProxmoxJson.decodeFromString<ApiEnvelope<ConsoleProxy>>(
            """{"data":{"port":"5901","ticket":"PVEVNC:def","password":"s3cret"}}""",
        ).data!!
        assertEquals("5901", quoted.portNumber)
        assertEquals("s3cret", quoted.vncPassword)
    }

    @Test
    fun `terminal protocol frames`() {
        assertEquals("root@pam:PVEVNC:abc\n", TermProxyProtocol.auth("root@pam", "PVEVNC:abc"))
        assertEquals("0:3:ls\r", TermProxyProtocol.input("ls\r"))
        assertEquals("0:2:é", TermProxyProtocol.input("é")) // byte length, not characters
        assertEquals("1:80:24:", TermProxyProtocol.resize(80, 24))
        assertTrue(TermProxyProtocol.isAccepted("OKwelcome".toByteArray()))
        assertFalse(TermProxyProtocol.isAccepted("NO".toByteArray()))
        Unit
    }

    @Test
    fun `SPICE file`() {
        val config = ProxmoxJson.decodeFromString<ApiEnvelope<JsonObject>>(
            """{"data":{"type":"spice","host":"pvespiceproxy:6720A3B1:102:pve2::abc","password":"pw","proxy":"http://10.0.0.11:3128",
            "tls-port":61000,"ca":"-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n","delete-this-file":1,"release-cursor":"Ctrl+Alt+R"}}""",
        ).data!!
        assertEquals(
            "[virt-viewer]\ntype=spice\nhost=pvespiceproxy:6720A3B1:102:pve2::abc\npassword=pw\nproxy=http://10.0.0.11:3128\n" +
                "tls-port=61000\nca=-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----\\n\ndelete-this-file=1\nrelease-cursor=Ctrl+Alt+R\n",
            SpiceFile.render(config),
        )
        assertEquals(mapOf("proxy" to "pve.example.com"), SpiceFile.proxyParams("pve.example.com"))
    }
}
