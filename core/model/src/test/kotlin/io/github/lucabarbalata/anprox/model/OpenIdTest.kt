package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.auth.OpenIdCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenIdTest {
    @Test
    fun `the origin of a server address is what a browser would send`() {
        assertEquals("https://pve.example.com:8006", OpenIdCallback.origin("https://pve.example.com:8006"))
        assertEquals("https://pve.example.com:8006", OpenIdCallback.origin("https://pve.example.com:8006/"))
        assertEquals("https://proxy.example.com", OpenIdCallback.origin("https://proxy.example.com:443/pve"))
        assertEquals("http://10.0.0.5:8007", OpenIdCallback.origin("HTTP://10.0.0.5:8007/api2/json"))
        assertEquals("https://[fd00::5]:8006", OpenIdCallback.origin("https://[fd00::5]:8006"))
    }

    @Test
    fun `the way back carries a code and a state`() {
        val back = "https://pve.example.com:8006"
        assertEquals(
            OpenIdCallback.Code("abc/123", "x y"),
            OpenIdCallback.parse("https://pve.example.com:8006/?state=x%20y&code=abc%2F123", back),
        )
        assertEquals(
            OpenIdCallback.Code("c", "s"),
            OpenIdCallback.parse("https://PVE.example.com:8006?code=c&state=s&session_state=z", back),
        )
        // The default port written out or left away is the same origin.
        assertEquals(OpenIdCallback.Code("c", "s"), OpenIdCallback.parse("https://pve.example.com:443/?code=c&state=s", "https://pve.example.com"))
    }

    @Test
    fun `a refusal is reported`() {
        assertEquals(
            OpenIdCallback.Error("access_denied", "The user said no"),
            OpenIdCallback.parse("https://pve.example.com:8006/?error=access_denied&error_description=The+user+said+no&state=s", "https://pve.example.com:8006"),
        )
    }

    @Test
    fun `pages of the sign-in flow are not the way back`() {
        val back = "https://pve.example.com:8006"
        assertNull(OpenIdCallback.parse("https://auth.example.com/authorize?code=c&state=s", back))
        assertNull(OpenIdCallback.parse("http://pve.example.com:8006/?code=c&state=s", back))
        assertNull(OpenIdCallback.parse("https://pve.example.com:8007/?code=c&state=s", back))
        assertNull(OpenIdCallback.parse("https://pve.example.com:8006/", back))
        assertNull(OpenIdCallback.parse("https://pve.example.com:8006/?code=c", back))
        assertNull(OpenIdCallback.parse("not a url", back))
        // Under a reverse proxy's sub-path only that path counts.
        assertEquals(OpenIdCallback.Code("c", "s"), OpenIdCallback.parse("https://proxy.example.com/pve/?code=c&state=s", "https://proxy.example.com/pve"))
        assertNull(OpenIdCallback.parse("https://proxy.example.com/other/?code=c&state=s", "https://proxy.example.com/pve"))
    }
}
