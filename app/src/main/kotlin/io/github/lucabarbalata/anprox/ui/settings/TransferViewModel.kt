package io.github.lucabarbalata.anprox.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.transfer.ProfileBundle
import io.github.lucabarbalata.anprox.data.transfer.ProfileTransfer
import io.github.lucabarbalata.anprox.data.transfer.ProfileTransferException
import io.github.lucabarbalata.anprox.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The export dialog: the passphrase twice and whether saved passwords go along. */
data class ExportForm(val passphrase: String = "", val repeat: String = "", val includePasswords: Boolean = false) {
    val tooShort: Boolean get() = passphrase.length < MIN_PASSPHRASE
    val mismatch: Boolean get() = repeat.isNotEmpty() && repeat != passphrase
    val ready: Boolean get() = !tooShort && repeat == passphrase

    companion object {
        const val MIN_PASSPHRASE = 8
    }
}

/** The import dialog: the file's text, its passphrase, and once opened, what it holds. */
data class ImportForm(val text: String, val passphrase: String = "", val bundle: ProfileBundle? = null, val error: UiText? = null)

data class TransferState(
    val exporting: ExportForm? = null,
    /** The export form was confirmed: waiting for the file picker. */
    val exportReady: ExportForm? = null,
    val importing: ImportForm? = null,
    val busy: Boolean = false,
    val message: UiText? = null,
)

/** More → Settings → move servers to another phone (milestone M4). */
class TransferViewModel(private val transfer: ProfileTransfer) : ViewModel() {
    private val ui = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = ui.asStateFlow()

    fun startExport() {
        ui.update { it.copy(exporting = ExportForm()) }
    }

    fun editExport(change: ExportForm.() -> ExportForm) {
        ui.update { s -> s.copy(exporting = s.exporting?.change()) }
    }

    fun closeExport() {
        ui.update { it.copy(exporting = null, exportReady = null) }
    }

    /** The form is fine: the screen now asks where to save the file. */
    fun confirmExport() {
        val form = ui.value.exporting ?: return
        if (!form.ready) return
        ui.update { it.copy(exporting = null, exportReady = form) }
    }

    /** Encrypts the servers and writes them to [uri] (null: the user cancelled the picker). */
    fun exportTo(uri: Uri?, resolver: ContentResolver) {
        val form = ui.value.exportReady ?: return
        ui.update { it.copy(exportReady = null) }
        if (uri == null) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = try {
                val text = transfer.export(form.passphrase.toCharArray(), form.includePasswords)
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException("no stream")
                }
                UiText.res(R.string.transfer_exported)
            } catch (_: IOException) {
                UiText.res(R.string.transfer_write_failed)
            } catch (_: SecurityException) {
                UiText.res(R.string.transfer_write_failed)
            }
            ui.update { it.copy(busy = false, message = message) }
        }
    }

    /** A file was picked to import (null: cancelled). */
    fun picked(uri: Uri?, resolver: ContentResolver) {
        if (uri == null) return
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > MAX_FILE_BYTES) null else bytes.toString(Charsets.UTF_8)
                    }
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (text == null) {
                ui.update { it.copy(message = UiText.res(R.string.transfer_not_an_export)) }
            } else {
                ui.update { it.copy(importing = ImportForm(text)) }
            }
        }
    }

    fun editImportPassphrase(text: String) {
        ui.update { s -> s.copy(importing = s.importing?.copy(passphrase = text, error = null)) }
    }

    fun openImport() {
        val form = ui.value.importing ?: return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val bundle = transfer.open(form.text, form.passphrase.toCharArray())
                ui.update { s -> s.copy(busy = false, importing = s.importing?.copy(bundle = bundle, error = null)) }
            } catch (e: ProfileTransferException) {
                val error = UiText.res(
                    when (e.reason) {
                        ProfileTransferException.Reason.WRONG_PASSPHRASE -> R.string.transfer_wrong_passphrase
                        ProfileTransferException.Reason.NEWER_VERSION -> R.string.transfer_newer_version
                        ProfileTransferException.Reason.NOT_AN_EXPORT -> R.string.transfer_not_an_export
                    },
                )
                ui.update { s -> s.copy(busy = false, importing = s.importing?.copy(error = error)) }
            }
        }
    }

    fun confirmImport() {
        val bundle = ui.value.importing?.bundle ?: return
        ui.update { it.copy(busy = true, importing = null) }
        viewModelScope.launch {
            val result = transfer.import(bundle)
            ui.update { it.copy(busy = false, message = UiText.res(R.string.transfer_imported, result.added, result.updated)) }
        }
    }

    fun closeImport() {
        ui.update { it.copy(importing = null) }
    }

    fun consumeMessage() {
        ui.update { it.copy(message = null) }
    }

    private companion object {
        /** An export of even hundreds of servers is a few hundred KB. */
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
    }
}
