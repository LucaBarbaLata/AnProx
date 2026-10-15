package io.github.lucabarbalata.anprox.data.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import io.github.lucabarbalata.anprox.model.ssh.SshFormat
import io.github.lucabarbalata.anprox.model.ssh.SshKeyInfo
import io.github.lucabarbalata.anprox.model.ssh.SshKeyType
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** A key ready to be stored: what the app shows and the secret half. */
data class SshKeyMaterial(val info: SshKeyInfo, val secret: SshKeySecret)

class SshKeyException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { UNREADABLE, PASSPHRASE_NEEDED, WRONG_PASSPHRASE }
}

/**
 * JSch picks its Ed25519, Curve25519 and ChaCha20 classes by Java version, and Android reports
 * an old one, so the Bouncy Castle versions are chosen here for every platform alike.
 */
internal object JschSetup {
    private val done by lazy {
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("chacha20-poly1305@openssh.com", "com.jcraft.jsch.bc.ChaCha20Poly1305")
        true
    }

    fun ensure() {
        check(done)
    }
}

/** Creating and importing SSH keys (questionnaire Q15). */
object SshKeys {

    fun generate(
        type: SshKeyType,
        name: String,
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): SshKeyMaterial {
        JschSetup.ensure()
        // JSch can't write Ed25519 private keys, so those are written here in OpenSSH's format.
        if (type == SshKeyType.ED25519) return import(name, ed25519(SshFormat.comment(name)), null, now, id)
        val pair = KeyPair.genKeyPair(JSch(), if (type == SshKeyType.ECDSA) KeyPair.ECDSA else KeyPair.RSA, type.bits)
        try {
            val private = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
            return SshKeyMaterial(info(pair, id, name, now, encrypted = false), SshKeySecret(private))
        } finally {
            pair.dispose()
        }
    }

    /** A new Ed25519 key as an unencrypted `-----BEGIN OPENSSH PRIVATE KEY-----` block. */
    private fun ed25519(comment: String): String {
        val private = Ed25519PrivateKeyParameters(SecureRandom())
        val seed = private.encoded
        val public = private.generatePublicKey().encoded
        val type = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
        val publicBlob = SshWire().string(type).string(public).bytes()
        val check = SecureRandom().nextInt()
        val section = SshWire().int(check).int(check).string(type).string(public).string(seed + public).string(comment.toByteArray(Charsets.UTF_8))
        var pad = 1
        while (section.size % 8 != 0) section.byte(pad++)
        val body = SshWire()
            .raw("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
            .string("none".toByteArray()).string("none".toByteArray()).string(ByteArray(0))
            .int(1).string(publicBlob).string(section.bytes())
            .bytes()
        val base64 = Base64.getEncoder().encodeToString(body).chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$base64\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    /** SSH's wire encoding: big-endian integers and length-prefixed strings. */
    private class SshWire {
        private val out = ByteArrayOutputStream()
        val size: Int get() = out.size()

        fun raw(bytes: ByteArray) = apply { out.write(bytes) }

        fun byte(b: Int) = apply { out.write(b) }

        fun int(v: Int) = apply { out.write(ByteBuffer.allocate(4).putInt(v).array()) }

        fun string(bytes: ByteArray) = int(bytes.size).raw(bytes)

        fun bytes(): ByteArray = out.toByteArray()
    }

    /** Reads a private key in OpenSSH, PEM or PuTTY format, checking the passphrase if it has one. */
    fun import(
        name: String,
        privateKey: String,
        passphrase: String?,
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): SshKeyMaterial {
        JschSetup.ensure()
        val text = privateKey.trim().replace("\r\n", "\n") + "\n"
        val pair = try {
            KeyPair.load(JSch(), text.toByteArray(Charsets.UTF_8), null)
        } catch (e: JSchException) {
            throw SshKeyException(SshKeyException.Reason.UNREADABLE, e)
        } catch (e: RuntimeException) {
            // Damaged input can trip JSch's parsers in other ways.
            throw SshKeyException(SshKeyException.Reason.UNREADABLE, e)
        }
        try {
            val phrase = passphrase?.takeIf { it.isNotEmpty() }
            val encrypted = pair.isEncrypted
            if (encrypted) {
                if (phrase == null) throw SshKeyException(SshKeyException.Reason.PASSPHRASE_NEEDED)
                if (!pair.decrypt(phrase.toByteArray(Charsets.UTF_8))) throw SshKeyException(SshKeyException.Reason.WRONG_PASSPHRASE)
            }
            if (pair.publicKeyBlob == null) throw SshKeyException(SshKeyException.Reason.UNREADABLE)
            // Stored as it came; the passphrase is kept (encrypted on the phone) so it's asked once.
            return SshKeyMaterial(info(pair, id, name, now, encrypted), SshKeySecret(text, phrase.takeIf { encrypted }))
        } finally {
            pair.dispose()
        }
    }

    private fun info(pair: KeyPair, id: String, name: String, now: Long, encrypted: Boolean): SshKeyInfo {
        val blob = pair.publicKeyBlob
        val line = ByteArrayOutputStream().also { pair.writePublicKey(it, SshFormat.comment(name)) }.toString(Charsets.UTF_8.name()).trim()
        return SshKeyInfo(
            id = id,
            name = name.trim(),
            type = typeOf(blob),
            publicKey = line,
            fingerprint = SshFormat.fingerprint(blob),
            createdAt = now,
            encrypted = encrypted,
        )
    }

    /** The key type at the start of an SSH key blob (`ssh-ed25519`, …). */
    internal fun typeOf(blob: ByteArray): String {
        val buffer = ByteBuffer.wrap(blob)
        val length = buffer.int
        return String(blob, 4, length, Charsets.US_ASCII)
    }
}
