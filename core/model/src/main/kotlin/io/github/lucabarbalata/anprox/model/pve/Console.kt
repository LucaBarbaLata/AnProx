package io.github.lucabarbalata.anprox.model.pve

import java.net.URLEncoder

/** Which of Proxmox's own console pages to open (questionnaire Q15). */
enum class ConsoleKind {
    /** noVNC: the VM's screen. */
    VM,

    /** xterm.js: the container's console. */
    CONTAINER,

    /** xterm.js: a root shell on the node (needs Sys.Console). */
    NODE,
    ;

    val isTerminal: Boolean get() = this != VM
}

data class ConsoleTarget(val kind: ConsoleKind, val node: String, val vmid: Int? = null, val name: String? = null) {

    /**
     * The page the Proxmox web UI opens in its console window, under [baseUrl] (an endpoint URL,
     * possibly with a reverse-proxy sub-path). It needs the `PVEAuthCookie` cookie; Proxmox then
     * embeds the CSRF token itself.
     */
    fun url(baseUrl: String): String {
        val params = buildList {
            when (kind) {
                ConsoleKind.VM -> {
                    add("console" to "kvm")
                    add("novnc" to "1")
                }
                ConsoleKind.CONTAINER -> {
                    add("console" to "lxc")
                    add("xtermjs" to "1")
                }
                ConsoleKind.NODE -> {
                    add("console" to "shell")
                    add("xtermjs" to "1")
                }
            }
            if (kind != ConsoleKind.NODE) {
                add("vmid" to requireNotNull(vmid) { "$kind console needs a vmid" }.toString())
                add("vmname" to name.orEmpty())
            }
            add("node" to node)
            // noVNC scales the VM's screen to the phone instead of showing a cut-off corner.
            if (kind == ConsoleKind.VM) add("resize" to "scale")
            add("cmd" to "")
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "${baseUrl.trimEnd('/')}/?$query"
    }
}

/**
 * Keys a phone keyboard lacks, for the terminal consoles. They are sent to xterm.js as synthetic
 * `keydown` events on its input element, so they reach the shell like real key presses.
 */
enum class TerminalKey(val label: String, val key: String, val keyCode: Int, val ctrl: Boolean = false) {
    ESC("Esc", "Escape", 27),
    TAB("Tab", "Tab", 9),
    CTRL_C("Ctrl+C", "c", 67, ctrl = true),
    CTRL_D("Ctrl+D", "d", 68, ctrl = true),
    UP("↑", "ArrowUp", 38),
    DOWN("↓", "ArrowDown", 40),
    LEFT("←", "ArrowLeft", 37),
    RIGHT("→", "ArrowRight", 39),
    ;

    /** JavaScript for `WebView.evaluateJavascript`; evaluates to `true` if a terminal took the key. */
    fun script(): String =
        """
        (function () {
          var t = document.querySelector('.xterm-helper-textarea');
          if (!t) return false;
          t.focus();
          var e = new KeyboardEvent('keydown', {key: '$key', ctrlKey: $ctrl, bubbles: true, cancelable: true});
          Object.defineProperty(e, 'keyCode', {get: function () { return $keyCode; }});
          Object.defineProperty(e, 'which', {get: function () { return $keyCode; }});
          t.dispatchEvent(e);
          return true;
        })();
        """.trimIndent()
}
