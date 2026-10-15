package io.github.lucabarbalata.anprox.model.pve

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Storage types the app can add and edit in full; others get the common options only. */
enum class StorageKind(val apiName: String, val contents: List<String>, val defaultContent: Set<String>) {
    DIR("dir", FILE_CONTENTS, setOf("iso", "vztmpl", "backup")),
    NFS("nfs", FILE_CONTENTS, setOf("images", "iso", "vztmpl", "backup")),
    CIFS("cifs", FILE_CONTENTS, setOf("images", "iso", "vztmpl", "backup")),
    PBS("pbs", listOf("backup"), setOf("backup")),
    LVM("lvm", DISK_CONTENTS, setOf("images", "rootdir")),
    LVMTHIN("lvmthin", DISK_CONTENTS, setOf("images", "rootdir")),
    ZFSPOOL("zfspool", DISK_CONTENTS, setOf("images", "rootdir")),
    ;

    /** Only directories and LVM can be marked as shared; network storages always are. */
    val canBeShared: Boolean get() = this == DIR || this == LVM

    companion object {
        fun fromApi(type: String?): StorageKind? = entries.firstOrNull { it.apiName == type }
    }
}

private val FILE_CONTENTS = listOf("images", "rootdir", "vztmpl", "iso", "backup", "snippets", "import")
private val DISK_CONTENTS = listOf("images", "rootdir")

/**
 * Adding or editing a storage (`POST /storage`, `PUT /storage/{storage}`). Fields that Proxmox
 * fixes once a storage exists (path, server, export, share, datastore, volume group, thin pool,
 * ZFS pool) are only sent when creating. A blank [password] on edit keeps the stored one.
 */
data class StorageSetupForm(
    val kind: StorageKind = StorageKind.DIR,
    val id: String = "",
    val content: Set<String> = kind.defaultContent,
    /** Empty: every node. */
    val nodes: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val shared: Boolean = false,
    val path: String = "",
    val server: String = "",
    val export: String = "",
    val share: String = "",
    val subdir: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val datastore: String = "",
    val namespace: String = "",
    val fingerprint: String = "",
    val vgname: String = "",
    val thinpool: String = "",
    val pool: String = "",
    val sparse: Boolean = true,
    /** Null: keep every backup (Proxmox's default for a storage). */
    val retention: Retention? = null,
) {
    enum class Problem { ID, CONTENT, PATH, SERVER, EXPORT, SHARE, CREDENTIALS, DATASTORE, FINGERPRINT, VOLUME_GROUP, THIN_POOL, POOL, RETENTION }

    /** Switches the type, taking that type's default content. */
    fun withKind(k: StorageKind): StorageSetupForm = copy(kind = k, content = k.defaultContent, shared = shared && k.canBeShared)

    fun problem(creating: Boolean): Problem? = when {
        creating && !STORAGE_ID.matches(id.trim()) -> Problem.ID
        content.isEmpty() || content.any { it !in kind.contents } -> Problem.CONTENT
        !creating -> commonProblem()
        kind == StorageKind.DIR && !path.trim().startsWith("/") -> Problem.PATH
        kind in setOf(StorageKind.NFS, StorageKind.CIFS, StorageKind.PBS) && server.isBlank() -> Problem.SERVER
        kind == StorageKind.NFS && !export.trim().startsWith("/") -> Problem.EXPORT
        kind == StorageKind.CIFS && share.isBlank() -> Problem.SHARE
        kind == StorageKind.PBS && (username.isBlank() || password.isEmpty()) -> Problem.CREDENTIALS
        kind == StorageKind.PBS && datastore.isBlank() -> Problem.DATASTORE
        (kind == StorageKind.LVM || kind == StorageKind.LVMTHIN) && vgname.isBlank() -> Problem.VOLUME_GROUP
        kind == StorageKind.LVMTHIN && thinpool.isBlank() -> Problem.THIN_POOL
        kind == StorageKind.ZFSPOOL && pool.isBlank() -> Problem.POOL
        else -> commonProblem()
    }

    private fun commonProblem(): Problem? = when {
        kind == StorageKind.PBS && username.isBlank() -> Problem.CREDENTIALS
        fingerprint.isNotBlank() && !FINGERPRINT.matches(fingerprint.trim()) -> Problem.FINGERPRINT
        retention != null && retention.isEmpty -> Problem.RETENTION
        else -> null
    }

    fun createParams(): Map<String, String> = buildMap {
        put("storage", id.trim())
        put("type", kind.apiName)
        when (kind) {
            StorageKind.DIR -> put("path", path.trim())
            StorageKind.NFS -> {
                put("server", server.trim())
                put("export", export.trim())
            }
            StorageKind.CIFS -> {
                put("server", server.trim())
                put("share", share.trim())
                subdir.trim().takeIf { it.isNotEmpty() }?.let { put("subdir", it) }
            }
            StorageKind.PBS -> {
                put("server", server.trim())
                put("datastore", datastore.trim())
            }
            StorageKind.LVM -> put("vgname", vgname.trim())
            StorageKind.LVMTHIN -> {
                put("vgname", vgname.trim())
                put("thinpool", thinpool.trim())
            }
            StorageKind.ZFSPOOL -> put("pool", pool.trim())
        }
        putAll(editable())
        if (!enabled) put("disable", "1")
        if (nodes.isNotEmpty()) put("nodes", nodes.sorted().joinToString(","))
        if (kind.canBeShared && shared) put("shared", "1")
        if (kind == StorageKind.ZFSPOOL && sparse) put("sparse", "1")
        retentionValue()?.let { put("prune-backups", it) }
    }

    /**
     * Every editable value; what was cleared is deleted. [original] is `GET /storage/{storage}`;
     * its digest refuses the change if someone else edited the storage meanwhile.
     */
    fun updateParams(original: Map<String, String>): Map<String, String> {
        val set = LinkedHashMap(editable())
        val delete = mutableListOf<String>()
        if (enabled) delete += "disable" else set["disable"] = "1"
        if (nodes.isNotEmpty()) set["nodes"] = nodes.sorted().joinToString(",") else delete += "nodes"
        if (kind.canBeShared) set["shared"] = if (shared) "1" else "0"
        if (kind == StorageKind.ZFSPOOL) set["sparse"] = if (sparse) "1" else "0"
        val prune = retentionValue()
        if (prune != null) set["prune-backups"] = prune else delete += "prune-backups"
        if (kind == StorageKind.CIFS && domain.isBlank()) delete += "domain"
        if (kind == StorageKind.CIFS && username.isBlank()) delete += "username"
        if (kind == StorageKind.PBS && namespace.isBlank()) delete += "namespace"
        if (kind == StorageKind.PBS && fingerprint.isBlank()) delete += "fingerprint"
        // Only delete what is set, so Proxmox doesn't complain about unknown keys.
        val present = delete.filter { it in original }
        if (present.isNotEmpty()) set["delete"] = present.joinToString(",")
        original["digest"]?.let { set["digest"] = it }
        return set
    }

    /** Values that can change after creation. */
    private fun editable(): Map<String, String> = buildMap {
        put("content", kind.contents.filter { it in content }.joinToString(","))
        when (kind) {
            StorageKind.CIFS -> {
                username.trim().takeIf { it.isNotEmpty() }?.let { put("username", it) }
                password.takeIf { it.isNotEmpty() }?.let { put("password", it) }
                domain.trim().takeIf { it.isNotEmpty() }?.let { put("domain", it) }
            }
            StorageKind.PBS -> {
                put("username", username.trim())
                password.takeIf { it.isNotEmpty() }?.let { put("password", it) }
                namespace.trim().takeIf { it.isNotEmpty() }?.let { put("namespace", it) }
                fingerprint.trim().takeIf { it.isNotEmpty() }?.let { put("fingerprint", it.lowercase()) }
            }
            else -> Unit
        }
    }

    /** Only for storages that hold backups: retention applies to backups. */
    private fun retentionValue(): String? = retention?.takeIf { "backup" in content && !it.isEmpty }?.value()

    companion object {
        /** Proxmox's storage ID rule. */
        val STORAGE_ID = Regex("^[A-Za-z][A-Za-z0-9\\-_.]*[A-Za-z0-9]$")
        private val FINGERPRINT = Regex("^([A-Fa-f0-9]{2}:){31}[A-Fa-f0-9]{2}$")

        /** The form for an existing storage, from `GET /storage/{storage}`; null for types the app can't edit in full. */
        fun from(o: JsonObject): StorageSetupForm? {
            val kind = StorageKind.fromApi(o.string("type")) ?: return null
            return StorageSetupForm(
                kind = kind,
                id = o.string("storage").orEmpty(),
                content = o.string("content").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                nodes = o.string("nodes").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                enabled = !o.string("disable").isOn(),
                shared = o.string("shared").isOn(),
                path = o.string("path").orEmpty(),
                server = o.string("server").orEmpty(),
                export = o.string("export").orEmpty(),
                share = o.string("share").orEmpty(),
                subdir = o.string("subdir").orEmpty(),
                domain = o.string("domain").orEmpty(),
                username = o.string("username").orEmpty(),
                datastore = o.string("datastore").orEmpty(),
                namespace = o.string("namespace").orEmpty(),
                fingerprint = o.string("fingerprint").orEmpty(),
                vgname = o.string("vgname").orEmpty(),
                thinpool = o.string("thinpool").orEmpty(),
                pool = o.string("pool").orEmpty(),
                sparse = o.string("sparse").isOn(),
                retention = Retention.parse(o["prune-backups"])?.takeIf { !it.keepAll },
            )
        }

        /** `GET /storage/{storage}` as flat strings, for [updateParams]. */
        fun flatten(o: JsonObject): Map<String, String> =
            o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } ?: (k to v.toString()) }.toMap()

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/** One row of `GET /nodes/{node}/scan/nfs`. */
@Serializable
data class NfsExport(val path: String, val options: String? = null)

/** One row of `GET /nodes/{node}/scan/cifs`. */
@Serializable
data class CifsShare(val share: String, val description: String? = null)

/** One row of `GET /nodes/{node}/scan/pbs`. */
@Serializable
data class PbsDatastore(val store: String, val comment: String? = null)

/** One row of `GET /nodes/{node}/scan/lvm`. */
@Serializable
data class LvmVolumeGroup(val vg: String, val size: Long? = null, val free: Long? = null)

/** One row of `GET /nodes/{node}/scan/lvmthin`. */
@Serializable
data class LvmThinPool(val lv: String, @kotlinx.serialization.SerialName("lv_size") val size: Long? = null)

/** One row of `GET /nodes/{node}/scan/zfs`. */
@Serializable
data class ZfsPoolName(val pool: String)
