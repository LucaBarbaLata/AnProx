package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.data.store.InMemoryProfileStore
import io.github.lucabarbalata.anprox.data.store.InMemorySecretStore
import io.github.lucabarbalata.anprox.mock.MockProxmoxServer
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.profile.Endpoint
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.DownloadUrlForm
import io.github.lucabarbalata.anprox.model.pve.fileName
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.http.UploadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Datacenter → Storage against the mock (milestone M4). */
class StorageTest {
    private val pve = MockProxmoxServer(ProxmoxProduct.PVE).start()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        pve.stop()
    }

    private class Repos(val storage: StorageRepository, val guests: GuestRepository)

    private suspend fun repos(): Repos {
        val profile = ServerProfile(
            id = "pve", name = "Homelab", product = ProxmoxProduct.PVE,
            endpoints = listOf(Endpoint(pve.baseUrl)), username = "root", realm = "pam", allowInsecureTls = true,
        )
        val hub = ServerHub(InMemoryProfileStore(listOf(profile)), InMemorySecretStore(), scope)
        hub.awaitLoaded()
        hub.login("pve", "anprox", remember = false)
        return Repos(StorageRepository(hub), GuestRepository(hub))
    }

    private suspend fun Repos.done(upid: String?) {
        assertNotNull(upid)
        assertTrue(guests.awaitTask("pve", "pve1", upid).succeeded)
    }

    @Test
    fun `lists storages and what they hold`() = runBlocking {
        val r = repos()
        val all = r.storage.storages("pve")
        assertEquals(listOf("local", "local-zfs", "nas-nfs", "pbs"), all.map { it.config.storage })
        val local = all.first()
        assertEquals(listOf("pve1", "pve2", "pve3"), local.nodes.map { it.node })
        assertEquals(listOf(true, true, false), local.nodes.map { it.available })
        assertEquals(listOf(ContentType.ISO, ContentType.TEMPLATE, ContentType.BACKUP), ContentType.parse(local.config.content))

        val isos = r.storage.content("pve", "pve1", "local", ContentType.ISO)
        assertEquals(listOf("debian-12.7.0-amd64-netinst.iso", "ubuntu-24.04.1-live-server-amd64.iso"), isos.map { it.fileName })
        // Guest disks on a node's own storage: the VMs and containers on pve1.
        val disks = r.storage.content("pve", "pve1", "local-zfs")
        assertTrue(disks.any { it.content == "rootdir" && it.vmid == 200 }, disks.toString())
        assertTrue(disks.all { it.vmid != null })
        Unit
    }

    @Test
    fun `container templates come from the appliance index`() = runBlocking {
        val r = repos()
        val catalogue = r.storage.appliances("pve", "pve1")
        // Distributions first, then the rest.
        assertEquals("alpine-3.20-default", catalogue.first().packageName)
        assertEquals(listOf("system", "system", "system"), catalogue.take(3).map { it.section })
        assertTrue(catalogue.first { it.packageName == "turnkey-wordpress" }.matches("WORDPRESS"))

        val wordpress = catalogue.first { it.packageName == "turnkey-wordpress" }
        r.done(r.storage.downloadAppliance("pve", "pve1", "local", wordpress.template))
        val templates = r.storage.content("pve", "pve1", "local", ContentType.TEMPLATE)
        assertTrue(templates.any { it.fileName == wordpress.template }, templates.toString())
        // Twice is refused (the mock has the distributions already); and a storage without templates can't take one.
        assertFailsWith<ApiException.Server> { r.storage.downloadAppliance("pve", "pve1", "local", wordpress.template) }
        assertFailsWith<ApiException.Server> { r.storage.downloadAppliance("pve", "pve1", "local", "debian-12-standard_12.7-1_amd64.tar.zst") }
        assertFailsWith<ApiException.Server> { r.storage.downloadAppliance("pve", "pve1", "local-zfs", wordpress.template) }
        Unit
    }

    @Test
    fun `download from a URL`() = runBlocking {
        val r = repos()
        val url = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-13.1.0-amd64-netinst.iso"
        val meta = r.storage.urlMetadata("pve", "pve1", url, verifyCertificates = true)
        assertEquals("debian-13.1.0-amd64-netinst.iso", meta.filename)
        assertTrue((meta.size ?: 0) > 0)
        assertFailsWith<ApiException> { r.storage.urlMetadata("pve", "pve1", "https://fail.invalid/x.iso", true) }

        r.done(r.storage.download("pve", "pve1", "local", DownloadUrlForm(url = url, filename = meta.filename!!)))
        assertTrue(r.storage.content("pve", "pve1", "local", ContentType.ISO).any { it.fileName == meta.filename })
        // The same name twice is refused, and so is a content type the storage doesn't hold.
        assertFailsWith<ApiException> { r.storage.download("pve", "pve1", "local", DownloadUrlForm(url = url, filename = meta.filename!!)) }
        assertFailsWith<ApiException> { r.storage.download("pve", "pve1", "local-zfs", DownloadUrlForm(url = url, filename = "x.iso")) }
        Unit
    }

    @Test
    fun `upload from the phone with progress`() = runBlocking {
        val r = repos()
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val source = object : UploadSource {
            override val name = "tiny.iso"
            override val size = bytes.size.toLong()
            override fun open() = ByteArrayInputStream(bytes)
        }
        var sent = 0L
        r.done(r.storage.upload("pve", "pve1", "local", ContentType.ISO, source) { sent = it })
        assertEquals(bytes.size.toLong(), sent)
        val item = r.storage.content("pve", "pve1", "local", ContentType.ISO).first { it.fileName == "tiny.iso" }
        assertEquals(bytes.size.toLong(), item.size)
        Unit
    }

    @Test
    fun `delete an ISO, but not a disk in use`() = runBlocking {
        val r = repos()
        val iso = r.storage.content("pve", "pve1", "local", ContentType.ISO).first()
        r.done(r.storage.delete("pve", "pve1", "local", iso.volid))
        assertTrue(r.storage.content("pve", "pve1", "local", ContentType.ISO).none { it.volid == iso.volid })

        val disk = r.storage.content("pve", "pve1", "local-zfs").first()
        val e = assertFailsWith<ApiException> { r.storage.delete("pve", "pve1", "local-zfs", disk.volid) }
        assertTrue("in use" in e.message.orEmpty(), e.message)
        Unit
    }
}
