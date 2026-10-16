package io.github.lucabarbalata.anprox.ui.console

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.console.ConsoleConnection
import io.github.lucabarbalata.anprox.data.console.ConsoleEvent
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.TerminalKey
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

enum class ConsoleStatus { CONNECTING, CONNECTED, CLOSED, FAILED }

data class ConsoleState(
    val target: ConsoleTarget,
    val status: ConsoleStatus = ConsoleStatus.CONNECTING,
    /** VMs: the name the VNC server reports. */
    val desktopName: String? = null,
    /** Why it failed or closed, when known. */
    val message: UiText? = null,
    /** Bumped by Reconnect: the page is created again and opens a new session. */
    val generation: Int = 0,
    /** The page's last JavaScript error, if it had one. */
    val pageError: String? = null,
)

/** What the console details dialog shows, to tell a connection problem from a display problem. */
data class ConsoleDiagnostics(
    val pageLoads: Int,
    val messagesIn: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    /** The latest events: page size, terminal size, VNC state, errors. */
    val events: List<String>,
)

/** Something for the page to do, tagged with the page it is meant for. */
sealed interface PageCommand {
    val page: Int

    /** Terminals: the server accepted the ticket. */
    data class Connected(override val page: Int) : PageCommand

    /** VMs: start noVNC with this password, then the bytes flow. */
    data class Start(override val page: Int, val password: String) : PageCommand

    class Write(override val page: Int, val base64: String) : PageCommand

    data class Closed(override val page: Int) : PageCommand
}

/** Opens the connection a console screen shows: a Proxmox console, or an SSH shell. */
fun interface ConsoleOpener {
    suspend fun open(): ConsoleConnection
}

/**
 * A console (questionnaire Q15). The page (xterm.js or noVNC, bundled in the app) only draws and
 * takes input; this ViewModel owns the connection, which goes through the app's own HTTP client
 * (Proxmox consoles, so certificates, custom headers and failover behave like everywhere else)
 * or its SSH client.
 *
 * The page is created again on rotation. A live terminal keeps its session and the new page gets
 * the recent output; noVNC can't take over a running RFB session, so the VM screen reconnects.
 */
class ConsoleViewModel(
    private val target: ConsoleTarget,
    private val opener: ConsoleOpener,
) : ViewModel() {

    private val ui = MutableStateFlow(ConsoleState(target))
    val state: StateFlow<ConsoleState> = ui.asStateFlow()

    private val commands = Channel<PageCommand>(Channel.UNLIMITED)
    val pageCommands: Flow<PageCommand> = commands.receiveAsFlow()

    /** The page that called [pageReady] last. Commands for older pages are dropped. */
    @Volatile var currentPage = 0
        private set

    @Volatile private var session: ConsoleConnection? = null
    private var job: Job? = null

    /** Terminal output kept for a page created again (main thread only). */
    private val history = ArrayDeque<ByteArray>()
    private var historyBytes = 0

    /** The page has loaded (called from the WebView's bridge thread). */
    fun pageReady() {
        viewModelScope.launch {
            val page = ++currentPage
            note("page $page loaded")
            if (target.kind.isTerminal && session != null && ui.value.status == ConsoleStatus.CONNECTED) {
                commands.send(PageCommand.Connected(page))
                if (history.isNotEmpty()) commands.send(PageCommand.Write(page, encode(history.joined())))
            } else {
                connect()
            }
        }
    }

    /** Drops the current session; the screen creates a new page, which opens a new one. */
    fun reconnect() {
        stop()
        ui.update { it.copy(generation = it.generation + 1, status = ConsoleStatus.CONNECTING, message = null) }
    }

    /** Ends the session without opening another (the SSH screen going back to its form). */
    fun disconnect() {
        stop()
        ui.update { it.copy(status = ConsoleStatus.CLOSED, message = null) }
    }

    private fun stop() {
        job?.cancel()
        job = null
        session?.close()
        session = null
        history.clear()
        historyBytes = 0
    }

    private fun connect() {
        stop()
        ui.update { it.copy(status = ConsoleStatus.CONNECTING, desktopName = null, message = null) }
        job = viewModelScope.launch {
            val opened = try {
                opener.open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note("open failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}")
                ui.update { it.copy(status = ConsoleStatus.FAILED, message = e.toUiText()) }
                return@launch
            }
            session = opened
            opened.events.collect { event ->
                when (event) {
                    ConsoleEvent.Connected -> if (target.kind.isTerminal) {
                        note("connected")
                        ui.update { it.copy(status = ConsoleStatus.CONNECTED) }
                        commands.send(PageCommand.Connected(currentPage))
                    } else {
                        // noVNC does the VNC handshake; it reports "connected" through the bridge.
                        commands.send(PageCommand.Start(currentPage, opened.vncPassword.orEmpty()))
                    }
                    is ConsoleEvent.Output -> {
                        if (messagesIn.incrementAndGet() == 1L) note("first output: ${event.bytes.size} bytes")
                        bytesIn.addAndGet(event.bytes.size.toLong())
                        if (target.kind.isTerminal) keep(event.bytes)
                        commands.send(PageCommand.Write(currentPage, encode(event.bytes)))
                    }
                    is ConsoleEvent.Closed -> {
                        note("closed: ${event.reason ?: "no reason"}")
                        ui.update { s -> s.copy(status = ConsoleStatus.CLOSED, message = event.reason?.let(UiText::Raw) ?: s.message) }
                        commands.send(PageCommand.Closed(currentPage))
                    }
                    is ConsoleEvent.Failed -> {
                        note("failed: ${event.message}")
                        ui.update { it.copy(status = ConsoleStatus.FAILED, message = UiText.Raw(event.message)) }
                    }
                }
            }
        }
    }

    private fun keep(bytes: ByteArray) {
        history.addLast(bytes)
        historyBytes += bytes.size
        while (historyBytes > HISTORY_LIMIT && history.size > 1) historyBytes -= history.removeAt(0).size
    }

    private fun Iterable<ByteArray>.joined(): ByteArray {
        val all = ByteArray(sumOf { it.size })
        var at = 0
        forEach { it.copyInto(all, at); at += it.size }
        return all
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // ---- diagnostics

    private val messagesIn = AtomicLong()
    private val bytesIn = AtomicLong()
    private val bytesOut = AtomicLong()
    private val events = ArrayDeque<String>()

    /** Something worth knowing when the console misbehaves; also written to logcat. */
    fun note(event: String) {
        Log.i(LOG_TAG, "${target.kind} ${target.node}: $event")
        synchronized(events) {
            events.addLast(event)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** A JavaScript error in the page (from the WebView's console). */
    fun pageError(message: String) {
        note("page error: $message")
        ui.update { it.copy(pageError = message) }
    }

    fun diagnostics() = ConsoleDiagnostics(
        pageLoads = currentPage,
        messagesIn = messagesIn.get(),
        bytesIn = bytesIn.get(),
        bytesOut = bytesOut.get(),
        events = synchronized(events) { events.toList() },
    )

    // ---- from the page (any thread)

    fun input(text: String) {
        bytesOut.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong())
        session?.sendInput(text)
    }

    fun resize(cols: Int, rows: Int) {
        if (session != null) note("terminal size ${cols}x$rows")
        session?.resize(cols, rows)
    }

    fun sendVnc(base64: String) {
        val bytes = Base64.getDecoder().decode(base64)
        bytesOut.addAndGet(bytes.size.toLong())
        session?.sendBinary(bytes)
    }

    fun closeFromPage() {
        session?.close()
    }

    /** noVNC's events: `connected`, `desktop` (name), `disconnected`, `failed` (reason). */
    fun vncStatus(state: String, detail: String) {
        note("vnc $state ${detail.take(80)}".trim())
        ui.update {
            when (state) {
                "connected" -> it.copy(status = ConsoleStatus.CONNECTED)
                "desktop" -> it.copy(desktopName = detail.ifBlank { null })
                "disconnected" -> it.copy(status = ConsoleStatus.CLOSED)
                "failed" -> it.copy(status = ConsoleStatus.FAILED, message = UiText.Raw(detail))
                else -> it
            }
        }
    }

    // ---- from the key row

    fun key(key: TerminalKey) {
        session?.sendInput(key.sequence)
    }

    override fun onCleared() {
        job?.cancel()
        session?.close()
    }

    companion object {
        /** Logcat tag for console events and the page's console messages. */
        const val LOG_TAG = "AnProxConsole"
        private const val MAX_EVENTS = 12

        /** Enough for the recent screens and some scrollback after a rotation. */
        private const val HISTORY_LIMIT = 256 * 1024
    }
}
