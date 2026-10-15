package io.github.lucabarbalata.anprox.data

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import io.github.lucabarbalata.anprox.data.console.ConsoleEvent
import io.github.lucabarbalata.anprox.data.ssh.InMemoryKnownHostStore
import io.github.lucabarbalata.anprox.data.ssh.InMemorySshKeyStore
import io.github.lucabarbalata.anprox.data.ssh.SshClient
import io.github.lucabarbalata.anprox.data.ssh.SshException
import io.github.lucabarbalata.anprox.data.ssh.SshKeyException
import io.github.lucabarbalata.anprox.data.ssh.SshKeys
import io.github.lucabarbalata.anprox.data.ssh.SshShell
import io.github.lucabarbalata.anprox.mock.MockSshServer
import io.github.lucabarbalata.anprox.model.ssh.HostKeyPrompt
import io.github.lucabarbalata.anprox.model.ssh.SshAuth
import io.github.lucabarbalata.anprox.model.ssh.SshFormat
import io.github.lucabarbalata.anprox.model.ssh.SshKeyType
import io.github.lucabarbalata.anprox.model.ssh.SshTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SSH client against the mock's SSH server. */
class SshTest {
    private val server = MockSshServer().start()
    private val keys = InMemorySshKeyStore()
    private val known = InMemoryKnownHostStore()
    private val client = SshClient(keys, known)
    private val target = SshTarget("127.0.0.1", server.port, "root")

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    /** Collects the shell's output until it contains [text]. */
    private suspend fun SshShell.awaitOutput(text: String): String = withTimeout(10_000) {
        events.scan("") { all, e -> if (e is ConsoleEvent.Output) all + e.bytes.decodeToString() else all }.first { text in it }
    }

    @Test
    fun `generated keys of every type`() {
        SshKeyType.entries.forEach { type ->
            val key = SshKeys.generate(type, "My phone")
            assertEquals(type.sshName, key.info.type)
            assertTrue(key.info.publicKey.startsWith(type.sshName + " "))
            assertTrue(key.info.publicKey.endsWith(" anprox-my-phone"))
            assertEquals(SshFormat.fingerprint(SshFormat.blob(key.info.publicKey)!!), key.info.fingerprint)
            // What is stored reads back as the same key.
            val again = SshKeys.import("Again", key.secret.privateKey, null)
            assertEquals(key.info.fingerprint, again.info.fingerprint)
        }
    }

    @Test
    fun `imported keys with a passphrase`() {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        val text = ByteArrayOutputStream().also { pair.writePrivateKey(it, "secret".toByteArray()) }.toString(Charsets.UTF_8.name())
        assertEquals(SshKeyException.Reason.PASSPHRASE_NEEDED, assertFailsWith<SshKeyException> { SshKeys.import("k", text, null) }.reason)
        assertEquals(SshKeyException.Reason.WRONG_PASSPHRASE, assertFailsWith<SshKeyException> { SshKeys.import("k", text, "nope") }.reason)
        val key = SshKeys.import("k", text, "secret")
        assertTrue(key.info.encrypted)
        assertEquals("secret", key.secret.passphrase)
        assertEquals(SshFormat.fingerprint(pair.publicKeyBlob), key.info.fingerprint)
        assertEquals(SshKeyException.Reason.UNREADABLE, assertFailsWith<SshKeyException> { SshKeys.import("k", "not a key", null) }.reason)
    }

    @Test
    fun `password sign-in trusts the host key once, then the shell answers`() = runBlocking {
        val prompts = mutableListOf<HostKeyPrompt>()
        val shell = client.connect(target, SshAuth.Password(MockSshServer.PASSWORD)) { prompts += it; true }
        assertEquals("ecdsa-sha2-nistp256", prompts.single().type)
        assertNull(prompts.single().previous)
        shell.awaitOutput("root@pve1:~# ")
        shell.sendInput("hostname\r")
        shell.awaitOutput("hostname\r\npve1\r\n")
        shell.close()
        assertEquals(prompts.single().fingerprint, known.get("127.0.0.1", server.port)?.fingerprint)

        // The same key again: no question.
        client.connect(target, SshAuth.Password(MockSshServer.PASSWORD)) { error("asked again: $it") }.close()
        Unit
    }

    @Test
    fun `a changed host key is put to the user and refused unless accepted`() = runBlocking {
        client.connect(target, SshAuth.Password(MockSshServer.PASSWORD)) { true }.close()
        val before = known.get("127.0.0.1", server.port)!!.fingerprint
        server.changeHostKey()
        var asked: HostKeyPrompt? = null
        assertFailsWith<SshException.HostKeyRejected> {
            client.connect(target, SshAuth.Password(MockSshServer.PASSWORD)) { asked = it; false }
        }
        assertEquals(before, asked?.previous)
        assertEquals(before, known.get("127.0.0.1", server.port)!!.fingerprint)
        client.connect(target, SshAuth.Password(MockSshServer.PASSWORD)) { true }.close()
        assertEquals(asked?.fingerprint, known.get("127.0.0.1", server.port)!!.fingerprint)
        Unit
    }

    @Test
    fun `a key works once it is installed over a password session`() = runBlocking {
        val key = SshKeys.generate(SshKeyType.ED25519, "phone")
        keys.save(key.info, key.secret)
        assertFailsWith<SshException.AuthFailed> { client.connect(target, SshAuth.Key(key.info.id)) { true } }
        val shell = client.connect(target, SshAuth.Password(MockSshServer.PASSWORD)) { true }
        client.installKey(shell, key.info)
        shell.close()
        assertTrue(SshFormat.blob(key.info.publicKey)!!.let { java.util.Base64.getEncoder().encodeToString(it) } in server.authorizedKeys)
        val withKey = client.connect(target, SshAuth.Key(key.info.id)) { true }
        assertNotNull(withKey.awaitOutput("root@pve1:~# "))
        withKey.close()
        assertFailsWith<SshException.KeyMissing> { client.connect(target, SshAuth.Key("deleted")) { true } }
        Unit
    }

    @Test
    fun `wrong password and closed port`() = runBlocking {
        assertFailsWith<SshException.AuthFailed> { client.connect(target, SshAuth.Password("wrong")) { true } }
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertFailsWith<SshException.Unreachable> { client.connect(target.copy(port = closed), SshAuth.Password("x")) { true } }
        Unit
    }

    @Test
    fun `the install command only takes key lines`() {
        val line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIB anprox-phone"
        assertTrue(SshFormat.installCommand(line).contains("echo '$line' >> ~/.ssh/authorized_keys"))
        assertFailsWith<IllegalArgumentException> { SshFormat.installCommand("ssh-ed25519 AAAA x'; rm -rf /") }
        assertEquals("anprox-my-phone-2", SshFormat.comment(" My phone #2 "))
        assertEquals("[2001:db8::1]:2222", SshTarget("2001:db8::1", 2222).address)
    }
}
