package io.github.lucabarbalata.anprox.ui.console

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.LocalBottomBarPadding
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.console.ConsoleSession
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.TerminalKey
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.asString
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A VM's screen (noVNC), a container's console or a node shell (xterm.js), using Proxmox's own
 * console pages in a WebView signed in with the app's session. Terminals get a row of keys that
 * phone keyboards lack.
 */
@Composable
fun ConsoleScreen(serverId: String, target: ConsoleTarget, onBack: () -> Unit) {
    val vmKey = "console-$serverId-${target.kind}-${target.node}-${target.vmid}"
    val viewModel: ConsoleViewModel = koinViewModel(key = vmKey) { parametersOf(serverId, target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }

    val kindLabel = stringResource(
        when (target.kind) {
            ConsoleKind.VM -> R.string.console_kind_vm
            ConsoleKind.CONTAINER -> R.string.console_kind_ct
            ConsoleKind.NODE -> R.string.console_kind_node
        },
    )
    val sslRefused = stringResource(R.string.console_ssl_refused)
    AnProxScaffold(
        title = target.name?.takeIf { it.isNotBlank() } ?: target.node,
        subtitle = state.pageTitle ?: kindLabel,
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::reload, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.console_reconnect))
            }
        },
    ) { inner ->
        // Above the bottom bar, or above the keyboard while it is open.
        val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val bottom = maxOf(inner.calculateBottomPadding() + LocalBottomBarPadding.current, ime)
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding(), bottom = bottom),
        ) {
            state.error?.let { error ->
                ErrorBanner(
                    error.asString(),
                    actionLabel = stringResource(R.string.console_reconnect),
                    onAction = viewModel::reload,
                    modifier = Modifier.padding(12.dp),
                )
            }
            val session = state.session
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (session != null) {
                    key(state.generation) {
                        AndroidView(
                            factory = { context ->
                                consoleWebView(
                                    context,
                                    session,
                                    onTitle = viewModel::setPageTitle,
                                    onFailed = { message -> viewModel.pageFailed(if (message == null) UiText.Raw(sslRefused) else UiText.Raw(message)) },
                                ).also { webView = it }
                            },
                            onRelease = { view ->
                                if (webView === view) webView = null
                                view.destroy()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (state.loading) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent, modifier = Modifier.align(Alignment.Center))
                }
            }
            if (target.kind.isTerminal) {
                KeyRow { k -> webView?.evaluateJavascript(k.script(), null) }
            }
        }
    }
}

/** Esc, Tab, Ctrl+C, Ctrl+D and the arrows, which phone keyboards lack. */
@Composable
private fun KeyRow(onKey: (TerminalKey) -> Unit) {
    val c = AnProxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TerminalKey.entries.forEach { k ->
            Text(
                k.label,
                style = MaterialTheme.typography.labelLarge,
                color = c.textPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.glassStrong)
                    .clickable(role = Role.Button) { onKey(k) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The WebView for one console session. The session cookie is set for the server's origin before
 * the page loads. A certificate error is accepted only for that server's own address, and only
 * if the user allowed an insecure certificate for it (same rule as the API client);
 * [onFailed] gets `null` for a refused certificate, or the error text.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun consoleWebView(
    context: Context,
    session: ConsoleSession,
    onTitle: (String?) -> Unit,
    onFailed: (String?) -> Unit,
): WebView = WebView(context).apply {
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    val host = Uri.parse(session.origin).host
    webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) = onTitle(title)
    }
    webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
            if (session.allowInsecureTls && Uri.parse(error.url).host == host) {
                handler.proceed()
            } else {
                handler.cancel()
                onFailed(null)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onFailed(error.description?.toString())
        }
    }
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(this, false)
    cookies.setCookie(session.origin, session.cookie) {
        cookies.flush()
        loadUrl(session.url, session.headers)
    }
}
