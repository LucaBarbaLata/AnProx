package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.StorageOverview
import io.github.lucabarbalata.anprox.data.datacenter.StorageRepository
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.DownloadUrlForm
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.http.UploadSource
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageListState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val storages: List<StorageOverview> = emptyList(),
)

/** Datacenter → Storage: every configured storage and its state on each node. */
class StorageListViewModel(private val serverId: String, private val repo: StorageRepository) : ViewModel() {
    private val ui = MutableStateFlow(StorageListState())
    val state: StateFlow<StorageListState> = ui.asStateFlow()

    fun refresh() {
        ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val storages = repo.storages(serverId)
                ui.update { it.copy(loading = false, error = null, storages = storages) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }
}

/** An upload in progress. */
data class UploadProgress(val name: String, val sent: Long, val total: Long)

/** A file picked on the phone, waiting for the user to confirm its type. */
data class PendingUpload(val source: UploadSource, val content: ContentType)

data class StorageContentState(
    val serverId: String,
    val node: String,
    val storage: String,
    val loading: Boolean = true,
    val error: UiText? = null,
    /** What the storage may hold; from its configuration. */
    val types: List<ContentType> = emptyList(),
    val selected: ContentType? = null,
    val items: List<StorageContentItem> = emptyList(),
    val confirmDelete: StorageContentItem? = null,
    /** A task (delete, download, upload's final move) is running. */
    val busy: Boolean = false,
    val showDownload: Boolean = false,
    val download: DownloadUrlForm = DownloadUrlForm(),
    val checkingUrl: Boolean = false,
    val urlInfo: String? = null,
    val urlError: UiText? = null,
    val pendingUpload: PendingUpload? = null,
    val upload: UploadProgress? = null,
    val result: TaskResult? = null,
) {
    /** Content types a file can be downloaded or uploaded as, on this storage. */
    val fileTypes: List<ContentType> get() = types.filter { it == ContentType.ISO || it == ContentType.TEMPLATE || it == ContentType.IMPORT }
}

/**
 * What one storage holds on one node (milestone M4), by content type: ISO images, container
 * templates, guest disks, snippets. ISO images and templates can be downloaded by the node from
 * a URL, uploaded from the phone, or deleted.
 */
class StorageContentViewModel(
    private val serverId: String,
    private val node: String,
    private val storage: String,
    private val repo: StorageRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(StorageContentState(serverId, node, storage))
    val state: StateFlow<StorageContentState> = ui.asStateFlow()
    private var uploading: Job? = null

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val config = repo.storages(serverId).firstOrNull { it.config.storage == storage }?.config
                val types = config?.let { ContentType.parse(it.content) }.orEmpty()
                val selected = ui.value.selected?.takeIf { it in types } ?: types.firstOrNull()
                ui.update { it.copy(types = types, selected = selected) }
                reloadItems()
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    private suspend fun reloadItems() {
        val type = ui.value.selected
        try {
            // Backups have their own screen; the list here shows everything else.
            val items = if (type == null || type == ContentType.BACKUP) emptyList() else repo.content(serverId, node, storage, type)
            ui.update { it.copy(loading = false, error = null, items = items) }
        } catch (e: ApiException) {
            ui.update { it.copy(loading = false, error = e.toUiText()) }
        }
    }

    fun select(type: ContentType) {
        ui.update { it.copy(selected = type, items = emptyList(), loading = true) }
        viewModelScope.launch { reloadItems() }
    }

    fun requestDelete(item: StorageContentItem) {
        ui.update { it.copy(confirmDelete = item) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = null) }
    }

    fun delete() {
        val item = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        runTask(UiText.res(R.string.storage_deleted)) { repo.delete(serverId, node, storage, item.volid) }
    }

    // ------------------------------------------------------------------ download from URL

    fun openDownload() {
        val type = ui.value.selected?.takeIf { it in ui.value.fileTypes } ?: ui.value.fileTypes.firstOrNull() ?: ContentType.ISO
        ui.update { it.copy(showDownload = true, download = DownloadUrlForm(content = type), urlInfo = null, urlError = null) }
    }

    fun closeDownload() {
        ui.update { it.copy(showDownload = false) }
    }

    fun editDownload(change: DownloadUrlForm.() -> DownloadUrlForm) {
        ui.update { s ->
            val before = s.download
            var next = before.change()
            // Suggest the URL's file name until the user types their own.
            if (next.url != before.url && (before.filename.isEmpty() || before.filename == DownloadUrlForm.suggestedName(before.url))) {
                next = next.copy(filename = DownloadUrlForm.suggestedName(next.url))
            }
            s.copy(download = next, urlError = if (next.url != before.url) null else s.urlError)
        }
    }

    /** Asks the node what the URL points to and fills in the file name. */
    fun checkUrl() {
        val form = ui.value.download
        ui.update { it.copy(checkingUrl = true, urlError = null, urlInfo = null) }
        viewModelScope.launch {
            try {
                val meta = repo.urlMetadata(serverId, node, form.url, form.verifyCertificates)
                ui.update { s ->
                    s.copy(
                        checkingUrl = false,
                        urlInfo = listOfNotNull(meta.filename, meta.size?.let { Formatters.bytes(it) }, meta.mimetype).joinToString(" · "),
                        download = meta.filename?.takeIf { it.isNotBlank() }?.let { s.download.copy(filename = it) } ?: s.download,
                    )
                }
            } catch (e: ApiException) {
                ui.update { it.copy(checkingUrl = false, urlError = e.toUiText()) }
            }
        }
    }

    fun startDownload() {
        val form = ui.value.download
        if (form.problem() != null) return
        ui.update { it.copy(showDownload = false, selected = form.content) }
        runTask(UiText.res(R.string.storage_downloaded, form.filename.trim())) { repo.download(serverId, node, storage, form) }
    }

    // ------------------------------------------------------------------ upload from the phone

    /** A file was picked: guess its type from the name, and let the user confirm. */
    fun picked(source: UploadSource) {
        val name = source.name.lowercase()
        val guess = when {
            DownloadUrlForm.TEMPLATE_EXTENSIONS.any { name.endsWith(it) } -> ContentType.TEMPLATE
            name.endsWith(".iso") -> ContentType.ISO
            DownloadUrlForm.IMPORT_EXTENSIONS.any { name.endsWith(it) } -> ContentType.IMPORT
            else -> ContentType.ISO
        }
        val type = guess.takeIf { it in ui.value.fileTypes } ?: ui.value.fileTypes.firstOrNull() ?: ContentType.ISO
        ui.update { it.copy(pendingUpload = PendingUpload(source, type)) }
    }

    fun setUploadType(type: ContentType) {
        ui.update { s -> s.copy(pendingUpload = s.pendingUpload?.copy(content = type)) }
    }

    fun cancelPendingUpload() {
        ui.update { it.copy(pendingUpload = null) }
    }

    fun startUpload() {
        val pending = ui.value.pendingUpload ?: return
        val source = pending.source
        ui.update { it.copy(pendingUpload = null, selected = pending.content, upload = UploadProgress(source.name, 0, source.size)) }
        uploading = viewModelScope.launch {
            var shown = 0L
            val result = try {
                val upid = repo.upload(serverId, node, storage, pending.content, source) { sent ->
                    // A redraw per megabyte is plenty.
                    if (sent - shown >= PROGRESS_STEP || sent == source.size) {
                        shown = sent
                        ui.update { s -> s.copy(upload = s.upload?.copy(sent = sent)) }
                    }
                }
                ui.update { it.copy(upload = null, busy = true) }
                finish(upid, UiText.res(R.string.storage_uploaded, source.name))
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(upload = null, busy = false, result = result) }
            reloadItems()
        }
    }

    fun cancelUpload() {
        uploading?.cancel()
        uploading = null
        ui.update { it.copy(upload = null) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun runTask(done: UiText, call: suspend () -> String?) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                finish(call(), done)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            reloadItems()
        }
    }

    private companion object {
        const val PROGRESS_STEP = 1024 * 1024L
    }

    /** Follows [upid] (when there is a task) and describes how it ended. */
    private suspend fun finish(upid: String?, done: UiText): TaskResult {
        if (upid == null) return TaskResult(done, null, ok = true)
        val status = guests.awaitTask(serverId, Upid.parse(upid)?.node ?: node, upid)
        return if (status.succeeded) {
            TaskResult(done, upid, ok = true)
        } else {
            val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
            TaskResult(UiText.res(R.string.guest_task_failed, label, status.exitstatus.orEmpty()), upid, ok = false)
        }
    }
}
