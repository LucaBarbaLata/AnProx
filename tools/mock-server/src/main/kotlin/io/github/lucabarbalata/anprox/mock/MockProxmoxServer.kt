package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.TicketResponse
import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.VersionInfo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.http.Headers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A fake Proxmox VE or Backup Server speaking the real wire format: `/api2/json` paths, ticket
 * cookies, CSRF header checks, `{"data": …}` envelopes, error reasons in the HTTP status line,
 * TFA partial tickets and asynchronous tasks.
 *
 * @param port 0 picks a free port (tests); see [port] after [start].
 * @param tls serve HTTPS with a freshly generated **self-signed** certificate, like a fresh
 *   Proxmox install — the client needs "Allow insecure certificate" to connect.
 * @param pathPrefix serve under a sub-path (e.g. `/pve`) to imitate a reverse proxy.
 */
class MockProxmoxServer(
    val product: ProxmoxProduct = ProxmoxProduct.PVE,
    private val requestedPort: Int = 0,
    private val tls: Boolean = true,
    private val pathPrefix: String = "",
    private val host: String = "127.0.0.1",
    taskDurationMillis: Long = 0,
) {
    val state = MockState(product, taskDurationMillis)
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    var port: Int = -1
        private set

    /** Headers of the most recent request — lets tests check per-endpoint custom headers. */
    @Volatile
    var lastRequestHeaders: Headers? = null
        private set

    /** Base URL to put in a server profile, e.g. `https://127.0.0.1:43123/pve`. */
    val baseUrl: String get() = "${if (tls) "https" else "http"}://$host:$port$pathPrefix"

    fun start(): MockProxmoxServer {
        val keyStore = buildKeyStore {
            certificate(KEY_ALIAS) {
                password = KEY_PASSWORD
                domains = listOf("localhost", "127.0.0.1", "10.0.2.2")
            }
        }
        val s = embeddedServer(
            Netty,
            configure = {
                if (tls) {
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = KEY_ALIAS,
                        keyStorePassword = { KEY_PASSWORD.toCharArray() },
                        privateKeyPassword = { KEY_PASSWORD.toCharArray() },
                    ) {
                        port = requestedPort
                        host = this@MockProxmoxServer.host
                    }
                } else {
                    connector {
                        port = requestedPort
                        host = this@MockProxmoxServer.host
                    }
                }
            },
            module = { routes() },
        )
        s.start(wait = false)
        port = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
        return this
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        server = null
    }

    // ------------------------------------------------------------------ routing

    private fun Application.routes() {
        install(createApplicationPlugin("HeaderRecorder") { onCall { lastRequestHeaders = it.request.headers } })
        routing {
            route("$pathPrefix/api2/json") {
                access()
                when (product) {
                    ProxmoxProduct.PVE -> pve()
                    ProxmoxProduct.PBS -> pbs()
                }
            }
        }
    }

    private fun Route.access() {
        get("access/domains") { call.ok(state.realms) }

        post("access/ticket") {
            val p = call.receiveParameters()
            val username = p["username"].orEmpty().let { u -> if ('@' in u) u else "$u@${p["realm"] ?: "pam"}" }
            val password = p["password"].orEmpty()
            if (product == ProxmoxProduct.PBS && p["new-format"] != null) {
                return@post call.fail(400, "Parameter verification failed.", mapOf("new-format" to "schema does not allow additional properties"))
            }
            when (val r = state.login(username, password, p["tfa-challenge"])) {
                is MockState.LoginOutcome.Ok -> call.ok(TicketResponse(username = r.user, ticket = r.ticket, csrfToken = r.csrf, clusterName = if (product == ProxmoxProduct.PVE) state.clusterName else null))
                is MockState.LoginOutcome.NeedTfa -> call.ok(TicketResponse(username = r.user, ticket = r.partialTicket, needTfa = true))
                MockState.LoginOutcome.Failed -> call.fail(401, "authentication failure")
            }
        }

        get("version") {
            call.authed() ?: return@get
            call.ok(
                when (product) {
                    ProxmoxProduct.PVE -> VersionInfo("9.0.10", "9.0", "deadbeef")
                    ProxmoxProduct.PBS -> VersionInfo("4.0.14", "4.0", "cafebabe")
                },
            )
        }
    }

    private fun Route.pve() {
        get("cluster/status") {
            call.authed() ?: return@get
            call.ok(state.clusterStatus())
        }
        get("cluster/resources") {
            call.authed() ?: return@get
            call.ok(state.resources(call.request.queryParameters["type"]))
        }
        get("cluster/tasks") {
            call.authed() ?: return@get
            call.ok(state.recentTasks())
        }
        get("nodes/{node}/status") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.nodeStatus(node))
        }
        get("nodes/{node}/{type}/{vmid}/status/current") {
            call.authed() ?: return@get
            val g = call.guest() ?: return@get
            call.ok(state.guestStatus(g))
        }
        post("nodes/{node}/{type}/{vmid}/status/{action}") {
            val s = call.authed() ?: return@post
            val g = call.guest() ?: return@post
            if (state.isReadOnly(s.user)) {
                return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.PowerMgmt)")
            }
            state.power(g, call.parameters["action"]!!, s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        get("nodes/{node}/tasks/{upid}/status") {
            call.authed() ?: return@get
            val st = state.taskStatus(call.parameters["upid"]!!) ?: return@get call.fail(404, "no such task")
            call.ok(st)
        }
        get("nodes/{node}/tasks/{upid}/log") {
            call.authed() ?: return@get
            val log = state.taskLog(call.parameters["upid"]!!) ?: return@get call.fail(404, "no such task")
            call.ok(log)
        }
    }

    private fun Route.pbs() {
        get("status/datastore-usage") {
            call.authed() ?: return@get
            call.ok(state.datastoreUsage())
        }
        get("admin/datastore") {
            call.authed() ?: return@get
            call.ok(state.datastores)
        }
        get("nodes/localhost/tasks") {
            call.authed() ?: return@get
            call.ok(state.recentTasks(call.request.queryParameters["limit"]?.toIntOrNull() ?: 50))
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Checks the ticket cookie (and CSRF header for writes). Responds 401 and returns null if invalid. */
    private suspend fun ApplicationCall.authed(): MockState.Session? {
        val ticket = request.cookies.rawCookies[product.cookieName]
        val session = state.session(ticket)
        if (session == null) {
            fail(401, "No ticket")
            return null
        }
        if (request.httpMethod.value != "GET" && request.headers["CSRFPreventionToken"] != session.csrf) {
            fail(401, "Permission denied - invalid csrf token")
            return null
        }
        return session
    }

    private suspend fun ApplicationCall.guest(): MockState.Guest? {
        val vmid = parameters["vmid"]?.toIntOrNull()
        val g = vmid?.let { state.guest(it) }
        if (g == null || g.type != parameters["type"] || g.node != parameters["node"]) {
            fail(500, "Configuration file 'nodes/${parameters["node"]}/${parameters["type"]}/$vmid.conf' does not exist")
            return null
        }
        return g
    }

    private suspend inline fun <reified T> ApplicationCall.ok(data: T) {
        respondText(ProxmoxJson.encodeToString(ApiEnvelope(data)), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.fail(code: Int, reason: String, errors: Map<String, String>? = null) {
        val body = buildJsonObject {
            put("data", JsonNull)
            put("message", "$reason\n")
            if (errors != null) putJsonObject("errors") { errors.forEach { (k, v) -> put(k, v) } }
        }
        respondText(body.toString(), ContentType.Application.Json, HttpStatusCode(code, reason))
    }

    companion object {
        private const val KEY_ALIAS = "anprox-mock"
        private const val KEY_PASSWORD = "anprox-mock"
    }
}
