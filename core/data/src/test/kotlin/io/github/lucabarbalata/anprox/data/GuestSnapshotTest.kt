package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.GuestSnapshot
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.SnapshotTree
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Snapshots end to end: repository → HTTP client → mock server (questionnaire Q18). */
class GuestSnapshotTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val web = GuestRef("pve", "pve1", GuestType.QEMU, 100)       // running, 3 snapshots
    private val win11 = GuestRef("pve", "pve2", GuestType.QEMU, 102)     // stopped, 1 snapshot
    private val pihole = GuestRef("pve", "pve1", GuestType.LXC, 200)     // running container

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private suspend fun repo(user: String = "root", realm: String = "pam"): GuestRepository {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = user, realm = realm, allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return GuestRepository(hub)
    }

    private suspend fun GuestRepository.finish(ref: GuestRef, upid: String) =
        assertTrue(awaitTask(ref.serverId, ref.node, upid).succeeded)

    @Test
    fun `lists snapshots as a tree with the current state`() = runBlocking {
        val rows = SnapshotTree.flatten(repo().snapshots(web)).map { it.snapshot.name to it.depth }
        assertEquals(
            listOf("before-upgrade" to 0, "after-upgrade" to 1, "current" to 2, "kernel-test" to 1),
            rows,
        )
    }

    @Test
    fun `taking a snapshot with RAM moves the current state under it`() = runBlocking {
        val guests = repo()
        guests.finish(web, guests.createSnapshot(web, "pre-deploy", "Before deploying v2", includeRam = true))

        val list = guests.snapshots(web)
        val snap = list.single { it.name == "pre-deploy" }
        assertEquals("after-upgrade", snap.parent)
        assertEquals("Before deploying v2", snap.description)
        assertTrue(snap.vmstate)
        assertEquals("pre-deploy", list.single { it.isCurrent }.parent)
        Unit
    }

    @Test
    fun `a snapshot's description can be changed and cleared`() = runBlocking {
        val repo = repo()
        repo.setSnapshotDescription(pihole, "pre-update", "  Before the Pi-hole v6 update  ")
        assertEquals("Before the Pi-hole v6 update", repo.snapshots(pihole).single { it.name == "pre-update" }.description)
        repo.setSnapshotDescription(pihole, "pre-update", "")
        assertTrue(repo.snapshots(pihole).single { it.name == "pre-update" }.description.isNullOrEmpty())
        assertFailsWith<ApiException> { repo.setSnapshotDescription(pihole, "no-such-snapshot", "x") }
        Unit
    }

    @Test
    fun `containers never save RAM`() = runBlocking {
        val guests = repo()
        guests.finish(pihole, guests.createSnapshot(pihole, "ct-snap", "", includeRam = true))
        assertFalse(guests.snapshots(pihole).single { it.name == "ct-snap" }.vmstate)
    }

    @Test
    fun `invalid and duplicate names are rejected by the server`() = runBlocking {
        val guests = repo()
        assertFailsWith<ApiException.Validation> { guests.createSnapshot(web, "1-bad name", "", includeRam = false) }
        val dup = assertFailsWith<ApiException.Server> { guests.createSnapshot(web, "kernel-test", "", includeRam = false) }
        assertTrue("already used" in dup.message.orEmpty())
        Unit
    }

    @Test
    fun `rollback restores the state saved in the snapshot`() = runBlocking {
        val guests = repo()
        // With RAM: the VM keeps running.
        guests.finish(web, guests.rollbackSnapshot(web, "before-upgrade"))
        val list = guests.snapshots(web)
        assertEquals("before-upgrade", list.single(GuestSnapshot::isCurrent).parent)
        assertTrue(list.single(GuestSnapshot::isCurrent).running)

        // Without RAM: the VM is stopped afterwards.
        guests.finish(web, guests.rollbackSnapshot(web, "kernel-test"))
        assertFalse(guests.snapshots(web).single(GuestSnapshot::isCurrent).running)
    }

    @Test
    fun `deleting a snapshot re-parents its children`() = runBlocking {
        val guests = repo()
        guests.finish(web, guests.deleteSnapshot(web, "before-upgrade"))
        val list = guests.snapshots(web)
        assertNull(list.firstOrNull { it.name == "before-upgrade" })
        assertNull(list.single { it.name == "after-upgrade" }.parent)

        guests.finish(win11, guests.deleteSnapshot(win11, "fresh-install"))
        assertNull(guests.snapshots(win11).single(GuestSnapshot::isCurrent).parent)
        assertFailsWith<ApiException.Server> { guests.deleteSnapshot(win11, "fresh-install") }
        Unit
    }

    @Test
    fun `read-only users get a permission error`() = runBlocking {
        val guests = repo(user = "auditor", realm = "pve")
        assertEquals(4, guests.snapshots(web).size)
        assertFailsWith<ApiException.Forbidden> { guests.createSnapshot(web, "nope", "", includeRam = false) }
        assertFailsWith<ApiException.Forbidden> { guests.rollbackSnapshot(web, "before-upgrade") }
        assertFailsWith<ApiException.Forbidden> { guests.deleteSnapshot(web, "before-upgrade") }
        Unit
    }
}
