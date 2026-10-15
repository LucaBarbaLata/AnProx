package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.Retention
import io.github.lucabarbalata.anprox.model.pve.StorageKind
import io.github.lucabarbalata.anprox.model.pve.StorageSetupForm
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorageSetupTest {

    private fun config(json: String) = ProxmoxJson.decodeFromString<ApiEnvelope<JsonObject>>(json).data!!

    @Test
    fun `new storages need what their type needs`() {
        val nfs = StorageSetupForm().withKind(StorageKind.NFS).copy(id = "nas", server = "10.0.0.50", export = "/export/proxmox")
        assertNull(nfs.problem(creating = true))
        assertEquals(StorageSetupForm.Problem.ID, nfs.copy(id = "1nas").problem(true))
        assertEquals(StorageSetupForm.Problem.ID, nfs.copy(id = "nas-").problem(true))
        assertEquals(StorageSetupForm.Problem.EXPORT, nfs.copy(export = "export").problem(true))
        assertEquals(StorageSetupForm.Problem.SERVER, nfs.copy(server = " ").problem(true))
        assertEquals(StorageSetupForm.Problem.CONTENT, nfs.copy(content = emptySet()).problem(true))

        val pbs = StorageSetupForm().withKind(StorageKind.PBS).copy(id = "pbs2", server = "10.0.0.60", datastore = "store1", username = "root@pam")
        assertEquals(StorageSetupForm.Problem.CREDENTIALS, pbs.problem(true))
        assertNull(pbs.copy(password = "x").problem(true))
        assertEquals(StorageSetupForm.Problem.FINGERPRINT, pbs.copy(password = "x", fingerprint = "ab:cd").problem(true))
        // Editing keeps the stored password when none is typed.
        assertNull(pbs.problem(creating = false))

        val thin = StorageSetupForm().withKind(StorageKind.LVMTHIN).copy(id = "thin", vgname = "pve")
        assertEquals(StorageSetupForm.Problem.THIN_POOL, thin.problem(true))
        assertEquals(StorageSetupForm.Problem.CONTENT, thin.copy(thinpool = "data", content = setOf("iso")).problem(true))
    }

    @Test
    fun `create parameters by type`() {
        val dir = StorageSetupForm(id = "usb", path = "/mnt/usb", nodes = setOf("pve2"), shared = false, enabled = false, retention = Retention(daily = 7))
        assertEquals(
            mapOf(
                "storage" to "usb", "type" to "dir", "path" to "/mnt/usb", "content" to "vztmpl,iso,backup",
                "disable" to "1", "nodes" to "pve2", "prune-backups" to "keep-daily=7",
            ),
            dir.createParams(),
        )
        val zfs = StorageSetupForm().withKind(StorageKind.ZFSPOOL).copy(id = "tank", pool = "tank")
        assertEquals(mapOf("storage" to "tank", "type" to "zfspool", "pool" to "tank", "content" to "images,rootdir", "sparse" to "1"), zfs.createParams())
        val cifs = StorageSetupForm().withKind(StorageKind.CIFS).copy(id = "smb", server = "nas", share = "media", username = "me", password = "pw", content = setOf("iso"))
        assertEquals(
            mapOf("storage" to "smb", "type" to "cifs", "server" to "nas", "share" to "media", "content" to "iso", "username" to "me", "password" to "pw"),
            cifs.createParams(),
        )
    }

    @Test
    fun `editing reads the stored values and sends only what can change`() {
        val o = config(
            """{"data":{"storage":"pbs","type":"pbs","server":"10.0.0.60","datastore":"store1","username":"root@pam","content":"backup",
                "fingerprint":"a1:b2","prune-backups":{"keep-daily":"7","keep-weekly":"4"},"nodes":"pve1,pve2","digest":"d9"}}""",
        )
        val form = StorageSetupForm.from(o)!!
        assertEquals(StorageKind.PBS, form.kind)
        assertEquals(setOf("pve1", "pve2"), form.nodes)
        assertEquals(Retention(daily = 7, weekly = 4), form.retention)
        assertEquals("", form.password)
        val raw = StorageSetupForm.flatten(o)
        assertEquals(
            mapOf(
                "content" to "backup", "username" to "root@pam", "fingerprint" to "a1:b2", "prune-backups" to "keep-daily=7,keep-weekly=4",
                "delete" to "nodes", "digest" to "d9",
            ),
            form.copy(nodes = emptySet()).updateParams(raw),
        )
        // Keep-all is the storage default: it reads as no retention, and clearing one deletes it.
        val dir = config("""{"data":{"storage":"local","type":"dir","path":"/var/lib/vz","content":"iso,vztmpl,backup","prune-backups":"keep-all=1","shared":0,"digest":"d1"}}""")
        val local = StorageSetupForm.from(dir)!!
        assertNull(local.retention)
        assertEquals(
            mapOf("content" to "iso,backup", "shared" to "0", "delete" to "prune-backups", "digest" to "d1"),
            local.copy(content = setOf("iso", "backup")).updateParams(StorageSetupForm.flatten(dir)),
        )
        assertNull(StorageSetupForm.from(config("""{"data":{"storage":"ceph","type":"rbd","content":"images"}}""")))
    }
}
