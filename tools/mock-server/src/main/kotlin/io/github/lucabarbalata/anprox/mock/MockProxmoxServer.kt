package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.TicketResponse
import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.AgentResult
import io.github.lucabarbalata.anprox.model.pve.GuestOptions
import io.github.lucabarbalata.anprox.model.pve.SnapshotName
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
        get("nodes/{node}/{type}/{vmid}/config") {
            call.authed() ?: return@get
            val g = call.guest() ?: return@get
            call.ok(state.guestConfig(g))
        }
        put("nodes/{node}/{type}/{vmid}/config") {
            val s = call.authed() ?: return@put
            val g = call.guest() ?: return@put
            val p = call.receiveParameters()
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Config.Options)")
            val error = state.updateGuestConfig(g, p.entries().associate { it.key to it.value.first() })
            if (error != null) call.fail(500, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/qemu/{vmid}/agent/{command}") {
            call.authed() ?: return@get
            val g = call.guest("qemu") ?: return@get
            when (call.parameters["command"]) {
                "network-get-interfaces" -> state.agentInterfaces(g).fold(
                    onSuccess = { call.ok(AgentResult(it)) },
                    onFailure = { call.fail(500, it.message ?: "error") },
                )
                "get-osinfo" -> state.agentOsInfo(g).fold(
                    onSuccess = { call.ok(AgentResult(it)) },
                    onFailure = { call.fail(500, it.message ?: "error") },
                )
                else -> call.fail(501, "the mock server doesn't implement this agent command")
            }
        }
        get("nodes/{node}/lxc/{vmid}/interfaces") {
            call.authed() ?: return@get
            val g = call.guest("lxc") ?: return@get
            state.lxcInterfaces(g).fold(onSuccess = { call.ok(it) }, onFailure = { call.fail(500, it.message ?: "error") })
        }
        get("nodes/{node}/storage") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            call.ok(state.nodeStorages(node, call.request.queryParameters["content"]))
        }
        get("nodes/{node}/storage/{storage}/content") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            val storage = call.parameters["storage"]!!
            val q = call.request.queryParameters
            val items = state.storageContent(node, storage, q["content"], q["vmid"]?.toIntOrNull())
                ?: return@get call.fail(500, "storage '$storage' does not exist")
            call.ok(items)
        }
        post("nodes/{node}/vzdump") {
            val s = call.authed() ?: return@post
            val p = call.receiveParameters()
            val vmid = p["vmid"]?.toIntOrNull()
                ?: return@post call.fail(400, "Parameter verification failed.", mapOf("vmid" to "property is missing and it is not optional"))
            val g = state.guest(vmid)?.takeIf { it.node == call.parameters["node"] }
                ?: return@post call.fail(500, "guest $vmid is not on node '${call.parameters["node"]}'")
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/$vmid, VM.Backup)")
            state.backupNow(g, p["storage"].orEmpty(), p["mode"] ?: "snapshot", p["notes-template"], s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        // Proxmox returns the id as a string.
        get("cluster/nextid") {
            call.authed() ?: return@get
            call.ok(state.nextId().toString())
        }
        post("nodes/{node}/qemu") { call.restore("qemu") }
        post("nodes/{node}/lxc") { call.restore("lxc") }
        post("nodes/{node}/{type}/{vmid}/clone") {
            val s = call.authed() ?: return@post
            val g = call.guest() ?: return@post
            val p = call.receiveParameters().entries().associate { it.key to it.value.first() }
            val newId = p["newid"]?.toIntOrNull()
            if (newId == null || newId < 100) {
                return@post call.fail(400, "Parameter verification failed.", mapOf("newid" to "value must have a minimum value of 100"))
            }
            val nameKey = if (g.type == "qemu") "name" else "hostname"
            p[nameKey]?.let { name ->
                if (!GuestOptions.isValidName(name)) {
                    return@post call.fail(400, "Parameter verification failed.", mapOf(nameKey to "value does not look like a valid DNS name"))
                }
            }
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Clone)")
            state.clone(g, p, s.user).fold(onSuccess = { call.ok(it) }, onFailure = { call.fail(500, it.message ?: "error") })
        }
        post("nodes/{node}/{type}/{vmid}/template") {
            val s = call.authed() ?: return@post
            val g = call.guest() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Allocate)")
            state.convertToTemplate(g, s.user).fold(onSuccess = { call.ok(it) }, onFailure = { call.fail(500, it.message ?: "error") })
        }
        get("nodes/{node}/qemu/{vmid}/migrate") {
            call.authed() ?: return@get
            val g = call.guest("qemu") ?: return@get
            call.ok(state.migrationCheck(g))
        }
        post("nodes/{node}/{type}/{vmid}/migrate") {
            val s = call.authed() ?: return@post
            val g = call.guest() ?: return@post
            val p = call.receiveParameters().entries().associate { it.key to it.value.first() }
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Migrate)")
            state.migrate(g, p, s.user).fold(onSuccess = { call.ok(it) }, onFailure = { call.fail(500, it.message ?: "error") })
        }
        get("nodes/{node}/rrddata") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            val data = state.nodeRrd(node, call.request.queryParameters["timeframe"].orEmpty())
                ?: return@get call.fail(400, "Parameter verification failed.", mapOf("timeframe" to "value must be one of hour, day, week, month, year"))
            call.ok(data)
        }
        get("nodes/{node}/{type}/{vmid}/rrddata") {
            call.authed() ?: return@get
            val g = call.guest() ?: return@get
            if (state.node(g.node)?.online != true) return@get call.fail(595, "Connection refused")
            val data = state.guestRrd(g, call.request.queryParameters["timeframe"].orEmpty())
                ?: return@get call.fail(400, "Parameter verification failed.", mapOf("timeframe" to "value must be one of hour, day, week, month, year"))
            call.ok(data)
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
        get("nodes/{node}/{type}/{vmid}/snapshot") {
            call.authed() ?: return@get
            val g = call.guest() ?: return@get
            call.ok(state.snapshots(g))
        }
        post("nodes/{node}/{type}/{vmid}/snapshot") {
            val s = call.authed() ?: return@post
            val g = call.guest() ?: return@post
            val p = call.receiveParameters()
            val name = p["snapname"].orEmpty()
            when (SnapshotName.validate(name)) {
                null, SnapshotName.Problem.DUPLICATE -> Unit
                else -> return@post call.fail(
                    400, "Parameter verification failed.",
                    mapOf("snapname" to "invalid format - invalid configuration ID '$name'"),
                )
            }
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Snapshot)")
            state.createSnapshot(g, name, p["description"].orEmpty(), p["vmstate"] == "1", s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        post("nodes/{node}/{type}/{vmid}/snapshot/{snapname}/rollback") {
            val s = call.authed() ?: return@post
            val g = call.guest() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Snapshot.Rollback)")
            state.rollbackSnapshot(g, call.parameters["snapname"]!!, s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        delete("nodes/{node}/{type}/{vmid}/snapshot/{snapname}") {
            val s = call.authed() ?: return@delete
            val g = call.guest() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Snapshot)")
            state.deleteSnapshot(g, call.parameters["snapname"]!!, s.user).fold(
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

    /** `POST /nodes/{node}/{qemu|lxc}` — the mock supports only restores (`archive=` / `ostemplate=` + `restore=1`). */
    private suspend fun ApplicationCall.restore(type: String) {
        val s = authed() ?: return
        val p = receiveParameters()
        val vmid = p["vmid"]?.toIntOrNull()
            ?: return fail(400, "Parameter verification failed.", mapOf("vmid" to "property is missing and it is not optional"))
        val volid = (if (type == "qemu") p["archive"] else p["ostemplate"]?.takeIf { p["restore"] == "1" })
            ?: return fail(501, "the mock server can only restore guests, not create new ones")
        if (state.isReadOnly(s.user)) return fail(403, "Permission check failed (/vms/$vmid, VM.Allocate)")
        state.restore(parameters["node"]!!, type, vmid, volid, p["force"] == "1", s.user).fold(
            onSuccess = { ok(it) },
            onFailure = { fail(500, it.message ?: "error") },
        )
    }

    /** The guest named by the path; [type] for routes with a literal `qemu`/`lxc` segment. */
    private suspend fun ApplicationCall.guest(type: String? = null): MockState.Guest? {
        val vmid = parameters["vmid"]?.toIntOrNull()
        val wanted = type ?: parameters["type"]
        val g = vmid?.let { state.guest(it) }
        if (g == null || g.type != wanted || g.node != parameters["node"]) {
            fail(500, "Configuration file 'nodes/${parameters["node"]}/$wanted/$vmid.conf' does not exist")
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
