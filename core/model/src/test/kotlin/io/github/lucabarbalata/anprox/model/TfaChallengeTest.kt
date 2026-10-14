package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.auth.TfaChallenge
import io.github.lucabarbalata.anprox.model.auth.TfaKind
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TfaChallengeTest {

    private fun ticketFor(json: String) =
        "PVE:!tfa!" + URLEncoder.encode(json, StandardCharsets.UTF_8) + ":65A1B2C3::c2lnbmF0dXJl"

    @Test
    fun `parses url-encoded challenge with totp and recovery`() {
        val c = TfaChallenge.fromTicket(ticketFor("""{"totp":true,"recovery":[0,1,2,5]}"""))
        assertTrue(c.totp)
        assertEquals(4, c.recoveryKeysLeft)
        assertEquals(listOf(TfaKind.TOTP, TfaKind.RECOVERY), c.availableKinds)
    }

    @Test
    fun `parses raw json challenge with webauthn`() {
        val c = TfaChallenge.fromTicket("""PBS:!tfa!{"webauthn":{"publicKey":{"challenge":"a:b"}},"yubico":1}:65A1B2C3::sig""")
        assertNotNull(c.webAuthn)
        assertTrue(c.yubico)
        assertEquals(listOf(TfaKind.WEBAUTHN, TfaKind.YUBICO), c.availableKinds)
    }

    @Test
    fun `empty recovery list means no recovery keys`() {
        val c = TfaChallenge.fromTicket(ticketFor("""{"totp":true,"recovery":[]}"""))
        assertEquals(null, c.recoveryKeysLeft)
    }

    @Test
    fun `rejects normal tickets`() {
        assertFailsWith<IllegalArgumentException> { TfaChallenge.fromTicket("PVE:root@pam:65A1B2C3::sig") }
    }

    @Test
    fun `answers are prefixed on the wire`() {
        assertEquals("totp:123456", TfaKind.TOTP.encode(" 123456 "))
        assertEquals("recovery:abcd-efgh", TfaKind.RECOVERY.encode("abcd-efgh"))
    }
}
