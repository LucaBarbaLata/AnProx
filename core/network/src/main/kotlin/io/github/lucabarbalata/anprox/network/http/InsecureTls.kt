package io.github.lucabarbalata.anprox.network.http

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Implements the per-server "Allow insecure certificate" switch (questionnaire Q10).
 *
 * When enabled, certificate chain **and** hostname checks are skipped for that server's
 * client only. Traffic is still encrypted, but an attacker on the network path could
 * impersonate the server. docs/security.md explains the trade-off; the UI shows a warning.
 */
internal object InsecureTls {

    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return builder
            .sslSocketFactory(context.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
    }
}
