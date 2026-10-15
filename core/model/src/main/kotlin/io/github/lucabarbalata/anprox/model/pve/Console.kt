package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Which console a screen shows (questionnaire Q15). */
enum class ConsoleKind {
    /** The VM's screen over VNC (noVNC in the app). */
    VM,

    /** The container's console as a terminal (xterm.js in the app). */
    CONTAINER,

    /** A root shell on the node as a terminal (needs Sys.Console). */
    NODE,
    ;

    val isTerminal: Boolean get() = this != VM
}

/**
 * A console to open. The app talks to Proxmox itself, like the web UI's console window does:
 * first [proxyPath] hands out a one-time ticket and port, then [websocketPath] carries the
 * session. Going through the app's own HTTP client (rather than letting a WebView connect)
 * means self-signed certificates, custom headers and failover work exactly as for the API.
 */
data class ConsoleTarget(
    val kind: ConsoleKind,
    val node: String,
    val vmid: Int? = null,
    val name: String? = null,
    /** Node shells only: what to run instead of a login shell (`upgrade` runs `apt dist-upgrade`). */
    val command: String? = null,
) {

    private val guestPath: String
        get() = requireNotNull(vmid) { "$kind console needs a vmid" }.let { id ->
            if (kind == ConsoleKind.VM) "nodes/$node/qemu/$id" else "nodes/$node/lxc/$id"
        }

    /** `POST`: `vncproxy` for VMs, `termproxy` for containers and nodes. */
    val proxyPath: String
        get() = when (kind) {
            ConsoleKind.VM -> "$guestPath/vncproxy"
            ConsoleKind.CONTAINER -> "$guestPath/termproxy"
            ConsoleKind.NODE -> "nodes/$node/termproxy"
        }

    /** `GET` upgraded to a WebSocket, with `port` and `vncticket` from the proxy call. */
    val websocketPath: String
        get() = if (kind == ConsoleKind.NODE) "nodes/$node/vncwebsocket" else "$guestPath/vncwebsocket"
}

/**
 * Answer of `vncproxy` / `termproxy`. Proxmox sends the port as a number or a string depending on
 * the version, so it is kept as a primitive.
 *
 * @property password VMs only, with `generate-password=1` (PVE 7.1+): the VNC password. Older
 *   servers use the [ticket] as the password.
 */
@Serializable
data class ConsoleProxy(
    val port: JsonPrimitive,
    val ticket: String,
    val user: String? = null,
    val upid: String? = null,
    val password: String? = null,
) {
    val portNumber: String get() = port.contentOrNull.orEmpty()
    val vncPassword: String get() = password ?: ticket
}

/**
 * Proxmox's terminal protocol over the WebSocket (the one its xterm.js page speaks):
 * - the client first sends `user:ticket\n`; the server answers `OK`, then the terminal output;
 * - input: `0:<UTF-8 byte length>:<data>`;
 * - resize: `1:<cols>:<rows>:`;
 * - keep-alive: `2`, every 30 seconds.
 */
object TermProxyProtocol {
    const val PING = "2"
    const val PING_INTERVAL_MILLIS = 30_000L

    fun auth(user: String, ticket: String): String = "$user:$ticket\n"

    fun input(data: String): String = "0:${data.toByteArray(Charsets.UTF_8).size}:$data"

    fun resize(cols: Int, rows: Int): String = "1:$cols:$rows:"

    /** Whether the first message accepts the session; anything else means the ticket was refused. */
    fun isAccepted(first: ByteArray): Boolean = first.size >= 2 && first[0] == 'O'.code.toByte() && first[1] == 'K'.code.toByte()
}

/** Keys phone keyboards lack, for terminals: sent as the bytes a real keyboard would produce. */
enum class TerminalKey(val label: String, val sequence: String) {
    ESC("Esc", "\u001b"),
    TAB("Tab", "\t"),
    CTRL_C("Ctrl+C", "\u0003"),
    CTRL_D("Ctrl+D", "\u0004"),
    UP("↑", "\u001b[A"),
    DOWN("↓", "\u001b[B"),
    LEFT("←", "\u001b[D"),
    RIGHT("→", "\u001b[C"),
}

/** Keys phone keyboards lack, for the VM screen: X11 keysyms sent through noVNC. */
enum class VncKey(val label: String, val keysym: Int) {
    ESC("Esc", 0xff1b),
    TAB("Tab", 0xff09),
    UP("↑", 0xff52),
    DOWN("↓", 0xff54),
    LEFT("←", 0xff51),
    RIGHT("→", 0xff53),
    ENTER("Enter", 0xff0d),
}
