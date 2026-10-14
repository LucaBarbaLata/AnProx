package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.pbs.DatastoreInfo
import io.github.lucabarbalata.anprox.model.pbs.DatastoreUsage
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.ClusterStatusEntry
import io.github.lucabarbalata.anprox.model.pve.CpuInfo
import io.github.lucabarbalata.anprox.model.pve.FsUsage
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.HaStatus
import io.github.lucabarbalata.anprox.model.pve.MemoryUsage
import io.github.lucabarbalata.anprox.model.pve.NodeStatus
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TaskSummary
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
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
        val name: String,
        var status: String,
        val cpus: Int,
        val maxmem: Long,
        val maxdisk: Long,
        val template: Boolean = false,
        val tags: String? = null,
        val agent: Boolean = false,
        var paused: Boolean = false,
        var startedAtMillis: Long = 0,
        val ha: Boolean = false,
    )

    data class Storage(val node: String, val id: String, val plugin: String, val content: String, val shared: Boolean, val total: Long, val used: Long)

    val nodes = listOf(
        Node("pve1", online = true, cpus = 16, memory = 64 * GIB, rootfs = 100 * GIB, ip = "10.0.0.11"),
        Node("pve2", online = true, cpus = 12, memory = 48 * GIB, rootfs = 100 * GIB, ip = "10.0.0.12"),
        Node("pve3", online = false, cpus = 8, memory = 32 * GIB, rootfs = 100 * GIB, ip = "10.0.0.13"),
    )

    private val bootMillis = clockMillis()

    val guests = mutableListOf(
        Guest(100, "qemu", "pve1", "web", "running", 4, 8 * GIB, 64 * GIB, tags = "prod;web", agent = true, startedAtMillis = bootMillis - 9 * DAY, ha = true),
        Guest(101, "qemu", "pve1", "db", "running", 8, 16 * GIB, 256 * GIB, tags = "prod;db", agent = true, startedAtMillis = bootMillis - 9 * DAY, ha = true),
        Guest(102, "qemu", "pve2", "win11", "stopped", 4, 8 * GIB, 128 * GIB, tags = "desktop"),
        Guest(103, "qemu", "pve2", "k8s-master", "running", 4, 8 * GIB, 64 * GIB, tags = "k8s", agent = true, startedAtMillis = bootMillis - 2 * DAY),
        Guest(104, "qemu", "pve3", "lab-vm", "stopped", 2, 4 * GIB, 32 * GIB, tags = "lab"),
        Guest(9000, "qemu", "pve1", "ubuntu-2404-template", "stopped", 2, 2 * GIB, 32 * GIB, template = true, tags = "template"),
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
            template = template, tags = tags, hastate = if (ha) (if (running) "started" else "stopped") else null,
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
            seedTask("pve2", "qmmigrate", "104", "admin@pve", now - 2 * HOUR, 70_000, "migration aborted: target node pve3 is offline")
            seedTask("pve2", "aptupdate", "", "root@pam", now - 1 * HOUR, 20_000, "OK")
        } else {
            seedTask("pbs", "garbage_collection", "store1", "root@pam", now - 4 * HOUR, 120_000, "OK")
            seedTask("pbs", "verificationjob", "store1:v-daily", "root@pam", now - 3 * HOUR, 600_000, "OK")
            seedTask("pbs", "syncjob", "store2:s-offsite", "root@pam", now - 2 * HOUR, 300_000, "sync failed: remote unreachable")
        }
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
                "start" -> { g.status = "running"; g.paused = false; g.startedAtMillis = clockMillis() }
                "shutdown", "stop" -> { g.status = "stopped"; g.paused = false }
                "reboot", "reset" -> { g.startedAtMillis = clockMillis(); g.paused = false }
                "suspend" -> g.paused = true
                "resume" -> g.paused = false
            }
        }
        Result.success(upid)
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
        const val MIB = 1024L * 1024
        const val GIB = 1024L * MIB
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
