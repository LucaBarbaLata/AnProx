package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pbs.BackupSnapshot
import io.github.lucabarbalata.anprox.model.pbs.CatalogEntry
import io.github.lucabarbalata.anprox.model.pbs.DatastoreMaintenance
import io.github.lucabarbalata.anprox.model.pbs.DatastoreStatus
import io.github.lucabarbalata.anprox.model.pbs.GcStatus
import io.github.lucabarbalata.anprox.model.pbs.KeepOptions
import io.github.lucabarbalata.anprox.model.pbs.PbsNamespace
import io.github.lucabarbalata.anprox.model.pbs.PbsRemoteForm
import io.github.lucabarbalata.anprox.model.pve.NodeSubscription
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PbsAdminModelTest {
    private fun json(text: String): JsonObject = ProxmoxJson.parseToJsonElement(text).jsonObject

    @Test
    fun `snapshots, their files and what can be browsed`() {
        val ct = BackupSnapshot.fromJson(
            json(
                """{"backup-type":"ct","backup-id":"200","backup-time":1760000000,"size":943718400,"comment":"pihole","protected":true,
                   "verification":{"state":"ok","upid":"UPID:x"},"owner":"root@pam",
                   "files":[{"filename":"index.json.blob","crypt-mode":"none","size":600},{"filename":"root.pxar.didx","crypt-mode":"none"},
                            {"filename":"catalog.pcat1.didx","crypt-mode":"none"}]}""",
            ),
        )
        assertEquals("ok", ct.verifyState)
        assertTrue(ct.protected)
        assertTrue(ct.hasCatalog)
        assertFalse(ct.encrypted)
        assertEquals(mapOf("backup-type" to "ct", "backup-id" to "200", "backup-time" to "1760000000"), ct.params())
        val encrypted = BackupSnapshot.fromJson(json("""{"backup-type":"ct","backup-id":"201","backup-time":1,"files":[{"filename":"catalog.pcat1.didx","crypt-mode":"encrypt"}]}"""))
        assertTrue(encrypted.encrypted)
        assertFalse(encrypted.hasCatalog) // PBS can't read an encrypted catalog
        val vm = BackupSnapshot.fromJson(json("""{"backup-type":"vm","backup-id":"100","backup-time":2,"files":[{"filename":"drive-scsi0.img.fidx"}]}"""))
        assertFalse(vm.hasCatalog)
        assertNull(vm.verifyState)
    }

    @Test
    fun `catalog paths are base64, the top is root`() {
        assertEquals("L3Jvb3QucHhhci5kaWR4L2V0Yw==", CatalogEntry.encodePath("/root.pxar.didx/etc"))
        assertEquals("/root.pxar.didx/etc", CatalogEntry.decodePath("L3Jvb3QucHhhci5kaWR4L2V0Yw=="))
        assertEquals("/", CatalogEntry.decodePath(CatalogEntry.ROOT))
        val dir = CatalogEntry.fromJson(json("""{"filepath":"L2V0Yw==","text":"etc","type":"d","leaf":false}"""))
        assertTrue(dir.browsable)
        assertTrue(dir.downloadable)
        val link = CatalogEntry.fromJson(json("""{"filepath":"L2Jpbg==","text":"bin","type":"l","leaf":true}"""))
        assertFalse(link.browsable)
        assertFalse(link.downloadable)
    }

    @Test
    fun `status, gc, keep options, namespaces, maintenance`() {
        val st = DatastoreStatus.fromJson(
            json("""{"total":1000,"used":250,"avail":750,"counts":{"vm":{"groups":2,"snapshots":5},"ct":{"groups":1,"snapshots":2}},"gc-status":{"removed-bytes":10,"disk-bytes":100,"index-data-bytes":300}}"""),
        )
        assertEquals(0.25, st.fraction)
        assertEquals(2 to 5, st.counts["vm"])
        assertEquals(3.0, st.gc?.dedupFactor)
        assertEquals("OK", GcStatus.fromJson(json("""{"last-run-state":"OK","last-run-endtime":5,"last-run-upid":"UPID:y"}""")).lastRunState)
        assertFalse(KeepOptions().valid)
        assertFalse(KeepOptions(last = "0").valid)
        assertTrue(KeepOptions(daily = "7", weekly = "4").valid)
        assertEquals(mapOf("keep-daily" to "7", "keep-weekly" to "4"), KeepOptions(daily = "7", weekly = "4").params())
        val ns = PbsNamespace("prod/db")
        assertEquals(2, ns.depth)
        assertEquals("db", ns.name)
        assertEquals("prod", ns.parent)
        assertEquals(0, PbsNamespace("").depth)
        assertFalse(PbsNamespace.NAME.matches("has space"))
        assertEquals(DatastoreMaintenance("read-only", "Moving disks"), DatastoreMaintenance.parse("type=read-only,message=Moving disks"))
        assertEquals(DatastoreMaintenance(), DatastoreMaintenance.parse(null))
        assertEquals("type=offline,message=a b", DatastoreMaintenance.value("offline", "a, b".replace(",", "")))
    }

    @Test
    fun `remote form and subscription keys of both products`() {
        val form = PbsRemoteForm(name = "offsite", host = "pbs2.example.net", authId = "sync@pbs!job", password = "secret", fingerprint = "AB:".repeat(31) + "CD")
        assertNull(form.problem())
        assertEquals("ab:".repeat(31) + "cd", form.params()["fingerprint"])
        assertEquals(PbsRemoteForm.Problem.AUTH_ID, form.copy(authId = "sync").problem())
        assertEquals(PbsRemoteForm.Problem.FINGERPRINT, form.copy(fingerprint = "abc").problem())
        assertEquals(PbsRemoteForm.Problem.NAME, form.copy(name = "x").problem())
        assertTrue(NodeSubscription.isValidKey("pve4c-0123456789"))
        assertTrue(NodeSubscription.isValidKey("pbsc-0123456789"))
        assertFalse(NodeSubscription.isValidKey("pbs4c-0123456789"))
    }
}
