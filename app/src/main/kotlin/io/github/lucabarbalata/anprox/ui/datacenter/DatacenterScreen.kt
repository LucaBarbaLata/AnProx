package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.core.designsystem.component.TagChip
import io.github.lucabarbalata.anprox.core.designsystem.theme.AnProxTheme
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.contentPadding

/** Cluster-wide settings of one Proxmox VE server (milestone M4). */
@Composable
fun DatacenterScreen(title: String, onBack: () -> Unit, onBackupJobs: () -> Unit, onStorage: () -> Unit, onFirewall: () -> Unit, onHa: () -> Unit, onReplication: () -> Unit, onPools: () -> Unit) {
    AnProxScaffold(title = stringResource(R.string.datacenter_title), subtitle = title, onBack = onBack) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Backup, stringResource(R.string.backupjobs_title), stringResource(R.string.datacenter_backup_jobs_body), onBackupJobs)
                    MenuRow(Icons.Outlined.Storage, stringResource(R.string.storage_title), stringResource(R.string.datacenter_storage_body), onStorage)
                    MenuRow(Icons.Outlined.Shield, stringResource(R.string.firewall_title), stringResource(R.string.firewall_open_body_dc), onFirewall)
                    MenuRow(Icons.Outlined.Sync, stringResource(R.string.ha_title), stringResource(R.string.ha_open_body), onHa)
                    MenuRow(Icons.Outlined.ContentCopy, stringResource(R.string.datacenter_replication), stringResource(R.string.replication_open_body), onReplication)
                    MenuRow(Icons.Outlined.Workspaces, stringResource(R.string.datacenter_pools), stringResource(R.string.pools_open_body), onPools)
                }
            }
            item { SectionHeader(stringResource(R.string.datacenter_planned)) }
            item { Planned() }
        }
    }
}

/** Honest pointer to the rest of M4, matching docs/roadmap.md. */
@Composable
private fun Planned() {
    val c = AnProxTheme.colors
    val items = listOf(
        R.string.datacenter_firewall_more,
    )
    GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items.forEach { label ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                TagChip(stringResource(R.string.coming_in, "M4"))
            }
        }
    }
}
