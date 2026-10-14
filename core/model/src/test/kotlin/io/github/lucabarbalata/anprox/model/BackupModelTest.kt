package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupModelTest {

    @Test
    fun `parses backup storage and content listings`() {
        val storages = ProxmoxJson.decodeFromString<ApiEnvelope<List<NodeStorage>>>(
            """{"data":[{"storage":"pbs","type":"pbs","content":"backup","active":1,"enabled":1,"shared":1,"total":100,"used":40,"avail":60},
                        {"storage":"local","type":"dir","content":"iso,vztmpl,backup","active":1,"enabled":1,"shared":0}]}""",
        ).data!!
        assertTrue(storages[0].isPbs && storages[0].shared)
        assertFalse(storages[1].isPbs)

        val items = ProxmoxJson.decodeFromString<ApiEnvelope<List<StorageContentItem>>>(
            """{"data":[
                {"volid":"pbs:backup/vm/100/2026-10-13T02:00:00Z","content":"backup","ctime":1791857000,"size":"21474836480",
                 "format":"pbs-vm","notes":"web","protected":1,"vmid":100,"subtype":"qemu","verification":{"state":"ok","upid":"UPID:x"}},
                {"volid":"local:backup/vzdump-lxc-200-2026_10_10-02_00_00.tar.zst","content":"backup","ctime":1791600000,"size":512,"format":"tar.zst","vmid":200}
            ]}""",
        ).data!!
        val pbs = items[0]
        assertEquals("pbs", pbs.storage)
        assertTrue(pbs.isProtected)
        assertTrue(pbs.verification!!.ok)
        assertEquals(GuestType.QEMU, pbs.guestType)
        assertEquals(21474836480, pbs.size)

        val local = items[1]
        assertFalse(local.isProtected)
        assertNull(local.verification)
        assertEquals(GuestType.LXC, local.guestType) // no subtype: guessed from the file name
    }

    @Test
    fun `guest type is guessed from every volume naming scheme`() {
        fun type(volid: String) = StorageContentItem(volid).guestType
        assertEquals(GuestType.QEMU, type("nas:backup/vzdump-qemu-101-2026_01_01-00_00_00.vma.zst"))
        assertEquals(GuestType.LXC, type("pbs:backup/ct/201/2026-01-01T00:00:00Z"))
        assertEquals(GuestType.LXC, type("old:backup/vzdump-openvz-300-2019_01_01-00_00_00.tar"))
        assertNull(type("local:iso/debian.iso"))
    }
}
