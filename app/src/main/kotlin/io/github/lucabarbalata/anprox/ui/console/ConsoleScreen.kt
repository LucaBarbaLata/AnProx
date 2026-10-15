package io.github.lucabarbalata.anprox.ui.console

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.LocalBottomBarPadding
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.TerminalKey
import io.github.lucabarbalata.anprox.model.pve.VncKey
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.asString
import org.json.JSONObject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The bundled pages use Chrome 80 features; older Android System WebView versions can't run them. */
private const val MIN_WEBVIEW_MAJOR = 80

/**
 * A VM's screen (noVNC) or a container / node terminal (xterm.js). Both libraries are bundled in
 * the app (`assets/console/`, built from `tools/console-web/`); the connection to Proxmox goes
 * through [ConsoleViewModel], not through the WebView.
 */
@Composable
fun ConsoleScreen(serverId: String, target: ConsoleTarget, onBack: () -> Unit) {
    val vmKey = "console-$serverId-${target.kind}-${target.node}-${target.vmid}"
    val viewModel: ConsoleViewModel = koinViewModel(key = vmKey) { parametersOf(serverId, target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val webViewVersion = remember { webViewVersion() }
    val supported = (webViewVersion?.substringBefore('.')?.toIntOrNull() ?: 0) >= MIN_WEBVIEW_MAJOR
    val terminal = target.kind.isTerminal

    val closedText = stringResource(R.string.console_closed)
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        viewModel.pageCommands.collect { command ->
            if (command.page != viewModel.currentPage) return@collect
            val js = when (command) {
                is PageCommand.Connected -> "anprox.connected()"
                is PageCommand.Start -> "anprox.start(${JSONObject.quote(command.password)});anprox.opened()"
                is PageCommand.Write -> "anprox.write('${command.base64}')"
                is PageCommand.Closed -> if (terminal) "anprox.closed(${JSONObject.quote(closedText)})" else "anprox.closed()"
            }
            view.evaluateJavascript(js, null)
        }
    }

    AnProxScaffold(
        title = target.name?.takeIf { it.isNotBlank() } ?: target.node,
        subtitle = statusText(state, target.kind),
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::reconnect, enabled = supported) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.console_reconnect))
            }
        },
    ) { inner ->
        // Above the bottom bar, or above the keyboard while it is open.
        val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottom = maxOf(inner.calculateBottomPadding() + LocalBottomBarPadding.current, ime)
        Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding(), bottom = bottom)) {
            if (!supported) {
                EmptyState(
                    Icons.Outlined.Keyboard,
                    stringResource(R.string.console_webview_old_title),
                    stringResource(R.string.console_webview_old_body, MIN_WEBVIEW_MAJOR, webViewVersion ?: "—"),
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }
            if (state.status == ConsoleStatus.FAILED || state.status == ConsoleStatus.CLOSED) {
                ErrorBanner(
                    state.message?.asString() ?: stringResource(R.string.console_closed),
                    actionLabel = stringResource(R.string.console_reconnect),
                    onAction = viewModel::reconnect,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(state.generation) {
                    AndroidView(
                        factory = { context ->
                            consoleWebView(context, if (terminal) "terminal" else "vnc", ConsoleBridge(viewModel)).also { webView = it }
                        },
                        onRelease = { view ->
                            if (webView === view) webView = null
                            view.destroy()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (state.status == ConsoleStatus.CONNECTING) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent, modifier = Modifier.align(Alignment.Center))
                }
            }
            KeyRow(
                terminal = terminal,
                onKeyboard = { webView?.showKeyboard() },
                onTerminalKey = viewModel::key,
                onVncKey = { k -> webView?.evaluateJavascript("anprox.key(${k.keysym})", null) },
                onCtrlAltDel = { webView?.evaluateJavascript("anprox.ctrlAltDel()", null) },
            )
        }
    }
}

@Composable
private fun statusText(state: ConsoleState, kind: ConsoleKind): String = when (state.status) {
    ConsoleStatus.CONNECTING -> stringResource(R.string.console_connecting)
    ConsoleStatus.CONNECTED -> state.desktopName?.let { stringResource(R.string.console_connected_desktop, it) }
        ?: stringResource(R.string.console_connected)
    ConsoleStatus.CLOSED -> stringResource(R.string.console_closed)
    ConsoleStatus.FAILED -> stringResource(
        when (kind) {
            ConsoleKind.VM -> R.string.console_failed_vm
            ConsoleKind.CONTAINER -> R.string.console_failed_ct
            ConsoleKind.NODE -> R.string.console_failed_node
        },
    )
}

/** The keyboard button, then the keys phone keyboards lack. */
@Composable
private fun KeyRow(
    terminal: Boolean,
    onKeyboard: () -> Unit,
    onTerminalKey: (TerminalKey) -> Unit,
    onVncKey: (VncKey) -> Unit,
    onCtrlAltDel: () -> Unit,
) {
    val c = AnProxTheme.colors
    val keyboard = stringResource(R.string.console_keyboard)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.accent.copy(alpha = 0.22f))
                .clickable(role = Role.Button, onClickLabel = keyboard, onClick = onKeyboard)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.Keyboard, contentDescription = keyboard, tint = c.textPrimary, modifier = Modifier.size(22.dp))
        }
        if (terminal) {
            TerminalKey.entries.forEach { k -> KeyChip(k.label) { onTerminalKey(k) } }
        } else {
            VncKey.entries.forEach { k -> KeyChip(k.label) { onVncKey(k) } }
            KeyChip(stringResource(R.string.console_ctrl_alt_del), onCtrlAltDel)
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    val c = AnProxTheme.colors
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = c.textPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.glassStrong)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** What the bundled page calls (on the WebView's bridge thread). */
private class ConsoleBridge(private val viewModel: ConsoleViewModel) {
    @JavascriptInterface
    fun ready() {
        viewModel.pageReady()
    }

    @JavascriptInterface
    fun input(text: String) {
        viewModel.input(text)
    }

    @JavascriptInterface
    fun resize(cols: Int, rows: Int) {
        viewModel.resize(cols, rows)
    }

    @JavascriptInterface
    fun send(base64: String) {
        viewModel.sendVnc(base64)
    }

    @JavascriptInterface
    fun close() {
        viewModel.closeFromPage()
    }

    @JavascriptInterface
    fun status(state: String, detail: String) {
        viewModel.vncStatus(state, detail)
    }
}

/**
 * Loads a bundled console page. It never leaves the app's assets: links are not followed and
 * the page has no network access of its own; everything goes through [ConsoleBridge].
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun consoleWebView(context: Context, page: String, bridge: ConsoleBridge): WebView = WebView(context).apply {
    setBackgroundColor(android.graphics.Color.rgb(0x0b, 0x0f, 0x1a))
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.textZoom = 100
    settings.setSupportZoom(false)
    isFocusable = true
    isFocusableInTouchMode = true
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
    }
    addJavascriptInterface(bridge, "AnProxNative")
    loadUrl("file:///android_asset/console/$page.html")
}

/** Focuses the page's input and asks Android to show the keyboard. */
private fun WebView.showKeyboard() {
    evaluateJavascript("anprox.focus()") {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}

/** e.g. "131.0.6778.200", or null if there is no WebView provider. */
private fun webViewVersion(): String? = try {
    WebView.getCurrentWebViewPackage()?.versionName
} catch (_: Exception) {
    null
}
