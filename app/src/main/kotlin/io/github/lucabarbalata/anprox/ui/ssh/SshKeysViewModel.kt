package io.github.lucabarbalata.anprox.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.ssh.SshKeys
import io.github.lucabarbalata.anprox.model.ssh.KnownHost
import io.github.lucabarbalata.anprox.model.ssh.SshKeyInfo
import io.github.lucabarbalata.anprox.model.ssh.SshKeyType
import io.github.lucabarbalata.anprox.storage.SshStore
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "new key" and "import key" dialogs. */
data class KeyDialog(
    val importing: Boolean,
    val name: String = "",
    val type: SshKeyType = SshKeyType.ED25519,
    val privateKey: String = "",
    val passphrase: String = "",
    val error: UiText? = null,
) {
    val valid: Boolean get() = name.isNotBlank() && (!importing || privateKey.isNotBlank())
}

data class SshKeysState(
    val keys: List<SshKeyInfo> = emptyList(),
    val hosts: List<KnownHost> = emptyList(),
    val dialog: KeyDialog? = null,
    /** Generating an RSA key takes a moment. */
    val working: Boolean = false,
    val confirmDelete: SshKeyInfo? = null,
    val confirmForget: KnownHost? = null,
    val message: UiText? = null,
)

/** SSH keys and trusted hosts (questionnaire Q15), under Settings. */
class SshKeysViewModel(private val store: SshStore) : ViewModel() {
    private val ui = MutableStateFlow(SshKeysState())

    val state: StateFlow<SshKeysState> = combine(store.keyList, store.knownHosts, ui) { keys, hosts, s ->
        s.copy(keys = keys.sortedBy { it.name.lowercase() }, hosts = hosts.sortedBy { it.address })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    fun openNew() = ui.update { it.copy(dialog = KeyDialog(importing = false)) }

    fun openImport() = ui.update { it.copy(dialog = KeyDialog(importing = true)) }

    fun edit(change: KeyDialog.() -> KeyDialog) = ui.update { s -> s.copy(dialog = s.dialog?.change()?.copy(error = null)) }

    /** A key file picked with the system file picker. */
    fun picked(text: String?) {
        if (text == null) {
            ui.update { s -> s.copy(dialog = s.dialog?.copy(error = UiText.res(R.string.ssh_key_error_unreadable))) }
            return
        }
        edit { copy(privateKey = text) }
    }

    fun closeDialog() = ui.update { it.copy(dialog = null) }

    fun save() {
        val dialog = ui.value.dialog?.takeIf { it.valid } ?: return
        ui.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                val material = withContext(Dispatchers.Default) {
                    if (dialog.importing) SshKeys.import(dialog.name, dialog.privateKey, dialog.passphrase)
                    else SshKeys.generate(dialog.type, dialog.name)
                }
                store.save(material.info, material.secret)
                ui.update {
                    it.copy(
                        dialog = null,
                        working = false,
                        message = UiText.res(if (dialog.importing) R.string.ssh_key_imported else R.string.ssh_key_created, material.info.name),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SshKeyException explains itself; anything else shows its message.
                ui.update { s -> s.copy(working = false, dialog = s.dialog?.copy(error = e.toUiText())) }
            }
        }
    }

    fun askDelete(key: SshKeyInfo) = ui.update { it.copy(confirmDelete = key) }

    fun dismissDelete() = ui.update { it.copy(confirmDelete = null) }

    fun delete() {
        val key = ui.value.confirmDelete ?: return
        ui.update { it.copy(confirmDelete = null) }
        viewModelScope.launch {
            store.delete(key.id)
            ui.update { it.copy(message = UiText.res(R.string.ssh_key_deleted, key.name)) }
        }
    }

    fun askForget(host: KnownHost) = ui.update { it.copy(confirmForget = host) }

    fun dismissForget() = ui.update { it.copy(confirmForget = null) }

    fun forget() {
        val host = ui.value.confirmForget ?: return
        ui.update { it.copy(confirmForget = null) }
        viewModelScope.launch { store.remove(host.host, host.port) }
    }

    fun copied() = ui.update { it.copy(message = UiText.res(R.string.ssh_key_copied)) }

    fun consumeMessage() = ui.update { it.copy(message = null) }
}
