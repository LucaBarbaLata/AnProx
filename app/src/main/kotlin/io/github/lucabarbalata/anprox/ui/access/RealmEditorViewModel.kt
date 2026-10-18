package io.github.lucabarbalata.anprox.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.model.pve.RealmForm
import io.github.lucabarbalata.anprox.model.pve.RealmType
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RealmEditorState(
    val creating: Boolean,
    val loading: Boolean = false,
    val error: UiText? = null,
    val form: RealmForm = RealmForm(),
    val busy: Boolean = false,
    val saveError: UiText? = null,
    /** Saved: the screen closes. */
    val done: Boolean = false,
) {
    val problem: RealmForm.Problem? get() = form.problem(creating)
    val canSave: Boolean get() = !loading && !busy && error == null && problem == null
}

/** Datacenter → Permissions → Realms → add an LDAP, AD or OpenID realm ([realm] null) or edit one. */
class RealmEditorViewModel(
    private val serverId: String,
    private val realm: String?,
    type: RealmType,
    private val repo: AccessRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(RealmEditorState(creating = realm == null, form = RealmForm(type)))
    val state: StateFlow<RealmEditorState> = ui.asStateFlow()

    init {
        if (realm != null) load()
    }

    fun load() {
        val id = realm ?: return
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val form = repo.realmForm(serverId, id)
                ui.update { it.copy(loading = false, form = form) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun edit(change: RealmForm.() -> RealmForm) {
        ui.update { it.copy(form = it.form.change(), saveError = null) }
    }

    fun save() {
        val s = ui.value
        if (!s.canSave) return
        ui.update { it.copy(busy = true, saveError = null) }
        viewModelScope.launch {
            try {
                if (s.creating) repo.createRealm(serverId, s.form) else repo.updateRealm(serverId, s.form)
                ui.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, saveError = e.toUiText()) }
            }
        }
    }
}
