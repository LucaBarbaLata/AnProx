package io.github.lucabarbalata.anprox.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hardware.HardwareRepository
import io.github.lucabarbalata.anprox.model.format.TaskTypes
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.NewContainer
import io.github.lucabarbalata.anprox.model.pve.NewVm
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.taskFailedText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The number fields of the form, kept as typed so a half-typed value doesn't jump around. */
enum class NumberField { ID, DISK, CORES, SOCKETS, MEMORY, SWAP, VLAN }

data class CreateGuestState(
    val type: GuestType,
    val node: String,
    val loading: Boolean = true,
    val error: UiText? = null,
    val takenIds: Set<Int> = emptySet(),
    val hostCpus: Int? = null,
    val storages: List<NodeStorage> = emptyList(),
    val bridges: List<NodeNetwork> = emptyList(),
    /** ISO images (VMs) or container templates. */
    val files: List<StorageContentItem> = emptyList(),
    val vm: NewVm = NewVm(vmid = 0),
    val ct: NewContainer = NewContainer(vmid = 0),
    val numbers: Map<NumberField, String> = emptyMap(),
    val passwordAgain: String = "",
    val advanced: Boolean = false,
    val busy: Boolean = false,
    val result: TaskResult? = null,
    val created: GuestRef? = null,
) {
    private fun number(field: NumberField): Int = numbers[field]?.toIntOrNull() ?: 0
    private fun optional(field: NumberField): Int? = numbers[field]?.takeIf { it.isNotBlank() }?.toIntOrNull()

    /** The VM as the form describes it. */
    val vmForm: NewVm
        get() = vm.copy(
            vmid = number(NumberField.ID),
            diskGiB = number(NumberField.DISK),
            cores = number(NumberField.CORES),
            sockets = number(NumberField.SOCKETS),
            memoryMiB = number(NumberField.MEMORY),
            vlan = optional(NumberField.VLAN),
        )

    /** The container as the form describes it. */
    val ctForm: NewContainer
        get() = ct.copy(
            vmid = number(NumberField.ID),
            diskGiB = number(NumberField.DISK),
            cores = number(NumberField.CORES),
            memoryMiB = number(NumberField.MEMORY),
            swapMiB = number(NumberField.SWAP),
            network = ct.network.copy(tag = optional(NumberField.VLAN)),
        )

    private val freeBytes: Long?
        get() = storages.firstOrNull { it.storage == (if (type == GuestType.QEMU) vm.storage else ct.storage) }?.avail

    val vmProblem: NewVm.Problem? get() = vmForm.problem(takenIds, hostCpus, freeBytes)
    val ctProblem: NewContainer.Problem? get() = ctForm.problem(takenIds, hostCpus, freeBytes)
    val passwordsDiffer: Boolean get() = type == GuestType.LXC && ct.password != passwordAgain

    val canCreate: Boolean
        get() = !loading && !busy && created == null && error == null &&
            (if (type == GuestType.QEMU) vmProblem == null else ctProblem == null && !passwordsDiffer)
}

/**
 * The creation wizard (questionnaire Q17): a short form with the choices most people need, and
 * "Advanced" for the rest. The checks are the model's (`NewVm`, `NewContainer`), so the form
 * explains a problem before Proxmox would refuse it.
 */
class CreateGuestViewModel(
    private val serverId: String,
    private val node: String,
    private val type: GuestType,
    private val guests: GuestRepository,
    private val hardware: HardwareRepository,
) : ViewModel() {

    private val ui = MutableStateFlow(CreateGuestState(type, node))
    val state: StateFlow<CreateGuestState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val next = async { guests.nextVmid(serverId) }
                    val taken = async { guests.usedVmids(serverId) }
                    val storages = async { guests.diskStorages(serverId, node, type) }
                    val bridges = async { optional { hardware.bridges(serverId, node) } }
                    val files = async {
                        optional { if (type == GuestType.QEMU) hardware.isoImages(serverId, node) else hardware.containerTemplates(serverId, node) }
                    }
                    val cpus = async { hardware.hostCpus(serverId, node) }
                    val id = next.await()
                    val takenIds = taken.await()
                    val hostCpus = cpus.await()
                    val storageList = storages.await()
                    val bridgeList = bridges.await()
                    val fileList = files.await()
                    val firstStorage = storageList.firstOrNull()?.storage.orEmpty()
                    val firstBridge = bridgeList.firstOrNull()?.iface ?: "vmbr0"
                    ui.update { s ->
                        val vm = s.vm.copy(storage = s.vm.storage.ifEmpty { firstStorage }, bridge = firstBridge)
                        val ct = s.ct.copy(
                            storage = s.ct.storage.ifEmpty { firstStorage },
                            template = s.ct.template.ifEmpty { fileList.firstOrNull()?.volid.orEmpty() },
                            network = s.ct.network.copy(bridge = firstBridge),
                        )
                        s.copy(
                            loading = false,
                            takenIds = takenIds,
                            hostCpus = hostCpus,
                            storages = storageList,
                            bridges = bridgeList,
                            files = fileList,
                            vm = vm,
                            ct = ct,
                            numbers = s.numbers.ifEmpty { defaults(id, vm, ct) },
                        )
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    private suspend fun <T> optional(load: suspend () -> List<T>): List<T> = try {
        load()
    } catch (_: ApiException) {
        emptyList()
    }

    private fun defaults(id: Int, vm: NewVm, ct: NewContainer): Map<NumberField, String> =
        if (type == GuestType.QEMU) {
            mapOf(
                NumberField.ID to id.toString(),
                NumberField.DISK to vm.diskGiB.toString(),
                NumberField.CORES to vm.cores.toString(),
                NumberField.SOCKETS to vm.sockets.toString(),
                NumberField.MEMORY to vm.memoryMiB.toString(),
                NumberField.VLAN to "",
            )
        } else {
            mapOf(
                NumberField.ID to id.toString(),
                NumberField.DISK to ct.diskGiB.toString(),
                NumberField.CORES to ct.cores.toString(),
                NumberField.MEMORY to ct.memoryMiB.toString(),
                NumberField.SWAP to ct.swapMiB.toString(),
                NumberField.VLAN to "",
            )
        }

    fun setNumber(field: NumberField, text: String) {
        ui.update { it.copy(numbers = it.numbers + (field to text.filter(Char::isDigit).take(9))) }
    }

    fun editVm(change: NewVm.() -> NewVm) {
        ui.update { it.copy(vm = it.vm.change()) }
    }

    fun editCt(change: NewContainer.() -> NewContainer) {
        ui.update { it.copy(ct = it.ct.change()) }
    }

    fun editCtNetwork(change: CtNetSettings.() -> CtNetSettings) {
        ui.update { it.copy(ct = it.ct.copy(network = it.ct.network.change())) }
    }

    fun setPasswordAgain(text: String) {
        ui.update { it.copy(passwordAgain = text) }
    }

    fun setAdvanced(on: Boolean) {
        ui.update { it.copy(advanced = on) }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    fun create() {
        val s = ui.value
        if (!s.canCreate) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val vmid = if (type == GuestType.QEMU) s.vmForm.vmid else s.ctForm.vmid
            var created: GuestRef? = null
            val result = try {
                val upid = if (type == GuestType.QEMU) guests.createVm(serverId, node, s.vmForm) else guests.createContainer(serverId, node, s.ctForm)
                val task = guests.awaitTask(serverId, Upid.parse(upid)?.node ?: node, upid)
                val label = TaskTypes.label(Upid.parse(upid)?.type.orEmpty())
                if (task.succeeded) {
                    created = GuestRef(serverId, node, type, vmid)
                    TaskResult(UiText.res(R.string.guest_task_ok, label), upid, ok = true)
                } else {
                    TaskResult(taskFailedText(label, task.exitstatus), upid, ok = false)
                }
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            // The password isn't needed any more once the container exists.
            ui.update {
                it.copy(
                    busy = false,
                    result = result,
                    created = created,
                    ct = if (created != null) it.ct.copy(password = "") else it.ct,
                    passwordAgain = if (created != null) "" else it.passwordAgain,
                )
            }
        }
    }
}
