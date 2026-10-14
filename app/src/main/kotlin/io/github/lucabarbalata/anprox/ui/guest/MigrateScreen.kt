package io.github.lucabarbalata.anprox.ui.guest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.ErrorBanner
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.BusyCard
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.RadioRow
import io.github.lucabarbalata.anprox.ui.common.TaskResultSnackbar
import io.github.lucabarbalata.anprox.ui.common.asString
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Move a guest to another node. Running VMs move live (copying local disks if needed); running
 * containers are restarted on the new node. On success [onMigrated] gets the guest's new address.
 */
@Composable
fun MigrateScreen(
    ref: GuestRef,
    guestName: String,
    onBack: () -> Unit,
    onTask: (serverId: String, node: String, upid: String) -> Unit,
    onMigrated: (GuestRef) -> Unit,
) {
    val key = "migrate-${ref.serverId}-${ref.node}-${ref.type.apiName}-${ref.vmid}"
    val viewModel: MigrateViewModel = koinViewModel(key = key) { parametersOf(ref, guestName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    TaskResultSnackbar(state.result, snackbar, ref.serverId, ref.node, onTask, viewModel::consumeResult)
    LaunchedEffect(state.moved) { state.moved?.let(onMigrated) }

    val c = AnProxTheme.colors
    val isVm = ref.type == GuestType.QEMU
    val typeLabel = stringResource(if (isVm) R.string.guest_type_vm else R.string.guest_type_ct)
    AnProxScaffold(
        title = stringResource(R.string.migrate_title),
        subtitle = "$guestName · $typeLabel ${ref.vmid} · ${ref.node}",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = viewModel::load, enabled = !state.loading && !state.busy) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) { inner ->
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(contentPadding(inner)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                ErrorBanner(error.asString(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::load)
            }
            when {
                state.loading && state.targets.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent)
                }
                state.error != null -> Unit
                state.targets.isEmpty() -> EmptyState(
                    Icons.Outlined.SwapHoriz,
                    stringResource(R.string.migrate_no_nodes_title),
                    stringResource(R.string.migrate_no_nodes_body),
                )
                else -> {
                    HowCard(state, isVm)
                    SectionHeader(stringResource(R.string.migrate_target))
                    GlassCard {
                        state.targets.forEach { t ->
                            RadioRow(
                                selected = state.target == t.node,
                                title = t.node,
                                body = when {
                                    !t.online -> stringResource(R.string.clone_node_offline)
                                    t.missing.isNotEmpty() -> stringResource(R.string.migrate_missing, t.missing.joinToString(", "))
                                    else -> null
                                },
                                enabled = t.allowed && !state.busy,
                            ) { viewModel.select(t.node) }
                        }
                    }
                    if (state.busy) BusyCard(stringResource(R.string.migrate_busy, state.target.orEmpty()))
                    AnProxButton(
                        text = stringResource(R.string.migrate_action),
                        onClick = viewModel::requestMigrate,
                        icon = Icons.Outlined.SwapHoriz,
                        enabled = state.canMigrate,
                        loading = state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (state.confirm) {
        val target = state.target.orEmpty()
        ConfirmActionDialog(
            title = stringResource(R.string.migrate_confirm_title, guestName, target),
            message = stringResource(
                when {
                    !state.running -> R.string.migrate_confirm_offline
                    isVm -> R.string.migrate_confirm_live
                    else -> R.string.migrate_confirm_restart
                },
            ),
            confirmLabel = stringResource(R.string.migrate_action),
            danger = DangerLevel.CONFIRM,
            onConfirm = viewModel::migrate,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

/** What will happen, in plain words: live, restart or offline, and what slows it down or blocks it. */
@Composable
private fun HowCard(state: MigrateState, isVm: Boolean) {
    val c = AnProxTheme.colors
    val disks = state.check?.disksToCopy.orEmpty()
    val devices = state.check?.localResources.orEmpty()
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(
                    when {
                        !state.running -> R.string.migrate_how_offline
                        isVm -> R.string.migrate_how_live
                        else -> R.string.migrate_how_restart
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
            )
            if (state.haManaged) Note(stringResource(R.string.migrate_ha), c.textSecondary)
            if (disks.isNotEmpty()) {
                val total = disks.sumOf { it.size ?: 0L }
                Note(pluralStringResource(R.plurals.migrate_local_disks, disks.size, disks.size, Formatters.bytes(total)), c.textSecondary)
            }
            if (devices.isNotEmpty()) Note(stringResource(R.string.migrate_local_devices, devices.joinToString(", ")), c.warning)
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
