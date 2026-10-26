package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.backup.BackupRepository
import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.data.datacenter.BackupJobRepository
import io.github.lucabarbalata.anprox.data.datacenter.CephRepository
import io.github.lucabarbalata.anprox.data.datacenter.ClusterRepository
import io.github.lucabarbalata.anprox.data.datacenter.FirewallRepository
import io.github.lucabarbalata.anprox.data.datacenter.HaRepository
import io.github.lucabarbalata.anprox.data.datacenter.MetricServerRepository
import io.github.lucabarbalata.anprox.data.datacenter.NotificationRepository
import io.github.lucabarbalata.anprox.data.datacenter.PoolRepository
import io.github.lucabarbalata.anprox.data.datacenter.ReplicationRepository
import io.github.lucabarbalata.anprox.data.datacenter.SdnRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.datacenter.StorageSetupRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.metrics.MetricsRepository
import io.github.lucabarbalata.anprox.data.metrics.MetricsSource
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
import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.RrdTimeframe
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
 * Read-only checks against a real Proxmox VE: every read the app makes, so a field the mock
 * doesn't have, or has in another shape, shows up here. Nothing on the server is changed.
 *
 * Skipped unless these are set (use a dedicated user; an Auditor role on `/` is enough):
 * ```
 * ANPROX_PVE_URL=https://pve.example:8006 ANPROX_PVE_USER=anprox@pve ANPROX_PVE_PASSWORD=… \
 *   ./gradlew -Panprox.jvmOnly=true :core:data:test --tests '*RealServerSmokeTest*' -i
 * ```
 * `ANPROX_PVE_INSECURE=0` requires a trusted certificate (by default a self-signed one is accepted).
 * Each check prints ✓, – (not available on this server, such as Ceph) or ✗; the test fails if
 * any ✗ is left.
 */
class RealServerSmokeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val failures = mutableListOf<String>()

    /** Runs [block]; [optional] errors (a feature this server doesn't have) are reported, not failed. */
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
            // A parsing error (kotlinx.serialization) or a bug: always a failure.
            failures += "$name: ${e::class.simpleName}: ${e.message}"
            println("  ✗ $name: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "none"
        is Collection<*> -> "${value.size} item(s)"
        is Map<*, *> -> "${value.size} item(s)"
        is String -> "${value.length} characters"
        else -> value.toString().take(120)
    }

    /** Features a server may simply not have: answered with an error, not a broken app. */
    private val notConfigured: (ApiException) -> Boolean = { it is ApiException.Server || it is ApiException.NotFound || it is ApiException.Forbidden }

    @Test
    fun `every read the app makes parses the real server's answers`() = runBlocking {
        val url = System.getenv("ANPROX_PVE_URL").orEmpty()
        val user = System.getenv("ANPROX_PVE_USER").orEmpty()
        val password = System.getenv("ANPROX_PVE_PASSWORD").orEmpty()
        assumeTrue(url.isNotBlank() && user.contains('@') && password.isNotEmpty(), "ANPROX_PVE_URL/USER/PASSWORD not set")
        readEverything(url, user, password, insecure = System.getenv("ANPROX_PVE_INSECURE") != "0")
    }

    /** The same checks against the mock, so they keep working between real-server runs. */
    @Test
    fun `the checks pass against the mock`() = runBlocking {
        val mock = MockProxmoxServer(ProxmoxProduct.PVE).start()
        try {
            readEverything(mock.baseUrl, "root@pam", "anprox", insecure = true)
        } finally {
            mock.stop()
        }
    }

    private suspend fun readEverything(url: String, user: String, password: String, insecure: Boolean) {
        val profile = ServerProfile(
            id = "real", name = "Real server", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(url)),
            username = user.substringBefore('@'), realm = user.substringAfter('@'),
            allowInsecureTls = insecure,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        assertIs<LoginResult.Success>(hub.login("real", password, remember = false), "sign-in (a user with a second factor can't be used here)")
        val id = "real"

        val guests = GuestRepository(hub)
        val nodeAdmin = NodeAdminRepository(hub)
        val metrics = MetricsRepository(hub)
        val storage = StorageRepository(hub)
        val firewall = FirewallRepository(hub)
        val hardware = HardwareRepository(hub)
        val backups = BackupRepository(hub)

        println("Dashboard")
        val snapshot = SnapshotRepository(hub).fetch(hub.server(id)!!) as PveSnapshot
        println("  ✓ snapshot: ${snapshot.nodes.size} node(s), ${snapshot.guests.size} guest(s), ${snapshot.storages.size} storage(s), ${snapshot.version}")
        val node = snapshot.nodes.firstOrNull { it.status == "online" }?.node ?: error("no online node")

        println("Node $node")
        check("status") { hub.use(id) { it.pve { nodeStatus(node) } } }
        check("history (hour)") { metrics.history(MetricsSource.Node(id, node), RrdTimeframe.HOUR) }
        check("task history") { guests.taskHistory(id, node, 0, 20, errorsOnly = false) }
        check("failed tasks") { guests.taskHistory(id, node, 0, 20, errorsOnly = true) }
        check("updates") { nodeAdmin.updates(id, node) }
        check("package versions") { nodeAdmin.versions(id, node) }
        check("subscription") { nodeAdmin.subscription(id, node) }
        check("repositories") { nodeAdmin.repositories(id, node) }
        check("services") { nodeAdmin.services(id, node) }
        check("network") { nodeAdmin.network(id, node) }
        check("disks") { nodeAdmin.disks(id, node) }
        check("DNS") { nodeAdmin.dns(id, node) }
        check("hosts") { nodeAdmin.hosts(id, node) }
        check("time") { nodeAdmin.time(id, node) }
        check("journal") { nodeAdmin.journal(id, node, 50) }
        check("firewall rules") { firewall.rules(id, FirewallScope.Node(node)) }
        check("firewall options") { firewall.options(id, FirewallScope.Node(node)) }
        check("firewall log") { firewall.log(id, FirewallScope.Node(node), 50) }
        check("bridges") { hardware.bridges(id, node) }
        check("ISO images") { hardware.isoImages(id, node) }
        check("container templates") { hardware.containerTemplates(id, node) }
        check("host CPUs") { hardware.hostCpus(id, node) }
        check("backup storages") { backups.backupStorages(id, node) }
        check("template catalogue") { storage.appliances(id, node) }
        check("LVM volume groups", notConfigured) { StorageSetupRepository(hub).scanLvm(id, node) }
        check("ZFS pools", notConfigured) { StorageSetupRepository(hub).scanZfs(id, node) }
        val certs = CertificateRepository(hub)
        check("certificates") { certs.certificates(id, node).joinToString { "${it.role}: ${it.commonName}" } }
        check("ACME settings") { certs.acmeConfig(id, node).let { "${it.domains.size} domain(s), account ${it.accountOrDefault}" } }

        println("Storage")
        check("storages") { storage.storages(id) }
        snapshot.storages.filter { it.node == node && it.status == "available" }.take(4).forEach { s ->
            val name = s.storage ?: return@forEach
            check("$name content") { storage.content(id, node, name) }
            check("$name usage history") { metrics.history(MetricsSource.Storage(id, node, name), RrdTimeframe.DAY) }
        }

        println("Guests")
        val sample = snapshot.guests.filter { it.node != null && it.vmid != null }
            .groupBy { it.type }.values.flatMap { it.take(3) }
        if (sample.isEmpty()) println("  – no guests")
        sample.forEach { g ->
            val type = GuestType.fromApi(g.type) ?: return@forEach
            val ref = GuestRef(id, g.node!!, type, g.vmid!!)
            val label = "${g.type} ${g.vmid}"
            check("$label status") { guests.status(ref) }
            check("$label options") { guests.options(ref) }
            check("$label snapshots") { guests.snapshots(ref) }
            check("$label addresses") { guests.networkInfo(ref) }
            check("$label hardware") { if (type == GuestType.QEMU) hardware.vm(ref) else hardware.container(ref) }
            if (type == GuestType.QEMU) check("$label cloud-init") { hardware.cloudInit(ref).first }
            if (!g.template) check("$label history (day)") { metrics.history(MetricsSource.Guest(ref), RrdTimeframe.DAY) }
            check("$label backups") { backups.backups(ref) }
            val fw = FirewallScope.Guest(ref.node, type, ref.vmid)
            check("$label firewall rules") { firewall.rules(id, fw) }
            check("$label firewall options") { firewall.options(id, fw) }
            check("$label firewall aliases") { firewall.aliases(id, fw) }
            check("$label firewall IP sets") { firewall.ipSets(id, fw) }
            if (type == GuestType.QEMU && g.status == "running") {
                check("$label agent freeze state", notConfigured) { guests.freezeStatus(ref) }
            }
        }
        check("next free ID") { guests.nextVmid(id) }
        check("used IDs") { guests.usedVmids(id) }

        println("Datacenter")
        check("options") { ClusterRepository(hub).options(id) }
        check("cluster log") { ClusterRepository(hub).log(id, 100) }
        check("backup jobs") { BackupJobRepository(hub).jobs(id) }
        check("guests without backup") { BackupJobRepository(hub).notBackedUp(id) }
        check("firewall rules") { firewall.rules(id, FirewallScope.Datacenter) }
        check("firewall options") { firewall.options(id, FirewallScope.Datacenter) }
        check("firewall macros") { firewall.macros(id) }
        check("security groups") { firewall.securityGroups(id) }
        check("aliases") { firewall.aliases(id, FirewallScope.Datacenter) }
        check("IP sets") { firewall.ipSets(id, FirewallScope.Datacenter) }
        check("HA resources") { HaRepository(hub).resources(id) }
        check("HA status") { HaRepository(hub).status(id) }
        check("HA groups") { HaRepository(hub).groups(id) }
        check("HA rules") { HaRepository(hub).rules(id) }
        check("replication") { ReplicationRepository(hub).overview(id) }
        check("pools") { PoolRepository(hub).pools(id) }
        val access = AccessRepository(hub)
        check("users") { access.users(id) }
        check("groups") { access.groups(id) }
        check("roles") { access.roles(id) }
        check("permissions (ACL)") { access.acl(id) }
        check("realms") { access.realms(id) }
        check("scheduled realm syncs", notConfigured) { access.syncJobs(id) }
        check("second factors", notConfigured) { access.tfa(id) }
        check("own permissions") { access.permissions(id, null) }
        val sdn = SdnRepository(hub)
        check("SDN zones", notConfigured) { sdn.zones(id) }
        check("SDN VNets", notConfigured) { sdn.vnets(id) }
        check("SDN controllers", notConfigured) { sdn.controllers(id) }
        check("SDN IPAMs", notConfigured) { sdn.ipams(id) }
        check("SDN DNS", notConfigured) { sdn.dnsServers(id) }
        val ceph = CephRepository(hub)
        check("Ceph status", { CephRepository.notSetUp(it) }) { ceph.overview(id, node) }
        // Accounts and plugins need Sys.Modify; an auditor gets "–".
        check("ACME accounts", notConfigured) { certs.accounts(id) }
        val directories = try {
            certs.directories(id).also { println("  ✓ ACME directories: ${it.size} item(s)") }
        } catch (e: ApiException) {
            failures += "ACME directories: ${e.message}"
            println("  ✗ ACME directories: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
        // The terms come from the CA itself, so the server needs to reach it.
        directories.firstOrNull()?.let { dir -> check("ACME terms of ${dir.name}", notConfigured) { certs.meta(id, dir.url) } }
        check("ACME plugins", notConfigured) { certs.plugins(id) }
        check("ACME DNS providers") { certs.dnsApis(id) }
        val notify = NotificationRepository(hub)
        val targets = try {
            notify.targets(id).also { println("  ✓ notification targets: ${it.size} item(s)") }
        } catch (e: ApiException) {
            failures += "notification targets: ${e.message}"
            println("  ✗ notification targets: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
        targets.forEach { t ->
            val type = t.kind ?: return@forEach
            check("${t.type} target ${t.name}: settings") { notify.settings(id, type, t.name).form.type }
        }
        check("notification matchers") { notify.matchers(id) }
        val metricRepo = MetricServerRepository(hub)
        val metricServers = try {
            metricRepo.servers(id).also { println("  ✓ metric servers: ${it.size} item(s)") }
        } catch (e: ApiException) {
            failures += "metric servers: ${e.message}"
            println("  ✗ metric servers: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
        metricServers.forEach { m -> check("${m.type} metric server ${m.id}: settings") { metricRepo.settings(id, m.id).form.type } }
        check("notification fields and values") { notify.fieldValues(id) }

        println(if (failures.isEmpty()) "All checks passed." else "Failed:\n" + failures.joinToString("\n") { "  $it" })
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
