package io.github.lucabarbalata.anprox.ui.common

import android.webkit.WebView

/**
 * The Android System WebView's version, e.g. "131.0.6778.200", or null when the phone has none
 * (or it is switched off). Creating a WebView then throws, so check this first.
 */
fun webViewVersion(): String? = try {
    WebView.getCurrentWebViewPackage()?.versionName
} catch (_: Exception) {
    null
}
