package io.github.lucabarbalata.anprox.data.console

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleProxy
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.TermProxyProtocol
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.ProxmoxConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** What happens on a console connection, in order. */
sealed interface ConsoleEvent {
    /** Terminals: Proxmox accepted the ticket. VNC: the socket is open; noVNC does the handshake. */
    data object Connected : ConsoleEvent

    /** Bytes from the server: terminal output, or raw RFB data for noVNC. */
    class Output(val bytes: ByteArray) : ConsoleEvent

    data class Closed(val reason: String?) : ConsoleEvent

    data class Failed(val message: String) : ConsoleEvent
}

/**
 * One live console. Events arrive on [events] (collect it once); input goes the other way through
 * [sendInput] / [resize] for terminals and [sendBinary] for VNC.
 *
 * @property vncPassword VMs only: the password noVNC answers the VNC challenge with.
 */
class ConsoleSession internal constructor(val target: ConsoleTarget, val vncPassword: String?) {
    private val channel = Channel<ConsoleEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var socket: WebSocket? = null

    @Volatile private var accepted = false

    val events: Flow<ConsoleEvent> = channel.receiveAsFlow()

    /** Terminals: what the user typed (or an extra key's bytes). */
    fun sendInput(text: String) {
        if (accepted) socket?.send(TermProxyProtocol.input(text))
    }

    /** Terminals: the visible size in characters. */
    fun resize(cols: Int, rows: Int) {
        if (accepted && cols > 0 && rows > 0) socket?.send(TermProxyProtocol.resize(cols, rows))
    }

    /** VNC: raw RFB bytes from noVNC. */
    fun sendBinary(bytes: ByteArray) {
        socket?.send(bytes.toByteString())
    }

    fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        scope.cancel()
    }

    internal fun attach(socket: WebSocket) {
        this.socket = socket
    }

    internal fun listener(user: String, ticket: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            if (target.kind.isTerminal) {
                webSocket.send(TermProxyProtocol.auth(user, ticket))
            } else {
                accepted = true
                channel.trySend(ConsoleEvent.Connected)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = received(webSocket, bytes.toByteArray())

        override fun onMessage(webSocket: WebSocket, text: String) = received(webSocket, text.encodeToByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            channel.trySend(ConsoleEvent.Closed(reason.ifBlank { null }))
            scope.cancel()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = when (response?.code) {
                401 -> "Permission denied (the console ticket was refused)"
                403 -> "Permission denied"
                null -> t.message ?: t.javaClass.simpleName
                else -> "HTTP ${response.code} ${response.message}".trim()
            }
            channel.trySend(ConsoleEvent.Failed(message))
            scope.cancel()
        }
    }

    private fun received(webSocket: WebSocket, bytes: ByteArray) {
        if (accepted) {
            channel.trySend(ConsoleEvent.Output(bytes))
            return
        }
        // Terminals: the first message says whether the ticket was accepted.
        if (!TermProxyProtocol.isAccepted(bytes)) {
            channel.trySend(ConsoleEvent.Failed("The console refused the ticket"))
            webSocket.close(NORMAL_CLOSURE, null)
            return
        }
        accepted = true
        channel.trySend(ConsoleEvent.Connected)
        if (bytes.size > 2) channel.trySend(ConsoleEvent.Output(bytes.copyOfRange(2, bytes.size)))
        scope.launch {
            while (isActive) {
                delay(TermProxyProtocol.PING_INTERVAL_MILLIS)
                webSocket.send(TermProxyProtocol.PING)
            }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

/**
 * Opens consoles the way the Proxmox web UI's console window does, but through the app's own
 * connection (questionnaire Q15): a one-time ticket from `termproxy` / `vncproxy`, then the
 * `vncwebsocket`. Certificates, custom headers and address failover therefore behave exactly as
 * for every other API call, which a WebView talking to Proxmox directly could not guarantee.
 */
class ConsoleRepository(private val hub: ServerHub) {

    suspend fun open(serverId: String, target: ConsoleTarget): ConsoleSession = hub.use(serverId) { conn ->
        val proxy = proxy(conn, target)
        val user = proxy.user ?: conn.session?.username ?: throw ApiException.Unauthorized("Not logged in")
        val session = ConsoleSession(target, if (target.kind == ConsoleKind.VM) proxy.vncPassword else null)
        val query = mapOf("port" to proxy.portNumber, "vncticket" to proxy.ticket)
        session.attach(conn.openWebSocket(target.websocketPath, query, session.listener(user, proxy.ticket)))
        session
    }

    /** VMs ask for a generated VNC password (PVE 7.1+); older servers reject the parameter. */
    private suspend fun proxy(conn: ProxmoxConnection, target: ConsoleTarget): ConsoleProxy {
        if (target.kind != ConsoleKind.VM) return conn.pve { consoleProxy(target.proxyPath, emptyMap()) }
        return try {
            conn.pve { consoleProxy(target.proxyPath, mapOf("websocket" to "1", "generate-password" to "1")) }
        } catch (_: ApiException.Validation) {
            conn.pve { consoleProxy(target.proxyPath, mapOf("websocket" to "1")) }
        }
    }
}
