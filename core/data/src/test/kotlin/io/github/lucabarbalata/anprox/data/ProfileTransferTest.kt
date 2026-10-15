package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.data.transfer.ProfileCrypto
import io.github.lucabarbalata.anprox.data.transfer.ProfileTransfer
import io.github.lucabarbalata.anprox.data.transfer.ProfileTransferException
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTransferTest {
    private val homelab = ServerProfile(
        id = "a", name = "Homelab", endpoints = listOf(Endpoint("https://10.0.0.11:8006", "LAN", mapOf("X-Auth" to "k"))),
        username = "root", realm = "pam", allowInsecureTls = true, rememberPassword = true, sortOrder = 0,
    )
    private val backup = ServerProfile(
        id = "b", name = "Backup", product = ProxmoxProduct.PBS, endpoints = listOf(Endpoint("https://10.0.0.60:8007")),
        username = "admin", realm = "pbs", sortOrder = 1,
    )

    private fun phone(vararg profiles: ServerProfile) = InMemoryProfileStore(profiles.toList()) to InMemorySecretStore()

    @Test
    fun `servers and saved passwords move to another phone`() = runBlocking {
        val (oldProfiles, oldSecrets) = phone(homelab, backup)
        oldSecrets.setPassword("a", "s3cret")
        val file = ProfileTransfer(oldProfiles, oldSecrets, iterations = 1_000).export("correct horse".toCharArray(), includePasswords = true)
        assertFalse("s3cret" in file)
        assertFalse("Homelab" in file)

        val (newProfiles, newSecrets) = phone()
        val transfer = ProfileTransfer(newProfiles, newSecrets, iterations = 1_000)
        val bundle = transfer.open(file, "correct horse".toCharArray())
        assertEquals(listOf("Homelab", "Backup"), bundle.servers.map { it.profile.name })
        assertEquals(2, transfer.import(bundle).added)
        assertEquals(listOf("Homelab", "Backup"), newProfiles.profiles.first().sortedBy { it.sortOrder }.map { it.name })
        assertEquals("s3cret", newSecrets.password("a"))
        Unit
    }

    @Test
    fun `import adds new servers at the end and replaces ones with the same id`() = runBlocking {
        val (oldProfiles, oldSecrets) = phone(homelab, backup)
        oldSecrets.setPassword("a", "s3cret")
        val file = ProfileTransfer(oldProfiles, oldSecrets, iterations = 1_000).export("pw".toCharArray(), includePasswords = true)

        val other = ServerProfile(id = "c", name = "Office", endpoints = listOf(Endpoint("https://office:8006")), username = "me", sortOrder = 0)
        val renamed = homelab.copy(name = "Old name", sortOrder = 1)
        val (profiles, secrets) = phone(other, renamed)
        val transfer = ProfileTransfer(profiles, secrets, iterations = 1_000)
        val result = transfer.import(transfer.open(file, "pw".toCharArray()))
        assertEquals(1, result.added)
        assertEquals(1, result.updated)
        val now = profiles.profiles.first().associateBy { it.id }
        assertEquals("Homelab", now.getValue("a").name)
        assertEquals(1, now.getValue("a").sortOrder)
        assertEquals(2, now.getValue("b").sortOrder)
        assertEquals(mapOf("X-Auth" to "k"), now.getValue("a").endpoints.single().headers)
        assertEquals("s3cret", secrets.password("a"))
        assertNull(secrets.password("b"))
        Unit
    }

    @Test
    fun `passwords stay behind unless asked for`() = runBlocking {
        val (profiles, secrets) = phone(homelab)
        secrets.setPassword("a", "s3cret")
        val transfer = ProfileTransfer(profiles, secrets, iterations = 1_000)
        val bundle = transfer.open(transfer.export("pw".toCharArray(), includePasswords = false), "pw".toCharArray())
        assertNull(bundle.servers.single().password)
        Unit
    }

    @Test
    fun `wrong passphrase, other files and newer versions are told apart`() = runBlocking {
        val (profiles, secrets) = phone(homelab)
        val transfer = ProfileTransfer(profiles, secrets, iterations = 1_000)
        val file = transfer.export("pw".toCharArray(), includePasswords = false)
        assertEquals(
            ProfileTransferException.Reason.WRONG_PASSPHRASE,
            assertFailsWith<ProfileTransferException> { transfer.open(file, "nope".toCharArray()) }.reason,
        )
        // The header is authenticated: a lowered cost doesn't open the file.
        assertTrue(""""iterations":1000""" in file)
        val tampered = file.replace(""""iterations":1000""", """"iterations":1001""")
        assertFailsWith<ProfileTransferException> { transfer.open(tampered, "pw".toCharArray()) }
        assertEquals(
            ProfileTransferException.Reason.NOT_AN_EXPORT,
            assertFailsWith<ProfileTransferException> { transfer.open("hello", "pw".toCharArray()) }.reason,
        )
        assertEquals(
            ProfileTransferException.Reason.NEWER_VERSION,
            assertFailsWith<ProfileTransferException> { transfer.open(file.replace(""""version":1""", """"version":2"""), "pw".toCharArray()) }.reason,
        )
        assertEquals(ProfileCrypto.FORMAT, "anprox-servers")
        Unit
    }
}
