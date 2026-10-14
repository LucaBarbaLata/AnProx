package io.github.lucabarbalata.anprox.network.http

import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.network.EndpointAttempt
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Retrofit is built against the placeholder [PLACEHOLDER_BASE]. This interceptor rewrites every
 * request to a real endpoint, adds that endpoint's custom headers, and on connection failure
 * moves on to the next endpoint.
 *
 * Safety: GET requests fail over on any I/O error. Requests that change state (POST/PUT/DELETE)
 * only fail over when the failure happened while *connecting* — i.e. the server certainly never
 * received them — so an action is never executed twice.
 */
internal class FailoverInterceptor(private val selector: EndpointSelector) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val attempts = mutableListOf<EndpointAttempt>()
        var untrusted: AllEndpointsFailedException.Untrusted? = null

        for (endpoint in selector.candidates()) {
            val request = rewrite(original, endpoint)
            try {
                val response = chain.proceed(request)
                selector.markGood(endpoint)
                return response
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                attempts += EndpointAttempt(endpoint.url, describe(e))
                if (untrusted == null && isCertificateProblem(e)) {
                    untrusted = AllEndpointsFailedException.Untrusted(endpoint.url, e)
                }
                val mayRetry = original.method == "GET" || isConnectPhase(e)
                if (!mayRetry) throw AllEndpointsFailedException(attempts, untrusted, e)
            }
        }
        throw AllEndpointsFailedException(attempts, untrusted, null)
    }

    private fun rewrite(request: Request, endpoint: Endpoint): Request {
        val base = endpoint.url.toHttpUrl()
        val placeholderPrefix = PLACEHOLDER_BASE.toHttpUrl().encodedPath.trimEnd('/')
        val apiPath = request.url.encodedPath.removePrefix(placeholderPrefix)
        val url: HttpUrl = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + "/api2/json" + apiPath)
            .encodedQuery(request.url.encodedQuery)
            .build()
        return request.newBuilder().url(url).apply {
            endpoint.headers.forEach { (name, value) -> header(name, value) }
        }.build()
    }

    companion object {
        /** Retrofit needs *a* base URL; this one is never contacted. */
        const val PLACEHOLDER_BASE = "https://anprox.invalid/"

        fun isConnectPhase(e: IOException): Boolean = when (e) {
            is ConnectException, is UnknownHostException, is NoRouteToHostException,
            is SSLHandshakeException, is SSLPeerUnverifiedException,
            -> true
            is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
            else -> false
        }

        fun isCertificateProblem(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is CertificateException || t is SSLPeerUnverifiedException) return true
                val msg = t.message.orEmpty()
                if (msg.contains("PKIX") || msg.contains("trust anchor", ignoreCase = true)) return true
                t = t.cause
            }
            return false
        }

        private fun describe(e: IOException): String = when (e) {
            is UnknownHostException -> "unknown host"
            is ConnectException -> "connection refused"
            is NoRouteToHostException -> "no route to host"
            is SocketTimeoutException -> "timed out"
            is SSLHandshakeException, is SSLPeerUnverifiedException ->
                if (isCertificateProblem(e)) "certificate not trusted" else "TLS handshake failed"
            else -> e.message ?: e::class.simpleName.orEmpty()
        }
    }
}

/** Thrown (as an IOException, as OkHttp requires) when no endpoint could be reached. */
internal class AllEndpointsFailedException(
    val attempts: List<EndpointAttempt>,
    val untrusted: Untrusted?,
    cause: IOException?,
) : IOException("All endpoints failed: $attempts", cause) {
    class Untrusted(val url: String, val error: Throwable)
}
