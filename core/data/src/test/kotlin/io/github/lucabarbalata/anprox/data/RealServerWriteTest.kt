package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.backup.BackupRepository
import io.github.lucabarbalata.anprox.data.backupserver.PbsRepository
import io.github.lucabarbalata.anprox.data.console.ConsoleRepository
import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.data.datacenter.BackupJobRepository
import io.github.lucabarbalata.anprox.data.datacenter.ClusterRepository
import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.data.datacenter.HaRepository
import io.github.lucabarbalata.anprox.data.datacenter.MetricServerRepository
import io.github.lucabarbalata.anprox.data.datacenter.NotificationRepository
import io.github.lucabarbalata.anprox.data.datacenter.PoolRepository
import io.github.lucabarbalata.anprox.data.datacenter.SdnRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageSetupRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.node.CertificateRepository
import io.github.lucabarbalata.anprox.data.node.NodeAdminRepository
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.AclForm
import io.github.lucabarbalata.anprox.model.pve.AclSubject
import io.github.lucabarbalata.anprox.model.pve.AcmeDomain
import io.github.lucabarbalata.anprox.model.pve.AcmePluginForm
import io.github.lucabarbalata.anprox.model.pve.BackupJobForm
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.BackupSelection
import io.github.lucabarbalata.anprox.model.pve.CertificateInfo
import io.github.lucabarbalata.anprox.model.pve.CloneRequest
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.CustomCertificateForm
import io.github.lucabarbalata.anprox.model.pve.DownloadUrlForm
import io.github.lucabarbalata.anprox.model.pve.FirewallAliasForm
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.HaResourceForm
import io.github.lucabarbalata.anprox.model.pve.HaState
import io.github.lucabarbalata.anprox.model.pve.MetricServerForm
import io.github.lucabarbalata.anprox.model.pve.NetworkInterfaceForm
import io.github.lucabarbalata.anprox.model.pve.NewContainer
import io.github.lucabarbalata.anprox.model.pve.NewVm
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.NotifyMatcherForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetForm
import io.github.lucabarbalata.anprox.model.pve.NotifyTargetType
import io.github.lucabarbalata.anprox.model.pve.PoolChange
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.Retention
import io.github.lucabarbalata.anprox.model.pve.SdnVnetForm
import io.github.lucabarbalata.anprox.model.pve.SdnZoneForm
import io.github.lucabarbalata.anprox.model.pve.SdnZoneType
import io.github.lucabarbalata.anprox.model.pve.StartupOrder
import io.github.lucabarbalata.anprox.model.pve.StorageKind
import io.github.lucabarbalata.anprox.model.pve.StorageSetupForm
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.TokenForm
import io.github.lucabarbalata.anprox.model.pve.UserForm
import io.github.lucabarbalata.anprox.model.pve.VmDisk
import io.github.lucabarbalata.anprox.model.pve.fileName
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import io.github.lucabarbalata.anprox.network.http.UploadSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Creates, changes and deletes things on a **test** Proxmox VE the way the app does: VMs, a
 * container (downloading a small template from the catalogue when the node has none), storage,
 * firewall, SDN, network, access control and the three consoles (the VM's screen over VNC, the
 * container's and the node's terminals); docs/testing.md lists every step. It only touches what
 * it creates, all named `anprox-test…`, removes leftovers of an earlier run first and deletes
 * everything at the end.
 *
 * Needs a user allowed to do all that (root@pam on a test server), and `ANPROX_PVE_WRITE=1` on top
 * of the variables [RealServerSmokeTest] uses, so it never runs against a server by accident:
 * ```
 * ANPROX_PVE_URL=https://pve:8006 ANPROX_PVE_USER=root@pam ANPROX_PVE_PASSWORD=… ANPROX_PVE_WRITE=1 \
 *   ./gradlew -Panprox.jvmOnly=true :core:data:test --tests '*RealServerWriteTest*' -i
 * ```
 * Without them the same steps run against the mock.
 */
class RealServerWriteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val failures = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `create, change and delete on a real test server`() = runBlocking {
        val url = System.getenv("ANPROX_PVE_URL").orEmpty()
        val user = System.getenv("ANPROX_PVE_USER").orEmpty()
        val password = System.getenv("ANPROX_PVE_PASSWORD").orEmpty()
        assumeTrue(
            url.isNotBlank() && user.contains('@') && password.isNotEmpty() && System.getenv("ANPROX_PVE_WRITE") == "1",
            "ANPROX_PVE_URL/USER/PASSWORD and ANPROX_PVE_WRITE=1 not set",
        )
        writeEverything(url, user, password, insecure = System.getenv("ANPROX_PVE_INSECURE") != "0", screenshot = "vm-screen.png", pbsLogin = PbsLogin.fromEnv())
    }

    @Test
    fun `the same steps pass against the mock`() = runBlocking {
        val mock = MockProxmoxServer(ProxmoxProduct.PVE).start()
        try {
            writeEverything(mock.baseUrl, "root@pam", "anprox", insecure = true, screenshot = "vm-screen-mock.png")
        } finally {
            mock.stop()
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

    private fun expect(condition: Boolean, what: String) {
        if (!condition) throw IllegalStateException("expected $what")
    }

    /**
     * [screenshot]: the file under `build/real-server/` the VM's screen is saved to. [pbsLogin]: the
     * test Backup Server; with it, the container is also backed up to the Proxmox VE storage that
     * points to it, and that backup is checked on the Backup Server itself.
     */
    private suspend fun writeEverything(url: String, user: String, password: String, insecure: Boolean, screenshot: String, pbsLogin: PbsLogin? = null) {
        val profile = ServerProfile(
            id = "real", name = "Test server", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(url)),
            username = user.substringBefore('@'), realm = user.substringAfter('@'),
            allowInsecureTls = insecure,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        assertIs<LoginResult.Success>(hub.login("real", password, remember = false), "sign-in (a user with a second factor can't be used here)")
        val id = "real"

        val guests = GuestRepository(hub)
        val hardware = HardwareRepository(hub)
        val backups = BackupRepository(hub)
        val storage = StorageRepository(hub)
        val firewall = FirewallRepository(hub)
        val pools = PoolRepository(hub)
        val access = AccessRepository(hub)
        val snapshots = SnapshotRepository(hub)
        val jobs = BackupJobRepository(hub)
        val setup = StorageSetupRepository(hub)
        val sdn = SdnRepository(hub)
        val nodeAdmin = NodeAdminRepository(hub)
        val cluster = ClusterRepository(hub)
        val ha = HaRepository(hub)
        val consoles = ConsoleRepository(hub)
        val notify = NotificationRepository(hub)
        val certs = CertificateRepository(hub)
        val metrics = MetricServerRepository(hub)

        suspend fun clusterState() = snapshots.fetch(hub.server(id)!!) as PveSnapshot
        /** Waits for a task; a failed one is reported with the end of its log, which says why. */
        suspend fun done(node: String, upid: String?, what: String) {
            if (upid == null) return
            val task = guests.awaitTask(id, node, upid)
            if (!task.succeeded) {
                val log = guests.taskLog(id, node, upid).map { it.t }.filter { it.isNotBlank() && it != "TASK OK" }.takeLast(4)
                expect(false, "$what to succeed, got ${task.exitstatus}${log.joinToString("") { "\n      | $it" }}")
            }
        }

        /**
         * Which processes hold the guest's config lock, asked in a node shell (the app's console).
         * On the test host it was `qm cleanup` for some 15–25 s after a rollback with RAM.
         */
        suspend fun lockHolders(ref: GuestRef): String = try {
            val session = consoles.open(id, ConsoleTarget(ConsoleKind.NODE, ref.node))
            try {
                session.awaitConnected()
                val term = TerminalReader(session)
                term.await("# ", "$ ", millis = 20_000)
                session.resize(250, 50)
                val lock = "lock-${ref.vmid}\\.conf"
                session.sendInput("for p in $(lslocks -n -o PID,PATH | awk '/$lock/ {print $1}' | sort -u); do ps -o pid=,etime=,args= -p \$p; done; echo anprox-done-$((6*7))\r")
                val output = term.expect("anprox-done-42", millis = 20_000)
                output.substringBeforeLast("anprox-done-42").replace(Regex("\u001b\\[[0-9;?]*[A-Za-z]"), "").lines()
                    .map { it.trim() }.filter { it.isNotEmpty() && "lslocks" !in it }
                    .joinToString(" / ") { it.take(160) }.ifEmpty { "no process" }
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            "unknown (${e.message})"
        }

        /**
         * Runs a task on a guest. While something else holds the guest's lock ("can't lock file"),
         * says what holds it and tries again after a pause.
         */
        suspend fun lockedTask(ref: GuestRef, what: String, start: suspend () -> String) {
            for (attempt in 1..5) {
                val upid = start()
                val task = guests.awaitTask(id, ref.node, upid)
                if (task.succeeded) return
                if (!TaskStatus.isLockTimeout(task.exitstatus) || attempt == 5) return done(ref.node, upid, what)
                println("    ($what: the lock is held by: ${lockHolders(ref)}; again in 10 s)")
                delay(10_000)
            }
        }

        /** Stops a guest if it runs, waiting out a held lock. */
        suspend fun stop(ref: GuestRef) {
            val status = guests.status(ref)
            if (status.isRunning || status.isPaused) lockedTask(ref, "stop ${ref.vmid}") { guests.power(ref, PowerAction.STOP) }
        }

        /** Starts [ref], runs [block] and stops it again. A failing [block] is what gets reported, not a stop that fails after it. */
        suspend fun <T> whileRunning(ref: GuestRef, block: suspend () -> T): T {
            done(ref.node, guests.power(ref, PowerAction.START), "start")
            val result = try {
                block()
            } catch (e: Exception) {
                runCatching { stop(ref) }.exceptionOrNull()?.let { println("    (stopping ${ref.vmid} afterwards failed too: ${it.message})") }
                throw e
            }
            stop(ref)
            return result
        }

        val backupVolumes = mutableListOf<Pair<String, String>>()

        /**
         * Backs [ct] up to [pbsStorage] like the app's "Back up now", finds that backup on the Backup
         * Server (the storage's datastore and namespace) and runs [checkPbsSnapshot] on it. The
         * cleanup deletes the backup through Proxmox VE, like the others.
         */
        suspend fun backUpToBackupServer(ct: GuestRef, pbsStorage: NodeStorage, pbsLogin: PbsLogin) {
            val made = step("back up the container to ${pbsStorage.storage} (the Backup Server)") {
                done(ct.node, backups.backupNow(ct, pbsStorage, BackupMode.STOP, "$PREFIX {{guestname}}"), "the backup")
                val item = backups.backups(ct).backups.firstOrNull { it.volid.startsWith("${pbsStorage.storage}:") }
                    ?: error("the backup is not listed on ${pbsStorage.storage}")
                backupVolumes += pbsStorage.storage to item.volid
            }
            if (made == null) return
            // Inside a step: a Backup Server that can't be reached fails this check, not the whole test.
            val pbsHub = step("sign in to the Backup Server") { pbsLogin.signIn(scope) } ?: return
            val pbs = PbsRepository(pbsHub)
            val found = step("find the backup on the Backup Server") {
                val form = setup.load(id, pbsStorage.storage).form ?: error("can't read the settings of ${pbsStorage.storage}")
                val group = pbs.groups(RealServerPbsTest.ID, form.datastore, form.namespace).firstOrNull { it.key == "ct/${ct.vmid}" }
                    ?: error("${form.datastore} has no ct/${ct.vmid}: does ${pbsStorage.storage} point to the Backup Server in ANPROX_PBS_URL?")
                Triple(form.datastore, form.namespace, group)
            } ?: return
            val (store, ns, group) = found
            val snapshot = pbs.snapshots(RealServerPbsTest.ID, store, ns, group).first()
            checkPbsSnapshot({ name, block -> step(name) { block() } }, pbs, GuestRepository(pbsHub), RealServerPbsTest.ID, store, ns, group, snapshot)
        }

        /** Stops (if needed) and destroys a guest of ours. */
        suspend fun remove(ref: GuestRef) {
            stop(ref)
            done(ref.node, guests.destroy(ref, purge = true, destroyUnreferencedDisks = true), "destroy ${ref.vmid}")
        }


        val node = clusterState().nodes.firstOrNull { it.status == "online" }?.node ?: error("no online node")
        println("Working on node $node")

        println("Leftovers of an earlier run")
        clusterState().guests.filter { it.name.orEmpty().startsWith(PREFIX) && it.node != null && it.vmid != null }.forEach { g ->
            val ref = GuestRef(id, g.node!!, GuestType.fromApi(g.type) ?: return@forEach, g.vmid!!)
            step("remove ${g.type} ${g.vmid} (${g.name})") { remove(ref) }
        }
        if (pools.pools(id).any { it.id == PREFIX }) {
            step("remove pool $PREFIX") {
                val members = pools.pool(id, PREFIX).members.mapNotNull { it.vmid }.toSet()
                if (members.isNotEmpty()) pools.change(id, PREFIX, PoolChange(vmids = members, remove = true))
                pools.delete(id, PREFIX)
            }
        }
        if (access.users(id).any { it.id == "$PREFIX@pve" }) step("remove user $PREFIX@pve") { access.deleteUser(id, "$PREFIX@pve") }
        if (access.groups(id).any { it.id == PREFIX }) step("remove group $PREFIX") { access.deleteGroup(id, PREFIX) }
        if (access.roles(id).any { it.id == PREFIX }) step("remove role $PREFIX") { access.deleteRole(id, PREFIX) }
        jobs.jobs(id).filter { it.comment == PREFIX }.forEach { job -> step("remove backup job ${job.id}") { jobs.delete(id, job.id) } }
        if (storage.storages(id).any { it.config.storage == PREFIX }) step("remove storage $PREFIX") { setup.remove(id, PREFIX) }
        listOf(FirewallScope.Datacenter, FirewallScope.Node(node)).forEach { scope ->
            // One at a time, re-reading the list: positions shift, and the digest guards each delete.
            repeat(10) {
                val rule = firewall.rules(id, scope).firstOrNull { it.comment == PREFIX } ?: return@repeat
                step("remove a ${scope.path} rule") { firewall.delete(id, scope, rule) }
            }
        }
        if (firewall.securityGroups(id).any { it.group == PREFIX }) {
            step("remove security group $PREFIX") {
                val group = FirewallScope.Group(PREFIX)
                firewall.rules(id, group).sortedByDescending { it.pos }.forEach { firewall.delete(id, group, it) }
                firewall.deleteGroup(id, PREFIX)
            }
        }
        if (firewall.ipSets(id, FirewallScope.Datacenter).any { (set, _) -> set.name == PREFIX }) step("remove IP set $PREFIX") { firewall.deleteIpSet(id, FirewallScope.Datacenter, PREFIX) }
        firewall.aliases(id, FirewallScope.Datacenter).firstOrNull { it.name.equals(PREFIX, ignoreCase = true) }?.let { alias ->
            step("remove alias $PREFIX") { firewall.deleteAlias(id, FirewallScope.Datacenter, alias) }
        }
        try {
            if (sdn.vnets(id).any { it.id == SDN_VNET }) step("remove VNet $SDN_VNET") { sdn.deleteVnet(id, SDN_VNET) }
            if (sdn.zones(id).any { it.id == SDN_ZONE }) step("remove zone $SDN_ZONE") { sdn.deleteZone(id, SDN_ZONE) }
        } catch (_: ApiException) {
            // No SDN on this server.
        }
        removeLeftovers(notify, certs, metrics, id, node)

        val bridge = hardware.bridges(id, node).firstOrNull()?.iface ?: "vmbr0"
        val vmStorage = guests.diskStorages(id, node, GuestType.QEMU).firstOrNull()?.storage ?: error("no storage for VM disks on $node")
        val created = mutableListOf<GuestRef>()
        val downloadedTemplates = mutableListOf<Pair<String, String>>()
        try {
            println("VM")
            val vmid = guests.nextVmid(id)
            val vm = GuestRef(id, node, GuestType.QEMU, vmid)
            val made = step("create VM $vmid on $vmStorage") {
                done(node, guests.createVm(id, node, NewVm(vmid, name = "$PREFIX-vm", storage = vmStorage, diskGiB = 1, cores = 1, memoryMiB = 512, bridge = bridge, ballooning = false)), "create")
                created += vm
            }
            if (made != null) {
                step("options: notes, tag, start order") {
                    val old = guests.options(vm)
                    guests.saveOptions(vm, old, old.copy(description = "AnProx write test", tags = listOf(PREFIX), startup = StartupOrder(order = 9, up = 5)))
                    val saved = guests.options(vm)
                    expect(saved.description == "AnProx write test" && PREFIX in saved.tags && saved.startup.order == 9, "the options to be saved, got $saved")
                }
                step("hardware: 2 cores, 768 MiB") {
                    hardware.apply(vm, mapOf("cores" to "2", "memory" to "768"))
                    val hw = hardware.vm(vm)
                    expect(hw.cores == 2 && hw.memoryMiB == 768, "2 cores and 768 MiB, got ${hw.cores} / ${hw.memoryMiB}")
                }
                // Snapshots need a storage that has them (ZFS, LVM-thin, qcow2 on a directory).
                step("snapshot: take, describe, roll back, delete", optional = { "snapshot" in it.message.orEmpty() && "not available" in it.message.orEmpty() }) {
                    done(node, guests.createSnapshot(vm, "anprox1", "first", includeRam = false), "snapshot")
                    guests.setSnapshotDescription(vm, "anprox1", "changed by the write test")
                    expect(guests.snapshots(vm).any { it.name == "anprox1" && it.description?.trim() == "changed by the write test" }, "the snapshot with its new description")
                    done(node, guests.rollbackSnapshot(vm, "anprox1"), "rollback")
                    done(node, guests.deleteSnapshot(vm, "anprox1"), "delete snapshot")
                    expect(guests.snapshots(vm).none { it.name == "anprox1" }, "the snapshot to be gone")
                }
                step("firewall: add and remove a rule") {
                    val scope = FirewallScope.Guest(node, GuestType.QEMU, vmid)
                    firewall.create(id, scope, FirewallRuleForm(type = "in", action = "ACCEPT", proto = "tcp", dport = "22", comment = PREFIX))
                    val rule = firewall.rules(id, scope).firstOrNull { it.comment == PREFIX }
                    expect(rule != null, "the new rule")
                    firewall.delete(id, scope, rule!!)
                    expect(firewall.rules(id, scope).none { it.comment == PREFIX }, "the rule to be gone")
                }
                // A VM without KVM (a nested test host) can't start; that's the server, not the app.
                step("start and stop", optional = { "KVM" in it.message.orEmpty() }) {
                    done(node, guests.power(vm, PowerAction.START), "start")
                    expect(guests.status(vm).isRunning, "the VM to run")
                    done(node, guests.power(vm, PowerAction.STOP), "stop")
                    expect(!guests.status(vm).isRunning, "the VM to stop")
                }
                step("VM screen over VNC: sign in, read the screen, press a key", optional = { "KVM" in it.message.orEmpty() }) {
                    // Only the empty disk boots, so the screen shows the firmware's lines and nothing about the network.
                    val disk = hardware.vm(vm).disks.first { it.kind == VmDisk.Kind.DISK }.key
                    hardware.apply(vm, mapOf("boot" to "order=$disk"))
                    whileRunning(vm) {
                        delay(3_000) // the firmware finds nothing to boot and says so
                        val session = consoles.open(id, ConsoleTarget(ConsoleKind.VM, node, vmid, "$PREFIX-vm"))
                        val screen = try {
                            readVncScreen(session)
                        } finally {
                            session.close()
                        }
                        val file = File("build/real-server/$screenshot").apply { parentFile.mkdirs() }
                        file.writeBytes(screen.png())
                        println("    (VNC desktop \"${screen.name}\", ${screen.width}x${screen.height}, ${screen.colours} colours, saved to ${file.path})")
                        expect(screen.width > 0 && screen.height > 0, "a screen size")
                        expect(screen.colours > 1, "something drawn on the screen, not one flat colour")
                    }
                }
                val backupStorage = backups.backupStorages(id, node).firstOrNull { !it.isPbs } ?: backups.backupStorages(id, node).firstOrNull()
                if (backupStorage == null) {
                    println("  – backup and restore: no backup storage on $node")
                } else {
                    step("back up to ${backupStorage.storage}, restore as a new VM") {
                        done(node, backups.backupNow(vm, backupStorage, BackupMode.STOP, "$PREFIX {{guestname}}"), "backup")
                        val backup = backups.backups(vm).backups.firstOrNull() ?: error("no backup listed")
                        backupVolumes += backupStorage.storage to backup.volid
                        val restoredId = guests.nextVmid(id)
                        done(node, backups.restore(id, node, backup, restoredId, replace = false, storage = vmStorage), "restore")
                        val restored = GuestRef(id, node, GuestType.QEMU, restoredId)
                        created += restored
                        expect(guests.status(restored).status == "stopped", "the restored VM")
                        guests.saveOptions(restored, guests.options(restored), guests.options(restored).copy(name = "$PREFIX-restored"))
                    }
                }
                val clone = step("clone (full)") {
                    val cloneId = guests.nextVmid(id)
                    done(node, guests.clone(vm, CloneRequest(cloneId, "$PREFIX-clone", full = true)), "clone")
                    GuestRef(id, node, GuestType.QEMU, cloneId).also { created += it }
                }
                if (clone != null) {
                    step("template, then a linked clone of it") {
                        done(node, guests.convertToTemplate(clone), "convert to template")
                        expect(guests.status(clone).template, "a template")
                        val linkedId = guests.nextVmid(id)
                        done(node, guests.clone(clone, CloneRequest(linkedId, "$PREFIX-linked", full = false)), "linked clone")
                        created += GuestRef(id, node, GuestType.QEMU, linkedId)
                    }
                }
                step("pool: create, add the VM, remove it, delete") {
                    pools.create(id, PREFIX, "AnProx write test")
                    pools.change(id, PREFIX, PoolChange(vmids = setOf(vmid)))
                    expect(pools.pool(id, PREFIX).members.any { it.vmid == vmid }, "the VM in the pool")
                    pools.change(id, PREFIX, PoolChange(vmids = setOf(vmid), remove = true))
                    pools.delete(id, PREFIX)
                }
                step("disks: add, grow, detach, delete") {
                    done(node, hardware.apply(vm, mapOf("scsi1" to "$vmStorage:1"), asTask = true), "add disk")
                    expect(hardware.vm(vm).disks.any { it.key == "scsi1" }, "the new disk scsi1")
                    done(node, hardware.resize(vm, mapOf("disk" to "scsi1", "size" to "+1G")), "grow disk")
                    val size = hardware.vm(vm).disks.first { it.key == "scsi1" }.props["size"]
                    expect(size == "2G", "scsi1 to be 2G, got $size")
                    hardware.apply(vm, mapOf("delete" to "scsi1"))
                    val unused = hardware.vm(vm).disks.firstOrNull { it.kind == VmDisk.Kind.UNUSED } ?: error("no unused disk after detaching scsi1")
                    hardware.apply(vm, mapOf("delete" to unused.key))
                    expect(hardware.vm(vm).disks.none { it.kind == VmDisk.Kind.UNUSED }, "the unused disk to be deleted")
                }
                step("snapshot with RAM of the running VM, roll back, delete", optional = { "KVM" in it.message.orEmpty() }) {
                    whileRunning(vm) {
                        done(node, guests.createSnapshot(vm, "anproxram", "with RAM", includeRam = true), "snapshot with RAM")
                        val saved = guests.snapshots(vm).firstOrNull { it.name == "anproxram" }
                        expect(saved?.vmstate == true, "a snapshot with RAM, got $saved")
                        done(node, guests.rollbackSnapshot(vm, "anproxram"), "rollback")
                        val status = guests.status(vm)
                        expect(status.isRunning, "the VM running again after the rollback, got ${status.status}/${status.qmpstatus}")
                        lockedTask(vm, "delete snapshot") { guests.deleteSnapshot(vm, "anproxram") }
                    }
                }
                if (backupStorage != null) {
                    step("backup job: create, run now, delete") {
                        jobs.create(
                            id,
                            BackupJobForm(
                                enabled = false, schedule = "sun 03:00", storage = backupStorage.storage, node = node,
                                selection = BackupSelection.INCLUDE, vmids = setOf(vmid), mode = BackupMode.STOP, comment = PREFIX,
                                // Never prunes the earlier backup, whatever the storage's retention says.
                                retention = Retention(keepAll = true),
                            ),
                        )
                        val job = jobs.jobs(id).firstOrNull { it.comment == PREFIX } ?: error("the new job is not listed")
                        val before = backups.backups(vm).backups.map { it.volid }.toSet()
                        jobs.runNow(id, job).forEach { done(it.node, it.upid, "backup job run") }
                        backups.backups(vm).backups.filter { it.volid !in before }.forEach { backupVolumes += backupStorage.storage to it.volid }
                        jobs.delete(id, job.id)
                        expect(jobs.jobs(id).none { it.comment == PREFIX }, "the job to be gone")
                    }
                }
                // A single node without a cluster can't run HA; that's the server, not the app. The resource stays
                // "ignored", so the HA manager never takes the VM over or arms the node's watchdog.
                step("high availability: add, change, remove", optional = { e -> listOf("quorum", "cluster", "corosync").any { it in e.message.orEmpty() } }) {
                    ha.add(id, "vm:$vmid", HaResourceForm(state = HaState.IGNORED, comment = PREFIX))
                    val resource = ha.resources(id).firstOrNull { it.sid == "vm:$vmid" } ?: error("the HA resource is not listed")
                    ha.update(id, resource, HaResourceForm(state = HaState.IGNORED, maxRestart = 3, comment = PREFIX))
                    expect(ha.resources(id).any { it.sid == "vm:$vmid" && it.maxRestart == 3 }, "max_restart changed to 3")
                    ha.remove(id, ha.resources(id).first { it.sid == "vm:$vmid" })
                    expect(ha.resources(id).none { it.sid == "vm:$vmid" }, "the HA resource to be gone")
                }
            }

            println("Container")
            var template = hardware.containerTemplates(id, node).firstOrNull()
            if (template == null) {
                // None on the node: fetch the smallest distribution from the catalogue (Alpine, a few MB), like the app does.
                val templateStorage = storage.storages(id)
                    .firstOrNull { s -> ContentType.TEMPLATE in ContentType.parse(s.config.content) && s.nodes.any { it.node == node && it.available } }
                    ?.config?.storage
                val pick = storage.appliances(id, node).filter { it.section == "system" }
                    .sortedBy { if (it.packageName.startsWith("alpine")) 0 else 1 }.firstOrNull()
                if (templateStorage != null && pick != null) {
                    step("download template ${pick.template} to $templateStorage") {
                        done(node, storage.downloadAppliance(id, node, templateStorage, pick.template), "template download")
                        template = hardware.containerTemplates(id, node).firstOrNull { it.volid.endsWith(pick.template) }
                        template?.let { downloadedTemplates += templateStorage to it.volid }
                    }
                }
            }
            val ctStorage = guests.diskStorages(id, node, GuestType.LXC).firstOrNull()?.storage
            val ctTemplate = template
            if (ctTemplate == null || ctStorage == null) {
                println("  – no container template or storage on $node")
            } else {
                val ctid = guests.nextVmid(id)
                val ct = GuestRef(id, node, GuestType.LXC, ctid)
                val ctMade = step("create container $ctid from ${ctTemplate.volid}") {
                    val form = NewContainer(
                        ctid, hostname = "$PREFIX-ct", template = ctTemplate.volid, password = TEST_PASSWORD,
                        storage = ctStorage, diskGiB = 1, memoryMiB = 256, network = CtNetSettings(bridge = bridge),
                    )
                    done(node, guests.createContainer(id, node, form), "create container")
                    created += ct
                    expect(guests.status(ct).status == "stopped", "the new container")
                }
                if (ctMade != null && pbsLogin != null) {
                    val pbsStorage = backups.backupStorages(id, node).firstOrNull { it.isPbs }
                    if (pbsStorage == null) {
                        println("  – Backup Server: no Backup Server storage on $node")
                    } else {
                        backUpToBackupServer(ct, pbsStorage, pbsLogin)
                    }
                }
                if (ctMade != null) {
                    val started = step("container: start") {
                        done(node, guests.power(ct, PowerAction.START), "start container")
                        expect(guests.status(ct).isRunning, "the container to run")
                    }
                    if (started != null) {
                        step("container console: log in, resize, run a command") {
                            val session = consoles.open(id, ConsoleTarget(ConsoleKind.CONTAINER, node, ctid, "$PREFIX-ct"))
                            try {
                                session.awaitConnected()
                                val term = TerminalReader(session)
                                // Enter brings back the login prompt (or the shell's, when one is open already).
                                var prompt: String? = null
                                for (attempt in 1..6) {
                                    session.sendInput("\r")
                                    prompt = term.await("login: ", "# ", millis = 5_000)?.first
                                    if (prompt != null) break
                                }
                                expect(prompt != null, "a login or shell prompt on the container's console")
                                if (prompt == "login: ") {
                                    session.sendInput("root\r")
                                    term.expect("Password: ")
                                    session.sendInput("$TEST_PASSWORD\r")
                                    term.expect("# ")
                                }
                                session.resize(100, 30)
                                session.sendInput("stty size; echo anprox-$((6*7))\r")
                                val output = term.expect("anprox-42")
                                // lxc-console passes the size on to the container's terminal. On a mismatch the
                                // test container's own output is shown, control characters spelled out.
                                expect(
                                    TerminalReader.sttySize(output) == (100 to 30),
                                    "the container's terminal to be 100x30 after the resize, got ${TerminalReader.visible(output.takeLast(160))}",
                                )
                            } finally {
                                session.close()
                            }
                        }
                        step("container: stop") {
                            done(node, guests.power(ct, PowerAction.STOP), "stop container")
                            expect(!guests.status(ct).isRunning, "the container to stop")
                        }
                    }
                }
            }

            println("Storage")
            val isoStorage = storage.storages(id)
                .firstOrNull { s -> ContentType.ISO in ContentType.parse(s.config.content) && s.nodes.any { it.node == node && it.available } }
                ?.config?.storage
            if (isoStorage == null) {
                println("  – no storage for ISO images on $node")
            } else {
                step("upload a small image to $isoStorage, then delete it") {
                    val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
                    val source = object : UploadSource {
                        override val name = "$PREFIX-upload.iso"
                        override val size = bytes.size.toLong()
                        override fun open() = ByteArrayInputStream(bytes)
                    }
                    done(node, storage.upload(id, node, isoStorage, ContentType.ISO, source) {}, "upload")
                    val item = storage.content(id, node, isoStorage, ContentType.ISO).firstOrNull { it.fileName == source.name } ?: error("the uploaded image is not listed")
                    done(node, storage.delete(id, node, isoStorage, item.volid), "delete image")
                }
                step("download a file from a URL to $isoStorage, then delete it") {
                    val name = "$PREFIX-download.iso"
                    done(node, storage.download(id, node, isoStorage, DownloadUrlForm(url = DOWNLOAD_URL, filename = name)), "download")
                    val item = storage.content(id, node, isoStorage, ContentType.ISO).firstOrNull { it.fileName == name } ?: error("the downloaded file is not listed")
                    done(node, storage.delete(id, node, isoStorage, item.volid), "delete download")
                }
            }
            step("directory storage: add, then remove") {
                setup.create(id, StorageSetupForm(kind = StorageKind.DIR, id = PREFIX, path = "/tmp/$PREFIX", content = setOf("iso"), nodes = setOf(node)))
                expect(storage.storages(id).any { it.config.storage == PREFIX }, "the new storage")
                setup.remove(id, PREFIX)
                expect(storage.storages(id).none { it.config.storage == PREFIX }, "the storage to be gone")
            }

            println("Firewall")
            // Rules are added switched off, so they never filter anything.
            listOf(FirewallScope.Datacenter, FirewallScope.Node(node)).forEach { scope ->
                step("${scope.path}: add and remove a rule") {
                    firewall.create(id, scope, FirewallRuleForm(type = "in", action = "DROP", proto = "tcp", dport = "65001", comment = PREFIX, enabled = false))
                    val rule = firewall.rules(id, scope).firstOrNull { it.comment == PREFIX } ?: error("the new rule is not listed")
                    firewall.delete(id, scope, rule)
                    expect(firewall.rules(id, scope).none { it.comment == PREFIX }, "the rule to be gone")
                }
            }
            step("security group with a rule, then delete it") {
                firewall.createGroup(id, PREFIX, "AnProx write test")
                val group = FirewallScope.Group(PREFIX)
                firewall.create(id, group, FirewallRuleForm(type = "in", action = "ACCEPT", proto = "tcp", dport = "65002", comment = PREFIX, enabled = false))
                val rule = firewall.rules(id, group).firstOrNull { it.comment == PREFIX } ?: error("the group's rule is not listed")
                firewall.delete(id, group, rule)
                firewall.deleteGroup(id, PREFIX)
                expect(firewall.securityGroups(id).none { it.group == PREFIX }, "the group to be gone")
            }
            step("alias and IP set, then delete them") {
                val dc = FirewallScope.Datacenter
                suspend fun alias() = firewall.aliases(id, dc).firstOrNull { it.name.equals(PREFIX, ignoreCase = true) }
                firewall.createAlias(id, dc, FirewallAliasForm(name = PREFIX, cidr = "192.0.2.10", comment = "AnProx write test"))
                expect(alias() != null, "the new alias")
                firewall.createIpSet(id, dc, PREFIX, "AnProx write test")
                firewall.addIpSetEntry(id, dc, PREFIX, "192.0.2.0/24", "test", nomatch = false)
                expect(firewall.ipSets(id, dc).any { (set, entries) -> set.name == PREFIX && entries.any { it.cidr == "192.0.2.0/24" } }, "the IP set with its entry")
                firewall.deleteIpSet(id, dc, PREFIX)
                // Aliases and IP sets share one file, so the alias is read again for a current digest.
                firewall.deleteAlias(id, dc, alias() ?: error("the alias is not listed"))
                expect(firewall.aliases(id, dc).none { it.name.equals(PREFIX, ignoreCase = true) }, "the alias to be gone")
            }

            println("SDN")
            // Only the pending configuration: nothing is applied to the node's network.
            step("zone and VNet: create, then remove before applying") {
                sdn.createZone(id, SdnZoneForm(type = SdnZoneType.SIMPLE, id = SDN_ZONE))
                sdn.createVnet(id, SdnVnetForm(id = SDN_VNET, zone = SDN_ZONE))
                expect(sdn.zones(id).any { it.id == SDN_ZONE } && sdn.vnets(id).any { it.id == SDN_VNET }, "the new zone and VNet")
                sdn.deleteVnet(id, SDN_VNET)
                sdn.deleteZone(id, SDN_ZONE)
                expect(sdn.zones(id).none { it.id == SDN_ZONE } && sdn.vnets(id).none { it.id == SDN_VNET }, "the zone and VNet to be gone")
            }

            println("Node")
            step("node shell: sign in, resize, run commands") {
                val session = consoles.open(id, ConsoleTarget(ConsoleKind.NODE, node))
                try {
                    session.awaitConnected()
                    val term = TerminalReader(session)
                    expect(term.await("# ", "$ ", millis = 20_000) != null, "a shell prompt")
                    session.resize(120, 40)
                    session.sendInput("stty size; echo anprox-$((6*7)); pveversion\r")
                    val output = term.expect("anprox-42")
                    val version = term.await("running kernel", millis = 10_000)?.second?.let { Regex("""pve-manager/\S+""").find(it)?.value }
                    // Only these values are printed: the shell's own output (last login and so on) stays out of the log.
                    println("    (shell: ${version ?: "no pveversion output"}, stty size ${TerminalReader.sttySize(output)?.let { "${it.first}x${it.second}" }})")
                    expect(TerminalReader.sttySize(output) == (120 to 40), "the shell to be 120x40 after the resize")
                    expect(version != null, "pveversion's output")
                } finally {
                    session.close()
                }
            }
            if (nodeAdmin.network(id, node).pending) {
                println("  – network: $node already has pending changes, so the test leaves its network alone")
            } else {
                step("network: a new bridge waits as a pending change, then is reverted") {
                    val existing = nodeAdmin.network(id, node).interfaces.map { it.iface }.toSet()
                    val name = (99 downTo 90).map { "vmbr$it" }.first { it !in existing }
                    nodeAdmin.createInterface(id, node, NetworkInterfaceForm(type = "bridge", name = name, comment = PREFIX))
                    expect(nodeAdmin.network(id, node).pending, "the bridge as a pending change")
                    nodeAdmin.revertNetwork(id, node)
                    expect(!nodeAdmin.network(id, node).pending, "nothing pending after the revert")
                }
            }
            step("updates: refresh the package index") {
                val task = guests.awaitTask(id, node, nodeAdmin.refresh(id, node))
                // A repository the server can't reach (enterprise without a subscription) fails the task: the server's setup.
                if (!task.succeeded) println("    (apt update ended with: ${task.exitstatus})")
            }
            step("datacenter options: change the sender address, then restore it") {
                val original = cluster.options(id)
                cluster.saveOptions(id, original, original.copy(emailFrom = "anprox-test@example.com"))
                val changed = cluster.options(id)
                try {
                    expect(changed.emailFrom == "anprox-test@example.com", "the new sender address, got ${changed.emailFrom}")
                } finally {
                    cluster.saveOptions(id, changed, changed.copy(emailFrom = original.emailFrom))
                }
                expect(cluster.options(id).emailFrom == original.emailFrom, "the original sender address back")
            }

            notificationSteps(notify, id)
            metricServerSteps(metrics, id)
            certificateSteps(certs, id, node, insecure)

            accessSteps(access, id, password)
        } finally {
            println("Cleaning up")
            created.reversed().forEach { ref -> step("delete ${ref.type.apiName} ${ref.vmid}") { remove(ref) } }
            backupVolumes.forEach { (store, volid) -> step("delete backup $volid") { done(node, storage.delete(id, node, store, volid), "delete backup") } }
            downloadedTemplates.forEach { (store, volid) -> step("delete template $volid") { done(node, storage.delete(id, node, store, volid), "delete template") } }
            if (created.isNotEmpty()) {
                step("nothing of ours is left") { expect(clusterState().guests.none { it.name.orEmpty().startsWith(PREFIX) }, "no $PREFIX guests") }
            }
        }

        println(if (failures.isEmpty()) "All steps passed." else "Failed:\n" + failures.joinToString("\n") { "  $it" })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** A group and user, a role and permission for the group, an API token, a new password; all removed again. */
    private suspend fun accessSteps(access: AccessRepository, id: String, password: String) {
        println("Users and groups")
        val userMade = step("group and user: create") {
            access.createGroup(id, PREFIX, "AnProx write test")
            access.createUser(id, UserForm(name = PREFIX, realm = "pve", password = TEST_PASSWORD, confirmPassword = TEST_PASSWORD, groups = setOf(PREFIX)))
            expect(access.users(id).any { it.id == "$PREFIX@pve" }, "the new user")
        }
        if (userMade != null) {
            step("role and permission for the group, then remove them") {
                access.createRole(id, PREFIX, setOf("VM.Audit", "Datastore.Audit"))
                access.addAcl(id, AclForm(path = "/vms", subject = AclSubject.GROUP, ugid = PREFIX, role = PREFIX))
                val entry = access.acl(id).firstOrNull { it.ugid == PREFIX && it.path == "/vms" } ?: error("the new permission is not listed")
                access.removeAcl(id, entry)
                access.deleteRole(id, PREFIX)
                expect(access.roles(id).none { it.id == PREFIX }, "the role to be gone")
            }
            step("API token: create, then delete") {
                access.createToken(id, "$PREFIX@pve", TokenForm(id = "ci", comment = PREFIX))
                expect(access.tokens(id, "$PREFIX@pve").any { it.id == "ci" || it.id.endsWith("!ci") }, "the new token")
                access.deleteToken(id, "$PREFIX@pve", "ci")
            }
            step("change the test user's password") {
                access.changePassword(id, "$PREFIX@pve", "AnProx-test-2027!", yourPassword = password)
            }
            step("delete the user and the group") {
                access.deleteUser(id, "$PREFIX@pve")
                access.deleteGroup(id, PREFIX)
                expect(access.users(id).none { it.id == "$PREFIX@pve" }, "the user to be gone")
            }
        }
    }

    /** A metric server, switched off so the server never sends to it: added, changed, deleted. */
    private suspend fun metricServerSteps(metrics: MetricServerRepository, id: String) {
        println("Metric servers")
        step("InfluxDB metric server (switched off): add, change, delete") {
            metrics.create(id, MetricServerForm(id = PREFIX, server = "127.0.0.1", port = "8089", disabled = true))
            val settings = metrics.settings(id, PREFIX)
            expect(settings.form.server == "127.0.0.1" && settings.form.protocol == "udp" && settings.form.disabled, "the server as added, got ${settings.form}")
            metrics.update(id, settings.form.copy(mtu = "1400"), settings.digest)
            expect(metrics.settings(id, PREFIX).form.mtu == "1400", "the new MTU")
            expect(metrics.servers(id).first { it.id == PREFIX }.disable, "the server still switched off")
            metrics.delete(id, PREFIX)
            expect(metrics.servers(id).none { it.id == PREFIX }, "the server to be gone")
        }
    }

    /** Notification targets and a matcher, all `anprox-test…`; nothing is sent anywhere real. */
    private suspend fun notificationSteps(notify: NotificationRepository, id: String) {
        println("Notifications")
        step("e-mail target: create, change, delete") {
            val name = "$PREFIX-mail"
            // Switched off and to an address that can't exist: nothing is ever sent through it.
            notify.createTarget(id, NotifyTargetForm(type = NotifyTargetType.SENDMAIL, name = name, mailto = "anprox-test@example.invalid", comment = PREFIX, disabled = true))
            val settings = notify.settings(id, NotifyTargetType.SENDMAIL, name)
            expect("anprox-test@example.invalid" in settings.form.mailto, "the recipient back, got ${settings.form.mailto}")
            notify.updateTarget(id, settings.form.copy(comment = "$PREFIX changed", author = "AnProx test"), settings.digest)
            val target = notify.targets(id).firstOrNull { it.name == name } ?: error("the target is not listed")
            expect(target.comment == "$PREFIX changed" && target.disable, "the new comment, still switched off, got $target")
            notify.deleteTarget(id, target)
            expect(notify.targets(id).none { it.name == name }, "the target to be gone")
        }
        step("webhook target with a header and a secret, a test send, a matcher, then remove them") {
            val hook = "$PREFIX-hook"
            val matcher = "$PREFIX-matcher"
            // Nothing listens on port 9 of the server itself, so nothing leaves it.
            notify.createTarget(
                id,
                NotifyTargetForm(
                    type = NotifyTargetType.WEBHOOK, name = hook, url = "http://127.0.0.1:9/anprox-test", method = "post",
                    headers = "X-AnProx: test", body = "{{ title }}", secrets = "token=not-a-secret", comment = PREFIX,
                ),
            )
            val settings = notify.settings(id, NotifyTargetType.WEBHOOK, hook)
            expect(settings.form.url == "http://127.0.0.1:9/anprox-test" && "X-AnProx" in settings.form.headers, "the address and header back, got ${settings.form.url} / ${settings.form.headers}")
            expect(settings.form.body.trim() == "{{ title }}", "the body back, got ${settings.form.body}")
            // The server reports the failed send; the app shows its message.
            val sent = try {
                notify.test(id, hook)
                "sent"
            } catch (e: ApiException) {
                "the server says: ${e.message}"
            }
            println("    (test through $hook: $sent)")
            // Switched off, so no real notification goes to the test target.
            notify.createMatcher(id, NotifyMatcherForm(name = matcher, targets = setOf(hook), severities = setOf("error"), fields = "exact:type=vzdump", mode = "any", comment = PREFIX, disabled = true))
            val m = notify.matchers(id).firstOrNull { it.name == matcher } ?: error("the matcher is not listed")
            expect(m.targets == listOf(hook) && m.severities == listOf("error") && m.fields == listOf("exact:type=vzdump") && m.disabled, "the matcher as made, got $m")
            notify.updateMatcher(id, m, NotifyMatcherForm.from(m).copy(invert = true, comment = "$PREFIX changed"))
            val changed = notify.matchers(id).first { it.name == matcher }
            expect(changed.invert && changed.comment == "$PREFIX changed", "the matcher inverted, got $changed")
            notify.deleteMatcher(id, matcher)
            notify.deleteTarget(id, notify.targets(id).first { it.name == hook })
            expect(notify.matchers(id).none { it.name == matcher } && notify.targets(id).none { it.name == hook }, "the matcher and the target to be gone")
        }
    }

    /**
     * An ACME domain and a DNS plugin (only settings: nothing is ordered), and, when the node serves
     * the certificate its cluster CA signed, an uploaded one of its own and back.
     */
    private suspend fun certificateSteps(certs: CertificateRepository, id: String, node: String, insecure: Boolean) {
        println("Certificates")
        step("ACME domain: add to $node, then remove it") {
            val config = certs.acmeConfig(id, node)
            if (config.freeKey == null) {
                println("    ($node already has all six ACME domains: nothing added)")
            } else {
                certs.addDomain(id, node, config, ACME_DOMAIN, AcmeDomain.STANDALONE, null)
                val after = certs.acmeConfig(id, node)
                val added = after.domains.firstOrNull { it.domain == ACME_DOMAIN } ?: error("the domain is not listed")
                expect(added.isStandalone, "HTTP validation (standalone), got ${added.plugin}")
                certs.removeDomain(id, node, after, added)
                expect(certs.acmeConfig(id, node).domains.none { it.domain == ACME_DOMAIN }, "the domain to be gone")
            }
        }
        step("ACME DNS plugin: add, change, delete (only its settings)") {
            val apis = certs.dnsApis(id)
            val api = apis.firstOrNull { it.id == "acmedns" } ?: apis.firstOrNull() ?: error("no DNS providers listed")
            val data = api.fields.take(2).ifEmpty { listOf("ANPROX_TEST") }.joinToString("\n") { "$it=anprox-test" }
            certs.createPlugin(id, AcmePluginForm(id = PREFIX, api = api.id, data = data, validationDelay = "30"))
            val plugin = certs.plugins(id).firstOrNull { it.id == PREFIX } ?: error("the plugin is not listed")
            expect(plugin.api == api.id && plugin.data.lines().map { it.trim() }.filter { it.isNotEmpty() }.size == data.lines().size, "the provider and its ${data.lines().size} line(s) back, got ${plugin.api} with ${plugin.data.lines().size}")
            certs.updatePlugin(id, plugin, AcmePluginForm.from(plugin).copy(validationDelay = "60"))
            expect(certs.plugins(id).first { it.id == PREFIX }.validationDelay == 60, "the new validation delay")
            certs.deletePlugin(id, PREFIX)
            expect(certs.plugins(id).none { it.id == PREFIX }, "the plugin to be gone")
        }
        val inUse = (step("certificates of $node") { certs.certificates(id, node) } ?: return).firstOrNull { it.role == CertificateInfo.Role.IN_USE }
        when {
            !insecure -> println("  – own certificate: only tried with \"Allow insecure certificate\" on (the test's certificate is self-signed)")
            // Never replace a certificate the server already has: its own upload, or an ACME one. Its
            // name stays out of the log, which is public on GitHub.
            inUse != null -> println("  – own certificate: $node already serves its own (uploaded or ACME), which the test leaves alone")
            else -> step("own certificate: upload one (the web proxy restarts), then go back to the node's") {
                val (pem, key) = selfSignedCertificate() ?: error("openssl could not make a test certificate")
                ignoreRestart { certs.uploadCustom(id, node, CustomCertificateForm(pem, key)) }
                val served = afterRestart("the uploaded certificate in use") { certs.certificates(id, node).firstOrNull { it.role == CertificateInfo.Role.IN_USE } }
                try {
                    expect(served.commonName == PREFIX, "the uploaded certificate (CN=$PREFIX) in use, got another one")
                } finally {
                    ignoreRestart { certs.removeCustom(id, node) }
                }
                afterRestart("the node's own certificate back") { certs.certificates(id, node).takeIf { list -> list.none { it.role == CertificateInfo.Role.IN_USE } } }
            }
        }
    }

    /** What an earlier run's notification and certificate steps may have left. */
    private suspend fun removeLeftovers(notify: NotificationRepository, certs: CertificateRepository, metrics: MetricServerRepository, id: String, node: String) {
        if (metrics.servers(id).any { it.id == PREFIX }) step("remove metric server $PREFIX") { metrics.delete(id, PREFIX) }
        // Notification matchers first: a target a matcher still names can't be deleted.
        notify.matchers(id).filter { it.name.startsWith(PREFIX) }.forEach { m -> step("remove notification matcher ${m.name}") { notify.deleteMatcher(id, m.name) } }
        notify.targets(id).filter { it.name.startsWith(PREFIX) }.forEach { t -> step("remove notification target ${t.name}") { notify.deleteTarget(id, t) } }
        certs.acmeConfig(id, node).let { config ->
            config.domains.firstOrNull { it.domain == ACME_DOMAIN }?.let { d -> step("remove ACME domain $ACME_DOMAIN") { certs.removeDomain(id, node, config, d) } }
        }
        try {
            if (certs.plugins(id).any { it.id == PREFIX }) step("remove ACME plugin $PREFIX") { certs.deletePlugin(id, PREFIX) }
        } catch (_: ApiException) {
            // Not allowed to read plugins: then this user didn't make one either.
        }
        if (certs.certificates(id, node).any { it.role == CertificateInfo.Role.IN_USE && it.commonName == PREFIX }) {
            step("remove the test's certificate from $node") {
                ignoreRestart { certs.removeCustom(id, node) }
                afterRestart("the node's own certificate back") { certs.certificates(id, node).takeIf { list -> list.none { it.role == CertificateInfo.Role.IN_USE } } }
            }
        }
    }

    /** A call that restarts the web proxy (a certificate change): the connection may drop as it does. */
    private suspend fun ignoreRestart(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: ApiException.Unreachable) {
            println("    (the connection dropped while the web proxy restarted)")
        }
    }

    /** Asks [block] again, for up to 90 s, until it answers (not null) once the web proxy is back. */
    private suspend fun <T : Any> afterRestart(what: String, block: suspend () -> T?): T {
        repeat(30) {
            try {
                block()?.let { return it }
            } catch (e: ApiException) {
                // Still restarting: no connection, or a proxy error while it comes back.
                if (e !is ApiException.Unreachable && !(e is ApiException.Server && e.code >= 500)) throw e
            }
            delay(3_000)
        }
        error("expected $what within 90 s")
    }

    /** A self-signed certificate and its key (PEM) for CN=anprox-test, made with openssl; null without it. */
    private fun selfSignedCertificate(): Pair<String, String>? = try {
        val dir = Files.createTempDirectory("anprox-cert").toFile()
        try {
            val key = File(dir, "key.pem")
            val cert = File(dir, "cert.pem")
            val process = ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                "-subj", "/CN=$PREFIX", "-addext", "subjectAltName=DNS:$ACME_DOMAIN",
                "-keyout", key.path, "-out", cert.path,
            ).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) cert.readText() to key.readText() else null
        } finally {
            dir.deleteRecursively()
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val PREFIX = "anprox-test"
        const val TEST_PASSWORD = "AnProx-test-2026!"

        /** Only ever in the node's ACME settings: `.invalid` can't be ordered or resolved. */
        const val ACME_DOMAIN = "anprox-test.invalid"

        /** SDN IDs are at most 8 lowercase letters and digits. */
        const val SDN_ZONE = "apxtest"
        const val SDN_VNET = "apxtvnet"

        /**
         * A small file on Proxmox's own download server, stored under an `.iso` name (all Proxmox checks).
         * Plain HTTP, as Proxmox's own apt and template sources use it: the test host got a certificate
         * for another name over HTTPS.
         */
        const val DOWNLOAD_URL = "http://download.proxmox.com/debian/pve/dists/bookworm/Release"
    }
}
