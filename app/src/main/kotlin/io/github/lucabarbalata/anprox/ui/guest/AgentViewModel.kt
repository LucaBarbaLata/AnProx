package io.github.lucabarbalata.anprox.ui.guest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.model.pve.AgentPassword
import io.github.lucabarbalata.anprox.model.pve.AgentTrimPath
import io.github.lucabarbalata.anprox.model.pve.FsFreezeStatus
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AgentAction { FREEZE, THAW, TRIM, PASSWORD }

data class AgentState(
    val loading: Boolean = true,
    val freeze: FsFreezeStatus? = null,
    /** The agent can't answer (not installed, not running, VM stopped). */
    val error: UiText? = null,
    val busy: AgentAction? = null,
    /** Freezing waits for confirmation: the guest can't write until it is thawed. */
    val confirmFreeze: Boolean = false,
    /** Result of the last trim, one entry per filesystem. */
    val trimmed: List<AgentTrimPath>? = null,
    val showPassword: Boolean = false,
    /** Why the last password change failed, shown in the dialog. */
    val passwordError: UiText? = null,
    val result: TaskResult? = null,
)

/** QEMU guest agent tools of one VM (questionnaire Q18): freeze and thaw, trim, user passwords. */
class AgentViewModel(
    private val ref: GuestRef,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val status = guests.freezeStatus(ref)
                ui.update { it.copy(loading = false, freeze = status, error = null) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun requestFreeze() = ui.update { it.copy(confirmFreeze = true) }

    fun dismissFreeze() = ui.update { it.copy(confirmFreeze = false) }

    fun freeze() {
        ui.update { it.copy(confirmFreeze = false) }
        run(AgentAction.FREEZE) { UiText.res(R.string.agent_frozen, guests.freeze(ref)) }
    }

    fun thaw() {
        run(AgentAction.THAW) { UiText.res(R.string.agent_thawed, guests.thaw(ref)) }
    }

    fun trim() {
        run(AgentAction.TRIM) {
            val paths = guests.trim(ref)
            ui.update { it.copy(trimmed = paths) }
            UiText.res(R.string.agent_trim_done)
        }
    }

    fun openPassword() = ui.update { it.copy(showPassword = true, passwordError = null) }

    fun dismissPassword() = ui.update { it.copy(showPassword = false, passwordError = null) }

    fun passwordProblem(username: String, password: String, repeat: String): AgentPassword.Problem? =
        AgentPassword.validate(username, password, repeat)

    fun setPassword(username: String, password: String, repeat: String) {
        if (passwordProblem(username, password, repeat) != null || ui.value.busy != null) return
        ui.update { it.copy(busy = AgentAction.PASSWORD, passwordError = null) }
        viewModelScope.launch {
            try {
                guests.setGuestPassword(ref, username, password)
                val done = TaskResult(UiText.res(R.string.agent_password_done, username.trim()), null, ok = true)
                ui.update { it.copy(busy = null, showPassword = false, result = done) }
            } catch (e: ApiException) {
                // Keep the dialog open so a typo in the user name is quick to fix.
                ui.update { it.copy(busy = null, passwordError = e.toUiText()) }
            }
        }
    }

    fun consumeResult() = ui.update { it.copy(result = null) }

    /** Runs one agent command, reports its outcome and re-reads the freeze state. */
    private fun run(action: AgentAction, block: suspend () -> UiText) {
        if (ui.value.busy != null) return
        ui.update { it.copy(busy = action) }
        viewModelScope.launch {
            val result = try {
                TaskResult(block(), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = null, result = result) }
            refresh()
        }
    }
}
