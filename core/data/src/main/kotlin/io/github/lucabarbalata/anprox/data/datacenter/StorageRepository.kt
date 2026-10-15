package io.github.lucabarbalata.anprox.data.datacenter

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.DownloadUrlForm
import io.github.lucabarbalata.anprox.model.pve.StorageConfig
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.UrlMetadata
import io.github.lucabarbalata.anprox.model.pve.fileName
import io.github.lucabarbalata.anprox.network.http.UploadBody
import io.github.lucabarbalata.anprox.network.http.UploadSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A storage on one node: whether it is available there and how full it is. */
data class StorageOnNode(val node: String, val status: String?, val used: Long?, val total: Long?) {
    val available: Boolean get() = status == "available"
}

/** A configured storage and where it can be used. */
data class StorageOverview(val config: StorageConfig, val nodes: List<StorageOnNode>)

/**
 * Datacenter → Storage (milestone M4): the configured storages, what each one holds on a node,
 * and getting ISO images and templates onto it (download by the node, or upload from the phone).
 */
class StorageRepository(private val hub: ServerHub) {

    suspend fun storages(serverId: String): List<StorageOverview> = coroutineScope {
        val configs = async { hub.use(serverId) { it.pve { storageConfigs() } } }
        val rows = async { hub.use(serverId) { it.pve { clusterResources("storage") } } }
        val byStorage = rows.await().groupBy { it.storage }
        configs.await().sortedBy { it.storage }.map { config ->
            val nodes = byStorage[config.storage].orEmpty()
                .mapNotNull { r -> r.node?.let { StorageOnNode(it, r.status, r.disk, r.maxdisk) } }
                .sortedBy { it.node }
            StorageOverview(config, nodes)
        }
    }

    /** What [storage] holds on [node], optionally only one [type], by file name. */
    suspend fun content(serverId: String, node: String, storage: String, type: ContentType? = null): List<StorageContentItem> =
        hub.use(serverId) { it.pve { storageContent(node, storage, type?.apiName, null) } }
            .sortedWith(compareBy({ it.content }, { it.fileName.lowercase() }))

    suspend fun urlMetadata(serverId: String, node: String, url: String, verifyCertificates: Boolean): UrlMetadata =
        hub.use(serverId) { it.pve { queryUrlMetadata(node, url.trim(), if (verifyCertificates) 1 else 0) } }

    /** Starts the node's download; returns the task UPID. */
    suspend fun download(serverId: String, node: String, storage: String, form: DownloadUrlForm): String =
        hub.use(serverId) { it.pve { downloadUrl(node, storage, form.params()) } }

    /**
     * Uploads [source] as [content] (an ISO image, a container template or an import file);
     * [onProgress] gets the bytes sent so far. Returns the UPID of the task that moves the file
     * into place once it has arrived.
     */
    suspend fun upload(
        serverId: String,
        node: String,
        storage: String,
        content: ContentType,
        source: UploadSource,
        onProgress: (sent: Long) -> Unit,
    ): String {
        val type = content.apiName.toRequestBody("text/plain".toMediaType())
        val file = MultipartBody.Part.createFormData("filename", source.name, UploadBody(source, onProgress))
        return hub.use(serverId) { it.pve { upload(node, storage, type, file) } }
    }

    /** Deletes a volume; returns the task UPID, or null when the server did it right away. */
    suspend fun delete(serverId: String, node: String, storage: String, volid: String): String? {
        val answer = hub.use(serverId) { it.pveOrNull { deleteVolume(node, storage, volid) } }
        return (answer as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("UPID:") }
    }
}
