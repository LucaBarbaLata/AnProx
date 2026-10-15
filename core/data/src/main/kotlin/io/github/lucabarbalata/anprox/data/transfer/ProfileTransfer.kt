package io.github.lucabarbalata.anprox.data.transfer

import io.github.lucabarbalata.anprox.data.store.ProfileStore
import io.github.lucabarbalata.anprox.data.store.SecretStore
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** One server in an export: its profile and, if the user chose so, its saved password. */
@Serializable
data class ExportedServer(val profile: ServerProfile, val password: String? = null)

/** What an export file holds once decrypted. */
@Serializable
data class ProfileBundle(val exportedAt: Long, val servers: List<ExportedServer>)

/** How many servers an import added and how many it replaced (same id). */
data class ImportResult(val added: Int, val updated: Int)

class ProfileTransferException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_AN_EXPORT, NEWER_VERSION, WRONG_PASSPHRASE }
}

/**
 * The encrypted file: a small JSON envelope with the key derivation's salt and cost, the GCM
 * nonce, and the encrypted [ProfileBundle]. The header fields are authenticated too, so they
 * can't be changed without the passphrase failing.
 */
@Serializable
internal data class ExportEnvelope(
    val format: String,
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val nonce: String,
    val data: String,
)

/**
 * Password-based encryption for moving servers to another phone (milestone M4): PBKDF2 with
 * HMAC-SHA256 derives an AES-256 key from the passphrase, AES-GCM encrypts. Both are in Android
 * 8.0's (API 26) standard providers, so the file opens on any phone the app runs on.
 */
object ProfileCrypto {
    const val FORMAT = "anprox-servers"
    const val VERSION = 1
    private const val KDF = "PBKDF2WithHmacSHA256"

    /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256; about a second on a mid-range phone. */
    const val ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 10_000_000

    private val json = Json { ignoreUnknownKeys = true }

    fun encrypt(plain: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ExportEnvelope(FORMAT, VERSION, KDF, iterations, b64(salt), b64(nonce), data = "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(header))
        return json.encodeToString(ExportEnvelope.serializer(), header.copy(data = b64(cipher.doFinal(plain))))
    }

    fun decrypt(text: String, passphrase: CharArray): ByteArray {
        val envelope = try {
            json.decodeFromString(ExportEnvelope.serializer(), text.trim())
        } catch (_: SerializationException) {
            throw ProfileTransferException(ProfileTransferException.Reason.NOT_AN_EXPORT)
        } catch (_: IllegalArgumentException) {
            throw ProfileTransferException(ProfileTransferException.Reason.NOT_AN_EXPORT)
        }
        // The cost comes from the file: refuse absurd values rather than spin for hours.
        if (envelope.format != FORMAT || envelope.kdf != KDF || envelope.iterations !in 1_000..MAX_ITERATIONS) {
            throw ProfileTransferException(ProfileTransferException.Reason.NOT_AN_EXPORT)
        }
        if (envelope.version > VERSION) throw ProfileTransferException(ProfileTransferException.Reason.NEWER_VERSION)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, unb64(envelope.salt), envelope.iterations), GCMParameterSpec(128, unb64(envelope.nonce)))
            cipher.updateAAD(aad(envelope.copy(data = "")))
            cipher.doFinal(unb64(envelope.data))
        } catch (_: AEADBadTagException) {
            throw ProfileTransferException(ProfileTransferException.Reason.WRONG_PASSPHRASE)
        } catch (_: GeneralSecurityException) {
            throw ProfileTransferException(ProfileTransferException.Reason.NOT_AN_EXPORT)
        } catch (_: IllegalArgumentException) {
            throw ProfileTransferException(ProfileTransferException.Reason.NOT_AN_EXPORT)
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** The header without the data, as the authenticated additional data. */
    private fun aad(header: ExportEnvelope) = json.encodeToString(ExportEnvelope.serializer(), header).toByteArray(Charsets.UTF_8)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String) = Base64.getDecoder().decode(text)
}

/**
 * Exports the saved servers to an encrypted file and imports them back, on this phone or a new
 * one. Session tickets are never exported (they expire); saved passwords only when asked to.
 */
class ProfileTransfer(
    private val profiles: ProfileStore,
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val iterations: Int = ProfileCrypto.ITERATIONS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun export(passphrase: CharArray, includePasswords: Boolean): String {
        val servers = profiles.profiles.first().sortedBy { it.sortOrder }.map { p ->
            ExportedServer(p, if (includePasswords && p.rememberPassword) secrets.password(p.id) else null)
        }
        val plain = json.encodeToString(ProfileBundle.serializer(), ProfileBundle(clock(), servers)).toByteArray(Charsets.UTF_8)
        return withContext(Dispatchers.Default) { ProfileCrypto.encrypt(plain, passphrase, iterations) }
    }

    /** Decrypts an export; throws [ProfileTransferException]. */
    suspend fun open(text: String, passphrase: CharArray): ProfileBundle {
        val plain = withContext(Dispatchers.Default) { ProfileCrypto.decrypt(text, passphrase) }
        return try {
            json.decodeFromString(ProfileBundle.serializer(), plain.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw ProfileTransferException(ProfileTransferException.Reason.NOT_AN_EXPORT)
        }
    }

    /**
     * Adds the servers of [bundle]; one with the id of a saved server replaces it, keeping its
     * place in the list. New ones go to the end.
     */
    suspend fun import(bundle: ProfileBundle): ImportResult {
        val existing = profiles.profiles.first().associateBy { it.id }
        var next = (existing.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
        var added = 0
        var updated = 0
        bundle.servers.forEach { server ->
            val old = existing[server.profile.id]
            val profile = server.profile.copy(sortOrder = old?.sortOrder ?: next++)
            profiles.upsert(profile)
            if (old == null) added++ else updated++
            server.password?.let { secrets.setPassword(profile.id, it) }
        }
        return ImportResult(added, updated)
    }
}
