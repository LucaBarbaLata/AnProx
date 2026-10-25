package io.github.lucabarbalata.anprox.ui.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.hub.OpenIdStart
import io.github.lucabarbalata.anprox.model.auth.OpenIdCallback
import io.github.lucabarbalata.anprox.ui.common.webViewVersion

/**
 * The identity provider's sign-in page for an OpenID Connect realm, full screen. When the provider
 * sends the browser back to the server's address, [onReturned] gets what it carried, and that
 * address is never loaded. The top bar names the provider's host, so it's clear where the
 * password goes.
 */
@Composable
fun OpenIdSignInDialog(
    start: OpenIdStart,
    title: String,
    allowInsecure: Boolean,
    onReturned: (OpenIdCallback) -> Unit,
    onFailed: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    var loading by remember { mutableStateOf(true) }
    val returned by rememberUpdatedState(onReturned)
    val failed by rememberUpdatedState(onFailed)
    val certificateRejected = stringResource(R.string.login_openid_certificate)
    // Without a WebView provider, creating one would crash the app.
    val hasWebView = remember { webViewVersion() != null }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.surfaceSolid)
                .systemBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) }
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Uri.parse(start.authUrl).host.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
                }
            }
            if (!hasWebView) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.login_openid_no_webview_title), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(stringResource(R.string.login_openid_no_webview_body), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            } else {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent) else Spacer(Modifier.height(4.dp))
                AndroidView(
                    factory = { context ->
                        openIdWebView(
                            context,
                            start,
                            allowInsecure,
                            onLoading = { loading = it },
                            onReturned = { returned(it) },
                            onFailed = { reason -> failed(reason ?: certificateRejected) },
                        )
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * A WebView for the sign-in pages. It watches every navigation of the main frame, redirects
 * included, for the way back to [OpenIdStart.redirectUrl]. [onFailed] gets null for a
 * certificate the app won't accept.
 */
@SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
private fun openIdWebView(
    context: Context,
    start: OpenIdStart,
    allowInsecure: Boolean,
    onLoading: (Boolean) -> Unit,
    onReturned: (OpenIdCallback) -> Unit,
    onFailed: (String?) -> Unit,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    // "Allow insecure certificate" on the server covers its own address and the provider's
    // sign-in host that the server named; any other host must have a valid certificate.
    val trustedHosts = setOf(Uri.parse(start.authUrl).host, Uri.parse(start.redirectUrl).host).filterNotNull().map { it.lowercase() }
    var finished = false

    fun intercept(url: String): Boolean {
        if (finished) return true
        val callback = OpenIdCallback.parse(url, start.redirectUrl) ?: return false
        finished = true
        stopLoading()
        onReturned(callback)
        return true
    }

    fun fail(reason: String?) {
        if (finished) return
        finished = true
        onFailed(reason)
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && intercept(request.url.toString())

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!intercept(url)) onLoading(true)
        }

        override fun onPageFinished(view: WebView, url: String) {
            onLoading(false)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = Uri.parse(error.url).host?.lowercase()
            if (allowInsecure && host in trustedHosts) {
                handler.proceed()
            } else {
                handler.cancel()
                fail(null)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !intercept(request.url.toString())) fail(error.description?.toString() ?: "")
        }
    }
    loadUrl(start.authUrl)
}
