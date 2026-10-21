package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.backupserver.PbsRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.node.NodeAdminRepository
import io.github.lucabarbalata.anprox.data.snapshot.PbsSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.pbs.BackupGroup
import io.github.lucabarbalata.anprox.model.pbs.BackupSnapshot
import io.github.lucabarbalata.anprox.model.pbs.KeepOptions
import io.github.lucabarbalata.anprox.model.pbs.PbsJobKind
import io.github.lucabarbalata.anprox.model.pbs.PbsRemoteForm
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real-server tests for a **Proxmox Backup Server** (M8), like [RealServerSmokeTest] and
 * [RealServerWriteTest] for Proxmox VE: every read the app makes, and (with `ANPROX_PBS_WRITE=1`)
 * namespaces, garbage collection, verification, maintenance mode, remotes, jobs and the checks on
 * one backup (notes, protection, verify, file restore, a prune preview). It only creates things
 * named `anprox-test…` and puts back what it changes.
 * ```
 * ANPROX_PBS_URL=https://pbs:8007 ANPROX_PBS_USER=root@pam ANPROX_PBS_PASSWORD=… [ANPROX_PBS_WRITE=1] \
 *   ./gradlew -Panprox.jvmOnly=true :core:data:test --tests '*RealServerPbs*' -i
 * ```
 * Without them the same steps run against the mock.
 */
class RealServerPbsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val failures = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `every read the app makes parses the real Backup Server's answers`() = runBlocking {
        val login = PbsLogin.fromEnv()
        assumeTrue(login != null, "ANPROX_PBS_URL/USER/PASSWORD not set")
        readEverything(login!!.signIn(scope))
    }

    @Test
    fun `the reads pass against the mock`() = runBlocking {
        withMock { readEverything(it) }
    }

    @Test
    fun `create, change and delete on a real test Backup Server`() = runBlocking {
        val login = PbsLogin.fromEnv()
        assumeTrue(login != null && System.getenv("ANPROX_PBS_WRITE") == "1", "ANPROX_PBS_URL/USER/PASSWORD and ANPROX_PBS_WRITE=1 not set")
        writeEverything(login!!.signIn(scope))
    }

    @Test
    fun `the write steps pass against the mock`() = runBlocking {
        withMock { writeEverything(it) }
    }

    private suspend fun withMock(block: suspend (ServerHub) -> Unit) {
        val mock = MockProxmoxServer(ProxmoxProduct.PBS, taskDurationMillis = 0).start()
        try {
            block(signIn(scope, ProxmoxProduct.PBS, mock.baseUrl, "root@pam", "anprox", insecure = true))
        } finally {
            mock.stop()
        }
    }

    /** Runs [block] and reports it with what it returned; [optional] errors are reported, not failed. */
    private suspend fun check(name: String, optional: (ApiException) -> Boolean = { false }, block: suspend () -> Any?) {
        try {
            println("  ✓ $name: ${describe(block())}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (optional(e)) {
                println("  – $name: ${e.message}")
            } else {
                failures += "$name: ${e::class.simpleName}: ${e.message}"
                println("  ✗ $name: ${e::class.simpleName}: ${e.message}")
            }
        } catch (e: Exception) {
            failures += "$name: ${e::class.simpleName}: ${e.message}"
            println("  ✗ $name: ${e::class.simpleName}: ${e.message}")
        }
    }

    /** Runs [block] and reports it; returns its result, or null when it failed (or wasn't possible). */
    private suspend fun <T> step(name: String, optional: (ApiException) -> Boolean = { false }, block: suspend () -> T): T? = try {
        block().also { println("  ✓ $name") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (optional(e)) {
            println("  – $name: ${e.message}")
        } else {
            failures += "$name: ${e::class.simpleName}: ${e.message}"
            println("  ✗ $name: ${e::class.simpleName}: ${e.message}")
        }
        null
    } catch (e: Exception) {
        failures += "$name: ${e::class.simpleName}: ${e.message}"
        println("  ✗ $name: ${e::class.simpleName}: ${e.message}")
        null
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "none"
        is Collection<*> -> "${value.size} item(s)"
        else -> value.toString().take(120)
    }

    private fun finish() {
        println(if (failures.isEmpty()) "All Backup Server checks passed." else "Failed:\n" + failures.joinToString("\n") { "  $it" })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private suspend fun readEverything(hub: ServerHub) {
        val pbs = PbsRepository(hub)
        val guests = GuestRepository(hub)
        val admin = NodeAdminRepository(hub)
        val node = PbsRepository.NODE

        println("Backup Server")
        check("dashboard") {
            val s = SnapshotRepository(hub).fetch(hub.server(ID)!!) as PbsSnapshot
            "${s.datastores.size} datastore(s), ${s.recentTasks.size} recent task(s), version ${s.version}"
        }
        check("this server") { pbs.nodeStatus(ID).let { "${it.cpus} CPUs, kernel ${it.kernel}" } }
        val stores = step("datastores") { pbs.datastores(ID) }.orEmpty()
        println("    (${stores.size} datastore(s))")
        for (store in stores.map { it.info.store }) {
            println("Datastore $store")
            check("status") { pbs.status(ID, store).counts }
            check("garbage collection") { pbs.gc(ID, store) }
            check("maintenance") { pbs.maintenance(ID, store) }
            val namespaces = step("namespaces") { pbs.namespaces(ID, store) }.orEmpty()
            for (ns in namespaces.take(5).map { it.ns }) {
                val label = ns.ifEmpty { "/" }
                val groups = step("$label: backup groups") { pbs.groups(ID, store, ns) }.orEmpty()
                for (group in groups.take(3)) {
                    val snapshots = step("$label ${group.key}: snapshots") { pbs.snapshots(ID, store, ns, group) }.orEmpty()
                    snapshots.firstOrNull { it.hasCatalog }?.let { snapshot ->
                        check("$label ${group.key}: file catalog") {
                            val top = pbs.catalog(ID, store, ns, snapshot)
                            top.firstOrNull { it.browsable }?.let { pbs.catalog(ID, store, ns, snapshot, it.filepath) } ?: top
                        }
                    }
                }
            }
        }
        println("Backup Server: jobs, remotes and the node")
        check("jobs") { pbs.jobs(ID).groupingBy { it.kind }.eachCount() }
        check("remotes") { pbs.remotes(ID) }
        check("updates") { admin.updates(ID, node) }
        check("package versions") { admin.versions(ID, node) }
        check("subscription") { admin.subscription(ID, node) }
        check("repositories") { admin.repositories(ID, node).files }
        check("services") { admin.services(ID, node) }
        check("system log") { admin.journal(ID, node, 50) }
        check("task history") { guests.taskHistory(ID, node, 0, 50, errorsOnly = false) }
        check("failed tasks") { guests.taskHistory(ID, node, 0, 50, errorsOnly = true) }
        finish()
    }

    private suspend fun writeEverything(hub: ServerHub) {
        val pbs = PbsRepository(hub)
        val guests = GuestRepository(hub)
        val admin = NodeAdminRepository(hub)
        suspend fun done(upid: String, what: String) {
            val task = guests.awaitTask(ID, PbsRepository.NODE, upid)
            if (!task.succeeded) {
                val log = guests.taskLog(ID, PbsRepository.NODE, upid).map { it.t }.filter { it.isNotBlank() }.takeLast(4)
                error("$what to succeed, got ${task.exitstatus}${log.joinToString("") { "\n      | $it" }}")
            }
        }

        println("Backup Server: leftovers of an earlier run")
        val stores = pbs.datastores(ID).map { it.info.store }
        for (store in stores) {
            if (pbs.namespaces(ID, store).any { it.ns == PREFIX }) step("remove namespace $PREFIX on $store") { pbs.deleteNamespace(ID, store, PREFIX, deleteGroups = true) }
            if (pbs.maintenance(ID, store).message == MAINTENANCE_NOTE) step("end the test's maintenance mode on $store") { pbs.setMaintenance(ID, store, null, "") }
        }
        if (pbs.remotes(ID).any { it.name == PREFIX }) step("remove remote $PREFIX") { pbs.deleteRemote(ID, PREFIX) }

        val store = stores.firstOrNull { pbs.maintenance(ID, it).type == null } ?: error("no datastore that isn't in maintenance")
        println("Backup Server: datastore $store")
        step("namespace: create, verify, delete") {
            pbs.createNamespace(ID, store, "", PREFIX)
            expect(pbs.namespaces(ID, store).any { it.ns == PREFIX }, "the new namespace")
            done(pbs.verify(ID, store, PREFIX), "verifying the empty namespace")
            pbs.deleteNamespace(ID, store, PREFIX, deleteGroups = false)
            expect(pbs.namespaces(ID, store).none { it.ns == PREFIX }, "the namespace to be gone")
        }
        step("garbage collection") { done(pbs.startGc(ID, store), "garbage collection") }
        step("maintenance mode: read-only, then off") {
            pbs.setMaintenance(ID, store, "read-only", MAINTENANCE_NOTE)
            try {
                val now = pbs.maintenance(ID, store)
                expect(now.type == "read-only" && now.message == MAINTENANCE_NOTE, "read-only maintenance, got $now")
            } finally {
                pbs.setMaintenance(ID, store, null, "")
            }
            expect(pbs.maintenance(ID, store).type == null, "maintenance to be off")
        }

        // A container or host backup has a file catalog; the newest one (in any namespace) is checked.
        val target = pbs.namespaces(ID, store).map { it.ns }.firstNotNullOfOrNull { ns ->
            pbs.groups(ID, store, ns).filter { it.type != "vm" }.firstNotNullOfOrNull { group ->
                pbs.snapshots(ID, store, ns, group).firstOrNull { it.hasCatalog }?.let { Triple(ns, group, it) }
            }
        }
        if (target == null) {
            println("  – backup checks: no container or host backup with a file catalog on $store")
        } else {
            val (ns, group, snapshot) = target
            checkPbsSnapshot({ name, block -> step(name) { block() } }, pbs, guests, ID, store, ns, group, snapshot)
        }

        println("Backup Server: jobs, remotes and the node")
        step("remote: add, then delete") {
            // Only the configuration: nothing connects to it.
            pbs.createRemote(ID, PbsRemoteForm(name = PREFIX, host = "192.0.2.10", authId = "root@pam", password = "not-a-real-password", comment = "AnProx test"))
            expect(pbs.remotes(ID).any { it.name == PREFIX }, "the new remote")
            pbs.deleteRemote(ID, PREFIX)
            expect(pbs.remotes(ID).none { it.name == PREFIX }, "the remote to be gone")
        }
        val verifyJob = pbs.jobs(ID).firstOrNull { it.kind == PbsJobKind.VERIFY }
        if (verifyJob == null) {
            println("  – jobs: no verify job to run")
        } else {
            step("jobs: run verify job ${verifyJob.id} now") { done(pbs.runJob(ID, verifyJob), "the verify job") }
        }
        step("updates: refresh the package index") {
            val task = guests.awaitTask(ID, PbsRepository.NODE, admin.refresh(ID, PbsRepository.NODE))
            // The enterprise repository without a subscription fails the task: the server's setup.
            if (!task.succeeded) println("    (apt update ended with: ${task.exitstatus})")
        }
        finish()
    }

    private fun expect(condition: Boolean, what: String) {
        if (!condition) throw IllegalStateException("expected $what")
    }

    companion object {
        const val ID = "pbs"
        const val PREFIX = "anprox-test"
        const val MAINTENANCE_NOTE = "AnProx test"
    }
}

/** The test Backup Server from `ANPROX_PBS_URL`, `ANPROX_PBS_USER` (name@realm) and `ANPROX_PBS_PASSWORD`. */
internal data class PbsLogin(val url: String, val user: String, val password: String, val insecure: Boolean) {
    suspend fun signIn(scope: CoroutineScope): ServerHub = signIn(scope, ProxmoxProduct.PBS, url, user, password, insecure)

    companion object {
        fun fromEnv(): PbsLogin? {
            val url = System.getenv("ANPROX_PBS_URL").orEmpty()
            val user = System.getenv("ANPROX_PBS_USER").orEmpty()
            val password = System.getenv("ANPROX_PBS_PASSWORD").orEmpty()
            if (url.isBlank() || !user.contains('@') || password.isEmpty()) return null
            return PbsLogin(url, user, password, insecure = System.getenv("ANPROX_PBS_INSECURE") != "0")
        }
    }
}

/** Signs in to [url] as [user] (`name@realm`) and returns a hub holding that one server, as [RealServerPbsTest.ID] or "real". */
internal suspend fun signIn(scope: CoroutineScope, product: ProxmoxProduct, url: String, user: String, password: String, insecure: Boolean): ServerHub {
    val id = if (product == ProxmoxProduct.PBS) RealServerPbsTest.ID else "real"
    val profile = ServerProfile(
        id = id, name = "Test server", product = product,
        endpoints = listOf(Endpoint(url)),
        username = user.substringBefore('@'), realm = user.substringAfter('@'),
        allowInsecureTls = insecure,
    )
    val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
    hub.awaitLoaded()
    // An exception rather than an assertion, so a step that signs in reports it like any other failure.
    val result = hub.login(id, password, remember = false)
    check(result is LoginResult.Success) { "sign-in failed: $result (a user with a second factor can't be used here)" }
    return hub
}

/**
 * What the app does with one backup on a Backup Server, put back as it was afterwards: notes,
 * protection, verification, file restore (a file and a folder as a zip) and a prune preview.
 * [RealServerWriteTest] runs it on the container backup it just made; [RealServerPbsTest] on the
 * newest container or host backup it finds.
 */
internal suspend fun checkPbsSnapshot(
    step: suspend (String, suspend () -> Unit) -> Unit,
    pbs: PbsRepository,
    guests: GuestRepository,
    id: String,
    store: String,
    ns: String,
    group: BackupGroup,
    snapshot: BackupSnapshot,
) {
    fun expect(condition: Boolean, what: String) {
        if (!condition) throw IllegalStateException("expected $what")
    }
    suspend fun current() = pbs.snapshots(id, store, ns, group).firstOrNull { it.time == snapshot.time } ?: error("the backup is no longer listed")
    val label = "${ns.ifEmpty { "/" }} ${group.key}"

    step("$label: notes, then the old ones back") {
        val old = snapshot.comment.orEmpty()
        pbs.setNotes(id, store, ns, snapshot, "AnProx test note")
        try {
            expect(current().comment?.trim() == "AnProx test note", "the new notes")
        } finally {
            pbs.setNotes(id, store, ns, snapshot, old)
        }
    }
    step("$label: protection on and off") {
        val old = snapshot.protected
        pbs.setProtected(id, store, ns, snapshot, !old)
        try {
            expect(current().protected == !old, "protection to change")
        } finally {
            pbs.setProtected(id, store, ns, snapshot, old)
        }
        expect(current().protected == old, "protection as it was")
    }
    step("$label: verify the backup") {
        val upid = pbs.verify(id, store, ns, snapshot = snapshot)
        val task = guests.awaitTask(id, PbsRepository.NODE, upid)
        expect(task.succeeded, "verification to succeed, got ${task.exitstatus}")
        expect(current().verifyState == "ok", "the backup to be verified, got ${current().verifyState}")
    }
    step("$label: file restore: browse, save a file and a folder") {
        val archive = pbs.catalog(id, store, ns, snapshot).firstOrNull { it.browsable } ?: error("no archive in the catalog")
        val top = pbs.catalog(id, store, ns, snapshot, archive.filepath)
        val etc = top.firstOrNull { it.name == "etc" && it.downloadable } ?: top.firstOrNull { it.type == "d" } ?: error("no folder at the top of ${archive.name}")
        val inside = pbs.catalog(id, store, ns, snapshot, etc.filepath)
        val file = listOf("hostname", "os-release", "hosts").firstNotNullOfOrNull { n -> inside.firstOrNull { it.name == n && it.type == "f" } }
            ?: inside.filter { it.type == "f" }.minByOrNull { it.size ?: Long.MAX_VALUE } ?: error("no file in ${etc.path}")
        val bytes = ByteArrayOutputStream().also { pbs.download(id, store, ns, snapshot, file, it) }.toByteArray()
        expect(bytes.isNotEmpty() && (file.size == null || bytes.size.toLong() == file.size), "${file.path}: ${file.size} bytes, got ${bytes.size}")
        val zip = ByteArrayOutputStream().also { pbs.download(id, store, ns, snapshot, etc, it) }.toByteArray()
        val check = checkZip(zip)
        expect(check.entries > 0, "${etc.path} as a zip with files in it")
        expect(check.damaged.isEmpty(), "every file in the zip intact, but these aren't: ${check.damaged}")
        println("    (${file.path}: ${bytes.size} bytes; ${etc.path} as a zip: ${check.entries} entries, ${check.empty} empty, the rest intact${check.streamingNote})")
    }
    step("$label: prune preview (keep the last 1)") {
        val before = pbs.snapshots(id, store, ns, group).size
        val preview = pbs.prune(id, store, ns, group, KeepOptions(last = "1"), dryRun = true)
        expect(preview.size == before && preview.count { it.keep } >= 1, "a preview of all $before backups that keeps one, got $preview")
        expect(pbs.snapshots(id, store, ns, group).size == before, "nothing removed by a preview")
    }
}

/** What [checkZip] found: the entries, how many are empty files, and the ones whose content is damaged. */
internal class ZipCheck(val entries: Int, val empty: Int, val damaged: List<String>, val streamingError: String?) {
    val streamingNote: String get() = streamingError?.let { "; a streaming reader stops: $it" }.orEmpty()
}

/**
 * Checks a zip the way an unzip tool reads it: by its central directory, every non-empty entry's
 * length and CRC. Separately notes where a streaming reader (`ZipInputStream`) gives up, which is
 * what run 9 hit on the test Backup Server's folder download.
 */
internal fun checkZip(zip: ByteArray): ZipCheck {
    val file = Files.createTempFile("anprox-", ".zip").toFile()
    try {
        file.writeBytes(zip)
        var entries = 0
        var empty = 0
        val damaged = mutableListOf<String>()
        ZipFile(file).use { z ->
            for (entry in z.entries().asSequence()) {
                entries++
                if (entry.isDirectory) continue
                if (entry.size == 0L) {
                    empty++
                    continue
                }
                val crc = CRC32()
                val read = runCatching { z.getInputStream(entry).use { input -> input.readBytes().also { crc.update(it) }.size.toLong() } }
                if (read.getOrNull() != entry.size || crc.value != entry.crc) {
                    damaged += "${entry.name} (${read.exceptionOrNull()?.message ?: "${read.getOrNull()} of ${entry.size} bytes, CRC ${crc.value} vs ${entry.crc}"})"
                }
            }
        }
        var current = "the start"
        val streaming = try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    current = "${entry.name} (method ${entry.method}, ${entry.size} bytes)"
                    z.readBytes()
                }
            }
            null
        } catch (e: ZipException) {
            "at $current: ${e.message}"
        }
        return ZipCheck(entries, empty, damaged, streaming)
    } finally {
        file.delete()
    }
}

