package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.pbs.DatastoreInfo
import io.github.lucabarbalata.anprox.model.pbs.DatastoreUsage
import io.github.lucabarbalata.anprox.model.pve.AgentInterface
import io.github.lucabarbalata.anprox.model.pve.AgentIpAddress
import io.github.lucabarbalata.anprox.model.pve.AgentOsInfo
import io.github.lucabarbalata.anprox.model.pve.BackupVerification
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.ConsoleProxy
import io.github.lucabarbalata.anprox.model.pve.CpuInfo
import io.github.lucabarbalata.anprox.model.pve.FsUsage
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.HaStatus
import io.github.lucabarbalata.anprox.model.pve.LocalDisk
import io.github.lucabarbalata.anprox.model.pve.LxcInterface
import io.github.lucabarbalata.anprox.model.pve.MemoryUsage
import io.github.lucabarbalata.anprox.model.pve.MigrationCheck
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.PendingEntry
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import io.github.lucabarbalata.anprox.model.pve.RrdTimeframe
import io.github.lucabarbalata.anprox.model.pve.SnapshotName
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import io.github.lucabarbalata.anprox.model.pve.UrlMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.math.abs
import kotlin.math.sin

/**
 * The fake cluster behind [MockProxmoxServer]. Deliberately small but realistic: three nodes
 * (one offline), a mix of VMs/containers/templates, local and shared storage, a running task
 * history — enough to exercise every screen of the first milestone.
 *
 * Accounts (password for all: `anprox`):
 * - `root@pam`    — full access, no second factor
 * - `admin@pve`   — full access, TOTP (code `123456`) + recovery keys `aaaa-bbbb-cccc`, `dddd-eeee-ffff`
 * - `auditor@pve` — read-only; power actions fail with 403
 */
class MockState(
    val product: ProxmoxProduct,
    /** How long fake tasks "run". Tests use 0. */
    private val taskDurationMillis: Long = 1_500,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    val clusterName = "homelab"

    /** Ceph health for `GET /cluster/ceph/status`; null answers like a cluster without Ceph. */
    @Volatile
    var cephHealth: String? = null
    private val random = SecureRandom()
    private val lock = Any()

    // ------------------------------------------------------------------ accounts & sessions

    data class User(val id: String, val password: String, val totp: String? = null, val recovery: MutableList<String> = mutableListOf(), val readOnly: Boolean = false)
    data class Session(val user: String, val csrf: String, val issuedAtMillis: Long)

    val users = mapOf(
        "root@pam" to User("root@pam", PASSWORD),
        "admin@pve" to User("admin@pve", PASSWORD, totp = "123456", recovery = mutableListOf("aaaa-bbbb-cccc", "dddd-eeee-ffff")),
        "auditor@pve" to User("auditor@pve", PASSWORD, readOnly = true),
    )
    private val sessions = HashMap<String, Session>()
    private val partialTickets = HashMap<String, String>()

    val realms: List<Realm> = when (product) {
        ProxmoxProduct.PVE -> listOf(
            Realm("pam", "pam", "Linux PAM standard authentication", default = true),
            Realm("pve", "pve", "Proxmox VE authentication server"),
            Realm("corp", "ad", "Corporate Active Directory"),
            Realm("sso", "openid", "Authentik SSO"),
        )
        ProxmoxProduct.PBS -> listOf(
            Realm("pam", "pam", "Linux PAM standard authentication", default = true),
            Realm("pbs", "pbs", "Proxmox Backup authentication server"),
        )
    }

    sealed interface LoginOutcome {
        data class Ok(val user: String, val ticket: String, val csrf: String) : LoginOutcome
        data class NeedTfa(val user: String, val partialTicket: String) : LoginOutcome
        data object Failed : LoginOutcome
    }

    fun login(username: String, password: String, tfaChallenge: String?): LoginOutcome = synchronized(lock) {
        val user = users[username] ?: return LoginOutcome.Failed
        // Ticket renewal: the "password" is a still-valid ticket of the same user.
        sessions[password]?.let { s ->
            if (s.user == username && !expired(s)) return issue(user.id)
            return LoginOutcome.Failed
        }
        if (tfaChallenge != null) {
            if (partialTickets.remove(tfaChallenge) != username) return LoginOutcome.Failed
            val kind = password.substringBefore(':')
            val answer = password.substringAfter(':', "")
            val ok = when (kind) {
                "totp" -> answer == user.totp
                "recovery" -> user.recovery.remove(answer)
                else -> false
            }
            return if (ok) issue(user.id) else LoginOutcome.Failed
        }
        if (password != user.password) return LoginOutcome.Failed
        if (user.totp != null) {
            val challenge = """{"totp":true,"recovery":[${user.recovery.indices.joinToString(",")}]}"""
            val partial = "${product.ticketPrefix}:!tfa!" +
                URLEncoder.encode(challenge, StandardCharsets.UTF_8) + ":" + hexTime() + "::" + token()
            partialTickets[partial] = user.id
            return LoginOutcome.NeedTfa(user.id, partial)
        }
        return issue(user.id)
    }

    private fun issue(user: String): LoginOutcome.Ok {
        val ticket = "${product.ticketPrefix}:$user:${hexTime()}::${token()}"
        val csrf = "${hexTime()}:${token()}"
        sessions[ticket] = Session(user, csrf, clockMillis())
        return LoginOutcome.Ok(user, ticket, csrf)
    }

    /** Returns the session for a ticket cookie, or null if unknown/expired. */
    fun session(ticket: String?): Session? = synchronized(lock) {
        ticket?.let { sessions[it] }?.takeUnless { expired(it) }
    }

    /** Test hook: make every existing ticket look [ageMillis] older. */
    fun ageSessions(ageMillis: Long) = synchronized(lock) {
        sessions.replaceAll { _, s -> s.copy(issuedAtMillis = s.issuedAtMillis - ageMillis) }
    }

    fun isReadOnly(user: String) = users[user]?.readOnly == true

    private fun expired(s: Session) = clockMillis() - s.issuedAtMillis > 2 * 60 * 60 * 1000L
    private fun hexTime() = "%08X".format(clockMillis() / 1000)
    private fun token(): String {
        val bytes = ByteArray(24).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ------------------------------------------------------------------ PVE inventory

    data class Node(val name: String, val online: Boolean, val cpus: Int, val memory: Long, val rootfs: Long, val ip: String)

    data class Guest(
        val vmid: Int,
        val type: String,
        var node: String,
        var name: String,
        var status: String,
        var cpus: Int,
        var maxmem: Long,
        var maxdisk: Long,
        var template: Boolean = false,
        var tags: String? = null,
        var agent: Boolean = false,
        var onBoot: Boolean = false,
        var protection: Boolean = false,
        var description: String = "",
        var paused: Boolean = false,
        var startedAtMillis: Long = 0,
        val ha: Boolean = false,
        /** Where the guest's disks live; local storage ties it to its node. */
        var diskStorage: String = "local-zfs",
    )

    data class Storage(val node: String, val id: String, val plugin: String, val content: String, val shared: Boolean, val total: Long, val used: Long)

    val nodes = listOf(
        Node("pve1", online = true, cpus = 16, memory = 64 * GIB, rootfs = 100 * GIB, ip = "10.0.0.11"),
        Node("pve2", online = true, cpus = 12, memory = 48 * GIB, rootfs = 100 * GIB, ip = "10.0.0.12"),
        Node("pve3", online = false, cpus = 8, memory = 32 * GIB, rootfs = 100 * GIB, ip = "10.0.0.13"),
    )

    private val bootMillis = clockMillis()

    val guests = mutableListOf(
        Guest(100, "qemu", "pve1", "web", "running", 4, 8 * GIB, 64 * GIB, tags = "prod;web", agent = true, startedAtMillis = bootMillis - 9 * DAY, ha = true, diskStorage = "nas-nfs"),
        Guest(101, "qemu", "pve1", "db", "running", 8, 16 * GIB, 256 * GIB, tags = "prod;db", agent = true, startedAtMillis = bootMillis - 9 * DAY, ha = true, diskStorage = "nas-nfs"),
        Guest(102, "qemu", "pve2", "win11", "stopped", 4, 8 * GIB, 128 * GIB, tags = "desktop"),
        Guest(103, "qemu", "pve2", "k8s-master", "running", 4, 8 * GIB, 64 * GIB, tags = "k8s", agent = true, startedAtMillis = bootMillis - 2 * DAY),
        Guest(104, "qemu", "pve3", "lab-vm", "stopped", 2, 4 * GIB, 32 * GIB, tags = "lab"),
        Guest(9000, "qemu", "pve1", "ubuntu-2404-template", "stopped", 2, 2 * GIB, 32 * GIB, template = true, tags = "template", diskStorage = "nas-nfs"),
        Guest(200, "lxc", "pve1", "pihole", "running", 1, 512 * MIB, 8 * GIB, tags = "dns", startedAtMillis = bootMillis - 30 * DAY),
        Guest(201, "lxc", "pve2", "nginx-proxy", "running", 2, 1 * GIB, 8 * GIB, tags = "prod;web", startedAtMillis = bootMillis - 5 * DAY),
        Guest(202, "lxc", "pve2", "backup-client", "stopped", 1, 1 * GIB, 16 * GIB),
    )

    val storages = nodes.flatMap { n ->
        listOf(
            Storage(n.name, "local", "dir", "iso,vztmpl,backup", shared = false, total = 100 * GIB, used = 38 * GIB),
            Storage(n.name, "local-zfs", "zfspool", "images,rootdir", shared = false, total = 1800 * GIB, used = (if (n.name == "pve1") 1650 else 700) * GIB),
            Storage(n.name, "nas-nfs", "nfs", "images,backup,iso", shared = true, total = 8000 * GIB, used = 5100 * GIB),
            Storage(n.name, "pbs", "pbs", "backup", shared = true, total = 12000 * GIB, used = 7300 * GIB),
        )
    }

    private val hardware = MockHardware(storages, nodes)
    private val jobs = MockJobs(storages, clockMillis)
    private val firewall = MockFirewall()
    private val haManager = MockHa(guests)
    private val replication = MockReplication(clockMillis)

    /** Accepted `POST /nodes/{node}/status` calls (node, command); the mock nodes keep running. */
    val nodePowerRequests: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

    /** Console tickets from `termproxy` / `vncproxy`; each opens one WebSocket. */
    private val consoleTickets = java.util.concurrent.ConcurrentHashMap<String, MockConsoleTicket>()

    /**
     * Hands out a console ticket like Proxmox. For VNC with [generatePassword] (PVE 7.1+) the
     * client must answer the VNC challenge with the returned password, otherwise with the ticket.
     */
    fun issueConsole(user: String, websocketPath: String, name: String, vnc: Boolean, generatePassword: Boolean): ConsoleProxy {
        val ticket = "PVEVNC:${hexTime()}::${token()}"
        val password = if (vnc && generatePassword) token().take(8) else null
        val port = (5900 + consoleTickets.size % 100).toString()
        consoleTickets[ticket] = MockConsoleTicket(ticket, port, user, websocketPath, name, vncPassword = if (vnc) password ?: ticket else null)
        return ConsoleProxy(port = JsonPrimitive(port), ticket = ticket, user = user, password = password)
    }

    /** The ticket's console if [ticket] and [port] match one handed out for [websocketPath]; each ticket works once. */
    fun takeConsole(ticket: String?, port: String?, websocketPath: String): MockConsoleTicket? =
        ticket?.let(consoleTickets::remove)?.takeIf { it.port == port && it.websocketPath == websocketPath }

    fun guest(vmid: Int): Guest? = synchronized(lock) { guests.firstOrNull { it.vmid == vmid } }
    fun node(name: String): Node? = nodes.firstOrNull { it.name == name }

    /** Smooth pseudo-random load so charts and gauges look alive. */
    private fun wave(seed: Int, base: Double, amplitude: Double): Double {
        val t = clockMillis() / 1000.0
        return (base + amplitude * abs(sin(t / 37.0 + seed))).coerceIn(0.0, 1.0)
    }

    fun clusterStatus(): List<ClusterStatusEntry> = buildList {
        add(ClusterStatusEntry(type = "cluster", id = "cluster", name = clusterName, nodes = nodes.size, quorate = true, version = 12))
        nodes.forEachIndexed { i, n ->
            add(ClusterStatusEntry(type = "node", id = "node/${n.name}", name = n.name, online = n.online, local = i == 0, ip = n.ip, nodeid = i + 1, level = ""))
        }
    }

    fun resources(type: String?): List<ClusterResource> = synchronized(lock) {
        settleTasks()
        val all = buildList {
            nodes.forEachIndexed { i, n ->
                val running = guests.filter { it.node == n.name && it.status == "running" }
                add(
                    ClusterResource(
                        id = "node/${n.name}", type = "node", node = n.name, name = n.name,
                        status = if (n.online) "online" else "offline",
                        cpu = if (n.online) wave(i, 0.08, 0.35) else null, maxcpu = n.cpus.toDouble(),
                        mem = if (n.online) running.sumOf { it.maxmem * 3 / 5 } + 6 * GIB else null, maxmem = n.memory,
                        disk = if (n.online) n.rootfs / 4 else null, maxdisk = n.rootfs,
                        uptime = if (n.online) (clockMillis() - bootMillis) / 1000 + 40 * DAY / 1000 else null,
                    ),
                )
            }
            guests.forEach { g -> add(g.toResource()) }
            storages.forEach { s ->
                val online = node(s.node)?.online == true
                add(
                    ClusterResource(
                        id = "storage/${s.node}/${s.id}", type = "storage", node = s.node, storage = s.id,
                        status = if (online) "available" else "unknown", plugintype = s.plugin, content = s.content,
                        shared = s.shared, disk = if (online) s.used else null, maxdisk = if (online) s.total else null,
                    ),
                )
            }
        }
        when (type) {
            null -> all
            "vm" -> all.filter { it.type == "qemu" || it.type == "lxc" }
            else -> all.filter { it.type == type }
        }
    }

    private fun Guest.toResource(): ClusterResource {
        val running = status == "running"
        return ClusterResource(
            id = "$type/$vmid", type = type, node = node, vmid = vmid, name = name, status = status,
            cpu = if (running) wave(vmid, 0.02, 0.4) else 0.0, maxcpu = cpus.toDouble(),
            mem = if (running) (maxmem * wave(vmid + 7, 0.35, 0.4)).toLong() else 0, maxmem = maxmem,
            disk = if (type == "lxc") maxdisk / 3 else 0, maxdisk = maxdisk,
            uptime = if (running) (clockMillis() - startedAtMillis) / 1000 else 0,
            netin = if (running) vmid * 1_234_567L else 0, netout = if (running) vmid * 765_432L else 0,
            template = template, tags = tags, hastate = haManager.state(this),
        )
    }

    fun guestStatus(g: Guest): GuestStatus = synchronized(lock) {
        settleTasks()
        val r = g.toResource()
        GuestStatus(
            status = g.status, qmpstatus = if (g.type == "qemu") (if (g.paused) "paused" else g.status) else null,
            name = g.name, cpus = g.cpus.toDouble(), cpu = r.cpu, mem = r.mem, maxmem = g.maxmem,
            disk = r.disk, maxdisk = g.maxdisk, uptime = r.uptime, netin = r.netin, netout = r.netout,
            tags = g.tags, agent = g.agent, template = g.template, ha = HaStatus(managed = g.ha),
            runningQemu = if (g.type == "qemu" && g.status == "running") "10.0.2" else null,
            runningMachine = if (g.type == "qemu" && g.status == "running") "pc-q35-10.0+pve1" else null,
        )
    }

    fun nodeStatus(n: Node): NodeStatus {
        val i = nodes.indexOf(n)
        return NodeStatus(
            cpu = wave(i, 0.08, 0.35), wait = 0.01,
            loadavg = listOf("1.12", "0.98", "0.87"),
            memory = MemoryUsage(total = n.memory, used = n.memory / 2, free = n.memory / 2),
            swap = MemoryUsage(total = 8 * GIB, used = 0, free = 8 * GIB),
            rootfs = FsUsage(total = n.rootfs, used = n.rootfs / 4, avail = n.rootfs * 3 / 4),
            uptime = (clockMillis() - bootMillis) / 1000 + 40 * DAY / 1000,
            cpuinfo = CpuInfo(model = "AMD EPYC 4464P 12-Core Processor", cpus = n.cpus, sockets = 1, cores = n.cpus / 2, mhz = "3700.000"),
            kversion = "Linux 6.14.11-2-pve #1 SMP PREEMPT_DYNAMIC PMX 6.14.11-2",
            pveversion = "pve-manager/9.0.10/deadbeefcafebabe",
        )
    }

    // ------------------------------------------------------------------ performance history

    /** A smooth, repeatable 0..1 curve over time, different per [seed]. */
    private fun history(seed: Int, t: Long, period: Double): Double {
        val slow = sin(t / period + seed)
        val fast = sin(t / (period / 7.3) + seed * 2.1)
        return (0.5 + 0.35 * slow + 0.15 * fast).coerceIn(0.0, 1.0)
    }

    /** Sample times ending now, oldest first, like Proxmox (70 samples per timeframe). */
    private fun sampleTimes(timeframe: RrdTimeframe): List<Long> {
        val end = clockMillis() / 1000 / timeframe.stepSeconds * timeframe.stepSeconds
        return (RRD_SAMPLES - 1 downTo 0).map { end - it * timeframe.stepSeconds }
    }

    /** `null` when [timeframe] is unknown. An offline node only has data from before it went down. */
    fun nodeRrd(n: Node, timeframe: String): List<RrdPoint>? {
        val tf = RrdTimeframe.entries.firstOrNull { it.apiName == timeframe } ?: return null
        val i = nodes.indexOf(n)
        val period = tf.stepSeconds * 9.0
        return sampleTimes(tf).mapIndexed { index, t ->
            if (!n.online && index >= RRD_SAMPLES / 2) return@mapIndexed RrdPoint(time = t, maxcpu = n.cpus.toDouble())
            val cpu = 0.06 + 0.4 * history(i, t, period)
            RrdPoint(
                time = t, cpu = cpu, maxcpu = n.cpus.toDouble(),
                iowait = 0.002 + 0.03 * history(i + 5, t, period),
                loadavg = n.cpus * cpu * 1.1,
                memtotal = n.memory.toDouble(), memused = n.memory * (0.4 + 0.2 * history(i + 9, t, period)),
                swaptotal = 8.0 * GIB, swapused = 0.0,
                roottotal = n.rootfs.toDouble(), rootused = n.rootfs / 4.0,
                netin = 40_000 + 2_500_000 * history(i + 13, t, period),
                netout = 25_000 + 1_200_000 * history(i + 17, t, period),
            )
        }
    }

    /** Running guests have data throughout; stopped ones only for the older part (they ran back then). */
    fun guestRrd(g: Guest, timeframe: String): List<RrdPoint>? = synchronized(lock) {
        settleTasks()
        val tf = RrdTimeframe.entries.firstOrNull { it.apiName == timeframe } ?: return null
        val period = tf.stepSeconds * 9.0
        sampleTimes(tf).mapIndexed { index, t ->
            val ran = !g.template && (g.status == "running" || index < RRD_SAMPLES * 2 / 5)
            val base = RrdPoint(
                time = t, maxcpu = g.cpus.toDouble(), maxmem = g.maxmem.toDouble(), maxdisk = g.maxdisk.toDouble(),
                disk = if (g.type == "lxc") g.maxdisk / 3.0 else 0.0,
            )
            if (!ran) return@mapIndexed base
            base.copy(
                cpu = 0.02 + 0.5 * history(g.vmid, t, period),
                mem = g.maxmem * (0.3 + 0.45 * history(g.vmid + 3, t, period)),
                netin = 5_000 + 900_000 * history(g.vmid + 7, t, period),
                netout = 3_000 + 400_000 * history(g.vmid + 11, t, period),
                diskread = 20_000 * history(g.vmid + 19, t, period),
                diskwrite = 150_000 * history(g.vmid + 23, t, period),
            )
        }
    }

    // ------------------------------------------------------------------ tasks

    data class Task(
        val upid: String,
        val node: String,
        val type: String,
        val id: String,
        val user: String,
        val startMillis: Long,
        val endMillis: Long,
        val exitStatus: String,
        val effect: (() -> Unit)?,
        var applied: Boolean = false,
    )

    private val tasks = mutableListOf<Task>()
    private var pidCounter = 0x1000

    init {
        // A little history so the task list isn't empty on first launch.
        val now = clockMillis()
        if (product == ProxmoxProduct.PVE) {
            seedTask("pve1", "vzdump", "", "root@pam", now - 6 * HOUR, 14 * MINUTE, "OK")
            seedTask("pve2", "vzdump", "", "root@pam", now - 5 * HOUR, 9 * MINUTE, "WARNINGS: 1")
            seedTask("pve1", "qmstart", "100", "root@pam", now - 3 * HOUR, 3_000, "OK")
            seedTask("pve2", "qmigrate", "104", "admin@pve", now - 2 * HOUR, 70_000, "migration aborted: target node pve3 is offline")
            seedTask("pve2", "aptupdate", "", "root@pam", now - 1 * HOUR, 20_000, "OK")
        } else {
            seedTask("pbs", "garbage_collection", "store1", "root@pam", now - 4 * HOUR, 120_000, "OK")
            seedTask("pbs", "verificationjob", "store1:v-daily", "root@pam", now - 3 * HOUR, 600_000, "OK")
            seedTask("pbs", "syncjob", "store2:s-offsite", "root@pam", now - 2 * HOUR, 300_000, "sync failed: remote unreachable")
        }
    }

    /** Test hook: the guest stops on its own (a crash, an out-of-memory kill), without a task. */
    fun crash(vmid: Int) = synchronized(lock) {
        guests.first { it.vmid == vmid }.status = "stopped"
    }

    /** Test hook: a task that just ended with [status]. */
    fun finishTask(node: String, type: String, id: String, status: String) = synchronized(lock) {
        val now = clockMillis()
        seedTask(node, type, id, "root@pam", now - 1_000, 1_000, status)
    }

    private fun seedTask(node: String, type: String, id: String, user: String, start: Long, duration: Long, status: String) {
        tasks += Task(upid(node, type, id, user, start), node, type, id, user, start, start + duration, status, effect = null, applied = true)
    }

    private fun upid(node: String, type: String, id: String, user: String, startMillis: Long): String {
        val pid = pidCounter++
        val start = "%08X".format(startMillis / 1000)
        return if (product == ProxmoxProduct.PVE) {
            "UPID:$node:%08X:%08X:$start:$type:$id:$user:".format(pid, pid * 7)
        } else {
            "UPID:$node:%08X:%08X:%08X:$start:$type:${id.replace(":", "\\x3a")}:$user:".format(pid, pid * 7, tasks.size)
        }
    }

    /** Creates a task that finishes after [taskDurationMillis] and then runs [effect]. */
    fun startTask(node: String, type: String, id: String, user: String, exitStatus: String = "OK", effect: (() -> Unit)? = null): String =
        synchronized(lock) {
            val now = clockMillis()
            val t = Task(upid(node, type, id, user, now), node, type, id, user, now, now + taskDurationMillis, exitStatus, effect)
            tasks += t
            if (taskDurationMillis <= 0) settleTasks()
            t.upid
        }

    /** Applies the effects of tasks whose time is up. Called before every read. */
    private fun settleTasks() {
        val now = clockMillis()
        tasks.filter { !it.applied && now >= it.endMillis }.forEach {
            it.effect?.invoke()
            it.applied = true
        }
    }

    fun recentTasks(limit: Int = 50): List<TaskSummary> = synchronized(lock) {
        settleTasks()
        tasks.sortedByDescending { it.startMillis }.take(limit).map { it.summary() }
    }

    fun taskStatus(upid: String): TaskStatus? = synchronized(lock) {
        settleTasks()
        val t = tasks.firstOrNull { it.upid == upid } ?: return null
        TaskStatus(
            status = if (t.applied) "stopped" else "running",
            exitstatus = if (t.applied) t.exitStatus else null,
            upid = t.upid, node = t.node, type = t.type, id = t.id, user = t.user, starttime = t.startMillis / 1000,
        )
    }

    fun taskLog(upid: String): List<TaskLogLine>? = synchronized(lock) {
        settleTasks()
        val t = tasks.firstOrNull { it.upid == upid } ?: return null
        buildList {
            add(TaskLogLine(1, "starting ${t.type} ${t.id}".trim()))
            if (t.type == "vzdump") {
                add(TaskLogLine(2, "INFO: starting new backup job: vzdump --all 1 --mode snapshot --storage pbs"))
                add(TaskLogLine(3, "INFO: Backup job finished successfully"))
            }
            if (t.applied) add(TaskLogLine(size + 1, "TASK ${if (t.exitStatus == "OK") "OK" else "ERROR: ${t.exitStatus}"}"))
        }
    }

    private fun Task.summary() = TaskSummary(
        upid = upid, node = node, type = type, id = id.ifEmpty { null }, user = user,
        starttime = startMillis / 1000,
        endtime = if (applied) endMillis / 1000 else null,
        status = if (applied) exitStatus else null,
    )

    // ------------------------------------------------------------------ power actions

    /** Returns a UPID, or an error message (the API answers 500 with it). */
    fun power(g: Guest, action: String, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        val label = if (g.type == "qemu") "VM" else "CT"
        val prefix = if (g.type == "qemu") "qm" else "vz"
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val running = g.status == "running"
        when (action) {
            "start" -> when {
                g.template -> return fail("$label ${g.vmid} is a template")
                running -> return fail("$label ${g.vmid} already running")
                node(g.node)?.online != true -> return fail("node '${g.node}' seems to be offline")
            }
            "shutdown", "stop", "reboot", "reset", "suspend" -> if (!running) return fail("$label ${g.vmid} not running")
            "resume" -> if (!g.paused) return fail("$label ${g.vmid} not paused")
            else -> return fail("unknown action '$action'")
        }
        val upid = startTask(g.node, prefix + action, g.vmid.toString(), user) {
            when (action) {
                "start" -> {
                    hardware.applyPending(g)
                    g.status = "running"
                    g.paused = false
                    g.startedAtMillis = clockMillis()
                }
                "shutdown", "stop" -> { g.status = "stopped"; g.paused = false }
                "reboot" -> {
                    hardware.applyPending(g)
                    g.startedAtMillis = clockMillis()
                    g.paused = false
                }
                "reset" -> { g.startedAtMillis = clockMillis(); g.paused = false }
                "suspend" -> g.paused = true
                "resume" -> g.paused = false
            }
        }
        Result.success(upid)
    }

    // ------------------------------------------------------------------ snapshots

    data class Snap(val name: String, var parent: String?, val description: String, val createdMillis: Long, val vmstate: Boolean)

    /** vmid → its snapshots, and vmid → the snapshot it currently runs on (parent of `current`). */
    private val snaps = HashMap<Int, MutableList<Snap>>()
    private val snapHead = HashMap<Int, String?>()

    init {
        if (product == ProxmoxProduct.PVE) {
            val now = clockMillis()
            // web: rolled back once, so the tree has a branch.
            snaps[100] = mutableListOf(
                Snap("before-upgrade", null, "Before the apt full-upgrade to PVE 9", now - 20 * DAY, vmstate = true),
                Snap("after-upgrade", "before-upgrade", "", now - 19 * DAY, vmstate = false),
                Snap("kernel-test", "before-upgrade", "Trying the 6.14 kernel", now - 10 * DAY, vmstate = false),
            )
            snapHead[100] = "after-upgrade"
            snaps[102] = mutableListOf(Snap("fresh-install", null, "Clean Windows 11 24H2 install", now - 40 * DAY, vmstate = false))
            snapHead[102] = "fresh-install"
            snaps[200] = mutableListOf(Snap("pre-update", null, "", now - 3 * DAY, vmstate = false))
            snapHead[200] = "pre-update"
        }
    }

    fun snapshots(g: Guest): List<GuestSnapshot> = synchronized(lock) {
        settleTasks()
        snaps[g.vmid].orEmpty().map {
            GuestSnapshot(it.name, it.description, it.createdMillis / 1000, it.parent, vmstate = it.vmstate)
        } + GuestSnapshot(
            GuestSnapshot.CURRENT, "You are here!", parent = snapHead[g.vmid],
            running = g.type == "qemu" && g.status == "running",
        )
    }

    /** Returns a UPID, or an error message (the API answers 500 with it). Name format is checked by the route. */
    fun createSnapshot(g: Guest, name: String, description: String, vmstate: Boolean, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        val list = snaps.getOrPut(g.vmid) { mutableListOf() }
        if (SnapshotName.validate(name, list.map { it.name }) == SnapshotName.Problem.DUPLICATE) {
            return Result.failure(IllegalStateException("snapshot name '$name' already used"))
        }
        val prefix = if (g.type == "qemu") "qm" else "vz"
        val upid = startTask(g.node, prefix + "snapshot", g.vmid.toString(), user) {
            list += Snap(name, snapHead[g.vmid], description, clockMillis(), vmstate = vmstate && g.type == "qemu" && g.status == "running")
            snapHead[g.vmid] = name
        }
        Result.success(upid)
    }

    /** A rollback stops the guest; a VM snapshot that includes RAM resumes running instead. */
    fun rollbackSnapshot(g: Guest, name: String, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        val snap = snaps[g.vmid]?.firstOrNull { it.name == name }
            ?: return Result.failure(IllegalStateException("snapshot '$name' does not exist"))
        val prefix = if (g.type == "qemu") "qm" else "vz"
        val upid = startTask(g.node, prefix + "rollback", g.vmid.toString(), user) {
            snapHead[g.vmid] = name
            g.paused = false
            g.status = if (snap.vmstate) "running" else "stopped"
            if (snap.vmstate) g.startedAtMillis = clockMillis()
        }
        Result.success(upid)
    }

    /** Children of a deleted snapshot move up to its parent, like in Proxmox. */
    fun deleteSnapshot(g: Guest, name: String, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        val list = snaps[g.vmid]
        val snap = list?.firstOrNull { it.name == name }
            ?: return Result.failure(IllegalStateException("snapshot '$name' does not exist"))
        val prefix = if (g.type == "qemu") "qm" else "vz"
        val upid = startTask(g.node, prefix + "delsnapshot", g.vmid.toString(), user) {
            list.remove(snap)
            list.filter { it.parent == name }.forEach { it.parent = snap.parent }
            if (snapHead[g.vmid] == name) snapHead[g.vmid] = snap.parent
        }
        Result.success(upid)
    }

    // ------------------------------------------------------------------ guest config

    private fun Guest.digest(): String =
        Integer.toHexString(listOf(name, tags, agent, onBoot, protection, description, hardware.digestPart(this)).hashCode()).padStart(8, '0')

    /** `GET …/config`: the options, then the hardware with pending values applied (Proxmox's default). */
    fun guestConfig(g: Guest): JsonObject = synchronized(lock) {
        settleTasks()
        buildJsonObject {
            put(if (g.type == "qemu") "name" else "hostname", g.name)
            put("onboot", if (g.onBoot) 1 else 0)
            if (g.protection) put("protection", 1)
            if (g.description.isNotEmpty()) put("description", g.description)
            g.tags?.let { put("tags", it) }
            if (g.type == "qemu" && g.agent) put("agent", "enabled=1,fstrim_cloned_disks=1")
            hardware.effective(g).forEach { (k, v) ->
                // Proxmox sends numbers as numbers for some keys and strings for others; the app must cope with both.
                when {
                    k == "cores" || k == "sockets" || (k == "memory" && g.type == "lxc") -> put(k, v.toIntOrNull() ?: 0)
                    k == "cipassword" -> put(k, "**********") // Proxmox never returns it
                    else -> put(k, v)
                }
            }
            put("digest", g.digest())
        }
    }

    /** `GET …/pending`. */
    fun guestPending(g: Guest): List<PendingEntry> = synchronized(lock) {
        settleTasks()
        hardware.pendingEntries(g)
    }

    /**
     * `PUT …/config` (and `POST` for VMs): options and hardware. Returns an error message, or null
     * when applied.
     */
    fun updateGuestConfig(g: Guest, params: Map<String, String>): String? = synchronized(lock) {
        settleTasks()
        params["digest"]?.let { if (it != g.digest()) return "detected modified configuration - file changed by other user? Try again." }
        val nameKey = if (g.type == "qemu") "name" else "hostname"
        val nameRule = Regex("^(?:(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])\\.)*(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])$")
        params[nameKey]?.let { if (!nameRule.matches(it)) return "$nameKey: invalid format - value does not look like a valid DNS name" }
        val delete = params["delete"]?.split(',')?.map { it.trim() }.orEmpty()
        hardware.update(
            g,
            params.filterKeys { it !in hardware.optionKeys },
            delete.filter { it !in hardware.optionKeys },
        )?.let { return it }
        params[nameKey]?.let { g.name = it }
        params["onboot"]?.let { g.onBoot = it == "1" }
        params["protection"]?.let { g.protection = it == "1" }
        params["description"]?.let { g.description = it }
        params["tags"]?.let { g.tags = it }
        params["agent"]?.let { g.agent = it.startsWith("1") || it.contains("enabled=1") }
        if ("description" in delete) g.description = ""
        if ("tags" in delete) g.tags = null
        null
    }

    /** `POST …/qemu/{vmid}/config`: the same change, reported as a task. */
    fun updateVmConfigAsync(g: Guest, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        updateGuestConfig(g, params)?.let { return Result.failure(IllegalStateException(it)) }
        Result.success(startTask(g.node, "qmconfig", g.vmid.toString(), user))
    }

    /** `PUT …/resize`: grows the disk at once, reported as a task. */
    fun resize(g: Guest, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        params["digest"]?.let { if (it != g.digest()) return Result.failure(IllegalStateException("detected modified configuration - file changed by other user? Try again.")) }
        val disk = params["disk"] ?: return Result.failure(IllegalStateException("missing disk"))
        val size = params["size"] ?: return Result.failure(IllegalStateException("missing size"))
        hardware.resize(g, disk, size)?.let { return Result.failure(IllegalStateException(it)) }
        Result.success(startTask(g.node, "resize", g.vmid.toString(), user))
    }

    /** `move_disk` (VMs) / `move_volume` (containers). */
    fun moveVolume(g: Guest, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        params["digest"]?.let { if (it != g.digest()) return Result.failure(IllegalStateException("detected modified configuration - file changed by other user? Try again.")) }
        val key = params[if (g.type == "qemu") "disk" else "volume"] ?: return Result.failure(IllegalStateException("missing disk"))
        val storage = params["storage"] ?: return Result.failure(IllegalStateException("missing storage"))
        hardware.checkMove(g, key, storage)?.let { return Result.failure(IllegalStateException(it)) }
        val delete = params["delete"] == "1"
        Result.success(
            startTask(g.node, if (g.type == "qemu") "qmmove" else "move_volume", g.vmid.toString(), user) {
                hardware.moveEffect(g, key, storage, delete)
            },
        )
    }

    /** `POST …/spiceproxy`: like Proxmox, only for running VMs with a SPICE (`qxl`) display. */
    fun spiceProxy(g: Guest, proxy: String?): Result<JsonObject> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<JsonObject>(IllegalStateException(msg))
        if (g.status != "running") return fail("VM ${g.vmid} not running")
        val vga = hardware.effective(g)["vga"].orEmpty()
        if (!vga.startsWith("qxl")) return fail("VM ${g.vmid} has no SPICE display (set Display to SPICE)")
        Result.success(
            buildJsonObject {
                put("type", "spice")
                put("title", "VM ${g.vmid} - ${g.name}")
                put("host", "pvespiceproxy:${hexTime()}:${g.vmid}:${g.node}::${token().take(40)}")
                put("password", token().take(32))
                put("proxy", "http://${proxy ?: node(g.node)!!.ip}:3128")
                put("tls-port", 61000)
                put("host-subject", "OU=PVE Cluster Node,O=Proxmox Virtual Environment,CN=${g.node}.$clusterName")
                put("ca", "-----BEGIN CERTIFICATE-----\nMIIFakeMockCertificateForTheTestsOnly\n-----END CERTIFICATE-----\n")
                put("delete-this-file", 1)
                put("secure-attention", "Ctrl+Alt+Ins")
                put("toggle-fullscreen", "Shift+F11")
                put("release-cursor", "Ctrl+Alt+R")
            },
        )
    }

    /** `GET /nodes/{node}/network`; [type] `any_bridge` lists bridges only. */
    fun nodeNetworks(type: String?): List<NodeNetwork> =
        if (type == "any_bridge" || type == "bridge") hardware.bridges.filter { it.isBridge } else hardware.bridges

    // ------------------------------------------------------------------ guest agent

    private val agentOs = mapOf(
        100 to AgentOsInfo("Debian GNU/Linux", "Debian GNU/Linux 12 (bookworm)", "12", "6.1.0-25-amd64", "debian"),
        101 to AgentOsInfo("Ubuntu", "Ubuntu 24.04.1 LTS", "24.04.1 LTS (Noble Numbat)", "6.8.0-45-generic", "ubuntu"),
        103 to AgentOsInfo("Ubuntu", "Ubuntu 24.04.1 LTS", "24.04.1 LTS (Noble Numbat)", "6.8.0-45-generic", "ubuntu"),
    )

    /** Like Proxmox: an error message (500) when the agent can't answer. */
    private fun agentProblem(g: Guest): String? = when {
        !g.agent -> "No QEMU guest agent configured"
        g.status != "running" -> "VM ${g.vmid} is not running"
        else -> null
    }

    fun agentInterfaces(g: Guest): Result<List<AgentInterface>> = synchronized(lock) {
        settleTasks()
        agentProblem(g)?.let { return Result.failure(IllegalStateException(it)) }
        Result.success(
            listOf(
                AgentInterface("lo", "00:00:00:00:00:00", listOf(AgentIpAddress("127.0.0.1", "ipv4", 8), AgentIpAddress("::1", "ipv6", 128))),
                AgentInterface(
                    "ens18", "bc:24:11:00:%02x:%02x".format(g.vmid / 256, g.vmid % 256),
                    listOf(
                        AgentIpAddress("10.0.0.${g.vmid % 256}", "ipv4", 24),
                        AgentIpAddress("2001:db8::${g.vmid}", "ipv6", 64),
                        AgentIpAddress("fe80::be24:11ff:fe00:${g.vmid}", "ipv6", 64),
                    ),
                ),
            ),
        )
    }

    fun agentOsInfo(g: Guest): Result<AgentOsInfo> = synchronized(lock) {
        settleTasks()
        agentProblem(g)?.let { return Result.failure(IllegalStateException(it)) }
        Result.success(agentOs[g.vmid] ?: AgentOsInfo("Debian GNU/Linux", "Debian GNU/Linux 12 (bookworm)", "12", id = "debian"))
    }

    fun lxcInterfaces(g: Guest): Result<List<LxcInterface>> = synchronized(lock) {
        settleTasks()
        if (g.status != "running") return Result.failure(IllegalStateException("CT ${g.vmid} not running"))
        Result.success(
            listOf(
                LxcInterface("lo", "00:00:00:00:00:00", "127.0.0.1/8", "::1/128"),
                LxcInterface("eth0", "bc:24:11:01:00:%02x".format(g.vmid % 256), "10.0.1.${g.vmid - 190}/24", "fe80::1/64"),
            ),
        )
    }

    // ------------------------------------------------------------------ backups

    /** A backup plus the guest settings it carries, so a restore can recreate the guest. */
    data class MockBackup(
        val storage: String,
        /** Where it was taken; only matters for node-local storages such as `local`. */
        val node: String,
        val vmid: Int,
        val type: String,
        val createdMillis: Long,
        val size: Long,
        val notes: String,
        val isProtected: Boolean,
        /** Backup Server verification: true ok, false failed, null not verified (yet). */
        val verified: Boolean?,
        val guestName: String,
        val cpus: Int,
        val maxmem: Long,
        val maxdisk: Long,
        val volid: String,
    )

    private val backups = mutableListOf<MockBackup>()

    private fun storagePlugin(id: String) = storages.firstOrNull { it.id == id }?.plugin

    private fun newBackup(storage: String, g: Guest, createdMillis: Long, notes: String, isProtected: Boolean = false, verified: Boolean? = null): MockBackup {
        val instant = Instant.ofEpochMilli(createdMillis).truncatedTo(ChronoUnit.SECONDS)
        val volid = if (storagePlugin(storage) == "pbs") {
            "$storage:backup/${if (g.type == "qemu") "vm" else "ct"}/${g.vmid}/$instant"
        } else {
            val stamp = DateTimeFormatter.ofPattern("yyyy_MM_dd-HH_mm_ss").withZone(ZoneOffset.UTC).format(instant)
            "$storage:backup/vzdump-${g.type}-${g.vmid}-$stamp.${if (g.type == "qemu") "vma.zst" else "tar.zst"}"
        }
        return MockBackup(
            storage, g.node, g.vmid, g.type, createdMillis, size = g.maxdisk / 4, notes = notes, isProtected = isProtected,
            verified = verified, guestName = g.name, cpus = g.cpus, maxmem = g.maxmem, maxdisk = g.maxdisk, volid = volid,
        )
    }

    init {
        if (product == ProxmoxProduct.PVE) {
            val now = clockMillis()
            fun add(vmid: Int, storage: String, daysAgo: Int, notes: String, isProtected: Boolean = false, verified: Boolean? = null) {
                backups += newBackup(storage, guest(vmid)!!, now - daysAgo * DAY - 2 * HOUR, notes, isProtected, verified)
            }
            add(100, "pbs", 1, "web", verified = true)
            add(100, "pbs", 8, "web — before the PVE 9 upgrade", isProtected = true, verified = true)
            add(100, "local", 3, "web")
            add(101, "pbs", 1, "db", verified = false)
            add(102, "nas-nfs", 5, "win11")
            add(200, "pbs", 2, "pihole", verified = true)
            add(201, "local", 4, "nginx-proxy")
        }
    }

    private fun MockBackup.toItem() = StorageContentItem(
        volid = volid, content = "backup", ctime = createdMillis / 1000, size = size,
        format = if (storagePlugin(storage) == "pbs") (if (type == "qemu") "pbs-vm" else "pbs-ct") else volid.substringAfterLast('-').substringAfter('.'),
        notes = notes.ifEmpty { null }, isProtected = isProtected, vmid = vmid, subtype = type,
        verification = verified?.let { BackupVerification(if (it) "ok" else "failed") },
    )

    fun nodeStorages(n: Node, content: String?): List<NodeStorage> = storages
        .filter { it.node == n.name && (content == null || content in it.content.split(',')) }
        .map { NodeStorage(it.id, it.plugin, it.content, active = n.online, enabled = true, shared = it.shared, total = it.total, used = it.used, avail = it.total - it.used) }

    /** `null` if the storage doesn't exist on that node. The mock stores backups, ISO images, templates and disks. */
    fun storageContent(n: Node, storage: String, content: String?, vmid: Int?): List<StorageContentItem>? = synchronized(lock) {
        settleTasks()
        val s = storages.firstOrNull { it.node == n.name && it.id == storage } ?: return null
        val files = if (vmid == null) {
            (hardware.isos + hardware.templates)
                .filter { it.storage == storage && (content == null || it.content == content) }
                .map { it.item(bootMillis / 1000 - 40 * DAY / 1000) }
        } else {
            emptyList()
        }
        val disks = if (content == null || content == "images" || content == "rootdir") volumes(n, s, vmid).filter { content == null || it.content == content } else emptyList()
        if (content != null && content != "backup") return files + disks
        backups
            .filter { it.storage == storage && (s.shared || it.node == n.name) && (vmid == null || it.vmid == vmid) }
            .map { it.toItem() } + files + disks
    }

    /** Guest disks on [s] as seen from node [n]: volumes in the guests' configs. */
    private fun volumes(n: Node, s: Storage, vmid: Int?): List<StorageContentItem> = guests
        .filter { (s.shared || it.node == n.name) && (vmid == null || it.vmid == vmid) }
        .flatMap { g ->
            hardware.of(g).filter { (key, value) -> DISK_KEY.matches(key) && value.startsWith("${s.id}:") && "media=cdrom" !in value }
                .map { (_, value) ->
                    val volid = value.substringBefore(',')
                    val size = Regex("size=(\\d+)([KMGT]?)").find(value)?.let { m ->
                        m.groupValues[1].toLong() * when (m.groupValues[2]) { "K" -> 1024L; "M" -> MIB; "G" -> GIB; "T" -> 1024 * GIB; else -> 1L }
                    }
                    StorageContentItem(volid = volid, content = if (g.type == "qemu") "images" else "rootdir", size = size, vmid = g.vmid, format = "raw")
                }
        }

    /** What a URL would download (the mock invents a size). `fail.invalid` can't be reached. */
    fun urlMetadata(url: String): Result<UrlMetadata> {
        if ("fail.invalid" in url) return Result.failure(IllegalStateException("can't connect to fail.invalid:443"))
        val name = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        val type = if (name.endsWith(".iso")) "application/x-iso9660-image" else "application/octet-stream"
        return Result.success(UrlMetadata(name, 123 * MIB, type))
    }

    /** `download-url`: a task that adds the file to the storage when it ends. */
    fun downloadUrl(nodeName: String, storage: String, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val s = storages.firstOrNull { it.node == nodeName && it.id == storage } ?: return fail("storage '$storage' does not exist")
        val content = params["content"].orEmpty()
        if (content !in s.content.split(',')) return fail("storage '$storage' does not support content-type '$content'")
        val name = params["filename"].orEmpty()
        if (name.isEmpty() || '/' in name) return fail("filename: invalid format")
        val url = params["url"].orEmpty()
        urlMetadata(url).exceptionOrNull()?.let { return fail(it.message ?: "download failed") }
        if (stored(storage, content).any { it.file == name }) return fail("refusing to override existing file '$name'")
        Result.success(startTask(nodeName, "download", "$storage:$content/$name", user) { addFile(MockHardware.StoredFile(storage, content, name, 123 * MIB)) })
    }

    /** `upload`: a task that moves the uploaded file into the storage. */
    fun upload(nodeName: String, storage: String, content: String, name: String, size: Long, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val s = storages.firstOrNull { it.node == nodeName && it.id == storage } ?: return fail("storage '$storage' does not exist")
        if (content !in listOf("iso", "vztmpl", "import")) return fail("upload content type '$content' is not allowed")
        if (content !in s.content.split(',')) return fail("storage '$storage' does not support content-type '$content'")
        if (name.isEmpty() || '/' in name) return fail("filename: invalid format")
        Result.success(startTask(nodeName, "imgcopy", "", user) { addFile(MockHardware.StoredFile(storage, content, name, size)) })
    }

    /** Deletes an ISO image, template or backup. Disks in use by a guest are refused, like Proxmox does. */
    fun deleteVolume(nodeName: String, storage: String, volid: String, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        storages.firstOrNull { it.node == nodeName && it.id == storage } ?: return fail("storage '$storage' does not exist")
        val file = (hardware.isos + hardware.templates).firstOrNull { it.volid == volid }
        val backup = backups.firstOrNull { it.volid == volid }
        when {
            file != null -> Unit
            backup != null -> if (backup.isProtected) return fail("backup '$volid' is protected")
            guests.any { g -> hardware.of(g).values.any { it.substringBefore(',') == volid } } -> return fail("volume '$volid' is in use by a guest")
            else -> return fail("volume '$volid' does not exist")
        }
        Result.success(
            startTask(nodeName, "imgdel", volid, user) {
                file?.let { hardware.isos.remove(it); hardware.templates.remove(it) }
                backup?.let { backups.remove(it) }
            },
        )
    }

    private fun stored(storage: String, content: String) = (hardware.isos + hardware.templates).filter { it.storage == storage && it.content == content }

    private fun addFile(f: MockHardware.StoredFile) {
        if (f.content == "vztmpl") hardware.templates += f else hardware.isos += f
    }

    /** `vzdump` for one guest. Returns a UPID, or an error message (500). */
    fun backupNow(g: Guest, storage: String, mode: String, notesTemplate: String?, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val s = storages.firstOrNull { it.node == g.node && it.id == storage } ?: return fail("storage '$storage' does not exist")
        if ("backup" !in s.content.split(',')) return fail("storage '$storage' does not support backups")
        if (mode !in listOf("snapshot", "suspend", "stop")) return fail("value '$mode' does not have a value in the enumeration 'snapshot, suspend, stop'")
        val notes = notesTemplate.orEmpty()
            .replace("{{guestname}}", g.name).replace("{{vmid}}", g.vmid.toString())
            .replace("{{node}}", g.node).replace("{{cluster}}", clusterName)
        Result.success(startTask(g.node, "vzdump", g.vmid.toString(), user) { backups += newBackup(storage, g, clockMillis(), notes) })
    }

    private val DISK_KEY = Regex("^(scsi|virtio|sata|ide|efidisk|tpmstate|unused|mp)\\d+$|^rootfs$")

    // ------------------------------------------------------------------ high availability

    fun replicationJobs() = synchronized(lock) { replication.list() }
    fun replicationStatus(nodeName: String) = synchronized(lock) { replication.status(nodeName, ::guestNode) }
    fun createReplicationJob(params: Map<String, String>) = synchronized(lock) {
        replication.create(params, ::guestNode) { name -> nodes.any { it.name == name } }
    }
    fun updateReplicationJob(id: String, params: Map<String, String>) = synchronized(lock) { replication.update(id, params) }
    fun deleteReplicationJob(id: String) = synchronized(lock) { replication.delete(id) }
    fun runReplicationNow(id: String) = synchronized(lock) { replication.runNow(id) { name -> nodes.any { it.name == name && it.online } } }
    fun replicationLog(id: String) = synchronized(lock) { replication.log(id) }
    private fun guestNode(vmid: Int): String? = guests.firstOrNull { it.vmid == vmid && !it.template }?.node

    fun haResources() = synchronized(lock) { haManager.list() }
    fun haGroups() = haManager.groups
    fun haStatus() = synchronized(lock) { haManager.status(nodes, guests) }
    fun addHaResource(params: Map<String, String>) = synchronized(lock) {
        haManager.add(params) { sid -> guests.any { !it.template && haManager.sid(it) == sid } }
    }
    fun updateHaResource(sid: String, params: Map<String, String>) = synchronized(lock) { haManager.update(sid, params) }
    fun removeHaResource(sid: String) = synchronized(lock) { haManager.remove(sid) }

    /** The HA manager moves the guest; the mock does it at once (offline VMs and containers alike). */
    fun migrateHaResource(sid: String, target: String): String? = synchronized(lock) {
        val g = guests.firstOrNull { haManager.sid(it) == sid } ?: return "no such resource '$sid'"
        if (haManager.list().none { it.sid == sid }) return "service '$sid' is not managed by HA"
        if (node(target)?.online != true) return "node '$target' is not online"
        if (g.node == target) return "service '$sid' already running on node '$target'"
        g.node = target
        null
    }

    // ------------------------------------------------------------------ firewall

    fun firewallRules(scope: String) = synchronized(lock) { firewall.rules(scope) }
    fun createFirewallRule(scope: String, params: Map<String, String>) = synchronized(lock) { firewall.create(scope, params) }
    fun updateFirewallRule(scope: String, pos: Int, params: Map<String, String>) = synchronized(lock) { firewall.update(scope, pos, params) }
    fun deleteFirewallRule(scope: String, pos: Int, digest: String?) = synchronized(lock) { firewall.delete(scope, pos, digest) }
    fun firewallOptions(scope: String) = synchronized(lock) { firewall.options(scope) }
    fun updateFirewallOptions(scope: String, params: Map<String, String>) = synchronized(lock) { firewall.updateOptions(scope, params) }
    fun firewallMacros() = firewall.macros
    fun securityGroups() = firewall.groups

    // ------------------------------------------------------------------ backup jobs, pools, storage config

    fun backupJobs(): List<JsonObject> = synchronized(lock) { jobs.list() }
    fun createBackupJob(params: Map<String, String>): String? = synchronized(lock) { jobs.create(params) }
    fun updateBackupJob(id: String, params: Map<String, String>): String? = synchronized(lock) { jobs.update(id, params) }
    fun deleteBackupJob(id: String): String? = synchronized(lock) { jobs.delete(id) }
    fun backupJob(id: String): Map<String, String>? = synchronized(lock) { jobs.job(id) }
    fun notBackedUp() = synchronized(lock) { jobs.notBackedUp(guests) }
    fun storageConfigs() = jobs.storageConfigs()
    fun pools() = jobs.pools

    /**
     * `POST /nodes/{node}/vzdump` with `all`, `exclude` or `pool` instead of one `vmid`: one task
     * that backs up every matching guest on [nodeName].
     */
    fun backupSelection(nodeName: String, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val storage = params["storage"].orEmpty()
        val s = storages.firstOrNull { it.node == nodeName && it.id == storage } ?: return fail("storage '$storage' does not exist")
        if ("backup" !in s.content.split(',')) return fail("storage '$storage' does not support backups")
        val selected = jobs.selected(params, guests, nodeName)
        val notes = params["notes-template"].orEmpty()
        Result.success(
            startTask(nodeName, "vzdump", "", user) {
                selected.forEach { g ->
                    val text = notes.replace("{{guestname}}", g.name).replace("{{vmid}}", g.vmid.toString())
                        .replace("{{node}}", g.node).replace("{{cluster}}", clusterName)
                    backups += newBackup(storage, g, clockMillis(), text)
                }
            },
        )
    }

    /** The lowest unused VMID, like `/cluster/nextid`. */
    fun nextId(): Int = synchronized(lock) { generateSequence(100) { it + 1 }.first { id -> guests.none { it.vmid == id } } }

    /**
     * Restores [volid] as guest [vmid] on [nodeName]. Replacing an existing guest needs [force]
     * and a stopped guest on the same node; its snapshots are gone afterwards, like in Proxmox.
     */
    fun restore(nodeName: String, type: String, vmid: Int, volid: String, force: Boolean, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        val label = if (type == "qemu") "VM" else "CT"
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val b = backups.firstOrNull { it.volid == volid } ?: return fail("volume '$volid' does not exist")
        if (b.type != type) return fail("'$volid' is not a ${if (type == "qemu") "VM" else "container"} backup")
        if (node(nodeName)?.online != true) return fail("node '$nodeName' seems to be offline")
        val existing = guests.firstOrNull { it.vmid == vmid }
        if (existing != null) {
            if (!force || existing.node != nodeName || existing.type != type) {
                return fail("unable to restore $label $vmid - $label $vmid already exists on node '${existing.node}'")
            }
            if (existing.status == "running") return fail("unable to restore $label $vmid - $label is running")
        }
        val prefix = if (type == "qemu") "qm" else "vz"
        Result.success(
            startTask(nodeName, prefix + "restore", vmid.toString(), user) {
                if (existing != null) {
                    guests.remove(existing)
                    snaps.remove(vmid)
                    snapHead.remove(vmid)
                }
                hardware.forget(vmid)
                guests += Guest(vmid, type, nodeName, b.guestName, "stopped", b.cpus, b.maxmem, b.maxdisk, tags = existing?.tags)
            },
        )
    }

    /**
     * `POST /nodes/{node}/{qemu|lxc}` without a backup: a new guest. Disks are allocated, the
     * guest exists at once (stopped), and `start=1` starts it when the task ends.
     */
    fun createGuest(nodeName: String, type: String, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val qemu = type == "qemu"
        val label = if (qemu) "VM" else "CT"
        val vmid = params["vmid"]?.toIntOrNull() ?: return fail("vmid: property is missing and it is not optional")
        guests.firstOrNull { it.vmid == vmid }?.let { return fail("unable to create $label $vmid: config file already exists") }
        val node = node(nodeName) ?: return fail("no such cluster node '$nodeName'")
        if (!node.online) return fail("node '$nodeName' seems to be offline")
        if (!qemu) {
            val template = params["ostemplate"] ?: return fail("ostemplate: property is missing and it is not optional")
            if (hardware.templates.none { it.volid == template }) return fail("volume '$template' does not exist")
            params["password"]?.let { if (it.length < 5) return fail("password: value must be at least 5 characters long") }
        }
        val name = params[if (qemu) "name" else "hostname"] ?: if (qemu) "VM$vmid" else "CT$vmid"
        val firstStorage = params.values.firstNotNullOfOrNull { ALLOCATED.find(it)?.groupValues?.get(1) } ?: "local-zfs"
        val g = Guest(
            vmid, type, nodeName, name, "stopped", cpus = 1, maxmem = 512 * MIB, maxdisk = 0,
            agent = params["agent"]?.startsWith("1") == true, onBoot = params["onboot"] == "1", diskStorage = firstStorage,
        )
        val notConfig = setOf("vmid", "start", "password", "ssh-public-keys", "ostemplate", "name", "hostname", "onboot", "agent", "description", "tags", "pool")
        hardware.create(g, params.filterKeys { it !in notConfig })?.let { return fail(it) }
        guests += g
        val start = params["start"] == "1"
        Result.success(
            startTask(nodeName, if (qemu) "qmcreate" else "vzcreate", vmid.toString(), user) {
                if (start) {
                    g.status = "running"
                    g.startedAtMillis = clockMillis()
                }
            },
        )
    }

    // ------------------------------------------------------------------ clone, template, migrate

    private fun Guest.label() = if (type == "qemu") "VM" else "CT"
    private fun Guest.onLocalDisks() = storages.firstOrNull { it.id == diskStorage }?.shared != true

    /**
     * `POST …/clone`. Like Proxmox: normal guests are always copied in full, linked clones need a
     * template, another node needs shared storage, and a target storage is only for full clones.
     */
    fun clone(g: Guest, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val newId = params["newid"]?.toIntOrNull() ?: return fail("missing newid")
        val label = g.label()
        guests.firstOrNull { it.vmid == newId }?.let { return fail("unable to create $label $newId: config file already exists") }
        val target = params["target"] ?: g.node
        val targetNode = node(target) ?: return fail("no such cluster node '$target'")
        if (!targetNode.online) return fail("target node '$target' is not online")
        val full = !g.template || params["full"] == "1"
        val storage = params["storage"]
        if (storage != null) {
            if (!full) return fail("parameter 'storage' not allowed for linked clones")
            val s = storages.firstOrNull { it.node == target && it.id == storage } ?: return fail("storage '$storage' does not exist")
            val needed = if (g.type == "qemu") "images" else "rootdir"
            if (needed !in s.content.split(',')) return fail("storage '$storage' does not support $needed")
        }
        if (target != g.node && g.onLocalDisks() && storage == null) {
            return fail("can't clone $label to node '$target' (${g.label()} uses local storage '${g.diskStorage}')")
        }
        val name = params[if (g.type == "qemu") "name" else "hostname"] ?: if (g.type == "qemu") "Copy-of-VM-${g.name}" else g.name
        val prefix = if (g.type == "qemu") "qm" else "vz"
        Result.success(
            startTask(g.node, prefix + "clone", g.vmid.toString(), user) {
                guests += Guest(
                    newId, g.type, target, name, "stopped", g.cpus, g.maxmem, g.maxdisk,
                    tags = g.tags, agent = g.agent, description = g.description, diskStorage = storage ?: g.diskStorage,
                )
            },
        )
    }

    /** `POST …/template`. VMs convert in a task; containers answer at once (`null`). */
    fun convertToTemplate(g: Guest, user: String): Result<String?> = synchronized(lock) {
        settleTasks()
        val label = g.label()
        if (g.template) return Result.failure(IllegalStateException("$label ${g.vmid} is already a template"))
        if (g.status != "stopped") return Result.failure(IllegalStateException("you can't convert a running $label to a template"))
        if (g.type == "qemu") {
            Result.success(startTask(g.node, "qmtemplate", g.vmid.toString(), user) { g.template = true })
        } else {
            g.template = true
            Result.success(null)
        }
    }

    /** `GET /nodes/{node}/qemu/{vmid}/migrate`: allowed nodes only for stopped VMs, as in Proxmox. */
    fun migrationCheck(g: Guest): MigrationCheck = synchronized(lock) {
        settleTasks()
        val running = g.status == "running"
        MigrationCheck(
            running = running,
            allowedNodes = if (running) null else nodes.map { it.name }.filter { it != g.node },
            localDisks = if (g.onLocalDisks()) listOf(LocalDisk("${g.diskStorage}:vm-${g.vmid}-disk-0", "scsi0", g.maxdisk)) else emptyList(),
        )
    }

    /** `POST …/migrate`. Running VMs need `online` (and `with-local-disks`), running containers `restart`. */
    fun migrate(g: Guest, params: Map<String, String>, user: String): Result<String> = synchronized(lock) {
        settleTasks()
        fun fail(msg: String) = Result.failure<String>(IllegalStateException(msg))
        val target = params["target"] ?: return fail("missing target")
        if (target == g.node) return fail("target is local node.")
        val targetNode = node(target) ?: return fail("no such cluster node '$target'")
        if (!targetNode.online) return fail("target node '$target' not online")
        val running = g.status == "running"
        if (running) {
            if (g.type == "qemu") {
                if (params["online"] != "1") return fail("can't migrate running VM without --online")
                if (g.onLocalDisks() && params["with-local-disks"] != "1") {
                    return fail("can't live migrate attached local disks without with-local-disks option")
                }
            } else if (params["restart"] != "1") {
                return fail("CT is running - use online migration")
            }
        }
        val type = when {
            g.ha -> "hamigrate"
            g.type == "qemu" -> "qmigrate"
            else -> "vzmigrate"
        }
        Result.success(startTask(g.node, type, g.vmid.toString(), user) { g.node = target })
    }

    // ------------------------------------------------------------------ PBS

    val datastores = listOf(
        DatastoreInfo("store1", comment = "Primary ZFS mirror"),
        DatastoreInfo("store2", comment = "Offsite copy"),
    )

    fun datastoreUsage(): List<DatastoreUsage> {
        val now = clockMillis() / 1000
        return listOf(
            DatastoreUsage("store1", total = 16_000 * GIB, used = 11_200 * GIB, avail = 4_800 * GIB, estimatedFullDate = now + 140 * DAY / 1000),
            DatastoreUsage("store2", total = 8_000 * GIB, used = 7_400 * GIB, avail = 600 * GIB, estimatedFullDate = now + 12 * DAY / 1000),
        )
    }

    companion object {
        const val PASSWORD = "anprox"
        private val ALLOCATED = Regex("^([A-Za-z0-9][A-Za-z0-9_.-]*):\\d+(?:,|$)")
        const val RRD_SAMPLES = 70
        const val MIB = 1024L * 1024
        const val GIB = 1024L * MIB
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
