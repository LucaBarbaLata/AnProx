package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.DownloadUrlForm
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.fileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StorageContentTest {

    @Test
    fun `content types and file names`() {
        assertEquals(listOf(ContentType.ISO, ContentType.TEMPLATE, ContentType.BACKUP), ContentType.parse("backup,vztmpl, iso,unknown"))
        assertEquals("debian.iso", StorageContentItem("local:iso/debian.iso").fileName)
        assertEquals("vm-100-disk-0", StorageContentItem("local-zfs:vm-100-disk-0").fileName)
        assertEquals("a.yaml", StorageContentItem("local:snippets/a.yaml").fileName)
    }

    @Test
    fun `download form`() {
        val url = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-12.7.0-amd64-netinst.iso?x=1"
        val name = DownloadUrlForm.suggestedName(url)
        assertEquals("debian-12.7.0-amd64-netinst.iso", name)
        val form = DownloadUrlForm(url = url, filename = name)
        assertNull(form.problem())
        assertEquals(
            mapOf("url" to url, "content" to "iso", "filename" to name, "verify-certificates" to "1"),
            form.params(),
        )
        val sha = "a".repeat(64)
        assertEquals(sha, form.copy(checksum = "A".repeat(64)).params()["checksum"])
        assertEquals("sha256", form.copy(checksum = sha).params()["checksum-algorithm"])

        assertEquals(DownloadUrlForm.Problem.URL, form.copy(url = "cdimage.debian.org/x.iso").problem())
        assertEquals(DownloadUrlForm.Problem.FILENAME, form.copy(filename = "../evil.iso").problem())
        assertEquals(DownloadUrlForm.Problem.EXTENSION, form.copy(filename = "debian.tar.zst").problem())
        assertNull(form.copy(content = ContentType.TEMPLATE, filename = "debian.tar.zst").problem())
        assertEquals(DownloadUrlForm.Problem.CHECKSUM, form.copy(checksum = "xyz").problem())
    }
}
