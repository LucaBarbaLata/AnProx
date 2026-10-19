package io.github.lucabarbalata.anprox.ui.guest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.pve.GuestOptions
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OptionsState(
    val ref: GuestRef,
    val loading: Boolean = true,
    /** As loaded from the server, with its config digest. Null until the first load succeeds. */
    val original: GuestOptions? = null,
    val draft: GuestOptions = GuestOptions(),
    /** The tags field as typed; [draft] holds the parsed tags. */
    val tagsText: String = "",
    val saving: Boolean = false,
    /** Loading failed: nothing to edit. */
    val error: UiText? = null,
    /** Saving failed: the draft is kept so the user can retry or reload. */
    val saveError: UiText? = null,
    /** One-shot "Options saved" snackbar. */
    val saved: Boolean = false,
) {
    val problem: GuestOptions.Problem? get() = draft.copy(name = draft.name.trim()).problem()
    val dirty: Boolean get() = original != null && GuestOptions.changes(ref.type, original, draft).isNotEmpty()
    val canSave: Boolean get() = dirty && problem == null && !saving
}

/** Name, notes, tags, start at boot and start order, protection and the guest agent flag of one guest (Q19). */
class OptionsViewModel(
    private val ref: GuestRef,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(OptionsState(ref))
    val state: StateFlow<OptionsState> = ui.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val options = guests.options(ref)
                ui.update { it.loaded(options).copy(loading = false, saveError = null) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun setName(name: String) {
        edit { copy(name = name) }
    }

    fun setDescription(description: String) {
        edit { copy(description = description) }
    }

    fun setTagsText(text: String) {
        ui.update { it.copy(tagsText = text, draft = it.draft.copy(tags = GuestOptions.parseTags(text))) }
    }

    fun setOnBoot(on: Boolean) {
        edit { copy(onBoot = on) }
    }

    /** The start order fields take digits only; empty means Proxmox's default. */
    fun setStartOrder(text: String) {
        edit { copy(startup = startup.copy(order = text.toIntOrNull())) }
    }

    fun setStartDelay(text: String) {
        edit { copy(startup = startup.copy(up = text.toIntOrNull())) }
    }

    fun setShutdownTimeout(text: String) {
        edit { copy(startup = startup.copy(down = text.toIntOrNull())) }
    }

    fun setProtection(on: Boolean) {
        edit { copy(protection = on) }
    }

    fun setAgent(on: Boolean) {
        edit { copy(agent = on) }
    }

    fun save() {
        val current = ui.value
        val original = current.original ?: return
        if (!current.canSave) return
        val draft = current.draft.copy(name = current.draft.name.trim())
        ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                guests.saveOptions(ref, original, draft)
            } catch (e: ApiException) {
                ui.update { it.copy(saving = false, saveError = e.toUiText()) }
                return@launch
            }
            // Reload for the new digest; if that fails, the saved draft is still what's on the server.
            val fresh = try {
                guests.options(ref)
            } catch (_: ApiException) {
                draft.copy(digest = null)
            }
            ui.update { it.loaded(fresh).copy(saving = false, saved = true) }
        }
    }

    fun consumeSaved() {
        ui.update { it.copy(saved = false) }
    }

    private fun edit(change: GuestOptions.() -> GuestOptions) {
        ui.update { it.copy(draft = it.draft.change(), saveError = null) }
    }

    private fun OptionsState.loaded(options: GuestOptions) =
        copy(original = options, draft = options, tagsText = options.tags.joinToString(" "))
}
