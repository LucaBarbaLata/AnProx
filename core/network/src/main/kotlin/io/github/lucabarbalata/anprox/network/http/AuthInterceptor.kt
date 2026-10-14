package io.github.lucabarbalata.anprox.network.http

import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.AuthSession
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the authentication ticket cookie to every request and, for anything that is not a GET,
 * the `CSRFPreventionToken` header. That is exactly what the Proxmox web UI does.
 */
internal class AuthInterceptor(
    private val product: ProxmoxProduct,
    private val session: () -> AuthSession?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val s = session() ?: return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("Cookie", "${product.cookieName}=${s.ticket}")
            .apply { if (chain.request().method != "GET") header("CSRFPreventionToken", s.csrfToken) }
            .build()
        return chain.proceed(request)
    }
}
