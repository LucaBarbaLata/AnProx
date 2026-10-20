package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.backup.BackupRepository
import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.data.datacenter.PoolRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.snapshot.PveSnapshot
import io.github.lucabarbalata.anprox.data.snapshot.SnapshotRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.CloneRequest
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.FirewallRuleForm
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NewContainer
import io.github.lucabarbalata.anprox.model.pve.NewVm
import io.github.lucabarbalata.anprox.model.pve.PoolChange
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.StartupOrder
import io.github.lucabarbalata.anprox.model.pve.UserForm
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Creates, changes and deletes things on a **test** Proxmox VE the way the app does: a VM (options,
 * hardware, snapshot, firewall rule, start/stop, backup and restore, clone), a container, a pool,
 * a user and a group. It only touches what it creates, all named `anprox-test…`, removes
 * leftovers of an earlier run first and deletes everything at the end.
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
        writeEverything(url, user, password, insecure = System.getenv("ANPROX_PVE_INSECURE") != "0")
    }

    @Test
    fun `the same steps pass against the mock`() = runBlocking {
        val mock = MockProxmoxServer(ProxmoxProduct.PVE).start()
        try {
            writeEverything(mock.baseUrl, "root@pam", "anprox", insecure = true)
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

    private suspend fun writeEverything(url: String, user: String, password: String, insecure: Boolean) {
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

        suspend fun cluster() = snapshots.fetch(hub.server(id)!!) as PveSnapshot
        suspend fun done(node: String, upid: String?, what: String) {
            if (upid == null) return
            val task = guests.awaitTask(id, node, upid)
            expect(task.succeeded, "$what to succeed, got ${task.exitstatus}")
        }

        /** Stops (if needed) and destroys a guest of ours. */
        suspend fun remove(ref: GuestRef) {
            val status = guests.status(ref)
            if (status.isRunning || status.isPaused) done(ref.node, guests.power(ref, PowerAction.STOP), "stop ${ref.vmid}")
            done(ref.node, guests.destroy(ref, purge = true, destroyUnreferencedDisks = true), "destroy ${ref.vmid}")
        }

        val node = cluster().nodes.firstOrNull { it.status == "online" }?.node ?: error("no online node")
        println("Working on node $node")

        println("Leftovers of an earlier run")
        cluster().guests.filter { it.name.orEmpty().startsWith(PREFIX) && it.node != null && it.vmid != null }.forEach { g ->
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

        val bridge = hardware.bridges(id, node).firstOrNull()?.iface ?: "vmbr0"
        val vmStorage = guests.diskStorages(id, node, GuestType.QEMU).firstOrNull()?.storage ?: error("no storage for VM disks on $node")
        val created = mutableListOf<GuestRef>()
        val backupVolumes = mutableListOf<Pair<String, String>>()
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
                step("clone (full)") {
                    val cloneId = guests.nextVmid(id)
                    done(node, guests.clone(vm, CloneRequest(cloneId, "$PREFIX-clone", full = true)), "clone")
                    created += GuestRef(id, node, GuestType.QEMU, cloneId)
                }
                step("pool: create, add the VM, remove it, delete") {
                    pools.create(id, PREFIX, "AnProx write test")
                    pools.change(id, PREFIX, PoolChange(vmids = setOf(vmid)))
                    expect(pools.pool(id, PREFIX).members.any { it.vmid == vmid }, "the VM in the pool")
                    pools.change(id, PREFIX, PoolChange(vmids = setOf(vmid), remove = true))
                    pools.delete(id, PREFIX)
                }
            }

            println("Container")
            val template = hardware.containerTemplates(id, node).firstOrNull()
            val ctStorage = guests.diskStorages(id, node, GuestType.LXC).firstOrNull()?.storage
            if (template == null || ctStorage == null) {
                println("  – no container template or storage on $node (download one in Storage → CT templates → Template catalogue)")
            } else {
                val ctid = guests.nextVmid(id)
                val ct = GuestRef(id, node, GuestType.LXC, ctid)
                step("create container $ctid from ${template.volid}") {
                    val form = NewContainer(
                        ctid, hostname = "$PREFIX-ct", template = template.volid, password = TEST_PASSWORD,
                        storage = ctStorage, diskGiB = 1, memoryMiB = 256, network = CtNetSettings(bridge = bridge),
                    )
                    done(node, guests.createContainer(id, node, form), "create container")
                    created += ct
                    expect(guests.status(ct).status == "stopped", "the new container")
                }
            }

            println("Users and groups")
            step("group and user: create, then delete") {
                access.createGroup(id, PREFIX, "AnProx write test")
                access.createUser(id, UserForm(name = PREFIX, realm = "pve", password = TEST_PASSWORD, confirmPassword = TEST_PASSWORD, groups = setOf(PREFIX)))
                expect(access.users(id).any { it.id == "$PREFIX@pve" }, "the new user")
                access.deleteUser(id, "$PREFIX@pve")
                access.deleteGroup(id, PREFIX)
            }
        } finally {
            println("Cleaning up")
            created.reversed().forEach { ref -> step("delete ${ref.type.apiName} ${ref.vmid}") { remove(ref) } }
            backupVolumes.forEach { (store, volid) -> step("delete backup $volid") { done(node, storage.delete(id, node, store, volid), "delete backup") } }
            if (created.isNotEmpty()) {
                step("nothing of ours is left") { expect(cluster().guests.none { it.name.orEmpty().startsWith(PREFIX) }, "no $PREFIX guests") }
            }
        }

        println(if (failures.isEmpty()) "All steps passed." else "Failed:\n" + failures.joinToString("\n") { "  $it" })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private companion object {
        const val PREFIX = "anprox-test"
        const val TEST_PASSWORD = "AnProx-test-2026!"
    }
}
