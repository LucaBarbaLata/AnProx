package io.github.lucabarbalata.anprox.ui.hardware

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.CtHardware
import io.github.lucabarbalata.anprox.model.pve.CtNet
import io.github.lucabarbalata.anprox.model.pve.CtVolume
import io.github.lucabarbalata.anprox.model.pve.DiskBus
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.VmDisk
import io.github.lucabarbalata.anprox.model.pve.VmHardware
import io.github.lucabarbalata.anprox.model.pve.VmNet
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private const val MIB = 1024L * 1024

/**
 * A VM's or container's hardware (questionnaire Q19, common settings first): processor and
 * memory, disks, CD/DVD drives, network devices and boot order for VMs; resources, root disk,
 * mount points and network for containers. Tapping a row opens what can be done with it.
 */
@Composable
fun HardwareScreen(
    ref: GuestRef,
    guestName: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
) {
    val key = "hardware-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    val viewModel: HardwareViewModel = koinViewModel(key = key) { parametersOf(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask, viewModel::consumeResult)

    val isVm = ref.type == GuestType.QEMU
    val typeLabel = stringResource(if (isVm) R.string.guest_type_vm else R.string.guest_type_ct)
    AnProxScaffold(
        title = stringResource(R.string.hardware_title),
        subtitle = "$guestName · $typeLabel ${ref.vmid}",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::reload, enabled = !state.loading && state.busy == null) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(contentPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::reload)
            }
            if (state.pending.isNotEmpty()) {
                ErrorBanner(
                    stringResource(R.string.hardware_pending_banner),
                    tone = Tone.WARNING,
                )
            }
            state.busy?.let { BusyCard(it.asString()) }
            val vm = state.vm
            val ct = state.ct
            when {
                vm != null -> VmSections(vm, state, viewModel::open)
                ct != null -> CtSections(ct, state, viewModel::open)
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AnProxTheme.colors.accent)
                }
            }
        }
    }

    HardwareDialogs(state, viewModel)
}

// ------------------------------------------------------------------------------------ VMs

@Composable
private fun VmSections(hw: VmHardware, state: HardwareState, open: (HardwareDialog) -> Unit) {
    val enabled = state.busy == null
    val pending = hw.pending
    SectionHeader(stringResource(R.string.hardware_cpu_memory))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        HardwareRow(
            Icons.Outlined.DeveloperBoard,
            stringResource(R.string.hardware_processor),
            stringResource(
                R.string.hardware_processor_value,
                hw.vcpus,
                hw.sockets,
                hw.cores,
                hw.cpuType ?: stringResource(R.string.hardware_cpu_default),
            ),
            pending = listOf("sockets", "cores", "cpu").any { it in pending },
            enabled = enabled,
        ) { open(HardwareDialog.CpuMemory) }
        HardwareRow(
            Icons.Outlined.Memory,
            stringResource(R.string.hardware_memory),
            when (hw.balloonMiB) {
                0 -> stringResource(R.string.hardware_memory_fixed, mib(hw.memoryMiB))
                null -> stringResource(R.string.hardware_memory_balloon, mib(hw.memoryMiB), mib(hw.memoryMiB))
                else -> stringResource(R.string.hardware_memory_balloon, mib(hw.memoryMiB), mib(hw.balloonMiB))
            },
            pending = "memory" in pending || "balloon" in pending,
            enabled = enabled,
        ) { open(HardwareDialog.CpuMemory) }
    }

    SectionHeader(stringResource(R.string.hardware_disks))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        hw.hardDisks.forEach { disk ->
            HardwareRow(Icons.Outlined.Storage, diskTitle(disk), diskBody(disk), disk.key in pending, enabled) {
                open(HardwareDialog.Volume(disk.key))
            }
        }
        hw.system.forEach { disk ->
            HardwareRow(Icons.Outlined.Security, diskTitle(disk), disk.storage ?: disk.volume, disk.key in pending, enabled = false) {}
        }
        hw.unused.forEach { disk ->
            HardwareRow(Icons.Outlined.LinkOff, diskTitle(disk), disk.volume, disk.key in pending, enabled) {
                open(HardwareDialog.Volume(disk.key))
            }
        }
        HardwareRow(Icons.Outlined.Add, stringResource(R.string.hardware_add_disk), null, pending = false, enabled = enabled) {
            open(HardwareDialog.AddDisk)
        }
    }

    SectionHeader(stringResource(R.string.hardware_drives))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        hw.drives.forEach { drive ->
            val cloudInit = drive.kind == VmDisk.Kind.CLOUD_INIT
            HardwareRow(
                Icons.Outlined.Album,
                if (cloudInit) stringResource(R.string.hardware_cloudinit_drive, busLabel(drive.key)) else busLabel(drive.key),
                when {
                    cloudInit -> drive.storage ?: drive.volume
                    drive.isoName != null -> drive.isoName!!
                    drive.volume == "cdrom" -> stringResource(R.string.hardware_drive_host)
                    else -> stringResource(R.string.hardware_drive_empty)
                },
                drive.key in pending,
                enabled = enabled && !cloudInit,
            ) { open(HardwareDialog.Drive(drive.key)) }
        }
        HardwareRow(Icons.Outlined.Add, stringResource(R.string.hardware_add_drive), null, pending = false, enabled = enabled) {
            open(HardwareDialog.Drive(null))
        }
    }

    SectionHeader(stringResource(R.string.hardware_network))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        hw.nets.forEach { net ->
            HardwareRow(Icons.Outlined.Lan, vmNetTitle(net), vmNetBody(net), net.key in pending, enabled) {
                open(HardwareDialog.Net(net.key))
            }
        }
        HardwareRow(Icons.Outlined.Add, stringResource(R.string.hardware_add_net), null, pending = false, enabled = enabled) {
            open(HardwareDialog.Net(null))
        }
    }

    SectionHeader(stringResource(R.string.hardware_boot_order))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        HardwareRow(
            Icons.Outlined.FormatListNumbered,
            if (hw.bootOrder.isEmpty()) stringResource(R.string.hardware_boot_none) else hw.bootOrder.joinToString(" → ") { bootLabel(hw, it) },
            stringResource(R.string.hardware_boot_change),
            "boot" in pending,
            enabled,
        ) { open(HardwareDialog.BootOrder) }
    }
}

// ------------------------------------------------------------------------------------ containers

@Composable
private fun CtSections(hw: CtHardware, state: HardwareState, open: (HardwareDialog) -> Unit) {
    val enabled = state.busy == null
    val pending = hw.pending
    SectionHeader(stringResource(R.string.hardware_resources))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        HardwareRow(
            Icons.Outlined.DeveloperBoard,
            stringResource(R.string.hardware_cores),
            hw.cores?.toString() ?: stringResource(R.string.hardware_cores_unlimited),
            "cores" in pending,
            enabled,
        ) { open(HardwareDialog.Resources) }
        HardwareRow(
            Icons.Outlined.Memory,
            stringResource(R.string.hardware_memory),
            stringResource(R.string.hardware_memory_swap, mib(hw.memoryMiB), mib(hw.swapMiB)),
            "memory" in pending || "swap" in pending,
            enabled,
        ) { open(HardwareDialog.Resources) }
    }

    SectionHeader(stringResource(R.string.hardware_volumes))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        hw.volumes.forEach { volume ->
            HardwareRow(
                if (volume.isRoot) Icons.Outlined.Storage else Icons.Outlined.Folder,
                volumeTitle(volume),
                volumeBody(volume),
                volume.key in pending,
                enabled = enabled && volume.isResizable,
            ) { open(HardwareDialog.Volume(volume.key)) }
        }
        hw.unused.forEach { volume ->
            HardwareRow(Icons.Outlined.LinkOff, stringResource(R.string.hardware_unused, volume.key.removePrefix("unused")), volume.volume, volume.key in pending, enabled) {
                open(HardwareDialog.Volume(volume.key))
            }
        }
        HardwareRow(Icons.Outlined.Add, stringResource(R.string.hardware_add_mount_point), null, pending = false, enabled = enabled) {
            open(HardwareDialog.AddMountPoint)
        }
    }

    SectionHeader(stringResource(R.string.hardware_network))
    GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        hw.nets.forEach { net ->
            HardwareRow(Icons.Outlined.Lan, ctNetTitle(net), ctNetBody(net), net.key in pending, enabled) {
                open(HardwareDialog.Net(net.key))
            }
        }
        HardwareRow(Icons.Outlined.Add, stringResource(R.string.hardware_add_net), null, pending = false, enabled = enabled) {
            open(HardwareDialog.Net(null))
        }
    }
}

// ------------------------------------------------------------------------------------ rows and labels

/** A tappable row with an "after restart" badge for pending changes. */
@Composable
private fun HardwareRow(
    icon: ImageVector,
    title: String,
    body: String?,
    pending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = AnProxTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (pending) {
            Spacer(Modifier.width(8.dp))
            StatusPill(stringResource(R.string.hardware_pending), Tone.WARNING)
        }
    }
}

internal fun mib(value: Int): String = Formatters.bytes(value * MIB)

internal fun busName(bus: DiskBus): String = when (bus) {
    DiskBus.SCSI -> "SCSI"
    DiskBus.VIRTIO -> "VirtIO"
    DiskBus.SATA -> "SATA"
    DiskBus.IDE -> "IDE"
}

/** `scsi0` → "SCSI 0". */
internal fun busLabel(key: String): String {
    val bus = DiskBus.of(key) ?: return key
    return "${busName(bus)} ${key.removePrefix(bus.prefix)}"
}

@Composable
internal fun diskTitle(disk: VmDisk): String = when (disk.kind) {
    VmDisk.Kind.EFI -> stringResource(R.string.hardware_efi_disk)
    VmDisk.Kind.TPM -> stringResource(R.string.hardware_tpm_state)
    VmDisk.Kind.UNUSED -> stringResource(R.string.hardware_unused, disk.key.removePrefix("unused"))
    else -> disk.sizeBytes?.let { "${busLabel(disk.key)} · ${Formatters.bytes(it)}" } ?: busLabel(disk.key)
}

@Composable
private fun diskBody(disk: VmDisk): String = listOfNotNull(
    disk.storage ?: disk.volume,
    stringResource(R.string.hardware_iothread).takeIf { disk.iothread },
    stringResource(R.string.hardware_discard).takeIf { disk.discard },
    stringResource(R.string.hardware_ssd).takeIf { disk.ssd },
).joinToString(" · ")

internal val NET_MODEL_NAMES = mapOf(
    "virtio" to "VirtIO",
    "e1000" to "Intel E1000",
    "e1000e" to "Intel E1000E",
    "rtl8139" to "Realtek RTL8139",
    "vmxnet3" to "VMware vmxnet3",
)

private fun vmNetTitle(net: VmNet): String =
    listOfNotNull(net.key, NET_MODEL_NAMES[net.model] ?: net.model, net.bridge).joinToString(" · ")

@Composable
private fun vmNetBody(net: VmNet): String = listOfNotNull(
    net.mac,
    net.tag?.let { stringResource(R.string.hardware_vlan_value, it) },
    stringResource(R.string.hardware_firewall).takeIf { net.firewall },
    stringResource(R.string.hardware_link_down).takeIf { net.linkDown },
).joinToString(" · ")

@Composable
internal fun bootLabel(hw: VmHardware, key: String): String {
    hw.nets.firstOrNull { it.key == key }?.let { return key }
    val disk = hw.disk(key) ?: return key
    return if (disk.kind == VmDisk.Kind.CDROM) stringResource(R.string.hardware_boot_drive, busLabel(key)) else busLabel(key)
}

@Composable
internal fun volumeTitle(volume: CtVolume): String = when {
    volume.isRoot -> stringResource(R.string.hardware_root_disk)
    else -> volume.mountPath ?: volume.key
}

@Composable
private fun volumeBody(volume: CtVolume): String = listOfNotNull(
    volume.sizeBytes?.let { Formatters.bytes(it) },
    volume.storage ?: stringResource(R.string.hardware_bind_mount, volume.volume),
    volume.key.takeUnless { volume.isRoot },
    stringResource(R.string.hardware_in_backups).takeIf { volume.backup && !volume.isRoot },
).joinToString(" · ")

private fun ctNetTitle(net: CtNet): String = listOfNotNull(net.name.ifBlank { net.key }, net.bridge).joinToString(" · ")

@Composable
private fun ctNetBody(net: CtNet): String = listOfNotNull(
    net.ip?.let { if (it == "dhcp") stringResource(R.string.hardware_ipv4_dhcp) else it },
    net.ip6?.let {
        when (it) {
            "dhcp" -> stringResource(R.string.hardware_ipv6_dhcp)
            "auto" -> stringResource(R.string.hardware_ipv6_slaac)
            else -> it
        }
    },
    net.tag?.let { stringResource(R.string.hardware_vlan_value, it) },
    stringResource(R.string.hardware_firewall).takeIf { net.firewall },
).joinToString(" · ")
