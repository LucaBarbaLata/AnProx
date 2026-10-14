package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.profile.EndpointUrl
import io.github.lucabarbalata.anprox.model.profile.EndpointUrl.Reason
import io.github.lucabarbalata.anprox.model.profile.EndpointUrl.Result
import kotlin.test.Test
import kotlin.test.assertEquals

class EndpointUrlTest {
    private fun ok(input: String, product: ProxmoxProduct = ProxmoxProduct.PVE) =
        (EndpointUrl.normalize(input, product) as Result.Ok).url

    private fun bad(input: String) = (EndpointUrl.normalize(input, ProxmoxProduct.PVE) as Result.Invalid).reason

    @Test
    fun `bare host gets https and default port`() {
        assertEquals("https://192.168.1.10:8006", ok("192.168.1.10"))
        assertEquals("https://pbs.lan:8007", ok("pbs.lan", ProxmoxProduct.PBS))
    }

    @Test
    fun `explicit https without port keeps 443`() {
        assertEquals("https://pve.example.com", ok("https://pve.example.com/"))
        assertEquals("https://pve.example.com", ok("https://pve.example.com:443"))
    }

    @Test
    fun `sub path is kept and api suffix stripped`() {
        assertEquals("https://example.com/pve", ok("https://example.com/pve/"))
        assertEquals("https://example.com/pve", ok("https://example.com/pve/api2/json"))
        assertEquals("https://10.0.0.5:8006", ok("10.0.0.5:8006/api2/json/?x=1#frag"))
    }

    @Test
    fun `ipv6 literals`() {
        assertEquals("https://[fd00::1]:8006", ok("[fd00::1]"))
    }

    @Test
    fun `invalid input`() {
        assertEquals(Reason.EMPTY, bad("  "))
        assertEquals(Reason.BAD_SCHEME, bad("http://pve.lan:8006"))
        assertEquals(Reason.BAD_SCHEME, bad("ftp://pve.lan"))
        assertEquals(Reason.BAD_PORT, bad("pve.lan:99999"))
    }
}
