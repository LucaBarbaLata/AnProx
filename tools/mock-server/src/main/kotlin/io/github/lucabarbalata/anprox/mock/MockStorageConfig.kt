package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.CifsShare
import io.github.lucabarbalata.anprox.model.pve.LvmThinPool
import io.github.lucabarbalata.anprox.model.pve.LvmVolumeGroup
import io.github.lucabarbalata.anprox.model.pve.NfsExport
import io.github.lucabarbalata.anprox.model.pve.PbsDatastore
import io.github.lucabarbalata.anprox.model.pve.StorageConfig
import io.github.lucabarbalata.anprox.model.pve.ZfsPoolName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `/etc/pve/storage.cfg`: each storage's options as Proxmox's flat key/value pairs, plus what the
 * scan endpoints find on the mock's network (an NFS/SMB server at 10.0.0.50 and a Backup Server
 * at 10.0.0.60 that accepts the password `anprox`).
 */
internal class MockStorageConfig {
    private val configs = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private var version = 1

    init {
        configs["local"] = linkedMapOf("type" to "dir", "path" to "/var/lib/vz", "content" to "iso,vztmpl,backup", "prune-backups" to "keep-all=1")
        configs["local-zfs"] = linkedMapOf("type" to "zfspool", "pool" to "rpool/data", "content" to "images,rootdir", "sparse" to "1")
        configs["nas-nfs"] = linkedMapOf("type" to "nfs", "server" to "10.0.0.50", "export" to "/export/proxmox", "path" to "/mnt/pve/nas-nfs", "content" to "images,backup,iso", "options" to "vers=4.2")
        configs["pbs"] = linkedMapOf("type" to "pbs", "server" to "10.0.0.60", "datastore" to "store1", "username" to "root@pam", "content" to "backup", "fingerprint" to FINGERPRINT, "prune-backups" to "keep-daily=7,keep-weekly=4")
    }

    private val digest get() = "stor%04d".format(version)

    fun list(): List<StorageConfig> = configs.map { (id, o) ->
        StorageConfig(id, o["type"].orEmpty(), o["content"].orEmpty(), shared = isShared(o), disable = o["disable"] == "1", nodes = o["nodes"])
    }

    fun get(id: String): JsonObject? = configs[id]?.let { o ->
        JsonObject(
            buildMap {
                put("storage", JsonPrimitive(id))
                put("digest", JsonPrimitive(digest))
                o.forEach { (k, v) -> put(k, if (k in FLAGS) JsonPrimitive(v.toInt()) else JsonPrimitive(v)) }
            },
        )
    }

    /** The per-node entries a storage has (all nodes, or the ones it is limited to). */
    fun entries(id: String, nodeNames: List<String>): List<MockState.Storage> {
        val o = configs[id] ?: return emptyList()
        val limited = o["nodes"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        return nodeNames.filter { limited == null || it in limited }.map { node ->
            MockState.Storage(node, id, o["type"].orEmpty(), o["content"].orEmpty(), isShared(o), total = 500 * GIB, used = 120 * GIB)
        }
    }

    fun create(params: Map<String, String>): String? {
        val id = params["storage"].orEmpty()
        if (!Regex("^[A-Za-z][A-Za-z0-9\\-_.]*[A-Za-z0-9]$").matches(id)) return "storage: invalid format - storage ID '$id' contains illegal characters"
        if (id in configs) return "storage ID '$id' already defined"
        val type = params["type"].orEmpty()
        val required = REQUIRED[type] ?: return "type: value '$type' does not have a value in the enumeration"
        required.firstOrNull { params[it].isNullOrBlank() }?.let { return "$it: property is missing and it is not optional" }
        if (type == "pbs" && params["password"] != PBS_PASSWORD) return "pbs-storage: authentication failure"
        validate(type, params)?.let { return it }
        configs[id] = LinkedHashMap(params.filterKeys { it != "storage" && it != "password" }).apply {
            if (type == "nfs" || type == "cifs") putIfAbsent("path", "/mnt/pve/$id")
        }
        version++
        return null
    }

    fun update(id: String, params: Map<String, String>): String? {
        val o = configs[id] ?: return "storage '$id' does not exist"
        params["digest"]?.let { if (it != digest) return "detected modified configuration - file changed by other user? Try again." }
        FIXED[o["type"]].orEmpty().firstOrNull { it in params }?.let { return "can't change value of fixed parameter '$it'" }
        validate(o["type"].orEmpty(), params)?.let { return it }
        if (o["type"] == "pbs" && params["password"] != null && params["password"] != PBS_PASSWORD) return "pbs-storage: authentication failure"
        params.filterKeys { it != "delete" && it != "digest" && it != "password" }.forEach { (k, v) -> o[k] = v }
        params["delete"].orEmpty().split(',').filter { it.isNotBlank() }.forEach { o.remove(it) }
        if (o["shared"] == "0") o.remove("shared")
        version++
        return null
    }

    fun delete(id: String): String? {
        configs.remove(id) ?: return "storage '$id' does not exist"
        version++
        return null
    }

    fun scanNfs(server: String): Result<List<NfsExport>> =
        if (server == NAS) Result.success(listOf(NfsExport("/export/proxmox", "10.0.0.0/24"), NfsExport("/export/media", "*")))
        else Result.failure(IllegalStateException("command '/sbin/showmount --no-headers --exports $server' failed: exit code 1"))

    fun scanCifs(server: String): Result<List<CifsShare>> =
        if (server == NAS) Result.success(listOf(CifsShare("media", "Films and music"), CifsShare("backups", "Backups")))
        else Result.failure(IllegalStateException("failed to connect to $server: NT_STATUS_HOST_UNREACHABLE"))

    fun scanPbs(server: String, password: String?): Result<List<PbsDatastore>> = when {
        server != BACKUP_SERVER -> Result.failure(IllegalStateException("error fetching datastores - 500 Can't connect to $server:8007 (No route to host)"))
        password != PBS_PASSWORD -> Result.failure(IllegalStateException("error fetching datastores - 401 Unauthorized"))
        else -> Result.success(listOf(PbsDatastore("store1", "Main"), PbsDatastore("store2", "Offsite copy")))
    }

    fun scanLvm() = listOf(LvmVolumeGroup("pve", 476 * GIB, 16 * GIB), LvmVolumeGroup("data", 1800 * GIB, 1800 * GIB))

    fun scanLvmThin(vg: String) = when (vg) {
        "pve" -> listOf(LvmThinPool("data", 348 * GIB))
        "data" -> emptyList()
        else -> null
    }

    fun scanZfs() = listOf(ZfsPoolName("rpool"), ZfsPoolName("tank"))

    private fun validate(type: String, p: Map<String, String>): String? {
        val allowed = if (type in setOf("lvm", "lvmthin", "zfspool")) setOf("images", "rootdir") else if (type == "pbs") setOf("backup") else FILE_CONTENTS
        p["content"]?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && it !in allowed }?.let { return "content: storage does not support content type '$it'" }
        p["path"]?.let { if (type == "dir" && !it.startsWith("/")) return "path: invalid format - '$it' is not an absolute path" }
        return null
    }

    private fun isShared(o: Map<String, String>) = o["type"] in setOf("nfs", "cifs", "pbs") || o["shared"] == "1"

    private companion object {
        const val GIB = 1024L * 1024 * 1024
        const val NAS = "10.0.0.50"
        const val BACKUP_SERVER = "10.0.0.60"
        const val PBS_PASSWORD = "anprox"
        const val FINGERPRINT = "a1:b2:c3:d4:e5:f6:07:18:29:3a:4b:5c:6d:7e:8f:90:a1:b2:c3:d4:e5:f6:07:18:29:3a:4b:5c:6d:7e:8f:90"
        val FLAGS = setOf("shared", "disable", "sparse")
        val FILE_CONTENTS = setOf("images", "rootdir", "vztmpl", "iso", "backup", "snippets", "import")
        val REQUIRED = mapOf(
            "dir" to listOf("path"),
            "nfs" to listOf("server", "export"),
            "cifs" to listOf("server", "share"),
            "pbs" to listOf("server", "datastore", "username"),
            "lvm" to listOf("vgname"),
            "lvmthin" to listOf("vgname", "thinpool"),
            "zfspool" to listOf("pool"),
        )
        val FIXED = mapOf(
            "dir" to listOf("path"),
            "nfs" to listOf("server", "export", "path"),
            "cifs" to listOf("server", "share", "path"),
            "pbs" to listOf("server", "datastore"),
            "lvm" to listOf("vgname"),
            "lvmthin" to listOf("vgname", "thinpool"),
            "zfspool" to listOf("pool"),
        )
    }
}
