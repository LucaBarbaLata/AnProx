package io.github.lucabarbalata.anprox.network

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.AuthSession
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.auth.TfaChallenge
import io.github.lucabarbalata.anprox.model.auth.TfaKind
import io.github.lucabarbalata.anprox.model.auth.TicketResponse
import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.VersionInfo
import io.github.lucabarbalata.anprox.network.api.AccessApi
import io.github.lucabarbalata.anprox.network.api.PbsApi
import io.github.lucabarbalata.anprox.network.api.PveApi
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import io.github.lucabarbalata.anprox.network.http.AllEndpointsFailedException
import io.github.lucabarbalata.anprox.network.http.AuthInterceptor
import io.github.lucabarbalata.anprox.network.http.EndpointSelector
import io.github.lucabarbalata.anprox.network.http.FailoverInterceptor
import io.github.lucabarbalata.anprox.network.http.InsecureTls
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Everything needed to talk to **one** saved server: its HTTP client (with that server's TLS
 * setting and endpoint failover), its login session, and typed access to its API.
 *
 * Typical use:
 * ```
 * val conn = ProxmoxConnection(profile)
 * when (val r = conn.login(password)) {
 *     is LoginResult.Success -> …
 *     is LoginResult.NeedsTfa -> conn.completeTfa(r.partialTicket, TfaKind.TOTP, "123456")
 * }
 * val resources = conn.pve { clusterResources(null) }
 * ```
 *
 * @param baseClient share one OkHttpClient across connections so they share a connection pool.
 * @param clock epoch seconds; injectable for tests.
 */
class ProxmoxConnection(
    val profile: ServerProfile,
    baseClient: OkHttpClient = DEFAULT_BASE_CLIENT,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    userAgent: String = "AnProx",
    httpLog: ((String) -> Unit)? = null,
) {
    val product: ProxmoxProduct get() = profile.product

    /** Which endpoint is used; call [EndpointSelector.reset] when the device network changes. */
    val endpoints = EndpointSelector(profile.endpoints)

    @Volatile
    var session: AuthSession? = null
        private set

    /**
     * Called whenever the session changes (login, renewal, logout) so the app can persist it
     * (encrypted) and survive restarts without asking for the password again.
     */
    @Volatile
    var onSessionChanged: ((AuthSession?) -> Unit)? = null

    private val renewLock = Mutex()

    val httpClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply { if (profile.allowInsecureTls) InsecureTls.apply(this) }
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .addInterceptor(FailoverInterceptor(endpoints))
        .addInterceptor(AuthInterceptor(profile.product) { session })
        .apply {
            // BASIC logs method, URL, status and timing only — never headers, so tickets don't leak.
            if (httpLog != null) addInterceptor(HttpLoggingInterceptor(httpLog).setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(FailoverInterceptor.PLACEHOLDER_BASE)
        .client(httpClient)
        .addConverterFactory(ProxmoxJson.asConverterFactory("application/json".toMediaType()))
        .build()

    private val accessApi: AccessApi = retrofit.create(AccessApi::class.java)
    private val pveApi: PveApi = retrofit.create(PveApi::class.java)
    private val pbsApi: PbsApi = retrofit.create(PbsApi::class.java)

    // ---------------------------------------------------------------- authentication

    /** Realms for the login screen. Works without being logged in. */
    suspend fun realms(): List<Realm> = execute(renew = false) { accessApi.realms() }

    /** First login step. Stores the session on success. */
    suspend fun login(password: String): LoginResult {
        val response = ticketRequest(mapOf("username" to profile.userId, "password" to password))
        return if (response.needTfa || TfaChallenge.isPartialTicket(response.ticket)) {
            LoginResult.NeedsTfa(response.ticket, TfaChallenge.fromTicket(response.ticket))
        } else {
            LoginResult.Success(adopt(response))
        }
    }

    /** Second login step: answer the challenge from [LoginResult.NeedsTfa]. */
    suspend fun completeTfa(partialTicket: String, kind: TfaKind, answer: String): AuthSession {
        val response = ticketRequest(
            mapOf(
                "username" to profile.userId,
                "tfa-challenge" to partialTicket,
                "password" to kind.encode(answer),
            ),
        )
        if (TfaChallenge.isPartialTicket(response.ticket)) throw ApiException.Unauthorized("Second factor rejected")
        return adopt(response)
    }

    /** Re-use a session saved earlier (e.g. after the app restarted). Ignored if it expired. */
    fun restore(saved: AuthSession?) {
        session = saved?.takeUnless { it.isExpired(clock()) }
    }

    /** Forgets the session locally. (Proxmox tickets cannot be revoked server-side.) */
    fun logout() {
        session = null
        onSessionChanged?.invoke(null)
    }

    /**
     * Swap the current ticket for a fresh one (tickets live 2h). Called automatically before
     * requests once a ticket is older than an hour; safe to call concurrently.
     */
    suspend fun renewSession(): AuthSession = renewLock.withLock {
        val current = session ?: throw ApiException.Unauthorized("Not logged in")
        if (!current.needsRenewal(clock())) return current
        val response = execute(renew = false) {
            accessApi.ticket(mapOf("username" to current.username, "password" to current.ticket))
        }
        adopt(response)
    }

    private suspend fun ticketRequest(fields: Map<String, String>): TicketResponse {
        if (product != ProxmoxProduct.PVE) return execute(renew = false) { accessApi.ticket(fields) }
        // PVE 7/8 want `new-format=1` for the modern TFA flow; newer releases may drop the
        // parameter, so retry without it if the server rejects it.
        return try {
            execute(renew = false) { accessApi.ticket(fields + ("new-format" to "1")) }
        } catch (e: ApiException.Validation) {
            if (!e.fieldErrors.containsKey("new-format")) throw e
            execute(renew = false) { accessApi.ticket(fields) }
        }
    }

    private fun adopt(response: TicketResponse): AuthSession {
        val csrf = response.csrfToken ?: throw ApiException.Protocol("Login response had no CSRF token")
        return AuthSession(
            username = response.username,
            ticket = response.ticket,
            csrfToken = csrf,
            issuedAtEpochSeconds = clock(),
            clusterName = response.clusterName,
        ).also {
            session = it
            onSessionChanged?.invoke(it)
        }
    }

    // ---------------------------------------------------------------- API access

    suspend fun version(): VersionInfo = execute { accessApi.version() }

    /** Run a call against the PVE API. Throws [ApiException] on any failure. */
    suspend fun <T> pve(call: suspend PveApi.() -> ApiEnvelope<T>): T {
        check(product == ProxmoxProduct.PVE) { "${profile.name} is not a Proxmox VE server" }
        return execute { pveApi.call() }
    }

    /** Like [pve] for calls that answer `{"data": null}` on success, such as `PUT …/config`. */
    suspend fun pveNoData(call: suspend PveApi.() -> ApiEnvelope<*>) {
        check(product == ProxmoxProduct.PVE) { "${profile.name} is not a Proxmox VE server" }
        envelope { pveApi.call() }
    }

    /** Like [pve], with the whole envelope (for `total` of a paged log). */
    suspend fun <T> pveEnvelope(call: suspend PveApi.() -> ApiEnvelope<T>): ApiEnvelope<T> {
        check(product == ProxmoxProduct.PVE) { "${profile.name} is not a Proxmox VE server" }
        return envelope { pveApi.call() }
    }

    /** Like [pve] for calls whose `data` may be `null`, such as converting a container to a template. */
    suspend fun <T> pveOrNull(call: suspend PveApi.() -> ApiEnvelope<T>): T? {
        check(product == ProxmoxProduct.PVE) { "${profile.name} is not a Proxmox VE server" }
        return envelope { pveApi.call() }.data
    }

    /**
     * Opens a WebSocket to the API path [apiPath] (e.g. `nodes/pve1/vncwebsocket`) through this
     * connection's client: the same address failover, custom headers, certificate setting and
     * session cookie as every API call. Proxmox's console sockets speak the `binary` subprotocol.
     */
    fun openWebSocket(apiPath: String, query: Map<String, String>, listener: WebSocketListener): WebSocket {
        val url = FailoverInterceptor.PLACEHOLDER_BASE.toHttpUrl().newBuilder()
            .addPathSegments(apiPath.trimStart('/'))
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "binary").build()
        return httpClient.newWebSocket(request, listener)
    }

    /** Run a call against the PBS API. Throws [ApiException] on any failure. */
    suspend fun <T> pbs(call: suspend PbsApi.() -> ApiEnvelope<T>): T {
        check(product == ProxmoxProduct.PBS) { "${profile.name} is not a Proxmox Backup Server" }
        return execute { pbsApi.call() }
    }

    /** Like [pbs] for calls that answer `{"data": null}` on success. */
    suspend fun pbsNoData(call: suspend PbsApi.() -> ApiEnvelope<*>) {
        check(product == ProxmoxProduct.PBS) { "${profile.name} is not a Proxmox Backup Server" }
        envelope { pbsApi.call() }
    }

    /**
     * A PBS call whose answer isn't a `{"data": …}` envelope, such as a file download. The caller
     * must close what it gets (a [okhttp3.ResponseBody]).
     */
    suspend fun <T> pbsRaw(call: suspend PbsApi.() -> T): T {
        check(product == ProxmoxProduct.PBS) { "${profile.name} is not a Proxmox Backup Server" }
        val s = session
        if (s != null && s.needsRenewal(clock()) && !s.isExpired(clock())) renewSession()
        return translateErrors { pbsApi.call() }
    }

    /** Polls a task until it stops, on either product (a Backup Server is always `localhost`). */
    suspend fun awaitTask(node: String, upid: String, pollMillis: Long = 1_000): TaskStatus {
        while (true) {
            val status = if (product == ProxmoxProduct.PBS) pbs { taskStatus(upid) } else pve { taskStatus(node, upid) }
            if (!status.isRunning) return status
            delay(pollMillis)
        }
    }

    /** Polls a PVE task until it stops. */
    suspend fun awaitPveTask(node: String, upid: String, pollMillis: Long = 1_000): TaskStatus {
        while (true) {
            val status = pve { taskStatus(node, upid) }
            if (!status.isRunning) return status
            delay(pollMillis)
        }
    }

    private suspend fun <T> execute(renew: Boolean = true, call: suspend () -> ApiEnvelope<T>): T =
        envelope(renew, call).data ?: throw ApiException.Protocol("The server returned no data")

    /** Renews the ticket when due, runs [call] and turns failures into [ApiException]s. */
    private suspend fun <T> envelope(renew: Boolean = true, call: suspend () -> ApiEnvelope<T>): ApiEnvelope<T> {
        if (renew) {
            val s = session
            if (s != null && s.needsRenewal(clock()) && !s.isExpired(clock())) renewSession()
        }
        return translateErrors { call() }
    }

    private inline fun <R> translateErrors(block: () -> R): R = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: HttpException) {
        throw httpError(e)
    } catch (e: AllEndpointsFailedException) {
        e.untrusted?.let { throw ApiException.UntrustedCertificate(it.url, it.error) }
        throw ApiException.Unreachable(e.attempts)
    } catch (e: SerializationException) {
        throw ApiException.Protocol("Unexpected response: ${e.message}", e)
    } catch (e: IOException) {
        throw ApiException.Unreachable(listOf(EndpointAttempt(endpoints.active?.url ?: "?", e.message ?: "I/O error")))
    }

    private fun httpError(e: HttpException): ApiException {
        val response = e.response()
        val reason = response?.message().orEmpty().ifBlank { e.message() }
        val body = runCatching { response?.errorBody()?.string() }.getOrNull()
        val envelope = body?.let {
            runCatching { ProxmoxJson.decodeFromString<ApiEnvelope<JsonElement>>(it) }.getOrNull()
        }
        val message = (envelope?.message?.takeIf { it.isNotBlank() } ?: reason).trim()
        return when (e.code()) {
            400 -> ApiException.Validation(message, envelope?.errors.orEmpty())
            401 -> ApiException.Unauthorized(message)
            403 -> ApiException.Forbidden(message)
            404 -> ApiException.NotFound(message)
            else -> ApiException.Server(e.code(), message)
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 30L

        /** Shared pool/dispatcher for all connections. */
        val DEFAULT_BASE_CLIENT: OkHttpClient by lazy { OkHttpClient() }
    }
}
