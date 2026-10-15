package io.github.lucabarbalata.anprox.ui.cloudinit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.model.pve.CloudInit
import io.github.lucabarbalata.anprox.model.pve.CloudInitForm
import io.github.lucabarbalata.anprox.model.pve.CloudInitIp
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudInitState(
    val ref: GuestRef,
    val loading: Boolean = true,
    val error: UiText? = null,
    /** As loaded, with the config digest. */
    val original: CloudInit? = null,
    val hardware: VmHardware? = null,
    val form: CloudInitForm? = null,
    val running: Boolean = false,
    val storages: List<NodeStorage> = emptyList(),
    /** Where a new cloud-init drive goes; null means the first storage. */
    val driveStorage: String? = null,
    val busy: Boolean = false,
    val result: TaskResult? = null,
) {
    val problem: CloudInitForm.Problem? get() = form?.problem()
    val dirty: Boolean get() = original != null && form != null && form.changes(original).isNotEmpty()
    val canSave: Boolean get() = dirty && problem == null && !busy
    val chosenStorage: String? get() = driveStorage ?: storages.firstOrNull()?.storage
}

/**
 * A VM's cloud-init settings (questionnaire Q18): user, password, SSH keys, IP per network
 * device, DNS. Without a cloud-init drive the screen offers to add one first.
 */
class CloudInitViewModel(
    private val ref: GuestRef,
    private val hardware: HardwareRepository,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(CloudInitState(ref))
    val state: StateFlow<CloudInitState> = ui.asStateFlow()

    init {
        reload()
        viewModelScope.launch {
            val storages = try {
                guests.diskStorages(ref.serverId, ref.node, ref.type)
            } catch (_: ApiException) {
                emptyList()
            }
            ui.update { it.copy(storages = storages) }
        }
    }

    fun reload() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (ci, hw) = hardware.cloudInit(ref)
                val running = try {
                    guests.status(ref).isRunning
                } catch (_: ApiException) {
                    false
                }
                ui.update { it.copy(loading = false, original = ci, hardware = hw, form = CloudInitForm.from(ci, hw.nets), running = running) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun edit(change: CloudInitForm.() -> CloudInitForm) {
        ui.update { s -> s.copy(form = s.form?.change()) }
    }

    fun editIp(index: Int, change: CloudInitIp.() -> CloudInitIp) {
        edit { copy(ipconfigs = ipconfigs + (index to (ipconfigs[index] ?: CloudInitIp()).change())) }
    }

    fun setDriveStorage(storage: String) {
        ui.update { it.copy(driveStorage = storage) }
    }

    fun addDrive() {
        val s = ui.value
        val hw = s.hardware ?: return
        val storage = s.chosenStorage ?: return
        run(UiText.res(R.string.cloudinit_drive_added)) { hardware.apply(ref, CloudInitForm.addDrive(hw, storage)) }
    }

    fun save() {
        val s = ui.value
        val original = s.original ?: return
        val form = s.form ?: return
        if (!s.canSave) return
        run(UiText.res(R.string.cloudinit_saved)) { hardware.apply(ref, form.changes(original)) }
    }

    fun regenerate() {
        run(UiText.res(R.string.cloudinit_regenerated)) { hardware.regenerateCloudInit(ref) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun run(done: UiText, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            if (result.ok) reload()
        }
    }
}
