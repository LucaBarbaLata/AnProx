package io.github.lucabarbalata.anprox.model.ssh

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/** Key types the app can create (questionnaire Q15). Imported keys may also be other types. */
enum class SshKeyType(val sshName: String, val bits: Int) {
    ED25519("ssh-ed25519", 256),
    ECDSA("ecdsa-sha2-nistp256", 256),
    RSA("ssh-rsa", 4096),
}

/** A key pair the app keeps; the private half is stored apart, encrypted. */
@Serializable
data class SshKeyInfo(
    val id: String,
    val name: String,
    /** `ssh-ed25519`, `ecdsa-sha2-nistp256`, `ssh-rsa`, … */
    val type: String,
    /** One `authorized_keys` line: `ssh-ed25519 AAAA… anprox-phone`. */
    val publicKey: String,
    /** `SHA256:…`, as `ssh-keygen -l` prints it. */
    val fingerprint: String,
    val createdAt: Long,
    /** Imported with a passphrase (the passphrase is stored with the key). */
    val encrypted: Boolean = false,
)

/** A server's host key the user accepted, for `host:port`. */
@Serializable
data class KnownHost(val host: String, val port: Int, val type: String, val fingerprint: String) {
    val address: String get() = SshTarget.address(host, port)
}

/** Where to connect. */
data class SshTarget(val host: String, val port: Int = DEFAULT_PORT, val user: String = "root") {
    val address: String get() = address(host, port)

    companion object {
        const val DEFAULT_PORT = 22

        /** `host`, `host:2222`, `[2001:db8::1]:2222`. */
        fun address(host: String, port: Int): String = when {
            port == DEFAULT_PORT -> host
            ':' in host -> "[$host]:$port"
            else -> "$host:$port"
        }
    }
}

/** How to sign in. */
sealed interface SshAuth {
    data class Password(val password: String) : SshAuth

    data class Key(val keyId: String) : SshAuth
}

/** A host key that needs the user's decision: new, or different from the one accepted before. */
data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val type: String,
    val fingerprint: String,
    /** The fingerprint accepted before, when the key changed. */
    val previous: String? = null,
) {
    val changed: Boolean get() = previous != null
    val address: String get() = SshTarget.address(host, port)
}

/** The connection form. */
data class SshConnectForm(
    val host: String = "",
    val port: String = SshTarget.DEFAULT_PORT.toString(),
    val user: String = "root",
    val useKey: Boolean = false,
    val keyId: String? = null,
    val password: String = "",
) {
    enum class Problem { HOST, PORT, USER, KEY, PASSWORD }

    fun problem(): Problem? = when {
        !HOST.matches(host.trim()) -> Problem.HOST
        (port.trim().toIntOrNull() ?: 0) !in 1..65535 -> Problem.PORT
        !USER.matches(user.trim()) -> Problem.USER
        useKey && keyId == null -> Problem.KEY
        !useKey && password.isEmpty() -> Problem.PASSWORD
        else -> null
    }

    fun target(): SshTarget = SshTarget(host.trim().removePrefix("[").removeSuffix("]"), port.trim().toInt(), user.trim())

    fun auth(): SshAuth = if (useKey) SshAuth.Key(requireNotNull(keyId)) else SshAuth.Password(password)

    private companion object {
        /** A host name, IPv4 address or IPv6 address (optionally in brackets). */
        val HOST = Regex("^(\\[?[0-9A-Fa-f:.]+]?|[A-Za-z0-9]([A-Za-z0-9.\\-]*[A-Za-z0-9])?)$")
        val USER = Regex("^[A-Za-z_][A-Za-z0-9_.@\\-]{0,63}$")
    }
}

object SshFormat {
    /** `SHA256:` and the unpadded Base64 of the key blob's SHA-256, like OpenSSH. */
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** The key blob of an `authorized_keys` line, or null. */
    fun blob(publicKeyLine: String): ByteArray? =
        publicKeyLine.trim().split(Regex("\\s+")).getOrNull(1)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** A key comment safe to put in a shell command: `anprox-` and the name's letters and digits. */
    fun comment(name: String): String = "anprox-" + name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "key" }

    /**
     * Adds [publicKeyLine] to the user's `~/.ssh/authorized_keys` unless it's there already, the
     * way `ssh-copy-id` does. The line only holds Base64, a type and a [comment], so it needs no
     * escaping inside single quotes; anything else is refused.
     */
    fun installCommand(publicKeyLine: String): String {
        val line = publicKeyLine.trim()
        require(SAFE_LINE.matches(line)) { "Not a public key line" }
        return "umask 077; mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qxF '$line' ~/.ssh/authorized_keys || echo '$line' >> ~/.ssh/authorized_keys)"
    }

    private val SAFE_LINE = Regex("^[a-z0-9@.\\-]+ [A-Za-z0-9+/=]+( [A-Za-z0-9._@\\-]+)?$")
}
