package io.github.lucabarbalata.anprox.ui.hardware

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.CtHardware
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the screen shows in a dialog; kept here so it survives rotation. */
sealed interface HardwareDialog {
    data object CpuMemory : HardwareDialog
    data object Resources : HardwareDialog
    data object AddDisk : HardwareDialog
    data object AddMountPoint : HardwareDialog
    data object BootOrder : HardwareDialog

    /** The actions for one disk, drive, volume or unused volume. */
    data class Volume(val key: String) : HardwareDialog
    data class Grow(val key: String) : HardwareDialog
    data class Move(val key: String) : HardwareDialog
    data class Detach(val key: String) : HardwareDialog
    data class Destroy(val key: String) : HardwareDialog
    data class Attach(val key: String) : HardwareDialog

    /** A CD/DVD drive's media; null adds a drive. */
    data class Drive(val key: String?) : HardwareDialog

    /** A network device; null adds one. */
    data class Net(val key: String?) : HardwareDialog
    data class RemoveNet(val key: String) : HardwareDialog
}

data class HardwareState(
    val ref: GuestRef,
    val loading: Boolean = true,
    val vm: VmHardware? = null,
    val ct: CtHardware? = null,
    val running: Boolean = false,
    val hostCpus: Int? = null,
    /** Storages for disks (VMs) or volumes (containers) on the guest's node. */
    val storages: List<NodeStorage>? = null,
    val bridges: List<NodeNetwork>? = null,
    val isos: List<StorageContentItem>? = null,
    /** Loading failed: nothing to show. */
    val error: UiText? = null,
    /** An operation is running; the screen shows a busy card and disables editing. */
    val busy: UiText? = null,
    val result: TaskResult? = null,
    val dialog: HardwareDialog? = null,
) {
    val loaded: Boolean get() = vm != null || ct != null
    val pending: Set<String> get() = vm?.pending ?: ct?.pending ?: emptySet()
    val digest: String? get() = vm?.digest ?: ct?.digest
}

/**
 * Hardware of one VM or container (questionnaire Q19, common settings first). Every change is a
 * form from the model, sent with the config digest; tasks (allocating, growing, moving disks) are
 * followed until they end, then the hardware is loaded again.
 */
class HardwareViewModel(
    private val ref: GuestRef,
    private val hardware: HardwareRepository,
    private val guests: GuestRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(HardwareState(ref))
    val state: StateFlow<HardwareState> = ui.asStateFlow()

    init {
        reload()
        loadChoices()
    }

    fun reload() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val status = async {
                        try {
                            guests.status(ref)
                        } catch (_: ApiException) {
                            null
                        }
                    }
                    if (ref.type == GuestType.QEMU) {
                        val vm = hardware.vm(ref)
                        ui.update { it.copy(vm = vm, running = status.await()?.isRunning == true) }
                    } else {
                        val ct = hardware.container(ref)
                        ui.update { it.copy(ct = ct, running = status.await()?.isRunning == true) }
                    }
                }
                ui.update { it.copy(loading = false) }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    /** Storages, bridges, ISO images and the node's CPU count for the dialogs. Each may fail on its own. */
    private fun loadChoices() {
        viewModelScope.launch {
            val storages = try {
                guests.diskStorages(ref.serverId, ref.node, ref.type)
            } catch (_: ApiException) {
                emptyList()
            }
            ui.update { it.copy(storages = storages) }
        }
        viewModelScope.launch {
            val bridges = try {
                hardware.bridges(ref.serverId, ref.node)
            } catch (_: ApiException) {
                emptyList()
            }
            ui.update { it.copy(bridges = bridges) }
        }
        if (ref.type == GuestType.QEMU) {
            viewModelScope.launch {
                val isos = try {
                    hardware.isoImages(ref.serverId, ref.node)
                } catch (_: ApiException) {
                    emptyList()
                }
                ui.update { it.copy(isos = isos) }
            }
        }
        viewModelScope.launch {
            val cpus = hardware.hostCpus(ref.serverId, ref.node)
            ui.update { it.copy(hostCpus = cpus) }
        }
    }

    fun open(dialog: HardwareDialog) {
        if (ui.value.busy == null) ui.update { it.copy(dialog = dialog) }
    }

    fun dismiss() {
        ui.update { it.copy(dialog = null) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    // ---- changes (the forms come from the model)

    /** A config change that is done when the call returns. */
    fun apply(params: Map<String, String>) {
        if (params.isEmpty()) {
            dismiss()
            return
        }
        run(UiText.res(R.string.hardware_saving)) {
            hardware.apply(ref, params)
            null
        }
    }

    /** Allocating a VM disk runs as a task. */
    fun addVmDisk(params: Map<String, String>) {
        if (params.isEmpty()) return
        run(UiText.res(R.string.hardware_adding_disk)) { hardware.apply(ref, params, asTask = ref.type == GuestType.QEMU) }
    }

    fun resize(params: Map<String, String>) {
        run(UiText.res(R.string.hardware_growing)) { hardware.resize(ref, params) }
    }

    fun move(params: Map<String, String>) {
        run(UiText.res(R.string.hardware_moving)) { hardware.move(ref, params) }
    }

    /** Runs [start]; a returned UPID is followed to the end. Then reports and reloads. */
    private fun run(busy: UiText, start: suspend () -> String?) {
        if (ui.value.busy != null) return
        ui.update { it.copy(busy = busy, dialog = null) }
        viewModelScope.launch {
            val result = try {
                val upid = start()
                if (upid == null) {
                    TaskResult(UiText.res(R.string.hardware_saved), null, ok = true)
                } else {
                    val task = guests.awaitTask(ref.serverId, Upid.parse(upid)?.node ?: ref.node, upid)
                    val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
                    if (task.succeeded) {
                        TaskResult(UiText.res(R.string.guest_task_ok, label), upid, ok = true)
                    } else {
                        TaskResult(UiText.res(R.string.guest_task_failed, label, task.exitstatus.orEmpty()), upid, ok = false)
                    }
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = null, result = result) }
            reload()
        }
    }
}
