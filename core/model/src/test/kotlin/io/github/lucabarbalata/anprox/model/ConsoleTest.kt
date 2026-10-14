package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.TerminalKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleTest {

    @Test
    fun `console pages match the Proxmox web UI`() {
        assertEquals(
            "https://pve.lan:8006/?console=kvm&novnc=1&vmid=100&vmname=web+server&node=pve1&resize=scale&cmd=",
            ConsoleTarget(ConsoleKind.VM, "pve1", 100, "web server").url("https://pve.lan:8006/"),
        )
        assertEquals(
            "https://proxy.example/pve/?console=lxc&xtermjs=1&vmid=200&vmname=pihole&node=pve2&cmd=",
            ConsoleTarget(ConsoleKind.CONTAINER, "pve2", 200, "pihole").url("https://proxy.example/pve"),
        )
        assertEquals(
            "https://10.0.0.11:8006/?console=shell&xtermjs=1&node=pve1&cmd=",
            ConsoleTarget(ConsoleKind.NODE, "pve1").url("https://10.0.0.11:8006"),
        )
    }

    @Test
    fun `terminal keys become keydown events with the right key code`() {
        val esc = TerminalKey.ESC.script()
        assertTrue("return 27;" in esc && "ctrlKey: false" in esc)
        val ctrlC = TerminalKey.CTRL_C.script()
        assertTrue("return 67;" in ctrlC && "ctrlKey: true" in ctrlC)
        assertTrue(TerminalKey.entries.none { "'" in it.key })
        Unit
    }
}
