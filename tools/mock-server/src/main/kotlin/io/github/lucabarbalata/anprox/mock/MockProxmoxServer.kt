package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.ApiEnvelope
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.TicketResponse
import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.AgentResult
import io.github.lucabarbalata.anprox.model.pve.CephHealth
import io.github.lucabarbalata.anprox.model.pve.CephStatus
import io.github.lucabarbalata.anprox.model.pve.GuestOptions
import io.github.lucabarbalata.anprox.model.pve.SnapshotName
import io.github.lucabarbalata.anprox.model.pve.VersionInfo
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.toByteArray
import io.ktor.websocket.close
import io.ktor.websocket.CloseReason
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.WebSockets
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
import io.ktor.server.response.respondBytes
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        install(WebSockets)
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
        get("access/domains") { call.ok(if (product == ProxmoxProduct.PVE) state.access.realmList() else state.realms) }

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

    /** `/access/…` administration (milestone M7). Changes need a user who isn't read-only. */
    private fun Route.sdn() {
        val sdn = state.sdn
        suspend fun ApplicationCall.params(): Map<String, String> = receiveParameters().let { p -> p.names().associateWith { p[it].orEmpty() } }
        suspend fun ApplicationCall.writer(): MockState.Session? {
            val s = authed() ?: return null
            if (state.isReadOnly(s.user)) {
                fail(403, "Permission check failed (/sdn, SDN.Allocate)")
                return null
            }
            return s
        }
        suspend fun ApplicationCall.done(error: String?) = if (error != null) fail(400, error) else ok(JsonNull)

        for (kind in listOf("zones", "vnets", "controllers", "ipams", "dns")) {
            get("cluster/sdn/$kind") {
                call.authed() ?: return@get
                val pending = call.request.queryParameters["pending"]
                // Like Proxmox: IPAMs and DNS servers have no pending state, and refuse the parameter.
                if (pending != null && kind in setOf("ipams", "dns")) return@get call.fail(400, "Parameter verification failed.", mapOf("pending" to "property is not defined in schema and the schema does not allow additional properties"))
                call.ok(sdn.list(kind, pending == "1") ?: JsonArray(emptyList()))
            }
            post("cluster/sdn/$kind") {
                call.writer() ?: return@post
                call.done(sdn.create(kind, call.params()))
            }
            put("cluster/sdn/$kind/{id}") {
                call.writer() ?: return@put
                call.done(sdn.update(kind, call.parameters["id"]!!, call.params()))
            }
            delete("cluster/sdn/$kind/{id}") {
                call.writer() ?: return@delete
                call.done(sdn.delete(kind, call.parameters["id"]!!))
            }
        }
        get("cluster/sdn/vnets/{vnet}/subnets") {
            call.authed() ?: return@get
            call.ok(sdn.list("subnets", call.request.queryParameters["pending"] == "1", call.parameters["vnet"]!!) ?: JsonArray(emptyList()))
        }
        post("cluster/sdn/vnets/{vnet}/subnets") {
            call.writer() ?: return@post
            call.done(sdn.createSubnet(call.parameters["vnet"]!!, call.params()))
        }
        put("cluster/sdn/vnets/{vnet}/subnets/{subnet}") {
            call.writer() ?: return@put
            call.done(sdn.updateSubnet(call.parameters["vnet"]!!, call.parameters["subnet"]!!, call.params()))
        }
        delete("cluster/sdn/vnets/{vnet}/subnets/{subnet}") {
            call.writer() ?: return@delete
            call.done(sdn.deleteSubnet(call.parameters["vnet"]!!, call.parameters["subnet"]!!))
        }
        get("cluster/sdn/ipams/{ipam}/status") {
            call.authed() ?: return@get
            call.ok(sdn.ipamStatus(call.parameters["ipam"]!!) ?: return@get call.fail(500, "no such ipam"))
        }
        put("cluster/sdn") {
            val s = call.writer() ?: return@put
            val node = state.nodes.first { it.online }.name
            call.ok(state.startTask(node, "reloadnetworkall", "", s.user, log = listOf("pve1: reloading network config", "pve2: reloading network config", "pve3: no answer (node offline)")) { sdn.apply() })
        }
        post("cluster/sdn/rollback") {
            call.writer() ?: return@post
            sdn.rollback()
            call.ok(JsonNull)
        }
    }

    private fun Route.ceph() {
        val ceph = state.ceph
        suspend fun ApplicationCall.params(): Map<String, String> = receiveParameters().let { p -> p.names().associateWith { p[it].orEmpty() } }

        /** The request's node if it is online and the cluster has Ceph; otherwise answers like Proxmox. */
        suspend fun ApplicationCall.cephNode(): String? {
            val name = parameters["node"]!!
            val node = state.node(name)
            when {
                node == null -> fail(404, "no such node '$name'")
                !node.online -> fail(595, "Connection refused")
                !ceph.installed -> fail(500, MockCeph.NOT_INSTALLED)
                else -> return name
            }
            return null
        }
        suspend fun ApplicationCall.writer(): MockState.Session? {
            val s = authed() ?: return null
            if (state.isReadOnly(s.user)) {
                fail(403, "Permission check failed (/, Sys.Modify)")
                return null
            }
            return s
        }
        suspend fun ApplicationCall.task(node: String, type: String, id: String, s: MockState.Session, change: MockCeph.Change) = when (change) {
            is MockCeph.Change.Refused -> fail(500, change.reason)
            is MockCeph.Change.Ok -> ok(state.startTask(node, type, id, s.user, effect = change.effect))
        }
        suspend fun ApplicationCall.done(error: String?) = if (error != null) fail(500, error) else ok(JsonNull)

        get("nodes/{node}/ceph/status") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.status())
        }
        get("nodes/{node}/ceph/osd") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.osdTree())
        }
        post("nodes/{node}/ceph/osd") {
            val s = call.writer() ?: return@post
            val node = call.cephNode() ?: return@post
            val p = call.params()
            val dev = p["dev"].orEmpty()
            val disk = state.disks(node)?.firstOrNull { it.devpath == dev } ?: return@post call.fail(500, "unable to get device info for '$dev'")
            if (!disk.unused) return@post call.fail(500, "device '$dev' is already in use")
            val deviceClass = p["crush-device-class"]?.takeIf { it.isNotBlank() } ?: disk.type?.takeIf { it in setOf("hdd", "ssd", "nvme") } ?: "hdd"
            call.task(node, "cephcreateosd", dev.substringAfterLast('/'), s, ceph.createOsd(node, dev, deviceClass))
        }
        delete("nodes/{node}/ceph/osd/{id}") {
            val s = call.writer() ?: return@delete
            val node = call.cephNode() ?: return@delete
            val id = call.parameters["id"]!!.toIntOrNull() ?: return@delete call.fail(400, "osdid: type check ('integer') failed")
            call.task(node, "cephdestroyosd", "$id", s, ceph.destroyOsd(node, id, call.request.queryParameters["cleanup"] == "1"))
        }
        for (direction in listOf("in", "out")) {
            post("nodes/{node}/ceph/osd/{id}/$direction") {
                call.writer() ?: return@post
                call.cephNode() ?: return@post
                call.done(ceph.setIn(call.parameters["id"]!!.toIntOrNull() ?: -1, direction == "in"))
            }
        }
        post("nodes/{node}/ceph/osd/{id}/scrub") {
            call.writer() ?: return@post
            call.cephNode() ?: return@post
            call.done(ceph.scrub(call.parameters["id"]!!.toIntOrNull() ?: -1))
        }
        get("nodes/{node}/ceph/pool") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.pools())
        }
        post("nodes/{node}/ceph/pool") {
            val s = call.writer() ?: return@post
            val node = call.cephNode() ?: return@post
            val p = call.params()
            call.task(node, "cephcreatepool", p["name"].orEmpty(), s, ceph.createPool(p))
        }
        put("nodes/{node}/ceph/pool/{name}") {
            val s = call.writer() ?: return@put
            val node = call.cephNode() ?: return@put
            val name = call.parameters["name"]!!
            call.task(node, "cephsetpool", name, s, ceph.updatePool(name, call.params()))
        }
        delete("nodes/{node}/ceph/pool/{name}") {
            val s = call.writer() ?: return@delete
            val node = call.cephNode() ?: return@delete
            val name = call.parameters["name"]!!
            call.task(node, "cephdestroypool", name, s, ceph.deletePool(name))
        }
        get("nodes/{node}/ceph/rules") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.rules())
        }
        for (type in listOf("mon", "mgr", "mds")) {
            get("nodes/{node}/ceph/$type") {
                call.authed() ?: return@get
                call.cephNode() ?: return@get
                call.ok(ceph.services(type) ?: JsonArray(emptyList()))
            }
            post("nodes/{node}/ceph/$type/{name}") {
                val s = call.writer() ?: return@post
                val node = call.cephNode() ?: return@post
                call.task(node, "cephcreate$type", call.parameters["name"]!!, s, ceph.createService(type, call.parameters["name"]!!))
            }
            delete("nodes/{node}/ceph/$type/{name}") {
                val s = call.writer() ?: return@delete
                val node = call.cephNode() ?: return@delete
                call.task(node, "cephdestroy$type", call.parameters["name"]!!, s, ceph.destroyService(type, call.parameters["name"]!!))
            }
        }
        for (action in listOf("start", "stop", "restart")) {
            post("nodes/{node}/ceph/$action") {
                val s = call.writer() ?: return@post
                val node = call.cephNode() ?: return@post
                val service = call.params()["service"].orEmpty().ifEmpty { "ceph.target" }
                call.task(node, "srv$action", service, s, ceph.serviceAction(node, action, service))
            }
        }
        get("nodes/{node}/ceph/fs") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.filesystems())
        }
        post("nodes/{node}/ceph/fs/{name}") {
            val s = call.writer() ?: return@post
            val node = call.cephNode() ?: return@post
            val name = call.parameters["name"]!!
            call.task(node, "cephfscreate", name, s, ceph.createFs(name, call.params()))
        }
        get("nodes/{node}/ceph/cfg/raw") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.config)
        }
        get("nodes/{node}/ceph/cfg/db") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.configDb())
        }
        get("nodes/{node}/ceph/crush") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.crushMap())
        }
        get("nodes/{node}/ceph/log") {
            call.authed() ?: return@get
            call.cephNode() ?: return@get
            call.ok(ceph.log(call.request.queryParameters["limit"]?.toIntOrNull() ?: 50))
        }
        get("cluster/ceph/flags") {
            call.authed() ?: return@get
            if (!ceph.installed) return@get call.fail(500, MockCeph.NOT_INSTALLED)
            call.ok(ceph.flags())
        }
        put("cluster/ceph/flags/{flag}") {
            call.writer() ?: return@put
            if (!ceph.installed) return@put call.fail(500, MockCeph.NOT_INSTALLED)
            call.done(ceph.setFlag(call.parameters["flag"]!!, call.params()["value"] == "1"))
        }
    }

    private fun Route.accessAdmin() {
        val access = state.access
        suspend fun ApplicationCall.params(): Map<String, String> = receiveParameters().let { p -> p.names().associateWith { p[it].orEmpty() } }
        suspend fun ApplicationCall.writer(): MockState.Session? {
            val s = authed() ?: return null
            if (state.isReadOnly(s.user)) {
                fail(403, "Permission check failed (/access, User.Modify)")
                return null
            }
            return s
        }
        suspend fun ApplicationCall.done(error: String?) = if (error != null) fail(400, error) else ok(JsonNull)

        get("access/users") {
            call.authed() ?: return@get
            call.ok(access.users())
        }
        get("access/users/{userid}") {
            call.authed() ?: return@get
            call.ok(access.user(call.parameters["userid"]!!) ?: return@get call.fail(500, "no such user"))
        }
        post("access/users") {
            call.writer() ?: return@post
            call.done(access.createUser(call.params()))
        }
        put("access/users/{userid}") {
            call.writer() ?: return@put
            call.done(access.updateUser(call.parameters["userid"]!!, call.params()))
        }
        delete("access/users/{userid}") {
            call.writer() ?: return@delete
            call.done(access.deleteUser(call.parameters["userid"]!!))
        }
        put("access/password") {
            val s = call.authed() ?: return@put
            call.done(access.changePassword(s.user, call.params()))
        }
        put("access/users/{userid}/unlock-tfa") {
            call.writer() ?: return@put
            call.done(access.unlockTfa(call.parameters["userid"]!!))
        }
        get("access/users/{userid}/token") {
            call.authed() ?: return@get
            call.ok(access.tokens(call.parameters["userid"]!!) ?: return@get call.fail(500, "no such user"))
        }
        post("access/users/{userid}/token/{tokenid}") {
            call.writer() ?: return@post
            val (token, error) = access.createToken(call.parameters["userid"]!!, call.parameters["tokenid"]!!, call.params())
            if (token != null) call.ok(token) else call.fail(400, error.orEmpty())
        }
        delete("access/users/{userid}/token/{tokenid}") {
            call.writer() ?: return@delete
            call.done(access.deleteToken(call.parameters["userid"]!!, call.parameters["tokenid"]!!))
        }
        get("access/groups") {
            call.authed() ?: return@get
            call.ok(access.groups())
        }
        get("access/groups/{groupid}") {
            call.authed() ?: return@get
            call.ok(access.group(call.parameters["groupid"]!!) ?: return@get call.fail(500, "group does not exist"))
        }
        post("access/groups") {
            call.writer() ?: return@post
            call.done(access.createGroup(call.params()))
        }
        put("access/groups/{groupid}") {
            call.writer() ?: return@put
            call.done(access.updateGroup(call.parameters["groupid"]!!, call.params()))
        }
        delete("access/groups/{groupid}") {
            call.writer() ?: return@delete
            call.done(access.deleteGroup(call.parameters["groupid"]!!))
        }
        get("access/roles") {
            call.authed() ?: return@get
            call.ok(access.roles())
        }
        post("access/roles") {
            call.writer() ?: return@post
            call.done(access.createRole(call.params()))
        }
        put("access/roles/{roleid}") {
            call.writer() ?: return@put
            call.done(access.updateRole(call.parameters["roleid"]!!, call.params()))
        }
        delete("access/roles/{roleid}") {
            call.writer() ?: return@delete
            call.done(access.deleteRole(call.parameters["roleid"]!!))
        }
        get("access/acl") {
            call.authed() ?: return@get
            call.ok(access.acl())
        }
        put("access/acl") {
            call.writer() ?: return@put
            call.done(access.updateAcl(call.params()))
        }
        get("access/domains/{realm}") {
            call.authed() ?: return@get
            call.ok(access.realmConfig(call.parameters["realm"]!!) ?: return@get call.fail(500, "domain does not exist"))
        }
        post("access/domains") {
            call.writer() ?: return@post
            call.done(access.createRealm(call.params()))
        }
        put("access/domains/{realm}") {
            call.writer() ?: return@put
            call.done(access.updateRealm(call.parameters["realm"]!!, call.params()))
        }
        delete("access/domains/{realm}") {
            call.writer() ?: return@delete
            call.done(access.deleteRealm(call.parameters["realm"]!!))
        }
        post("access/domains/{realm}/sync") {
            val s = call.writer() ?: return@post
            val realm = call.parameters["realm"]!!
            val (log, effect) = try {
                access.sync(realm, call.params())
            } catch (e: IllegalArgumentException) {
                return@post call.fail(400, e.message.orEmpty())
            }
            call.ok(state.startTask(state.nodes.first().name, "auth-realm-sync", realm, s.user, log = log, effect = effect))
        }
        get("access/tfa") {
            call.authed() ?: return@get
            call.ok(access.tfaList())
        }
        post("access/tfa/{userid}") {
            val s = call.authed() ?: return@post
            val (result, error) = access.addTfa(s.user, call.parameters["userid"]!!, call.params())
            if (result != null) call.ok(result) else call.fail(400, error.orEmpty())
        }
        put("access/tfa/{userid}/{id}") {
            val s = call.authed() ?: return@put
            call.done(access.updateTfa(s.user, call.parameters["userid"]!!, call.parameters["id"]!!, call.params()))
        }
        delete("access/tfa/{userid}/{id}") {
            val s = call.authed() ?: return@delete
            call.done(access.deleteTfa(s.user, call.parameters["userid"]!!, call.parameters["id"]!!, call.request.queryParameters["password"]))
        }
        get("access/permissions") {
            val s = call.authed() ?: return@get
            val user = call.request.queryParameters["userid"] ?: s.user
            call.ok(access.permissions(user, call.request.queryParameters["path"]))
        }
    }

    private fun Route.pve() {
        accessAdmin()
        ceph()
        sdn()
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
        get("cluster/ceph/status") {
            call.authed() ?: return@get
            // A test can force a health status; otherwise like a real cluster with or without Ceph.
            val health = state.cephHealth ?: if (state.ceph.installed) state.ceph.healthStatus() else null
            if (health == null) return@get call.fail(500, MockCeph.NOT_INSTALLED)
            call.ok(CephStatus(CephHealth(health)))
        }
        get("nodes/{node}/status") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.nodeStatus(node))
        }
        post("nodes/{node}/status") {
            val s = call.authed() ?: return@post
            val node = state.node(call.parameters["node"]!!) ?: return@post call.fail(404, "no such node")
            val command = call.receiveParameters()["command"]
            if (command !in listOf("reboot", "shutdown")) {
                return@post call.fail(400, "Parameter verification failed.", mapOf("command" to "value '$command' does not have a value in the enumeration 'reboot, shutdown'"))
            }
            if (!node.online) return@post call.fail(595, "Connection refused")
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/nodes/${node.name}, Sys.PowerMgmt)")
            state.nodePowerRequests += node.name to command!!
            call.ok(JsonNull)
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
        get("nodes/{node}/{type}/{vmid}/pending") {
            call.authed() ?: return@get
            val g = call.guest() ?: return@get
            call.ok(state.guestPending(g))
        }
        post("nodes/{node}/qemu/{vmid}/config") {
            val s = call.authed() ?: return@post
            val g = call.guest("qemu") ?: return@post
            val p = call.receiveParameters()
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Config.Disk)")
            state.updateVmConfigAsync(g, p.entries().associate { it.key to it.value.first() }, s.user)
                .fold(onSuccess = { call.ok(it) }, onFailure = { call.fail(500, it.message ?: "error") })
        }
        put("nodes/{node}/{type}/{vmid}/resize") {
            val s = call.authed() ?: return@put
            val g = call.guest() ?: return@put
            val p = call.receiveParameters()
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Config.Disk)")
            state.resize(g, p.entries().associate { it.key to it.value.first() }, s.user)
                .fold(onSuccess = { call.ok(it) }, onFailure = { call.fail(500, it.message ?: "error") })
        }
        post("nodes/{node}/qemu/{vmid}/move_disk") {
            val s = call.authed() ?: return@post
            val g = call.guest("qemu") ?: return@post
            call.moveVolume(s, g)
        }
        post("nodes/{node}/lxc/{vmid}/move_volume") {
            val s = call.authed() ?: return@post
            val g = call.guest("lxc") ?: return@post
            call.moveVolume(s, g)
        }
        post("nodes/{node}/qemu/{vmid}/spiceproxy") {
            val s = call.authed() ?: return@post
            val g = call.guest("qemu") ?: return@post
            val p = call.receiveParameters()
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Console)")
            state.spiceProxy(g, p["proxy"]).fold(onSuccess = { call.ok(it) }, onFailure = { call.fail(500, it.message ?: "error") })
        }
        put("nodes/{node}/qemu/{vmid}/cloudinit") {
            val s = call.authed() ?: return@put
            val g = call.guest("qemu") ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Config.Cloudinit)")
            call.ok(JsonNull) // the image is built again at the next start; nothing to do in the mock
        }
        get("nodes/{node}/network") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            val (list, changes) = state.nodeNetworks(node.name, call.request.queryParameters["type"]) ?: return@get call.fail(404, "no such node")
            call.respondText(ProxmoxJson.encodeToString(ApiEnvelope(list, changes = changes)), ContentType.Application.Json)
        }
        post("nodes/{node}/network") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.createNetwork(call.parameters["node"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("nodes/{node}/network/{iface}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.updateNetwork(call.parameters["node"]!!, call.parameters["iface"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("nodes/{node}/network/{iface}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val error = state.deleteNetwork(call.parameters["node"]!!, call.parameters["iface"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("nodes/{node}/network") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val node = state.node(call.parameters["node"]!!) ?: return@put call.fail(404, "no such node")
            if (!node.online) return@put call.fail(595, "Connection refused")
            call.ok(state.applyNetwork(node.name, s.user))
        }
        delete("nodes/{node}/network") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            state.revertNetwork(call.parameters["node"]!!)
            call.ok(JsonNull)
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
        post("nodes/{node}/qemu/{vmid}/agent/set-user-password") {
            val s = call.authed() ?: return@post
            val g = call.guest("qemu") ?: return@post
            val p = call.receiveParameters()
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.GuestAgent.Unrestricted)")
            val username = p["username"].orEmpty()
            val password = p["password"].orEmpty()
            if (username.isEmpty()) return@post call.fail(400, "Parameter verification failed.", mapOf("username" to "property is missing and it is not optional"))
            if (password.length !in 5..1024) return@post call.fail(400, "Parameter verification failed.", mapOf("password" to "value must be at least 5 characters long"))
            state.agentSetPassword(g, username, password).fold(
                onSuccess = { call.ok(JsonObject(mapOf("result" to JsonObject(emptyMap())))) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        post("nodes/{node}/qemu/{vmid}/agent/{command}") {
            val s = call.authed() ?: return@post
            val g = call.guest("qemu") ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.GuestAgent.FileSystemMgmt)")
            state.agentCommand(g, call.parameters["command"]!!).fold(
                onSuccess = { call.ok(JsonObject(mapOf("result" to it))) },
                onFailure = { call.fail(if (it is UnsupportedOperationException) 501 else 500, it.message ?: "error") },
            )
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
            val single = p["vmid"]?.takeIf { ',' !in it }?.toIntOrNull()
            if (single == null && (p["all"] == "1" || p["pool"] != null || p["vmid"] != null)) {
                // A whole selection, as "Run now" on a backup job sends it.
                if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, VM.Backup)")
                val params = p.names().associateWith { p[it].orEmpty() }
                return@post state.backupSelection(call.parameters["node"]!!, params, s.user).fold(
                    onSuccess = { call.ok(it) },
                    onFailure = { call.fail(500, it.message ?: "error") },
                )
            }
            val vmid = single
                ?: return@post call.fail(400, "Parameter verification failed.", mapOf("vmid" to "property is missing and it is not optional"))
            val g = state.guest(vmid)?.takeIf { it.node == call.parameters["node"] }
                ?: return@post call.fail(500, "guest $vmid is not on node '${call.parameters["node"]}'")
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/$vmid, VM.Backup)")
            state.backupNow(g, p["storage"].orEmpty(), p["mode"] ?: "snapshot", p["notes-template"], s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        get("cluster/backup") {
            call.authed() ?: return@get
            call.ok(state.backupJobs())
        }
        post("cluster/backup") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.createBackupJob(p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("cluster/backup/{id}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.updateBackupJob(call.parameters["id"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("cluster/backup/{id}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/, Sys.Modify)")
            val error = state.deleteBackupJob(call.parameters["id"]!!)
            if (error != null) call.fail(404, error) else call.ok(JsonNull)
        }
        get("cluster/backup-info/not-backed-up") {
            call.authed() ?: return@get
            call.ok(state.notBackedUp())
        }
        get("cluster/jobs/schedule-analyze") {
            call.authed() ?: return@get
            val q = call.request.queryParameters
            val runs = MockSchedule.analyze(q["schedule"].orEmpty(), System.currentTimeMillis() / 1000, q["iterations"]?.toIntOrNull() ?: 10)
                ?: return@get call.fail(400, "Parameter verification failed.", mapOf("schedule" to "value does not look like a valid calendar event"))
            call.ok(runs)
        }
        get("storage") {
            call.authed() ?: return@get
            call.ok(state.storageConfigs())
        }
        get("storage/{storage}") {
            call.authed() ?: return@get
            val id = call.parameters["storage"]!!
            call.ok(state.storageConfig(id) ?: return@get call.fail(500, "storage '$id' does not exist"))
        }
        post("storage") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/storage, Datastore.Allocate)")
            val p = call.receiveParameters()
            val error = state.createStorage(p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("storage/{storage}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/storage, Datastore.Allocate)")
            val p = call.receiveParameters()
            val error = state.updateStorage(call.parameters["storage"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("storage/{storage}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/storage, Datastore.Allocate)")
            val error = state.deleteStorage(call.parameters["storage"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/scan/{kind}") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            val q = call.request.queryParameters
            when (call.parameters["kind"]) {
                "nfs" -> state.scanNfs(q["server"].orEmpty()).fold({ call.ok(it) }, { call.fail(500, it.message ?: "error") })
                "cifs" -> state.scanCifs(q["server"].orEmpty()).fold({ call.ok(it) }, { call.fail(500, it.message ?: "error") })
                "pbs" -> state.scanPbs(q["server"].orEmpty(), q["password"]).fold({ call.ok(it) }, { call.fail(500, it.message ?: "error") })
                "lvm" -> call.ok(state.scanLvm())
                "lvmthin" -> call.ok(state.scanLvmThin(q["vg"].orEmpty()) ?: return@get call.fail(500, "no such volume group '${q["vg"]}'"))
                "zfs" -> call.ok(state.scanZfs())
                else -> call.fail(501, "Method 'GET /nodes/${node.name}/scan/${call.parameters["kind"]}' not implemented")
            }
        }
        get("pools") {
            call.authed() ?: return@get
            // Proxmox VE 8.1+: one pool with its members.
            val id = call.request.queryParameters["poolid"] ?: return@get call.ok(state.pools())
            val pool = state.pool(id) ?: return@get call.fail(500, "pool '$id' does not exist")
            call.ok(listOf(pool))
        }
        get("pools/{poolid}") {
            call.authed() ?: return@get
            val id = call.parameters["poolid"]!!
            call.ok(state.pool(id) ?: return@get call.fail(500, "pool '$id' does not exist"))
        }
        post("pools") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/pool, Pool.Allocate)")
            val p = call.receiveParameters()
            val error = state.createPool(p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("pools") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/pool, Pool.Allocate)")
            val p = call.receiveParameters()
            val params = p.names().associateWith { p[it].orEmpty() }
            val error = state.updatePool(params["poolid"].orEmpty(), params - "poolid")
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("pools/{poolid}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/pool, Pool.Allocate)")
            val p = call.receiveParameters()
            val error = state.updatePool(call.parameters["poolid"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("pools") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/pool, Pool.Allocate)")
            val error = state.deletePool(call.request.queryParameters["poolid"].orEmpty())
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("pools/{poolid}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/pool, Pool.Allocate)")
            val error = state.deletePool(call.parameters["poolid"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("cluster/replication") {
            call.authed() ?: return@get
            call.ok(state.replicationJobs())
        }
        post("cluster/replication") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.createReplicationJob(p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("cluster/replication/{id}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.updateReplicationJob(call.parameters["id"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("cluster/replication/{id}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/, Sys.Modify)")
            val error = state.deleteReplicationJob(call.parameters["id"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/replication") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.replicationStatus(node.name))
        }
        post("nodes/{node}/replication/{id}/schedule_now") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/vms/${call.parameters["id"]!!.substringBefore('-')}, VM.Replicate)")
            val node = state.node(call.parameters["node"]!!) ?: return@post call.fail(404, "no such node")
            if (!node.online) return@post call.fail(595, "Connection refused")
            val error = state.runReplicationNow(call.parameters["id"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/replication/{id}/log") {
            call.authed() ?: return@get
            val log = state.replicationLog(call.parameters["id"]!!) ?: return@get call.fail(500, "replication job '${call.parameters["id"]}' does not exist")
            call.ok(log)
        }
        get("cluster/ha/resources") {
            call.authed() ?: return@get
            call.ok(state.haResources())
        }
        post("cluster/ha/resources") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, Sys.Console)")
            val p = call.receiveParameters()
            val error = state.addHaResource(p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("cluster/ha/resources/{sid}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/, Sys.Console)")
            val p = call.receiveParameters()
            val error = state.updateHaResource(call.parameters["sid"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("cluster/ha/resources/{sid}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/, Sys.Console)")
            val error = state.removeHaResource(call.parameters["sid"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        post("cluster/ha/resources/{sid}/migrate") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, Sys.Console)")
            val error = state.migrateHaResource(call.parameters["sid"]!!, call.receiveParameters()["node"].orEmpty())
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        post("cluster/ha/groups") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, Sys.Console)")
            val p = call.receiveParameters()
            val error = state.createHaGroup(p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("cluster/ha/groups/{group}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/, Sys.Console)")
            val p = call.receiveParameters()
            val error = state.updateHaGroup(call.parameters["group"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("cluster/ha/groups/{group}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/, Sys.Console)")
            val error = state.deleteHaGroup(call.parameters["group"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("cluster/ha/rules") {
            call.authed() ?: return@get
            // Like Proxmox VE 8, which has groups instead.
            call.fail(501, "Method 'GET /cluster/ha/rules' not implemented")
        }
        get("cluster/ha/groups") {
            call.authed() ?: return@get
            call.ok(state.haGroups())
        }
        get("cluster/ha/status/current") {
            call.authed() ?: return@get
            call.ok(state.haStatus())
        }
        get("cluster/firewall/macros") {
            call.authed() ?: return@get
            call.ok(state.firewallMacros())
        }
        get("cluster/firewall/groups") {
            call.authed() ?: return@get
            call.ok(state.securityGroups())
        }
        post("cluster/firewall/groups") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.createSecurityGroup(p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("cluster/firewall/groups/{group}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (Sys.Modify)")
            val error = state.deleteSecurityGroup(call.parameters["group"]!!)
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        firewallRules("cluster/firewall/groups/{group}") { "cluster/firewall/groups/${it.parameters["group"]}" }
        firewall("cluster/firewall") { "cluster/firewall" }
        firewall("nodes/{node}/firewall") { "nodes/${it.parameters["node"]}/firewall" }
        firewall("nodes/{node}/{type}/{vmid}/firewall") { "nodes/${it.parameters["node"]}/${it.parameters["type"]}/${it.parameters["vmid"]}/firewall" }
        get("nodes/{node}/query-url-metadata") {
            call.authed() ?: return@get
            state.urlMetadata(call.request.queryParameters["url"].orEmpty()).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        post("nodes/{node}/storage/{storage}/download-url") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/storage/${call.parameters["storage"]}, Datastore.AllocateTemplate)")
            val p = call.receiveParameters()
            state.downloadUrl(call.parameters["node"]!!, call.parameters["storage"]!!, p.names().associateWith { p[it].orEmpty() }, s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        post("nodes/{node}/storage/{storage}/upload") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/storage/${call.parameters["storage"]}, Datastore.AllocateTemplate)")
            var content = ""
            var name = ""
            var size = 0L
            // Proxmox reads the form fields first and the file last, like the web UI sends them.
            call.receiveMultipart().forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> if (part.name == "content") content = part.value
                    is PartData.FileItem -> {
                        name = part.originalFileName.orEmpty()
                        size = part.provider().toByteArray().size.toLong()
                    }
                    else -> Unit
                }
                part.dispose()
            }
            state.upload(call.parameters["node"]!!, call.parameters["storage"]!!, content, name, size, s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        delete("nodes/{node}/storage/{storage}/content/{volume}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/storage/${call.parameters["storage"]}, Datastore.Allocate)")
            state.deleteVolume(call.parameters["node"]!!, call.parameters["storage"]!!, call.parameters["volume"]!!, s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(500, it.message ?: "error") },
            )
        }
        // Proxmox returns the id as a string.
        get("cluster/nextid") {
            call.authed() ?: return@get
            call.ok(state.nextId().toString())
        }
        post("nodes/{node}/qemu") { call.createOrRestore("qemu") }
        post("nodes/{node}/lxc") { call.createOrRestore("lxc") }
        get("nodes/{node}/apt/update") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.aptUpdates(node.name).orEmpty())
        }
        post("nodes/{node}/apt/update") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val node = state.node(call.parameters["node"]!!) ?: return@post call.fail(404, "no such node")
            if (!node.online) return@post call.fail(595, "Connection refused")
            call.ok(state.refreshAptIndex(node.name, s.user))
        }
        get("nodes/{node}/apt/changelog") {
            call.authed() ?: return@get
            val node = call.parameters["node"]!!
            val name = call.request.queryParameters["name"].orEmpty()
            call.ok(state.aptChangelog(node, name) ?: return@get call.fail(500, "changelog for '$name' not available"))
        }
        get("nodes/{node}/apt/versions") {
            call.authed() ?: return@get
            call.ok(state.packageVersions(call.parameters["node"]!!).orEmpty())
        }
        get("nodes/{node}/apt/repositories") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.aptRepositories(node.name) ?: return@get call.fail(500, "no repositories"))
        }
        post("nodes/{node}/apt/repositories") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.setAptRepositoryEnabled(
                call.parameters["node"]!!, p["path"].orEmpty(), p["index"]?.toIntOrNull() ?: -1, p["enabled"] == "1", p["digest"],
            )
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("nodes/{node}/apt/repositories") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.addStandardAptRepository(call.parameters["node"]!!, p["handle"].orEmpty(), p["digest"])
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/subscription") {
            call.authed() ?: return@get
            call.ok(state.subscription())
        }
        put("nodes/{node}/subscription") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val error = state.setSubscriptionKey(call.receiveParameters()["key"].orEmpty())
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("nodes/{node}/subscription") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            state.setSubscriptionKey(null)
            call.ok(JsonNull)
        }
        get("nodes/{node}/services") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.services(node.name).orEmpty())
        }
        post("nodes/{node}/services/{service}/{action}") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val node = state.node(call.parameters["node"]!!) ?: return@post call.fail(404, "no such node")
            if (!node.online) return@post call.fail(595, "Connection refused")
            state.serviceAction(node.name, call.parameters["service"]!!, call.parameters["action"]!!, s.user).fold(
                onSuccess = { call.ok(it) },
                onFailure = { call.fail(400, it.message ?: "error") },
            )
        }
        get("nodes/{node}/disks/list") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.disks(node.name).orEmpty())
        }
        get("nodes/{node}/disks/smart") {
            call.authed() ?: return@get
            val disk = call.request.queryParameters["disk"].orEmpty()
            call.ok(state.smart(call.parameters["node"]!!, disk) ?: return@get call.fail(500, "disk '$disk' not found"))
        }
        put("nodes/{node}/disks/wipedisk") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/, Sys.Modify)")
            state.wipeDisk(call.parameters["node"]!!, call.receiveParameters()["disk"].orEmpty(), s.user)
                .fold({ call.ok(it) }, { call.fail(400, it.message ?: "error") })
        }
        post("nodes/{node}/disks/initgpt") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, Sys.Modify)")
            state.initGpt(call.parameters["node"]!!, call.receiveParameters()["disk"].orEmpty(), s.user)
                .fold({ call.ok(it) }, { call.fail(400, it.message ?: "error") })
        }
        post("nodes/{node}/disks/{kind}") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/, Sys.Modify)")
            val p = call.receiveParameters()
            state.createDiskStorage(call.parameters["node"]!!, call.parameters["kind"]!!, p.names().associateWith { p[it].orEmpty() }, s.user)
                .fold({ call.ok(it) }, { call.fail(400, it.message ?: "error") })
        }
        get("nodes/{node}/dns") {
            call.authed() ?: return@get
            call.ok(state.nodeDns(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node"))
        }
        put("nodes/{node}/dns") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.setNodeDns(call.parameters["node"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/hosts") {
            call.authed() ?: return@get
            call.ok(state.nodeHosts(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node"))
        }
        post("nodes/{node}/hosts") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.setNodeHosts(call.parameters["node"]!!, p["data"].orEmpty(), p["digest"])
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/time") {
            call.authed() ?: return@get
            call.ok(state.nodeTime(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node"))
        }
        put("nodes/{node}/time") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/nodes/${call.parameters["node"]}, Sys.Modify)")
            val error = state.setNodeTimezone(call.parameters["node"]!!, call.receiveParameters()["timezone"].orEmpty())
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("nodes/{node}/journal") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            call.ok(state.journal(node.name, call.request.queryParameters["lastentries"]?.toIntOrNull() ?: 500))
        }
        post("nodes/{node}/termproxy") { call.consoleProxy(type = null) }
        post("nodes/{node}/lxc/{vmid}/termproxy") { call.consoleProxy(type = "lxc") }
        post("nodes/{node}/qemu/{vmid}/vncproxy") { call.consoleProxy(type = "qemu") }
        // Proxmox answers the `binary` subprotocol; browsers refuse the socket otherwise.
        webSocket("nodes/{node}/vncwebsocket", protocol = "binary") { console("nodes/${call.parameters["node"]}/vncwebsocket", vnc = false) }
        webSocket("nodes/{node}/{type}/{vmid}/vncwebsocket", protocol = "binary") {
            val p = call.parameters
            console("nodes/${p["node"]}/${p["type"]}/${p["vmid"]}/vncwebsocket", vnc = p["type"] == "qemu")
        }
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
        get("nodes/{node}/storage/{storage}/rrddata") {
            call.authed() ?: return@get
            val node = state.node(call.parameters["node"]!!) ?: return@get call.fail(404, "no such node")
            if (!node.online) return@get call.fail(595, "Connection refused")
            val id = call.parameters["storage"]!!
            val storage = state.storages.firstOrNull { it.node == node.name && it.id == id }
                ?: return@get call.fail(500, "storage '$id' does not exist")
            val data = state.storageRrd(storage, call.request.queryParameters["timeframe"].orEmpty())
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
        put("nodes/{node}/{type}/{vmid}/snapshot/{snapname}/config") {
            val s = call.authed() ?: return@put
            val g = call.guest() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (/vms/${g.vmid}, VM.Snapshot)")
            val error = state.setSnapshotDescription(g, call.parameters["snapname"]!!, call.receiveParameters()["description"].orEmpty())
            if (error != null) call.fail(500, error) else call.ok(JsonNull)
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
        get("nodes/localhost/tasks/{upid}/status") {
            call.authed() ?: return@get
            call.ok(state.taskStatus(call.parameters["upid"]!!) ?: return@get call.fail(404, "no such task"))
        }
        get("nodes/localhost/tasks/{upid}/log") {
            call.authed() ?: return@get
            call.ok(state.taskLog(call.parameters["upid"]!!) ?: return@get call.fail(404, "no such task"))
        }
        pbsAdmin()
    }

    private fun Route.pbsAdmin() {
        val pbs = state.pbs
        val node = "localhost"
        suspend fun ApplicationCall.params(): Map<String, String> = receiveParameters().let { p -> p.names().associateWith { p[it].orEmpty() } }
        fun ApplicationCall.query(): Map<String, String> = request.queryParameters.let { q -> q.names().associateWith { q[it].orEmpty() } }
        suspend fun ApplicationCall.writer(): MockState.Session? {
            val s = authed() ?: return null
            if (state.isReadOnly(s.user)) {
                fail(403, "permission check failed")
                return null
            }
            return s
        }
        suspend fun ApplicationCall.done(error: String?) = if (error != null) fail(400, error) else ok(JsonNull)
        suspend fun ApplicationCall.task(type: String, id: String, s: MockState.Session, change: Pair<String?, (() -> Unit)?>) {
            val (error, effect) = change
            if (error != null) fail(400, error) else ok(state.startTask("pbs", type, id, s.user, effect = effect))
        }

        get("admin/datastore/{store}/status") {
            call.authed() ?: return@get
            call.ok(pbs.status(call.parameters["store"]!!) ?: return@get call.fail(404, "datastore does not exist"))
        }
        get("admin/datastore/{store}/gc") {
            call.authed() ?: return@get
            call.ok(pbs.gc(call.parameters["store"]!!) ?: return@get call.fail(404, "datastore does not exist"))
        }
        post("admin/datastore/{store}/gc") {
            val s = call.writer() ?: return@post
            val store = call.parameters["store"]!!
            call.task("garbage_collection", store, s, pbs.startGc(store))
        }
        get("config/datastore/{store}") {
            call.authed() ?: return@get
            call.ok(pbs.config(call.parameters["store"]!!) ?: return@get call.fail(404, "datastore does not exist"))
        }
        put("config/datastore/{store}") {
            call.writer() ?: return@put
            call.done(pbs.updateConfig(call.parameters["store"]!!, call.params()))
        }
        get("admin/datastore/{store}/namespace") {
            call.authed() ?: return@get
            call.ok(pbs.namespaces(call.parameters["store"]!!) ?: return@get call.fail(404, "datastore does not exist"))
        }
        post("admin/datastore/{store}/namespace") {
            call.writer() ?: return@post
            val p = call.params()
            call.done(pbs.createNamespace(call.parameters["store"]!!, p["name"].orEmpty(), p["parent"]))
        }
        delete("admin/datastore/{store}/namespace") {
            call.writer() ?: return@delete
            val q = call.query()
            call.done(pbs.deleteNamespace(call.parameters["store"]!!, q["ns"].orEmpty(), q["delete-groups"] == "1"))
        }
        get("admin/datastore/{store}/groups") {
            call.authed() ?: return@get
            call.ok(pbs.groups(call.parameters["store"]!!, call.query()["ns"].orEmpty()) ?: return@get call.fail(404, "datastore or namespace does not exist"))
        }
        delete("admin/datastore/{store}/groups") {
            call.writer() ?: return@delete
            val q = call.query()
            call.done(pbs.deleteGroup(call.parameters["store"]!!, q["ns"].orEmpty(), q["backup-type"].orEmpty(), q["backup-id"].orEmpty()))
        }
        get("admin/datastore/{store}/snapshots") {
            call.authed() ?: return@get
            val q = call.query()
            call.ok(pbs.snapshots(call.parameters["store"]!!, q["ns"].orEmpty(), q["backup-type"], q["backup-id"]) ?: return@get call.fail(404, "datastore or namespace does not exist"))
        }
        delete("admin/datastore/{store}/snapshots") {
            call.writer() ?: return@delete
            val q = call.query()
            call.done(pbs.deleteSnapshot(call.parameters["store"]!!, q["ns"].orEmpty(), q))
        }
        put("admin/datastore/{store}/notes") {
            call.writer() ?: return@put
            val p = call.params()
            call.done(pbs.setNotes(call.parameters["store"]!!, p["ns"].orEmpty(), p))
        }
        put("admin/datastore/{store}/protected") {
            call.writer() ?: return@put
            val p = call.params()
            call.done(pbs.setProtected(call.parameters["store"]!!, p["ns"].orEmpty(), p))
        }
        post("admin/datastore/{store}/verify") {
            val s = call.writer() ?: return@post
            val p = call.params()
            val store = call.parameters["store"]!!
            call.task("verify", store, s, pbs.verify(store, p["ns"].orEmpty(), p))
        }
        post("admin/datastore/{store}/prune") {
            call.writer() ?: return@post
            val p = call.params()
            val (error, result) = pbs.prune(call.parameters["store"]!!, p["ns"].orEmpty(), p)
            if (error != null) call.fail(400, error) else call.ok(result!!)
        }
        get("admin/datastore/{store}/catalog") {
            call.authed() ?: return@get
            val q = call.query()
            val (error, result) = pbs.catalog(call.parameters["store"]!!, q["ns"].orEmpty(), q)
            if (error != null) call.fail(400, error) else call.ok(result!!)
        }
        get("admin/datastore/{store}/pxar-file-download") {
            call.authed() ?: return@get
            val q = call.query()
            val (error, bytes) = pbs.download(call.parameters["store"]!!, q["ns"].orEmpty(), q)
            if (error != null) call.fail(400, error) else call.respondBytes(bytes!!, ContentType.Application.OctetStream)
        }
        for (kind in listOf("sync", "verify", "prune")) {
            get("admin/$kind") {
                call.authed() ?: return@get
                call.ok(pbs.jobs(kind))
            }
            post("admin/$kind/{id}/run") {
                val s = call.writer() ?: return@post
                val id = call.parameters["id"]!!
                call.task("${kind}job", id, s, pbs.runJob(kind, id))
            }
        }
        put("config/prune/{id}") {
            call.writer() ?: return@put
            call.done(pbs.setPruneDisabled(call.parameters["id"]!!, call.params()))
        }
        get("config/remote") {
            call.authed() ?: return@get
            call.ok(pbs.remotes())
        }
        post("config/remote") {
            call.writer() ?: return@post
            call.done(pbs.createRemote(call.params()))
        }
        delete("config/remote/{name}") {
            call.writer() ?: return@delete
            call.done(pbs.deleteRemote(call.parameters["name"]!!))
        }

        // The node: the same answers as a Proxmox VE node's.
        get("nodes/$node/status") {
            call.authed() ?: return@get
            call.ok(pbs.nodeStatus())
        }
        get("nodes/$node/apt/update") {
            call.authed() ?: return@get
            call.ok(state.aptUpdates(node).orEmpty())
        }
        post("nodes/$node/apt/update") {
            val s = call.writer() ?: return@post
            call.ok(state.refreshAptIndex(node, s.user))
        }
        get("nodes/$node/apt/changelog") {
            call.authed() ?: return@get
            val name = call.request.queryParameters["name"].orEmpty()
            call.ok(state.aptChangelog(node, name) ?: return@get call.fail(500, "changelog for '$name' not available"))
        }
        get("nodes/$node/apt/versions") {
            call.authed() ?: return@get
            call.ok(state.packageVersions(node).orEmpty())
        }
        get("nodes/$node/apt/repositories") {
            call.authed() ?: return@get
            call.ok(state.aptRepositories(node) ?: return@get call.fail(500, "no repositories"))
        }
        post("nodes/$node/apt/repositories") {
            call.writer() ?: return@post
            val p = call.params()
            call.done(state.setAptRepositoryEnabled(node, p["path"].orEmpty(), p["index"]?.toIntOrNull() ?: -1, p["enabled"] == "1", p["digest"]))
        }
        put("nodes/$node/apt/repositories") {
            call.writer() ?: return@put
            val p = call.params()
            call.done(state.addStandardAptRepository(node, p["handle"].orEmpty(), p["digest"]))
        }
        get("nodes/$node/subscription") {
            call.authed() ?: return@get
            call.ok(state.subscription())
        }
        put("nodes/$node/subscription") {
            call.writer() ?: return@put
            call.done(state.setSubscriptionKey(call.params()["key"].orEmpty()))
        }
        delete("nodes/$node/subscription") {
            call.writer() ?: return@delete
            state.setSubscriptionKey(null)
            call.ok(JsonNull)
        }
        get("nodes/$node/services") {
            call.authed() ?: return@get
            call.ok(state.services(node).orEmpty())
        }
        post("nodes/$node/services/{service}/{action}") {
            val s = call.writer() ?: return@post
            state.serviceAction(node, call.parameters["service"]!!, call.parameters["action"]!!, s.user)
                .fold({ call.ok(it) }, { call.fail(400, it.message ?: "error") })
        }
        get("nodes/$node/journal") {
            call.authed() ?: return@get
            call.ok(state.journal(node, call.request.queryParameters["lastentries"]?.toIntOrNull() ?: 500))
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Checks the ticket cookie (and CSRF header for writes). Responds 401 and returns null if invalid. */
    /** Rules and options of one firewall scope; [scopeOf] gives its key, like Proxmox's API path. */
    private fun Route.firewall(path: String, scopeOf: (ApplicationCall) -> String) {
        firewallRules("$path/rules", scopeOf)
        get("$path/options") {
            call.authed() ?: return@get
            call.ok(JsonObject(state.firewallOptions(scopeOf(call)).mapValues { (k, v) -> if (k == "digest") JsonPrimitive(v) else v.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(v) }))
        }
        put("$path/options") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.updateFirewallOptions(scopeOf(call), p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("$path/aliases") {
            call.authed() ?: return@get
            call.ok(state.firewallAliases(scopeOf(call)))
        }
        post("$path/aliases") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.createFirewallAlias(scopeOf(call), p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("$path/aliases/{name}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.updateFirewallAlias(scopeOf(call), call.parameters["name"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("$path/aliases/{name}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (Sys.Modify)")
            val error = state.deleteFirewallAlias(scopeOf(call), call.parameters["name"]!!, call.request.queryParameters["digest"])
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("$path/ipset") {
            call.authed() ?: return@get
            call.ok(state.firewallIpSets(scopeOf(call)))
        }
        post("$path/ipset") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.createFirewallIpSet(scopeOf(call), p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("$path/ipset/{name}") {
            call.authed() ?: return@get
            val name = call.parameters["name"]!!
            call.ok(state.firewallIpSetEntries(scopeOf(call), name) ?: return@get call.fail(500, "no such IPSet '$name'"))
        }
        post("$path/ipset/{name}") {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.addIpSetEntry(scopeOf(call), call.parameters["name"]!!, p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("$path/ipset/{name}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (Sys.Modify)")
            val error = state.deleteFirewallIpSet(scopeOf(call), call.parameters["name"]!!, call.request.queryParameters["force"] == "1")
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("$path/ipset/{name}/{cidr}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (Sys.Modify)")
            val error = state.removeIpSetEntry(scopeOf(call), call.parameters["name"]!!, call.parameters["cidr"]!!, call.request.queryParameters["digest"])
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        get("$path/log") {
            call.authed() ?: return@get
            val q = call.request.queryParameters
            val (lines, total) = state.firewallLog(scopeOf(call), q["start"]?.toIntOrNull() ?: 0, q["limit"]?.toIntOrNull() ?: 50)
            call.respondText(
                ProxmoxJson.encodeToString(ApiEnvelope(lines, total = total)),
                ContentType.Application.Json,
            )
        }
    }

    /** A rule list at [rulesPath] (`…/rules`, or a security group's own path). */
    private fun Route.firewallRules(rulesPath: String, scopeOf: (ApplicationCall) -> String) {
        get(rulesPath) {
            call.authed() ?: return@get
            call.ok(state.firewallRules(scopeOf(call)))
        }
        post(rulesPath) {
            val s = call.authed() ?: return@post
            if (state.isReadOnly(s.user)) return@post call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.createFirewallRule(scopeOf(call), p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        put("$rulesPath/{pos}") {
            val s = call.authed() ?: return@put
            if (state.isReadOnly(s.user)) return@put call.fail(403, "Permission check failed (Sys.Modify)")
            val p = call.receiveParameters()
            val error = state.updateFirewallRule(scopeOf(call), call.parameters["pos"]!!.toInt(), p.names().associateWith { p[it].orEmpty() })
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
        delete("$rulesPath/{pos}") {
            val s = call.authed() ?: return@delete
            if (state.isReadOnly(s.user)) return@delete call.fail(403, "Permission check failed (Sys.Modify)")
            val error = state.deleteFirewallRule(scopeOf(call), call.parameters["pos"]!!.toInt(), call.request.queryParameters["digest"])
            if (error != null) call.fail(400, error) else call.ok(JsonNull)
        }
    }

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

    /** `POST /nodes/{node}/{qemu|lxc}`: a restore (`archive=` / `ostemplate=` + `restore=1`) or a new guest. */
    private suspend fun ApplicationCall.createOrRestore(type: String) {
        val s = authed() ?: return
        val p = receiveParameters()
        val vmid = p["vmid"]?.toIntOrNull()
            ?: return fail(400, "Parameter verification failed.", mapOf("vmid" to "property is missing and it is not optional"))
        if (state.isReadOnly(s.user)) return fail(403, "Permission check failed (/vms/$vmid, VM.Allocate)")
        val backup = if (type == "qemu") p["archive"] else p["ostemplate"]?.takeIf { p["restore"] == "1" }
        val result = if (backup != null) {
            state.restore(parameters["node"]!!, type, vmid, backup, p["force"] == "1", s.user)
        } else {
            state.createGuest(parameters["node"]!!, type, p.entries().associate { it.key to it.value.first() }, s.user)
        }
        result.fold(onSuccess = { ok(it) }, onFailure = { fail(500, it.message ?: "error") })
    }

    /** `termproxy` (node shell when [type] is null, containers) and `vncproxy` (VMs). */
    private suspend fun ApplicationCall.consoleProxy(type: String?) {
        val s = authed() ?: return
        val nodeName = parameters["node"]!!
        val node = state.node(nodeName) ?: return fail(404, "no such node")
        if (!node.online) return fail(595, "Connection refused")
        val g = if (type != null) guest(type) ?: return else null
        val params = receiveParameters()
        if (g != null && g.status != "running") return fail(500, "${if (type == "qemu") "VM" else "CT"} ${g.vmid} not running")
        if (state.isReadOnly(s.user)) {
            return fail(403, if (g != null) "Permission check failed (/vms/${g.vmid}, VM.Console)" else "Permission check failed (/nodes/$nodeName, Sys.Console)")
        }
        val path = if (g != null) "nodes/$nodeName/$type/${g.vmid}/vncwebsocket" else "nodes/$nodeName/vncwebsocket"
        ok(state.issueConsole(s.user, path, name = g?.name ?: nodeName, vnc = type == "qemu", generatePassword = params["generate-password"] == "1"))
    }

    /** The console WebSocket: the session cookie plus a one-time `vncticket` for this path. */
    private suspend fun DefaultWebSocketServerSession.console(path: String, vnc: Boolean) {
        val signedIn = state.session(call.request.cookies.rawCookies[product.cookieName]) != null
        val q = call.request.queryParameters
        val console = state.takeConsole(q["vncticket"], q["port"], path)
        if (!signedIn || console == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "permission denied - invalid vncticket"))
            return
        }
        if (vnc) mockVnc(console) else mockTerminal(console)
    }

    /** The guest named by the path; [type] for routes with a literal `qemu`/`lxc` segment. */
    private suspend fun ApplicationCall.moveVolume(s: MockState.Session, g: MockState.Guest) {
        val p = receiveParameters()
        if (state.isReadOnly(s.user)) return fail(403, "Permission check failed (/vms/${g.vmid}, VM.Config.Disk)")
        state.moveVolume(g, p.entries().associate { it.key to it.value.first() }, s.user)
            .fold(onSuccess = { ok(it) }, onFailure = { fail(500, it.message ?: "error") })
    }

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
