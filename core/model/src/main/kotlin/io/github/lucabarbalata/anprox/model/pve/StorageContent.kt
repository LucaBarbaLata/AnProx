package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a storage holds, by Proxmox's content type names. */
enum class ContentType(val apiName: String) {
    ISO("iso"),
    TEMPLATE("vztmpl"),
    IMAGES("images"),
    ROOTDIR("rootdir"),
    BACKUP("backup"),
    SNIPPETS("snippets"),
    IMPORT("import"),
    ;

    /** Files the app may delete from the storage browser; disks are removed from a guest's hardware. */
    val deletable: Boolean get() = this == ISO || this == TEMPLATE || this == SNIPPETS || this == IMPORT

    companion object {
        fun fromApi(name: String?): ContentType? = entries.firstOrNull { it.apiName == name }

        /** The types of a storage's `content` setting, in this enum's order. */
        fun parse(content: String): List<ContentType> {
            val names = content.split(',').map { it.trim() }.toSet()
            return entries.filter { it.apiName in names }
        }
    }
}

/** The file name part of a volume ID: `local:iso/debian.iso` → `debian.iso`. */
val StorageContentItem.fileName: String
    get() = volid.substringAfter(':').substringAfterLast('/')

/** `GET /nodes/{node}/query-url-metadata`: what a download URL points to. */
@Serializable
data class UrlMetadata(
    val filename: String? = null,
    val size: Long? = null,
    val mimetype: String? = null,
)

/**
 * "Download from URL" (`POST /nodes/{node}/storage/{storage}/download-url`): the node fetches the
 * file itself, so a large ISO never passes through the phone.
 */
data class DownloadUrlForm(
    val url: String = "",
    val content: ContentType = ContentType.ISO,
    val filename: String = "",
    /** Hex digest to verify, or empty. */
    val checksum: String = "",
    val algorithm: String = ALGORITHMS.first(),
    val verifyCertificates: Boolean = true,
) {
    enum class Problem { URL, FILENAME, EXTENSION, CHECKSUM }

    fun problem(): Problem? {
        val u = url.trim()
        val name = filename.trim()
        return when {
            !(u.startsWith("https://") || u.startsWith("http://") || u.startsWith("ftp://")) || u.length < 12 || ' ' in u -> Problem.URL
            name.isEmpty() || !NAME.matches(name) -> Problem.FILENAME
            !allowedExtension(name) -> Problem.EXTENSION
            checksum.isNotBlank() && !HEX.matches(checksum.trim()) -> Problem.CHECKSUM
            else -> null
        }
    }

    private fun allowedExtension(name: String): Boolean = when (content) {
        ContentType.ISO -> name.endsWith(".iso", ignoreCase = true) || name.endsWith(".img", ignoreCase = true)
        ContentType.TEMPLATE -> TEMPLATE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
        ContentType.IMPORT -> IMPORT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
        else -> false
    }

    fun params(): Map<String, String> = buildMap {
        put("url", url.trim())
        put("content", content.apiName)
        put("filename", filename.trim())
        if (checksum.isNotBlank()) {
            put("checksum", checksum.trim().lowercase())
            put("checksum-algorithm", algorithm)
        }
        put("verify-certificates", if (verifyCertificates) "1" else "0")
    }

    companion object {
        val ALGORITHMS = listOf("sha256", "sha512", "sha1", "md5", "sha224", "sha384")
        val TEMPLATE_EXTENSIONS = listOf(".tar.gz", ".tar.xz", ".tar.zst", ".tgz")
        val IMPORT_EXTENSIONS = listOf(".ova", ".qcow2", ".raw", ".vmdk", ".img")
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
        private val HEX = Regex("[0-9a-fA-F]{32,128}")

        /** The last path segment of [url], without a query, as a suggested file name. */
        fun suggestedName(url: String): String =
            url.trim().substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
    }
}

/**
 * One container template from Proxmox's appliance index (`GET /nodes/{node}/aplinfo`), which
 * the node can download to a storage. [template] is the file name it is stored under.
 */
@Serializable
data class ApplianceTemplate(
    val template: String,
    @SerialName("package") val packageName: String = "",
    val os: String? = null,
    val version: String? = null,
    /** `system` (plain distributions), `turnkeylinux` or `mail`. */
    val section: String? = null,
    val headline: String? = null,
    val description: String? = null,
    val type: String? = null,
    val architecture: String? = null,
    val infopage: String? = null,
) {
    fun matches(filter: String): Boolean {
        val f = filter.trim()
        return f.isEmpty() || listOf(template, packageName, os.orEmpty(), headline.orEmpty()).any { it.contains(f, ignoreCase = true) }
    }

    companion object {
        /** Plain distributions first, then the TurnKey appliances, each by name. */
        fun sorted(list: List<ApplianceTemplate>): List<ApplianceTemplate> =
            list.filter { it.type == null || it.type == "lxc" }
                .sortedWith(compareBy<ApplianceTemplate> { if (it.section == "system") 0 else 1 }.thenBy { it.packageName }.thenByDescending { it.version })
    }
}
