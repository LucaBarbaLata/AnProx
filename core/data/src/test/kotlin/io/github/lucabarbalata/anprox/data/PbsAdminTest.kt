package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.backupserver.PbsRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.node.NodeAdminRepository
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.pbs.CatalogEntry
import io.github.lucabarbalata.anprox.model.pbs.KeepOptions
import io.github.lucabarbalata.anprox.model.pbs.PbsJobKind
import io.github.lucabarbalata.anprox.model.pbs.PbsRemoteForm
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.ServiceAction
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Backup Server administration (M8) against the mock. */
class PbsAdminTest {
    private val pbs = MockProxmoxServer(ProxmoxProduct.PBS, taskDurationMillis = 0).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hub: ServerHub

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pbs.stop()
    }

    private suspend fun repo(): PbsRepository {
        val profile = ServerProfile(
            id = "pbs", name = "pbs", product = ProxmoxProduct.PBS,
            endpoints = listOf(Endpoint(pbs.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pbs", "anprox", remember = false)
        return PbsRepository(hub)
    }

    @Test
    fun `datastores, namespaces, groups and snapshots`() = runBlocking {
        val repo = repo()
        assertEquals(listOf("store1", "store2"), repo.datastores("pbs").map { it.info.store })
        assertTrue(repo.datastores("pbs").first().usage?.fraction != null)
        val status = repo.status("pbs", "store1")
        assertEquals(2 to 6, status.counts["vm"]) // across namespaces
        assertEquals(listOf("", "lab", "prod", "prod/db"), repo.namespaces("pbs", "store1").map { it.ns })
        val groups = repo.groups("pbs", "store1", "")
        assertEquals(listOf("ct/200", "host/pve1", "vm/100", "vm/101"), groups.map { it.key })
        val web = repo.snapshots("pbs", "store1", "", groups.single { it.key == "vm/100" })
        assertEquals(3, web.size)
        assertTrue(web.first().time > web.last().time) // newest first
        assertEquals(1, repo.snapshots("pbs", "store1", "prod", groups.single { it.key == "vm/100" }).size)

        repo.createNamespace("pbs", "store1", "lab", "k8s")
        assertTrue(repo.namespaces("pbs", "store1").any { it.ns == "lab/k8s" })
        assertFailsWith<ApiException> { repo.createNamespace("pbs", "store1", "lab", "k8s") }
        assertFailsWith<ApiException> { repo.deleteNamespace("pbs", "store1", "prod", deleteGroups = false) } // not empty
        repo.deleteNamespace("pbs", "store1", "lab", deleteGroups = false)
        assertFalse(repo.namespaces("pbs", "store1").any { it.ns.startsWith("lab") })
        Unit
    }

    @Test
    fun `notes, protection, verify, forget and prune`() = runBlocking {
        val repo = repo()
        val guests = GuestRepository(hub)
        val db = repo.groups("pbs", "store1", "").single { it.key == "vm/101" }
        val (newest, oldest) = repo.snapshots("pbs", "store1", "", db)
        assertTrue(newest.protected)
        assertFailsWith<ApiException> { repo.deleteSnapshot("pbs", "store1", "", newest) }
        assertFailsWith<ApiException> { repo.deleteGroup("pbs", "store1", "", db) }
        repo.setNotes("pbs", "store1", "", oldest, "before the schema change")
        repo.setProtected("pbs", "store1", "", oldest, true)
        val again = repo.snapshots("pbs", "store1", "", db).last()
        assertEquals("before the schema change", again.comment)
        assertTrue(again.protected)

        val upid = repo.verify("pbs", "store1", "", snapshot = again)
        assertTrue(guests.awaitTask("pbs", "localhost", upid).succeeded)
        assertEquals("ok", repo.snapshots("pbs", "store1", "", db).last().verifyState)
        assertTrue(guests.taskLog("pbs", "localhost", upid).isNotEmpty())

        val web = repo.groups("pbs", "store1", "").single { it.key == "vm/100" }
        val dry = repo.prune("pbs", "store1", "", web, KeepOptions(last = "1"), dryRun = true)
        assertEquals(listOf(true, false, false), dry.map { it.keep })
        assertEquals(3, repo.snapshots("pbs", "store1", "", web).size) // only a dry run
        repo.prune("pbs", "store1", "", web, KeepOptions(last = "2"), dryRun = false)
        assertEquals(2, repo.snapshots("pbs", "store1", "", web).size)

        val pihole = repo.groups("pbs", "store1", "").single { it.key == "ct/200" }
        repo.deleteSnapshot("pbs", "store1", "", repo.snapshots("pbs", "store1", "", pihole).last())
        assertEquals(1, repo.snapshots("pbs", "store1", "", pihole).size)
        Unit
    }

    @Test
    fun `file restore - browse the catalog and download a file and a folder`() = runBlocking {
        val repo = repo()
        val pihole = repo.groups("pbs", "store1", "").single { it.key == "ct/200" }
        val snapshot = repo.snapshots("pbs", "store1", "", pihole).first()
        assertTrue(snapshot.hasCatalog)
        val top = repo.catalog("pbs", "store1", "", snapshot)
        val archive = top.first()
        assertEquals("root.pxar.didx", archive.name)
        assertTrue(archive.browsable)
        val etc = repo.catalog("pbs", "store1", "", snapshot, archive.filepath).single { it.name == "etc" }
        val hosts = repo.catalog("pbs", "store1", "", snapshot, etc.filepath).single { it.name == "hosts" }
        assertEquals("/root.pxar.didx/etc/hosts", hosts.path)
        val file = ByteArrayOutputStream()
        repo.download("pbs", "store1", "", snapshot, hosts, file)
        assertTrue(file.toString(Charsets.UTF_8).contains("pihole.lan"))

        val zip = ByteArrayOutputStream()
        repo.download("pbs", "store1", "", snapshot, etc, zip)
        val names = ZipInputStream(ByteArrayInputStream(zip.toByteArray())).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue("pihole/pihole.toml" in names, names.toString())

        val vm = repo.snapshots("pbs", "store1", "", repo.groups("pbs", "store1", "").single { it.key == "vm/100" }).first()
        assertFalse(vm.hasCatalog)
        assertFailsWith<ApiException> { repo.catalog("pbs", "store1", "", vm, CatalogEntry.ROOT) }
        Unit
    }

    @Test
    fun `garbage collection, maintenance, jobs and remotes`() = runBlocking {
        val repo = repo()
        val guests = GuestRepository(hub)
        val before = repo.gc("pbs", "store1")!!
        assertEquals("OK", before.lastRunState)
        assertTrue(guests.awaitTask("pbs", "localhost", repo.startGc("pbs", "store1")).succeeded)
        assertTrue(repo.gc("pbs", "store1")!!.lastRunEnd!! >= before.lastRunEnd!!)

        repo.setMaintenance("pbs", "store2", "offline", "Replacing disks")
        assertEquals("offline", repo.maintenance("pbs", "store2").type)
        assertEquals("Replacing disks", repo.maintenance("pbs", "store2").message)
        assertFailsWith<ApiException> { repo.startGc("pbs", "store2") }
        repo.setMaintenance("pbs", "store2", null, "")
        assertEquals(null, repo.maintenance("pbs", "store2").type)

        val jobs = repo.jobs("pbs")
        assertEquals(listOf(PbsJobKind.SYNC, PbsJobKind.VERIFY, PbsJobKind.PRUNE), jobs.map { it.kind })
        val sync = jobs.first()
        assertTrue(sync.failed)
        assertEquals("offsite", sync.remote)
        assertTrue(guests.awaitTask("pbs", "localhost", repo.runJob("pbs", sync)).succeeded)
        assertFalse(repo.jobs("pbs").first().failed)
        val prune = jobs.last()
        assertEquals("14", prune.keep?.daily)
        repo.setPruneJobEnabled("pbs", prune, enabled = false)
        assertTrue(repo.jobs("pbs").last().disabled)

        assertEquals(listOf("offsite"), repo.remotes("pbs").map { it.name })
        assertFailsWith<ApiException> { repo.deleteRemote("pbs", "offsite") } // the sync job uses it
        repo.createRemote("pbs", PbsRemoteForm(name = "lab-pbs", host = "10.0.0.40", authId = "root@pam", password = "secret"))
        assertEquals(listOf("lab-pbs", "offsite"), repo.remotes("pbs").map { it.name })
        repo.deleteRemote("pbs", "lab-pbs")
        Unit
    }

    @Test
    fun `the node - status, and the node administration screens' calls`() = runBlocking {
        val repo = repo()
        val status = repo.nodeStatus("pbs")
        assertEquals(12, status.cpus)
        assertTrue(status.kernel!!.startsWith("Linux 6.8"))
        val admin = NodeAdminRepository(hub)
        assertTrue(admin.updates("pbs", PbsRepository.NODE).isNotEmpty())
        assertTrue(admin.repositories("pbs", PbsRepository.NODE).files.isNotEmpty())
        val services = admin.services("pbs", PbsRepository.NODE)
        assertTrue(services.isNotEmpty())
        val upid = admin.serviceAction("pbs", PbsRepository.NODE, services.first { it.running }, ServiceAction.RESTART)
        assertTrue(GuestRepository(hub).awaitTask("pbs", PbsRepository.NODE, upid).succeeded)
        assertTrue(admin.journal("pbs", PbsRepository.NODE, 50).isNotEmpty())
        assertTrue(GuestRepository(hub).awaitTask("pbs", PbsRepository.NODE, admin.refresh("pbs", PbsRepository.NODE)).succeeded)
        Unit
    }
}
