package io.github.lucabarbalata.anprox.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import io.github.lucabarbalata.anprox.data.console.ConsoleConnection
import io.github.lucabarbalata.anprox.data.console.ConsoleEvent
import io.github.lucabarbalata.anprox.model.ssh.HostKeyPrompt
import io.github.lucabarbalata.anprox.model.ssh.KnownHost
import io.github.lucabarbalata.anprox.model.ssh.SshAuth
import io.github.lucabarbalata.anprox.model.ssh.SshFormat
import io.github.lucabarbalata.anprox.model.ssh.SshKeyInfo
import io.github.lucabarbalata.anprox.model.ssh.SshTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Why an SSH connection didn't work out. */
sealed class SshException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(message: String, cause: Throwable? = null) : SshException(message, cause)

    /** The server refused the password or key. */
    class AuthFailed : SshException("Permission denied")

    /** The user didn't trust the host key. */
    class HostKeyRejected : SshException("Host key not trusted")

    /** The key picked for signing in was deleted. */
    class KeyMissing : SshException("The SSH key is gone")

    class Failed(message: String, cause: Throwable? = null) : SshException(message, cause)
}

/**
 * The built-in SSH client (questionnaire Q15), on JSch. Host keys are trusted on first use: an
 * unknown or changed key is put to the user through [connect]'s `trust`, and only an accepted key
 * is remembered.
 */
class SshClient(private val keys: SshKeyStore, private val knownHosts: KnownHostStore) {

    /** Signs in and opens a shell with a terminal of [cols]×[rows]. */
    suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        cols: Int = 80,
        rows: Int = 24,
        trust: suspend (HostKeyPrompt) -> Boolean,
    ): SshShell = withContext(Dispatchers.IO) {
        JschSetup.ensure()
        val jsch = JSch()
        if (auth is SshAuth.Key) {
            val secret = keys.secret(auth.keyId) ?: throw SshException.KeyMissing()
            jsch.addIdentity(auth.keyId, secret.privateKey.toByteArray(Charsets.UTF_8), null, secret.passphrase?.toByteArray(Charsets.UTF_8))
        }
        val known = knownHosts.get(target.host, target.port)
        val repository = TrustOnFirstUse(target, known) { prompt -> runBlocking { trust(prompt) } }
        val session = jsch.getSession(target.user, target.host, target.port)
        session.hostKeyRepository = repository
        session.setConfig("StrictHostKeyChecking", "yes")
        known?.let { session.setConfig("server_host_key", preferring(it.type, session.getConfig("server_host_key"))) }
        when (auth) {
            is SshAuth.Password -> {
                session.setPassword(auth.password)
                session.userInfo = PasswordInfo(auth.password)
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            }
            is SshAuth.Key -> session.setConfig("PreferredAuthentications", "publickey")
        }
        session.setServerAliveInterval(KEEPALIVE_MILLIS)
        session.serverAliveCountMax = 3
        try {
            session.connect(CONNECT_TIMEOUT_MILLIS)
        } catch (e: JSchException) {
            throw failure(e)
        }
        repository.accepted?.let { knownHosts.put(it) }
        try {
            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color", cols, rows, 0, 0)
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(CONNECT_TIMEOUT_MILLIS)
            SshShell(session, channel, input, output)
        } catch (e: JSchException) {
            session.disconnect()
            throw failure(e)
        }
    }

    /** Adds [key] to the signed-in user's `authorized_keys`, as `ssh-copy-id` does. */
    suspend fun installKey(shell: SshShell, key: SshKeyInfo) {
        val (status, output) = shell.exec(SshFormat.installCommand(key.publicKey))
        if (status != 0) throw SshException.Failed(output.trim().ifEmpty { "Exit status $status" })
    }

    private fun failure(e: JSchException): SshException {
        val message = e.message.orEmpty()
        return when {
            message.startsWith("Auth fail") || message.startsWith("Auth cancel") -> SshException.AuthFailed()
            message.startsWith("reject HostKey") || message.startsWith("HostKey has been changed") -> SshException.HostKeyRejected()
            e.cause is UnknownHostException -> SshException.Unreachable("Unknown host: ${e.cause?.message}", e)
            e.cause is ConnectException || e.cause is NoRouteToHostException || e.cause is SocketTimeoutException ->
                SshException.Unreachable(e.cause?.message ?: message, e)
            message.startsWith("timeout") -> SshException.Unreachable(message, e)
            else -> SshException.Failed(message.ifEmpty { e.javaClass.simpleName }, e)
        }
    }

    /** The accepted key's algorithms first, so the server shows the same key again. */
    private fun preferring(type: String, defaults: String): String {
        val first = if (type == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") else listOf(type)
        return (first + defaults.split(',')).distinct().joinToString(",")
    }

    private class PasswordInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>,
            echo: BooleanArray,
        ): Array<String> = Array(prompt.size) { password }
    }

    /**
     * Accepts the key remembered for this `host:port`; anything else goes to the user. JSch asks
     * on its connecting thread, which waits for the answer.
     */
    private class TrustOnFirstUse(
        private val target: SshTarget,
        private val known: KnownHost?,
        private val ask: (HostKeyPrompt) -> Boolean,
    ) : HostKeyRepository {
        @Volatile var accepted: KnownHost? = null
            private set

        override fun check(host: String, key: ByteArray): Int {
            val type = SshKeys.typeOf(key)
            val fingerprint = SshFormat.fingerprint(key)
            if (known != null && known.fingerprint == fingerprint) return HostKeyRepository.OK
            val prompt = HostKeyPrompt(target.host, target.port, type, fingerprint, previous = known?.fingerprint)
            if (!ask(prompt)) return if (known == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
            accepted = KnownHost(target.host, target.port, type, fingerprint)
            return HostKeyRepository.OK
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "anprox"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val KEEPALIVE_MILLIS = 30_000
    }
}

/** A shell on the server, driven by the console screen like a Proxmox terminal. */
class SshShell internal constructor(
    private val session: Session,
    private val channel: ChannelShell,
    private val input: InputStream,
    private val output: OutputStream,
) : ConsoleConnection {
    private val queue = Channel<ConsoleEvent>(Channel.UNLIMITED)
    private val writes = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val events: Flow<ConsoleEvent> = queue.receiveAsFlow()

    init {
        queue.trySend(ConsoleEvent.Connected)
        scope.launch { read() }
        scope.launch {
            for (bytes in writes) {
                try {
                    output.write(bytes)
                    output.flush()
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun read() {
        val buffer = ByteArray(BUFFER)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) queue.trySend(ConsoleEvent.Output(buffer.copyOf(n)))
            }
            val status = channel.exitStatus
            queue.trySend(ConsoleEvent.Closed(if (status > 0) "Exit status $status" else null))
        } catch (e: Exception) {
            queue.trySend(ConsoleEvent.Closed(e.message))
        } finally {
            close()
        }
    }

    override fun sendInput(text: String) {
        writes.trySend(text.toByteArray(Charsets.UTF_8))
    }

    override fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) scope.launch { runCatching { channel.setPtySize(cols, rows, 0, 0) } }
    }

    /** Runs [command] on the same connection; returns its exit status and output. */
    suspend fun exec(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val exec = session.openChannel("exec") as ChannelExec
        exec.setCommand(command)
        val out = ByteArrayOutputStream()
        exec.setErrStream(out, true)
        val stream = exec.inputStream
        try {
            exec.connect(EXEC_TIMEOUT_MILLIS)
            stream.copyTo(out)
            while (!exec.isClosed) Thread.sleep(20)
            exec.exitStatus to out.toString(Charsets.UTF_8.name())
        } catch (e: JSchException) {
            throw SshException.Failed(e.message ?: "exec failed", e)
        } finally {
            exec.disconnect()
        }
    }

    override fun close() {
        writes.close()
        channel.disconnect()
        session.disconnect()
        scope.cancel()
    }

    private companion object {
        const val BUFFER = 8192
        const val EXEC_TIMEOUT_MILLIS = 10_000
    }
}
