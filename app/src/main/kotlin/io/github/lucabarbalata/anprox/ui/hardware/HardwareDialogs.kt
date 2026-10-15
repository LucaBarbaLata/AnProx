package io.github.lucabarbalata.anprox.ui.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassTextField
import io.github.lucabarbalata.anprox.core.designsystem.component.SegmentedPicker
import io.github.lucabarbalata.anprox.core.designsystem.component.SwitchRow
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.CtHardware
import io.github.lucabarbalata.anprox.model.pve.CtNetSettings
import io.github.lucabarbalata.anprox.model.pve.CtResources
import io.github.lucabarbalata.anprox.model.pve.CtVolumeOps
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.DiskBus
import io.github.lucabarbalata.anprox.model.pve.DiskSize
import io.github.lucabarbalata.anprox.model.pve.NewMountPoint
import io.github.lucabarbalata.anprox.model.pve.NewVmDisk
import io.github.lucabarbalata.anprox.model.pve.NodeNetwork
import io.github.lucabarbalata.anprox.model.pve.NodeStorage
import io.github.lucabarbalata.anprox.model.pve.StorageContentItem
import io.github.lucabarbalata.anprox.model.pve.VmCpuMemory
import io.github.lucabarbalata.anprox.model.pve.VmDisk
import io.github.lucabarbalata.anprox.model.pve.VmDiskOps
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import io.github.lucabarbalata.anprox.model.pve.VmNet
import io.github.lucabarbalata.anprox.model.pve.VmNetSettings
import io.github.lucabarbalata.anprox.ui.common.BridgePicker
import io.github.lucabarbalata.anprox.ui.common.ChoiceChips
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.FormHint
import io.github.lucabarbalata.anprox.ui.common.FormLabel
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.NumberField
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.StoragePicker

/** The dialog [HardwareState.dialog] asks for, if any. */
@Composable
internal fun HardwareDialogs(state: HardwareState, viewModel: HardwareViewModel) {
    val dialog = state.dialog ?: return
    val vm = state.vm
    val ct = state.ct
    val dismiss: () -> Unit = viewModel::dismiss
    when (dialog) {
        HardwareDialog.CpuMemory -> if (vm != null) CpuMemoryDialog(vm, state.hostCpus, state.running, viewModel::apply, dismiss)
        HardwareDialog.Resources -> if (ct != null) CtResourcesDialog(ct, state.hostCpus, viewModel::apply, dismiss)
        HardwareDialog.AddDisk -> if (vm != null) AddDiskDialog(vm, state.storages, viewModel::addVmDisk, dismiss)
        HardwareDialog.AddMountPoint -> if (ct != null) AddMountPointDialog(ct, state.storages, viewModel::apply, dismiss)
        HardwareDialog.BootOrder -> if (vm != null) {
            BootOrderDialog(vm, state.running, { order -> viewModel.apply(VmDiskOps.bootOrder(order, vm.digest)) }, dismiss)
        }
        is HardwareDialog.Volume -> VolumeActionsDialog(dialog.key, state, viewModel::open, dismiss)
        is HardwareDialog.Grow -> GrowDialog(dialog.key, state, viewModel::resize, dismiss)
        is HardwareDialog.Move -> MoveDialog(dialog.key, state, viewModel::move, dismiss)
        is HardwareDialog.Detach -> ConfirmActionDialog(
            title = stringResource(R.string.hardware_detach_title),
            message = stringResource(
                if (vm != null) R.string.hardware_detach_body_vm else R.string.hardware_detach_body_ct,
                volumeName(dialog.key, state),
            ),
            confirmLabel = stringResource(R.string.hardware_detach),
            danger = DangerLevel.CONFIRM,
            onConfirm = { viewModel.apply(removeParams(dialog.key, state)) },
            onDismiss = dismiss,
        )
        is HardwareDialog.Destroy -> ConfirmActionDialog(
            title = stringResource(R.string.hardware_destroy_title),
            message = stringResource(R.string.hardware_destroy_body, volumeName(dialog.key, state), dialog.key),
            confirmLabel = stringResource(R.string.hardware_destroy),
            danger = DangerLevel.TYPE_TO_CONFIRM,
            confirmText = dialog.key,
            onConfirm = { viewModel.apply(removeParams(dialog.key, state)) },
            onDismiss = dismiss,
        )
        is HardwareDialog.Attach -> if (vm != null) {
            vm.disk(dialog.key)?.let { disk -> AttachDialog(disk, vm, viewModel::apply, dismiss) }
        }
        is HardwareDialog.Drive -> if (vm != null) {
            DriveDialog(dialog.key?.let(vm::disk), vm, state.isos, viewModel::apply, dismiss)
        }
        is HardwareDialog.Net -> when {
            vm != null -> VmNetDialog(vm.nets.firstOrNull { it.key == dialog.key }, vm, state.bridges, viewModel::apply, viewModel::open, dismiss)
            ct != null -> CtNetDialog(dialog.key, ct, state.bridges, viewModel::apply, viewModel::open, dismiss)
        }
        is HardwareDialog.RemoveNet -> ConfirmActionDialog(
            title = stringResource(R.string.hardware_remove_net_title, dialog.key),
            message = stringResource(R.string.hardware_remove_net_body),
            confirmLabel = stringResource(R.string.hardware_remove),
            danger = DangerLevel.CONFIRM,
            onConfirm = { viewModel.apply(mapOfNotNull("delete" to dialog.key, "digest" to state.digest)) },
            onDismiss = dismiss,
        )
    }
}

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

private fun removeParams(key: String, state: HardwareState): Map<String, String> = state.vm?.disk(key)?.let { VmDiskOps.remove(it, state.digest) }
    ?: state.ct?.volume(key)?.let { CtVolumeOps.remove(it, state.digest) }
    ?: emptyMap()

private fun volumeName(key: String, state: HardwareState): String =
    state.vm?.disk(key)?.volume ?: state.ct?.volume(key)?.volume ?: key

// ------------------------------------------------------------------------------------ shared pieces

@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extraButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = AnProxTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
        },
        dismissButton = {
            Row {
                extraButton?.invoke()
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

// ------------------------------------------------------------------------------------ VM processor and memory

@Composable
private fun CpuMemoryDialog(
    hw: VmHardware,
    hostCpus: Int?,
    running: Boolean,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember(hw) { VmCpuMemory.from(hw) }
    var sockets by rememberSaveable { mutableStateOf(start.sockets.toString()) }
    var cores by rememberSaveable { mutableStateOf(start.cores.toString()) }
    var cpuType by rememberSaveable { mutableStateOf(start.cpuType) }
    var memory by rememberSaveable { mutableStateOf(start.memoryMiB.toString()) }
    var ballooning by rememberSaveable { mutableStateOf(start.ballooning) }
    var minMemory by rememberSaveable { mutableStateOf(start.minMemoryMiB.toString()) }
    val form = VmCpuMemory(
        sockets = sockets.toIntOrNull() ?: 0,
        cores = cores.toIntOrNull() ?: 0,
        cpuType = cpuType,
        memoryMiB = memory.toIntOrNull() ?: 0,
        ballooning = ballooning,
        minMemoryMiB = minMemory.toIntOrNull() ?: -1,
    )
    val problem = form.problem(hostCpus)
    val changes = if (problem == null) form.changes(hw) else emptyMap()
    val defaultLabel = stringResource(R.string.hardware_cpu_default)
    val types = listOf<String?>(null) + (VmHardware.CPU_TYPES + listOfNotNull(start.cpuType)).distinct()
    FormDialog(
        title = stringResource(R.string.hardware_cpu_memory),
        confirmLabel = stringResource(R.string.action_save),
        confirmEnabled = problem == null && changes.isNotEmpty(),
        onConfirm = { onSave(changes) },
        onDismiss = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                NumberField(sockets, { sockets = it }, stringResource(R.string.hardware_sockets), isError = problem == VmCpuMemory.Problem.SOCKETS)
            }
            Column(Modifier.weight(1f)) {
                NumberField(cores, { cores = it }, stringResource(R.string.hardware_cores_per_socket), isError = problem == VmCpuMemory.Problem.CORES)
            }
        }
        FormHint(
            when (problem) {
                VmCpuMemory.Problem.TOO_MANY_CPUS -> stringResource(R.string.hardware_too_many_cpus, hostCpus ?: 0)
                VmCpuMemory.Problem.SOCKETS -> stringResource(R.string.hardware_sockets_error, VmCpuMemory.MAX_SOCKETS)
                else -> stringResource(R.string.hardware_vcpus, form.sockets * form.cores)
            },
            error = problem == VmCpuMemory.Problem.TOO_MANY_CPUS || problem == VmCpuMemory.Problem.SOCKETS,
        )
        FormLabel(stringResource(R.string.hardware_cpu_type))
        ChoiceChips(types, cpuType, { it ?: defaultLabel }, { cpuType = it })
        FormHint(stringResource(R.string.hardware_cpu_type_help))
        NumberField(
            memory,
            { memory = it },
            stringResource(R.string.hardware_memory_mib),
            supportingText = if (problem == VmCpuMemory.Problem.MEMORY) {
                stringResource(R.string.hardware_memory_error, VmCpuMemory.MIN_MEMORY_MIB)
            } else {
                memory.toIntOrNull()?.let { mib(it) }
            },
            isError = problem == VmCpuMemory.Problem.MEMORY,
        )
        SwitchRow(
            title = stringResource(R.string.hardware_ballooning),
            description = stringResource(R.string.hardware_ballooning_body),
            checked = ballooning,
            onCheckedChange = { ballooning = it },
        )
        if (ballooning) {
            NumberField(
                minMemory,
                { minMemory = it },
                stringResource(R.string.hardware_min_memory_mib),
                supportingText = if (problem == VmCpuMemory.Problem.MIN_MEMORY) {
                    stringResource(R.string.hardware_min_memory_error)
                } else {
                    minMemory.toIntOrNull()?.let { mib(it) }
                },
                isError = problem == VmCpuMemory.Problem.MIN_MEMORY,
            )
        }
        if (running) FormHint(stringResource(R.string.hardware_cold_plug_note))
    }
}

// ------------------------------------------------------------------------------------ container resources

@Composable
private fun CtResourcesDialog(hw: CtHardware, hostCpus: Int?, onSave: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val start = remember(hw) { CtResources.from(hw) }
    var unlimited by rememberSaveable { mutableStateOf(start.cores == null) }
    var cores by rememberSaveable { mutableStateOf((start.cores ?: hostCpus ?: 1).toString()) }
    var memory by rememberSaveable { mutableStateOf(start.memoryMiB.toString()) }
    var swap by rememberSaveable { mutableStateOf(start.swapMiB.toString()) }
    val form = CtResources(
        cores = if (unlimited) null else cores.toIntOrNull() ?: 0,
        memoryMiB = memory.toIntOrNull() ?: 0,
        swapMiB = swap.toIntOrNull() ?: -1,
    )
    val problem = form.problem(hostCpus)
    val changes = if (problem == null) form.changes(hw) else emptyMap()
    FormDialog(
        title = stringResource(R.string.hardware_resources),
        confirmLabel = stringResource(R.string.action_save),
        confirmEnabled = problem == null && changes.isNotEmpty(),
        onConfirm = { onSave(changes) },
        onDismiss = onDismiss,
    ) {
        SwitchRow(
            title = stringResource(R.string.hardware_cores_unlimited),
            description = stringResource(R.string.hardware_cores_unlimited_body),
            checked = unlimited,
            onCheckedChange = { unlimited = it },
        )
        if (!unlimited) {
            NumberField(
                cores,
                { cores = it },
                stringResource(R.string.hardware_cores),
                supportingText = if (problem == CtResources.Problem.CORES) stringResource(R.string.hardware_cores_error, hostCpus ?: 1) else null,
                isError = problem == CtResources.Problem.CORES,
            )
        }
        NumberField(
            memory,
            { memory = it },
            stringResource(R.string.hardware_memory_mib),
            supportingText = if (problem == CtResources.Problem.MEMORY) {
                stringResource(R.string.hardware_memory_error, VmCpuMemory.MIN_MEMORY_MIB)
            } else {
                memory.toIntOrNull()?.let { mib(it) }
            },
            isError = problem == CtResources.Problem.MEMORY,
        )
        NumberField(swap, { swap = it }, stringResource(R.string.hardware_swap_mib), supportingText = swap.toIntOrNull()?.let { mib(it) })
        FormHint(stringResource(R.string.hardware_ct_live_note))
    }
}

// ------------------------------------------------------------------------------------ disks and volumes

@Composable
private fun AddDiskDialog(hw: VmHardware, storages: List<NodeStorage>?, onAdd: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var bus by rememberSaveable { mutableStateOf(DiskBus.SCSI) }
    var storage by rememberSaveable { mutableStateOf<String?>(null) }
    var size by rememberSaveable { mutableStateOf("32") }
    var discard by rememberSaveable { mutableStateOf(true) }
    var iothread by rememberSaveable { mutableStateOf(true) }
    var ssd by rememberSaveable { mutableStateOf(false) }
    val chosen = storages?.firstOrNull { it.storage == storage } ?: storages?.firstOrNull()
    val form = NewVmDisk(bus, chosen?.storage.orEmpty(), size.toIntOrNull() ?: 0, iothread, discard, ssd)
    val problem = form.problem(hw, chosen?.avail)
    FormDialog(
        title = stringResource(R.string.hardware_add_disk),
        confirmLabel = stringResource(R.string.hardware_add),
        confirmEnabled = problem == null,
        onConfirm = { onAdd(form.params(hw)) },
        onDismiss = onDismiss,
    ) {
        FormLabel(stringResource(R.string.hardware_bus))
        SegmentedPicker(DiskBus.entries, bus, ::busName, { bus = it })
        FormHint(
            if (problem == NewVmDisk.Problem.NO_SLOT) {
                stringResource(R.string.hardware_bus_full, busName(bus))
            } else {
                stringResource(R.string.hardware_bus_help)
            },
            error = problem == NewVmDisk.Problem.NO_SLOT,
        )
        FormLabel(stringResource(R.string.hardware_storage))
        StoragePicker(storages, chosen?.storage, { storage = it })
        NumberField(
            size,
            { size = it },
            stringResource(R.string.hardware_size_gib),
            supportingText = if (problem == NewVmDisk.Problem.SIZE) stringResource(R.string.hardware_size_error, Formatters.bytes(chosen?.avail)) else null,
            isError = problem == NewVmDisk.Problem.SIZE,
        )
        SwitchRow(stringResource(R.string.hardware_discard), discard, { discard = it }, description = stringResource(R.string.hardware_discard_body))
        if (hw.supportsIothread(bus)) {
            SwitchRow(stringResource(R.string.hardware_iothread), iothread, { iothread = it }, description = stringResource(R.string.hardware_iothread_body))
        }
        if (bus.supportsSsd) {
            SwitchRow(stringResource(R.string.hardware_ssd), ssd, { ssd = it }, description = stringResource(R.string.hardware_ssd_body))
        }
    }
}

@Composable
private fun AddMountPointDialog(hw: CtHardware, storages: List<NodeStorage>?, onAdd: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var storage by rememberSaveable { mutableStateOf<String?>(null) }
    var size by rememberSaveable { mutableStateOf("8") }
    var path by rememberSaveable { mutableStateOf("/mnt/data") }
    var backup by rememberSaveable { mutableStateOf(true) }
    val chosen = storages?.firstOrNull { it.storage == storage } ?: storages?.firstOrNull()
    val form = NewMountPoint(chosen?.storage.orEmpty(), size.toIntOrNull() ?: 0, path, backup)
    val problem = form.problem(hw, chosen?.avail)
    FormDialog(
        title = stringResource(R.string.hardware_add_mount_point),
        confirmLabel = stringResource(R.string.hardware_add),
        confirmEnabled = problem == null,
        onConfirm = { onAdd(form.params(hw)) },
        onDismiss = onDismiss,
    ) {
        GlassTextField(
            value = path,
            onValueChange = { path = it.trim() },
            label = stringResource(R.string.hardware_mount_path),
            supportingText = when (problem) {
                NewMountPoint.Problem.PATH -> stringResource(R.string.hardware_mount_path_error)
                NewMountPoint.Problem.PATH_IN_USE -> stringResource(R.string.hardware_mount_path_in_use)
                else -> stringResource(R.string.hardware_mount_path_help)
            },
            isError = problem == NewMountPoint.Problem.PATH || problem == NewMountPoint.Problem.PATH_IN_USE,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        FormLabel(stringResource(R.string.hardware_storage))
        StoragePicker(storages, chosen?.storage, { storage = it })
        NumberField(
            size,
            { size = it },
            stringResource(R.string.hardware_size_gib),
            supportingText = if (problem == NewMountPoint.Problem.SIZE) stringResource(R.string.hardware_size_error, Formatters.bytes(chosen?.avail)) else null,
            isError = problem == NewMountPoint.Problem.SIZE,
        )
        SwitchRow(stringResource(R.string.hardware_in_backups), backup, { backup = it }, description = stringResource(R.string.hardware_in_backups_body))
    }
}

/** What can be done with a disk, drive, volume or unused volume. */
@Composable
private fun VolumeActionsDialog(key: String, state: HardwareState, open: (HardwareDialog) -> Unit, onDismiss: () -> Unit) {
    val c = AnProxTheme.colors
    val vmDisk = state.vm?.disk(key)
    val ctVolume = state.ct?.volume(key)
    val unused = vmDisk?.kind == VmDisk.Kind.UNUSED || ctVolume?.isUnused == true
    val title = when {
        vmDisk != null -> diskTitle(vmDisk)
        ctVolume != null && ctVolume.isUnused -> stringResource(R.string.hardware_unused, key.removePrefix("unused"))
        ctVolume != null -> volumeTitle(ctVolume)
        else -> key
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid,
        title = { Text(title) },
        text = {
            Column {
                Text(vmDisk?.volume ?: ctVolume?.volume.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                if (unused) {
                    if (vmDisk != null) {
                        MenuRow(Icons.Outlined.Link, stringResource(R.string.hardware_attach), stringResource(R.string.hardware_attach_body)) {
                            open(HardwareDialog.Attach(key))
                        }
                    }
                    MenuRow(Icons.Outlined.DeleteOutline, stringResource(R.string.hardware_destroy), stringResource(R.string.hardware_destroy_row_body)) {
                        open(HardwareDialog.Destroy(key))
                    }
                } else {
                    MenuRow(Icons.Outlined.OpenInFull, stringResource(R.string.hardware_grow), stringResource(R.string.hardware_grow_body)) {
                        open(HardwareDialog.Grow(key))
                    }
                    MenuRow(Icons.Outlined.SwapVert, stringResource(R.string.hardware_move), stringResource(R.string.hardware_move_body)) {
                        open(HardwareDialog.Move(key))
                    }
                    if (ctVolume?.isRoot != true) {
                        MenuRow(Icons.Outlined.LinkOff, stringResource(R.string.hardware_detach), stringResource(R.string.hardware_detach_row_body)) {
                            open(HardwareDialog.Detach(key))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun GrowDialog(key: String, state: HardwareState, onGrow: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val vmDisk = state.vm?.disk(key)
    val ctVolume = state.ct?.volume(key)
    val current = vmDisk?.sizeBytes ?: ctVolume?.sizeBytes
    var add by rememberSaveable { mutableStateOf("8") }
    val gib = add.toIntOrNull() ?: 0
    FormDialog(
        title = stringResource(R.string.hardware_grow_title, vmDisk?.let { busLabel(it.key) } ?: ctVolume?.let { volumeTitle(it) } ?: key),
        confirmLabel = stringResource(R.string.hardware_grow),
        confirmEnabled = gib >= 1,
        onConfirm = {
            val params = vmDisk?.let { VmDiskOps.resize(it, gib, state.digest) } ?: ctVolume?.let { CtVolumeOps.resize(it, gib, state.digest) }
            params?.let(onGrow)
        },
        onDismiss = onDismiss,
    ) {
        NumberField(
            add,
            { add = it },
            stringResource(R.string.hardware_grow_by_gib),
            supportingText = current?.let { stringResource(R.string.hardware_grow_result, Formatters.bytes(it), Formatters.bytes(it + gib * DiskSize.GIB)) },
        )
        FormHint(stringResource(if (vmDisk != null) R.string.hardware_grow_note_vm else R.string.hardware_grow_note_ct))
    }
}

@Composable
private fun MoveDialog(key: String, state: HardwareState, onMove: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val vmDisk = state.vm?.disk(key)
    val ctVolume = state.ct?.volume(key)
    val currentStorage = vmDisk?.storage ?: ctVolume?.storage
    var storage by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteSource by rememberSaveable { mutableStateOf(true) }
    val choices = state.storages?.filter { it.storage != currentStorage }
    val chosen = choices?.firstOrNull { it.storage == storage } ?: choices?.firstOrNull()
    // Proxmox moves container volumes only while the container is stopped.
    val blocked = ctVolume != null && state.running
    FormDialog(
        title = stringResource(R.string.hardware_move_title, vmDisk?.let { busLabel(it.key) } ?: ctVolume?.let { volumeTitle(it) } ?: key),
        confirmLabel = stringResource(R.string.hardware_move),
        confirmEnabled = chosen != null && !blocked,
        onConfirm = {
            chosen?.storage?.let { target ->
                val params = vmDisk?.let { VmDiskOps.move(it, target, deleteSource, state.digest) }
                    ?: ctVolume?.let { CtVolumeOps.move(it, target, deleteSource, state.digest) }
                params?.let(onMove)
            }
        },
        onDismiss = onDismiss,
    ) {
        if (blocked) FormHint(stringResource(R.string.hardware_move_ct_running), error = true)
        FormHint(stringResource(R.string.hardware_move_from, currentStorage ?: "—"))
        StoragePicker(state.storages, chosen?.storage, { storage = it }, exclude = currentStorage)
        SwitchRow(
            title = stringResource(R.string.hardware_move_delete),
            description = stringResource(R.string.hardware_move_delete_body),
            checked = deleteSource,
            onCheckedChange = { deleteSource = it },
        )
        if (vmDisk != null && state.running) FormHint(stringResource(R.string.hardware_move_live))
    }
}

@Composable
private fun AttachDialog(disk: VmDisk, hw: VmHardware, onAttach: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    val buses = DiskBus.entries.filter { hw.freeSlot(it) != null }
    var bus by rememberSaveable { mutableStateOf(buses.firstOrNull() ?: DiskBus.SCSI) }
    FormDialog(
        title = stringResource(R.string.hardware_attach),
        confirmLabel = stringResource(R.string.hardware_attach),
        confirmEnabled = hw.freeSlot(bus) != null,
        onConfirm = { onAttach(VmDiskOps.attach(disk, bus, hw)) },
        onDismiss = onDismiss,
    ) {
        FormHint(disk.volume)
        FormLabel(stringResource(R.string.hardware_bus))
        SegmentedPicker(buses.ifEmpty { listOf(DiskBus.SCSI) }, bus, ::busName, { bus = it })
        hw.freeSlot(bus)?.let { FormHint(stringResource(R.string.hardware_attach_as, busLabel(it))) }
    }
}

// ------------------------------------------------------------------------------------ CD/DVD

@Composable
private fun DriveDialog(
    drive: VmDisk?,
    hw: VmHardware,
    isos: List<StorageContentItem>?,
    onApply: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    val current = if (drive != null && drive.storage != null) drive.volume else null
    var selected by rememberSaveable { mutableStateOf(current) }
    FormDialog(
        title = drive?.let { busLabel(it.key) } ?: stringResource(R.string.hardware_add_drive),
        confirmLabel = stringResource(if (drive == null) R.string.hardware_add else R.string.action_save),
        confirmEnabled = drive == null || selected != current,
        onConfirm = { onApply(if (drive == null) VmDiskOps.addDrive(hw, selected) else VmDiskOps.insert(drive, selected, hw.digest)) },
        onDismiss = onDismiss,
        extraButton = if (drive != null) {
            {
                TextButton(onClick = { onApply(VmDiskOps.remove(drive, hw.digest)) }) {
                    Text(stringResource(R.string.hardware_remove_drive), color = c.danger)
                }
            }
        } else {
            null
        },
    ) {
        RadioRow(selected = selected == null, title = stringResource(R.string.hardware_drive_empty), body = stringResource(R.string.hardware_drive_empty_body)) {
            selected = null
        }
        when {
            isos == null -> CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.dp)
            isos.isEmpty() -> FormHint(stringResource(R.string.hardware_no_isos))
            else -> isos.forEach { iso ->
                RadioRow(
                    selected = selected == iso.volid,
                    title = iso.volid.substringAfterLast('/'),
                    body = "${iso.volid.substringBefore(':')} · ${Formatters.bytes(iso.size)}",
                ) { selected = iso.volid }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ network

@Composable
private fun VmNetDialog(
    net: VmNet?,
    hw: VmHardware,
    bridges: List<NodeNetwork>?,
    onApply: (Map<String, String>) -> Unit,
    open: (HardwareDialog) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    val start = remember(net) { net?.let(VmNetSettings::from) ?: VmNetSettings() }
    var model by rememberSaveable { mutableStateOf(start.model) }
    var bridge by rememberSaveable { mutableStateOf(start.bridge.ifBlank { bridges?.firstOrNull()?.iface ?: "vmbr0" }) }
    var tag by rememberSaveable { mutableStateOf(start.tag?.toString().orEmpty()) }
    var firewall by rememberSaveable { mutableStateOf(start.firewall) }
    var linkDown by rememberSaveable { mutableStateOf(start.linkDown) }
    var rate by rememberSaveable { mutableStateOf(start.rateMBps?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()) }
    val form = VmNetSettings(
        model = model,
        bridge = bridge,
        tag = tag.toIntOrNull(),
        firewall = firewall,
        linkDown = linkDown,
        rateMBps = rate.toDoubleOrNull(),
    )
    val problem = form.problem()
    val params = if (problem == null) form.params(hw, net) else emptyMap()
    FormDialog(
        title = net?.key ?: stringResource(R.string.hardware_add_net),
        confirmLabel = stringResource(if (net == null) R.string.hardware_add else R.string.action_save),
        confirmEnabled = problem == null && params.isNotEmpty(),
        onConfirm = { onApply(params) },
        onDismiss = onDismiss,
        extraButton = if (net != null) {
            { TextButton(onClick = { open(HardwareDialog.RemoveNet(net.key)) }) { Text(stringResource(R.string.hardware_remove), color = c.danger) } }
        } else {
            null
        },
    ) {
        net?.mac?.let { FormHint(stringResource(R.string.hardware_mac, it)) }
        FormLabel(stringResource(R.string.hardware_model))
        ChoiceChips(VmNet.MODELS, model, { NET_MODEL_NAMES[it] ?: it }, { model = it })
        BridgePicker(bridges, bridge) { bridge = it }
        NumberField(
            tag,
            { tag = it },
            stringResource(R.string.hardware_vlan),
            supportingText = stringResource(if (problem == VmNetSettings.Problem.TAG) R.string.hardware_vlan_error else R.string.hardware_vlan_help),
            isError = problem == VmNetSettings.Problem.TAG,
        )
        SwitchRow(stringResource(R.string.hardware_firewall), firewall, { firewall = it }, description = stringResource(R.string.hardware_firewall_body))
        SwitchRow(stringResource(R.string.hardware_link_down), linkDown, { linkDown = it }, description = stringResource(R.string.hardware_link_down_body))
        GlassTextField(
            value = rate,
            onValueChange = { v -> rate = v.filter { it.isDigit() || it == '.' }.take(8) },
            label = stringResource(R.string.hardware_rate),
            supportingText = stringResource(if (problem == VmNetSettings.Problem.RATE) R.string.hardware_rate_error else R.string.hardware_rate_help),
            isError = problem == VmNetSettings.Problem.RATE,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

@Composable
private fun CtNetDialog(
    key: String?,
    hw: CtHardware,
    bridges: List<NodeNetwork>?,
    onApply: (Map<String, String>) -> Unit,
    open: (HardwareDialog) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AnProxTheme.colors
    val net = hw.nets.firstOrNull { it.key == key }
    val start = remember(net) { net?.let(CtNetSettings::from) ?: CtNetSettings(name = hw.suggestNetName()) }
    var name by rememberSaveable { mutableStateOf(start.name) }
    var bridge by rememberSaveable { mutableStateOf(start.bridge.ifBlank { bridges?.firstOrNull()?.iface ?: "vmbr0" }) }
    var ipv4 by rememberSaveable { mutableStateOf(start.ipv4) }
    var ip by rememberSaveable { mutableStateOf(start.ip) }
    var gateway by rememberSaveable { mutableStateOf(start.gateway) }
    var ipv6 by rememberSaveable { mutableStateOf(start.ipv6) }
    var ip6 by rememberSaveable { mutableStateOf(start.ip6) }
    var gateway6 by rememberSaveable { mutableStateOf(start.gateway6) }
    var tag by rememberSaveable { mutableStateOf(start.tag?.toString().orEmpty()) }
    var firewall by rememberSaveable { mutableStateOf(start.firewall) }
    val form = CtNetSettings(name, bridge, ipv4, ip, gateway, ipv6, ip6, gateway6, tag.toIntOrNull(), firewall)
    val problem = form.problem(hw, net)
    val params = if (problem == null) form.params(hw, net) else emptyMap()
    val v4Labels = mapOf(
        CtNetSettings.Ipv4Mode.DHCP to stringResource(R.string.hardware_ipv4_dhcp),
        CtNetSettings.Ipv4Mode.STATIC to stringResource(R.string.hardware_ip_static),
        CtNetSettings.Ipv4Mode.NONE to stringResource(R.string.hardware_ip_none),
    )
    val v6Labels = mapOf(
        CtNetSettings.Ipv6Mode.NONE to stringResource(R.string.hardware_ip_none),
        CtNetSettings.Ipv6Mode.DHCP to stringResource(R.string.hardware_ipv6_dhcp),
        CtNetSettings.Ipv6Mode.SLAAC to stringResource(R.string.hardware_ipv6_slaac),
        CtNetSettings.Ipv6Mode.STATIC to stringResource(R.string.hardware_ip_static),
    )
    FormDialog(
        title = net?.name?.ifBlank { null } ?: key ?: stringResource(R.string.hardware_add_net),
        confirmLabel = stringResource(if (net == null) R.string.hardware_add else R.string.action_save),
        confirmEnabled = problem == null && params.isNotEmpty(),
        onConfirm = { onApply(params) },
        onDismiss = onDismiss,
        extraButton = if (net != null) {
            { TextButton(onClick = { open(HardwareDialog.RemoveNet(net.key)) }) { Text(stringResource(R.string.hardware_remove), color = c.danger) } }
        } else {
            null
        },
    ) {
        GlassTextField(
            value = name,
            onValueChange = { name = it.trim() },
            label = stringResource(R.string.hardware_net_name),
            supportingText = when (problem) {
                CtNetSettings.Problem.NAME -> stringResource(R.string.hardware_net_name_error)
                CtNetSettings.Problem.NAME_IN_USE -> stringResource(R.string.hardware_net_name_in_use)
                else -> null
            },
            isError = problem == CtNetSettings.Problem.NAME || problem == CtNetSettings.Problem.NAME_IN_USE,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        BridgePicker(bridges, bridge) { bridge = it }
        FormLabel(stringResource(R.string.hardware_ipv4))
        SegmentedPicker(CtNetSettings.Ipv4Mode.entries, ipv4, { v4Labels.getValue(it) }, { ipv4 = it })
        if (ipv4 == CtNetSettings.Ipv4Mode.STATIC) {
            GlassTextField(
                value = ip,
                onValueChange = { ip = it.trim() },
                label = stringResource(R.string.hardware_ipv4_address),
                supportingText = stringResource(if (problem == CtNetSettings.Problem.IP) R.string.hardware_ipv4_error else R.string.hardware_ipv4_help),
                isError = problem == CtNetSettings.Problem.IP,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            GlassTextField(
                value = gateway,
                onValueChange = { gateway = it.trim() },
                label = stringResource(R.string.hardware_gateway),
                supportingText = if (problem == CtNetSettings.Problem.GATEWAY) stringResource(R.string.hardware_gateway_error) else null,
                isError = problem == CtNetSettings.Problem.GATEWAY,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        FormLabel(stringResource(R.string.hardware_ipv6))
        SegmentedPicker(CtNetSettings.Ipv6Mode.entries, ipv6, { v6Labels.getValue(it) }, { ipv6 = it })
        if (ipv6 == CtNetSettings.Ipv6Mode.STATIC) {
            GlassTextField(
                value = ip6,
                onValueChange = { ip6 = it.trim() },
                label = stringResource(R.string.hardware_ipv6_address),
                supportingText = stringResource(if (problem == CtNetSettings.Problem.IP6) R.string.hardware_ipv6_error else R.string.hardware_ipv6_help),
                isError = problem == CtNetSettings.Problem.IP6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            GlassTextField(
                value = gateway6,
                onValueChange = { gateway6 = it.trim() },
                label = stringResource(R.string.hardware_gateway6),
                supportingText = if (problem == CtNetSettings.Problem.GATEWAY6) stringResource(R.string.hardware_gateway_error) else null,
                isError = problem == CtNetSettings.Problem.GATEWAY6,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        NumberField(
            tag,
            { tag = it },
            stringResource(R.string.hardware_vlan),
            supportingText = stringResource(if (problem == CtNetSettings.Problem.TAG) R.string.hardware_vlan_error else R.string.hardware_vlan_help),
            isError = problem == CtNetSettings.Problem.TAG,
        )
        SwitchRow(stringResource(R.string.hardware_firewall), firewall, { firewall = it }, description = stringResource(R.string.hardware_firewall_body))
    }
}

// ------------------------------------------------------------------------------------ boot order

@Composable
private fun BootOrderDialog(hw: VmHardware, running: Boolean, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val c = AnProxTheme.colors
    // Enabled devices in boot order first, then the others; each with an on/off flag.
    val entries = remember(hw) {
        (hw.bootOrder.filter { it in hw.bootable }.map { it to true } + hw.bootable.filter { it !in hw.bootOrder }.map { it to false })
            .toMutableStateList()
    }
    val order = entries.filter { it.second }.map { it.first }
    FormDialog(
        title = stringResource(R.string.hardware_boot_order),
        confirmLabel = stringResource(R.string.action_save),
        confirmEnabled = order.isNotEmpty() && order != hw.bootOrder,
        onConfirm = { onSave(order) },
        onDismiss = onDismiss,
    ) {
        FormHint(stringResource(R.string.hardware_boot_help))
        entries.forEachIndexed { index, (key, on) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = on, onCheckedChange = { entries[index] = key to it })
                Text(
                    bootLabel(hw, key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (on) c.textPrimary else c.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { entries.swap(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.hardware_boot_up, key))
                }
                IconButton(onClick = { entries.swap(index, index + 1) }, enabled = index < entries.lastIndex) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.hardware_boot_down, key))
                }
            }
        }
        if (running) FormHint(stringResource(R.string.hardware_cold_plug_note))
    }
}

private fun <T> MutableList<T>.swap(a: Int, b: Int) {
    if (a !in indices || b !in indices) return
    val tmp = this[a]
    this[a] = this[b]
    this[b] = tmp
}
