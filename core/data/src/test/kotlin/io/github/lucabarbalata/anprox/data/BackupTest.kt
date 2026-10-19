package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.backup.BackupRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.BackupMode
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Backup now, browse and restore, end to end against the mock (questionnaire Q20). */
class BackupTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val web = GuestRef("pve", "pve1", GuestType.QEMU, 100)       // running, backups on pbs and local
    private val win11 = GuestRef("pve", "pve2", GuestType.QEMU, 102)     // stopped, one backup on nas-nfs
    private val pihole = GuestRef("pve", "pve1", GuestType.LXC, 200)     // running container, one pbs backup

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private class Repos(val backups: BackupRepository, val guests: GuestRepository)

    private suspend fun repos(user: String = "root", realm: String = "pam"): Repos {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return Repos(BackupRepository(hub), GuestRepository(hub))
    }

    private suspend fun Repos.finish(node: String, upid: String) = assertTrue(guests.awaitTask("pve", node, upid).succeeded)

    @Test
    fun `backup storages put the Backup Server first`() = runBlocking {
        val names = repos().backups.backupStorages("pve", "pve1").map { it.storage }
        assertEquals(listOf("pbs", "local", "nas-nfs"), names)
    }

    @Test
    fun `a guest's backups come from every storage, newest first`() = runBlocking {
        val listing = repos().backups.backups(web)
        assertEquals(listOf("pbs", "local", "pbs"), listing.backups.map { it.storage })
        assertTrue(listing.backups.zipWithNext().all { (a, b) -> a.ctime!! >= b.ctime!! })
        assertTrue(listing.backups.any { it.isProtected })
        assertTrue(listing.failed.isEmpty())
    }

    @Test
    fun `backup now adds a backup with the rendered notes`() = runBlocking {
        val r = repos()
        val pbs = r.backups.backupStorages("pve", "pve1").first { it.isPbs }
        r.finish("pve1", r.backups.backupNow(pihole, pbs, BackupMode.SNAPSHOT, "{{guestname}} on {{node}}"))
        val newest = r.backups.backups(pihole).backups.first()
        assertEquals("pihole on pve1", newest.notes)
        assertEquals(GuestType.LXC, newest.guestType)
    }

    @Test
    fun `restore as a new guest uses the next free id`() = runBlocking {
        val r = repos()
        val backup = r.backups.backups(win11).backups.single()
        val id = r.backups.nextVmid("pve")
        assertEquals(105, id)
        r.finish("pve2", r.backups.restore("pve", "pve2", backup, id, replace = false))
        val status = r.guests.observeStatusOnce(GuestRef("pve", "pve2", GuestType.QEMU, id))
        assertEquals("win11", status.name)
        assertEquals(106, r.backups.nextVmid("pve"))
    }

    @Test
    fun `restore can put the disks on another storage`() = runBlocking {
        val r = repos()
        val backup = r.backups.backups(win11).backups.single()
        // `local` holds ISO images and backups, not VM disks.
        assertFailsWith<ApiException.Server> { r.backups.restore("pve", "pve2", backup, 105, replace = false, storage = "local") }
        r.finish("pve2", r.backups.restore("pve", "pve2", backup, 105, replace = false, storage = "nas-nfs"))
        assertEquals("nas-nfs", pve.state.guests.single { it.vmid == 105 }.diskStorage)
        Unit
    }

    @Test
    fun `replacing a running guest is refused until it is stopped`() = runBlocking {
        val r = repos()
        val backup = r.backups.backups(web).backups.first()
        val error = assertFailsWith<ApiException.Server> { r.backups.restore("pve", "pve1", backup, 100, replace = true) }
        assertTrue("running" in error.message.orEmpty())

        r.finish("pve1", r.guests.power(web, PowerAction.STOP))
        r.finish("pve1", r.backups.restore("pve", "pve1", backup, 100, replace = true))
        // The restored VM has no snapshots any more.
        assertEquals(listOf("current"), r.guests.snapshots(web).map { it.name })
    }

    @Test
    fun `an existing id is not overwritten without replace`() = runBlocking {
        val r = repos()
        val backup = r.backups.backups(win11).backups.single()
        val error = assertFailsWith<ApiException.Server> { r.backups.restore("pve", "pve2", backup, 102, replace = false) }
        assertTrue("already exists" in error.message.orEmpty())
        Unit
    }

    @Test
    fun `read-only users can browse but not back up or restore`() = runBlocking {
        val r = repos(user = "auditor", realm = "pve")
        val backups = r.backups.backups(web).backups
        assertTrue(backups.isNotEmpty())
        val pbs = r.backups.backupStorages("pve", "pve1").first()
        assertFailsWith<ApiException.Forbidden> { r.backups.backupNow(web, pbs, BackupMode.SNAPSHOT, "") }
        assertFailsWith<ApiException.Forbidden> { r.backups.restore("pve", "pve1", backups.first(), 999, replace = false) }
        Unit
    }

    private suspend fun GuestRepository.observeStatusOnce(ref: GuestRef) =
        observeStatus(ref).first { it.value != null }.value!!
}
