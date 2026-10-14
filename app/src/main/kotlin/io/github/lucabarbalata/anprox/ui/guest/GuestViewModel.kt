package io.github.lucabarbalata.anprox.ui.guest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestNetworkInfo
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.guest.Polled
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestStatus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.storage.SettingsRepository
import io.github.lucabarbalata.anprox.storage.favouriteKey
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuestState(
    val ref: GuestRef,
    val serverName: String = "",
    val status: GuestStatus? = null,
    /** OS and IP addresses from the guest agent / container; null while loading. */
    val network: GuestNetworkInfo? = null,
    val error: UiText? = null,
    /** The action currently running (button shows a spinner). */
    val running: PowerAction? = null,
    /** Action waiting for the user to confirm. */
    val confirm: PowerAction? = null,
    /** One-shot result shown as a snackbar. */
    val result: TaskResult? = null,
    /** Pinned to the dashboard. */
    val favourite: Boolean = false,
) {
    val title: String get() = status?.name?.takeIf { it.isNotBlank() } ?: ref.vmid.toString()

    /** Actions that make sense right now, in display order. */
    val availableActions: List<PowerAction>
        get() {
            val s = status ?: return emptyList()
            if (s.template) return emptyList()
            val list = when {
                s.isPaused -> listOf(PowerAction.RESUME, PowerAction.STOP)
                s.isRunning -> listOf(PowerAction.SHUTDOWN, PowerAction.REBOOT, PowerAction.SUSPEND, PowerAction.STOP, PowerAction.RESET)
                else -> listOf(PowerAction.START)
            }
            // Container suspend (CRIU) is experimental in Proxmox; offer it for VMs only.
            return list.filter { it.appliesTo(ref.type) && !(it == PowerAction.SUSPEND && ref.type == GuestType.LXC) }
        }
}

class GuestViewModel(
    private val ref: GuestRef,
    private val guests: GuestRepository,
    hub: ServerHub,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val favouriteKey = favouriteKey(ref.serverId, ref.type.apiName, ref.vmid)

    private val ui = MutableStateFlow(GuestState(ref, serverName = hub.server(ref.serverId)?.profile?.name.orEmpty()))

    val state: StateFlow<GuestState> = combine(
        guests.observeStatus(ref),
        guests.observeNetwork(ref),
        settings.settings.map { favouriteKey in it.favourites }.distinctUntilChanged(),
        ui,
    ) { polled: Polled<GuestStatus>, network, favourite, s ->
        s.copy(status = polled.value ?: s.status, network = network, error = polled.error?.toUiText(), favourite = favourite)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    fun toggleFavourite() {
        val pinned = !state.value.favourite
        viewModelScope.launch { settings.setFavourite(favouriteKey, pinned) }
    }

    /** Tiered confirmation (questionnaire Q12): SAFE actions run immediately. */
    fun request(action: PowerAction) {
        if (action.danger == DangerLevel.SAFE) run(action) else ui.update { it.copy(confirm = action) }
    }

    fun confirm() {
        val action = ui.value.confirm ?: return
        ui.update { it.copy(confirm = null) }
        run(action)
    }

    fun dismissConfirm() = ui.update { it.copy(confirm = null) }

    fun consumeResult() = ui.update { it.copy(result = null) }

    private fun run(action: PowerAction) {
        if (ui.value.running != null) return
        ui.update { it.copy(running = action) }
        viewModelScope.launch {
            val result = try {
                val upid = guests.power(ref, action)
                val task = guests.awaitTask(ref.serverId, ref.node, upid)
                val label = TaskTypes.label(Upid.parse(upid)?.type ?: action.apiPath)
                if (task.succeeded) {
                    TaskResult(UiText.res(R.string.guest_task_ok, label), upid, ok = true)
                } else {
                    TaskResult(UiText.res(R.string.guest_task_failed, label, task.exitstatus.orEmpty()), upid, ok = false)
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(running = null, result = result) }
        }
    }
}
