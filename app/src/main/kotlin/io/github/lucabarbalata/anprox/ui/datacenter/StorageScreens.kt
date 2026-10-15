package io.github.lucabarbalata.anprox.ui.datacenter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.ButtonStyle
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusDot
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.component.UsageBar
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.datacenter.StorageOverview
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.ContentType
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.DownloadUrlForm
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.fileName
import io.github.lucabarbalata.anprox.network.http.UploadSource
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ------------------------------------------------------------------------------------ storage list

/** Datacenter → Storage: every configured storage; tap a node's row to browse it there. */
@Composable
fun StorageListScreen(serverId: String, onBack: () -> Unit, onOpen: (node: String, storage: String) -> Unit) {
    val viewModel: StorageListViewModel = koinViewModel(key = "storages-$serverId") { parametersOf(serverId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    AnProxScaffold(
        title = stringResource(R.string.storage_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::refresh) }
            }
            if (state.loading && state.storages.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
            }
            items(state.storages, key = { it.config.storage }) { s -> StorageCard(s, onOpen) }
        }
    }
}

@Composable
private fun StorageCard(s: StorageOverview, onOpen: (node: String, storage: String) -> Unit) {
    val c = AnProxTheme.colors
    val config = s.config
    GlassCard(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Storage, contentDescription = null, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(config.storage, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    listOfNotNull(config.type, stringResource(R.string.storage_shared).takeIf { config.shared }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
            if (config.disable) TagChip(stringResource(R.string.storage_disabled))
        }
        Spacer(Modifier.height(6.dp))
        val contents = ContentType.parse(config.content).map { contentLabel(it) }
        Text(contents.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        // A shared storage is the same everywhere: one row is enough.
        val rows = if (config.shared) s.nodes.filter { it.available }.take(1).ifEmpty { s.nodes.take(1) } else s.nodes
        rows.forEach { n ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = n.available, onClickLabel = n.node) { onOpen(n.node, config.storage) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (n.available) Tone.SUCCESS else Tone.NEUTRAL)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (config.shared) stringResource(R.string.storage_all_nodes) else n.node,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textPrimary,
                    modifier = Modifier.width(96.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (n.available) {
                    val used = n.used
                    val total = n.total
                    val fraction = if (used != null && total != null && total > 0) used.toDouble() / total else null
                    UsageBar(fraction, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(Formatters.usage(used, total), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                } else {
                    Text(stringResource(R.string.storage_unavailable), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ one storage on one node

/**
 * What a storage holds on a node, by content type. ISO images and templates can be downloaded by
 * the node from a URL, uploaded from the phone, or deleted; backups open their own screen.
 */
@Composable
fun StorageContentScreen(
    serverId: String,
    node: String,
    storage: String,
    onBack: () -> Unit,
    onBackups: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val viewModel: StorageContentViewModel = koinViewModel(key = "storage-$serverId-$node-$storage") { parametersOf(serverId, node, storage) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, serverId, node, onTask, viewModel::consumeResult)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.picked(ContentUriSource.of(context, uri))
    }
    val typeLabels = ContentType.entries.associateWith { contentLabel(it) }

    AnProxScaffold(
        title = storage,
        subtitle = node,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.types.isNotEmpty()) {
                item { ChoiceChips(state.types, state.selected ?: state.types.first(), { typeLabels.getValue(it) }, viewModel::select) }
            }
            val selected = state.selected
            if (selected != null && selected in state.fileTypes) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnProxButton(
                            stringResource(R.string.storage_download),
                            onClick = viewModel::openDownload,
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.CloudDownload,
                            enabled = !state.busy && state.upload == null,
                            modifier = Modifier.weight(1f),
                        )
                        AnProxButton(
                            stringResource(R.string.storage_upload),
                            onClick = { pick.launch(arrayOf("*/*")) },
                            style = ButtonStyle.SECONDARY,
                            icon = Icons.Outlined.Upload,
                            enabled = !state.busy && state.upload == null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            state.upload?.let { up -> item { UploadCard(up, viewModel::cancelUpload) } }
            if (state.busy) item { BusyCard(stringResource(R.string.storage_working)) }
            state.error?.let { error ->
                item { ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load) }
            }
            when {
                state.loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AnProxTheme.colors.accent)
                    }
                }
                selected == ContentType.BACKUP -> item {
                    AnProxButton(stringResource(R.string.storage_open_backups), onClick = onBackups, icon = Icons.Outlined.Backup, modifier = Modifier.fillMaxWidth())
                }
                state.error == null && state.items.isEmpty() -> item {
                    EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.storage_empty_title), stringResource(R.string.storage_empty_body))
                }
                else -> items(state.items, key = { it.volid }) { item ->
                    ContentRow(item, deletable = ContentType.fromApi(item.content)?.deletable == true && !state.busy) { viewModel.requestDelete(item) }
                }
            }
        }
    }

    state.confirmDelete?.let { item ->
        ConfirmActionDialog(
            title = stringResource(R.string.storage_delete_title),
            message = stringResource(R.string.storage_delete_body, item.fileName),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::delete,
            onDismiss = viewModel::dismissDelete,
        )
    }
    if (state.showDownload) DownloadDialog(state, viewModel)
    state.pendingUpload?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPendingUpload,
            containerColor = AnProxTheme.colors.surfaceSolid,
            title = { Text(stringResource(R.string.storage_upload_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.source.name, style = MaterialTheme.typography.titleSmall, color = AnProxTheme.colors.textPrimary)
                    if (pending.source.size >= 0) FormHint(Formatters.bytes(pending.source.size))
                    FormLabel(stringResource(R.string.storage_upload_as))
                    ChoiceChips(state.fileTypes, pending.content, { typeLabels.getValue(it) }, viewModel::setUploadType)
                    FormHint(stringResource(R.string.storage_upload_keep_open))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::startUpload) { Text(stringResource(R.string.storage_upload)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelPendingUpload) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ContentRow(item: StorageContentItem, deletable: Boolean, onDelete: () -> Unit) {
    val c = AnProxTheme.colors
    val context = LocalContext.current
    GlassCard(contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.fileName, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        item.size?.let { Formatters.bytes(it) },
                        item.vmid?.let { stringResource(R.string.storage_owner, it) },
                        item.ctime?.let { DateUtils.formatDateTime(context, it * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
            if (deletable) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.storage_delete_item, item.fileName), tint = c.danger)
                }
            }
        }
    }
}

@Composable
private fun UploadCard(up: UploadProgress, onCancel: () -> Unit) {
    val c = AnProxTheme.colors
    GlassCard {
        Text(stringResource(R.string.storage_uploading, up.name), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        if (up.total > 0) {
            LinearProgressIndicator(progress = { (up.sent.toFloat() / up.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = c.accent)
            Spacer(Modifier.height(4.dp))
            FormHint("${Formatters.bytes(up.sent)} / ${Formatters.bytes(up.total)}")
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent)
            Spacer(Modifier.height(4.dp))
            FormHint(Formatters.bytes(up.sent))
        }
        FormHint(stringResource(R.string.storage_upload_keep_open))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun DownloadDialog(state: StorageContentState, viewModel: StorageContentViewModel) {
    val form = state.download
    val problem = form.problem()
    val typeLabels = ContentType.entries.associateWith { contentLabel(it) }
    AlertDialog(
        onDismissRequest = viewModel::closeDownload,
        containerColor = AnProxTheme.colors.surfaceSolid,
        title = { Text(stringResource(R.string.storage_download_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = form.url,
                    onValueChange = { u -> viewModel.editDownload { copy(url = u.trim()) } },
                    label = stringResource(R.string.storage_download_url),
                    isError = form.url.isNotEmpty() && problem == DownloadUrlForm.Problem.URL,
                )
                TextButton(onClick = viewModel::checkUrl, enabled = problem != DownloadUrlForm.Problem.URL && !state.checkingUrl) {
                    Text(stringResource(R.string.storage_download_check))
                }
                state.urlInfo?.let { FormHint(it) }
                state.urlError?.let { FormHint(it.asString(), error = true) }
                if (state.fileTypes.size > 1) {
                    ChoiceChips(state.fileTypes, form.content, { typeLabels.getValue(it) }) { t -> viewModel.editDownload { copy(content = t) } }
                }
                GlassTextField(
                    value = form.filename,
                    onValueChange = { n -> viewModel.editDownload { copy(filename = n) } },
                    label = stringResource(R.string.storage_download_name),
                    isError = form.filename.isNotEmpty() && (problem == DownloadUrlForm.Problem.FILENAME || problem == DownloadUrlForm.Problem.EXTENSION),
                )
                GlassTextField(
                    value = form.checksum,
                    onValueChange = { v -> viewModel.editDownload { copy(checksum = v.trim()) } },
                    label = stringResource(R.string.storage_download_checksum),
                    supportingText = stringResource(R.string.storage_download_checksum_hint),
                    isError = problem == DownloadUrlForm.Problem.CHECKSUM,
                )
                if (form.checksum.isNotBlank()) {
                    ChoiceChips(DownloadUrlForm.ALGORITHMS, form.algorithm, { it.uppercase() }) { a -> viewModel.editDownload { copy(algorithm = a) } }
                }
                SwitchRow(
                    title = stringResource(R.string.storage_download_verify),
                    description = stringResource(R.string.storage_download_verify_body),
                    checked = form.verifyCertificates,
                    onCheckedChange = { on -> viewModel.editDownload { copy(verifyCertificates = on) } },
                )
                if (problem != null && form.url.isNotEmpty()) FormHint(downloadProblem(problem), error = true)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::startDownload, enabled = problem == null) { Text(stringResource(R.string.storage_download_start)) }
        },
        dismissButton = { TextButton(onClick = viewModel::closeDownload) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun downloadProblem(problem: DownloadUrlForm.Problem): String = stringResource(
    when (problem) {
        DownloadUrlForm.Problem.URL -> R.string.storage_problem_url
        DownloadUrlForm.Problem.FILENAME -> R.string.storage_problem_name
        DownloadUrlForm.Problem.EXTENSION -> R.string.storage_problem_extension
        DownloadUrlForm.Problem.CHECKSUM -> R.string.storage_problem_checksum
    },
)

@Composable
private fun contentLabel(type: ContentType): String = stringResource(contentLabelRes(type))

private fun contentLabelRes(type: ContentType): Int = when (type) {
    ContentType.ISO -> R.string.content_iso
    ContentType.TEMPLATE -> R.string.content_vztmpl
    ContentType.IMAGES -> R.string.content_images
    ContentType.ROOTDIR -> R.string.content_rootdir
    ContentType.BACKUP -> R.string.content_backup
    ContentType.SNIPPETS -> R.string.content_snippets
    ContentType.IMPORT -> R.string.content_import
}

/** A file picked with the system's document picker, read through the content resolver. */
private class ContentUriSource(private val context: Context, private val uri: Uri, override val name: String, override val size: Long) : UploadSource {
    override fun open() = context.contentResolver.openInputStream(uri) ?: error("Can't open $uri")

    companion object {
        fun of(context: Context, uri: Uri): ContentUriSource {
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            return ContentUriSource(context.applicationContext, uri, name, size)
        }
    }
}
