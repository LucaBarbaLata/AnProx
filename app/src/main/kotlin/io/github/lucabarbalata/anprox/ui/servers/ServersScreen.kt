package io.github.lucabarbalata.anprox.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AnProxButton
import io.github.lucabarbalata.anprox.core.designsystem.component.EmptyState
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.StatusPill
import io.github.lucabarbalata.anprox.core.designsystem.component.Tone
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.hub.ServerState
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.pve.DangerLevel
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.ConfirmActionDialog
import io.github.lucabarbalata.anprox.ui.common.contentPadding
import org.koin.compose.viewmodel.koinViewModel

/** Saved servers: add, edit, sign in/out, delete. */
@Composable
fun ServersScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onLogin: (String) -> Unit,
    viewModel: ServersViewModel = koinViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ServerState?>(null) }

    AnProxScaffold(
        title = stringResource(R.string.servers_title),
        onBack = onBack,
        actions = {
            if (servers.isNotEmpty()) {
                IconButton(onClick = onAdd) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.servers_add)) }
            }
        },
    ) { inner ->
        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.servers_empty_title),
                    body = stringResource(R.string.servers_empty_body),
                    action = { AnProxButton(stringResource(R.string.servers_add), onClick = onAdd, icon = Icons.Outlined.Add) },
                )
            }
        } else {
            LazyColumn(
                contentPadding = contentPadding(inner),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        onClick = { if (server.auth == AuthState.LOGGED_IN) onEdit(server.id) else onLogin(server.id) },
                        onEdit = { onEdit(server.id) },
                        onLogin = { onLogin(server.id) },
                        onLogout = { viewModel.logout(server.id) },
                        onDelete = { pendingDelete = server },
                    )
                }
            }
        }
    }

    pendingDelete?.let { server ->
        ConfirmActionDialog(
            title = stringResource(R.string.servers_delete_title, server.profile.name),
            message = stringResource(R.string.servers_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            danger = DangerLevel.CONFIRM,
            onConfirm = {
                viewModel.delete(server.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ServerRow(
    server: ServerState,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = AnProxTheme.colors
    var menu by remember { mutableStateOf(false) }
    GlassCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (server.profile.product == ProxmoxProduct.PBS) Icons.Outlined.Backup else Icons.Outlined.Dns,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(server.profile.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    "${server.profile.userId} · ${server.profile.endpoints.first().url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.size(6.dp))
                AuthPill(server.auth)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; onEdit() })
                    if (server.auth == AuthState.LOGGED_IN) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_sign_out)) }, onClick = { menu = false; onLogout() })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_sign_in)) }, onClick = { menu = false; onLogin() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = c.danger) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun AuthPill(auth: AuthState) = when (auth) {
    AuthState.LOGGED_IN -> StatusPill(stringResource(R.string.auth_signed_in), Tone.SUCCESS)
    AuthState.LOGGED_OUT -> StatusPill(stringResource(R.string.auth_signed_out), Tone.NEUTRAL)
    AuthState.SESSION_EXPIRED -> StatusPill(stringResource(R.string.auth_expired), Tone.WARNING)
}
