package io.github.lucabarbalata.anprox.mock

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import kotlin.concurrent.thread

/**
 * An SSH server for the app's SSH client (questionnaire Q15): password `anprox` for any user, or
 * any key added through the `ssh-copy-id`-like command the app runs. The shell is a toy that
 * echoes, answers a few commands (`hostname`, `whoami`, `uname -a`, `echo`) and `exit`s.
 */
class MockSshServer(requestedPort: Int = 0, host: String = "127.0.0.1", private val hostname: String = "pve1") {
    private val authorized: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        this.host = host
        this.port = requestedPort
        keyPairProvider = SimpleGeneratorHostKeyProvider().apply {
            algorithm = "EC"
            keySize = 256
        }
        setPasswordAuthenticator { _, password, _ -> password == PASSWORD }
        setPublickeyAuthenticator { _, key, _ -> PublicKeyEntry.toString(key).split(' ').getOrNull(1) in authorized }
        setShellFactory { channel -> ToyShell(channel.session.username) }
        setCommandFactory { _, command -> AddKeyCommand(command) }
    }

    val port: Int get() = server.port

    /** Base64 blobs of the keys added with the install command. */
    val authorizedKeys: Set<String> get() = synchronized(authorized) { authorized.toSet() }

    fun start(): MockSshServer = apply { server.start() }

    fun stop() {
        server.stop(true)
    }

    /** Replaces the host key, as a reinstalled server would. */
    fun changeHostKey() {
        server.keyPairProvider = SimpleGeneratorHostKeyProvider().apply {
            algorithm = "EC"
            keySize = 256
        }
    }

    private abstract class Base : Command {
        protected lateinit var input: InputStream
        protected lateinit var output: OutputStream
        protected var exit: ExitCallback? = null

        override fun setInputStream(`in`: InputStream) {
            input = `in`
        }

        override fun setOutputStream(out: OutputStream) {
            output = out
        }

        override fun setErrorStream(err: OutputStream) = Unit

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun destroy(channel: ChannelSession) = Unit

        protected fun write(text: String) {
            output.write(text.replace("\n", "\r\n").toByteArray())
            output.flush()
        }
    }

    private inner class ToyShell(private val user: String) : Base() {
        override fun start(channel: ChannelSession, env: Environment) {
            thread(name = "mock-ssh-shell", isDaemon = true) {
                val term = env.env["TERM"] ?: "dumb"
                write("Linux $hostname 6.8.12-4-pve x86_64 (AnProx mock, TERM=$term)\n\n${prompt()}")
                val line = StringBuilder()
                try {
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        when (b) {
                            '\r'.code, '\n'.code -> {
                                write("\n")
                                val command = line.toString().trim()
                                line.clear()
                                if (command == "exit" || command == "logout") {
                                    write("logout\n")
                                    exit?.onExit(0)
                                    return@thread
                                }
                                answer(command)?.let { write(it + "\n") }
                                write(prompt())
                            }
                            0x7f, 0x08 -> if (line.isNotEmpty()) {
                                line.setLength(line.length - 1)
                                write("\b \b")
                            }
                            0x03 -> {
                                line.clear()
                                write("^C\n${prompt()}")
                            }
                            0x04 -> if (line.isEmpty()) {
                                write("logout\n")
                                exit?.onExit(0)
                                return@thread
                            }
                            else -> if (b >= 0x20) {
                                line.append(b.toChar())
                                output.write(b)
                                output.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The client went away.
                }
                exit?.onExit(0)
            }
        }

        private fun prompt() = "$user@$hostname:~# "

        private fun answer(command: String): String? = when {
            command.isEmpty() -> null
            command == "hostname" -> hostname
            command == "whoami" -> user
            command == "uname -a" -> "Linux $hostname 6.8.12-4-pve #1 SMP PREEMPT_DYNAMIC PMX 6.8.12-4 x86_64 GNU/Linux"
            command.startsWith("echo ") -> command.removePrefix("echo ")
            else -> "-bash: ${command.substringBefore(' ')}: command not found"
        }
    }

    /** The app's "add my key" command: `… echo 'ssh-ed25519 AAAA… name' >> ~/.ssh/authorized_keys)`. */
    private inner class AddKeyCommand(private val command: String) : Base() {
        override fun start(channel: ChannelSession, env: Environment) {
            val blob = Regex("echo '[^ ]+ ([A-Za-z0-9+/=]+)[^']*' >> ~/\\.ssh/authorized_keys").find(command)?.groupValues?.get(1)
            if (blob == null) {
                write("sh: unsupported command in the mock\n")
                exit?.onExit(127)
            } else {
                authorized += blob
                exit?.onExit(0)
            }
        }
    }

    companion object {
        const val PASSWORD = "anprox"
    }
}
