package io.github.lucabarbalata.anprox.ui.datacenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.GlassCard
import io.github.lucabarbalata.anprox.core.designsystem.component.SectionHeader
import io.github.lucabarbalata.anprox.ui.common.AnProxScaffold
import io.github.lucabarbalata.anprox.ui.common.MenuRow
import io.github.lucabarbalata.anprox.ui.common.contentPadding

/** The Permissions pages (milestone M7). */
enum class AccessPage { USERS, GROUPS, ROLES, ACL, REALMS }

/** Cluster-wide settings of one Proxmox VE server (milestones M4, M6 and M7). */
@Composable
fun DatacenterScreen(
    title: String,
    onBack: () -> Unit,
    onBackupJobs: () -> Unit,
    onStorage: () -> Unit,
    onFirewall: () -> Unit,
    onHa: () -> Unit,
    onReplication: () -> Unit,
    onPools: () -> Unit,
    onCeph: () -> Unit,
    onSdn: () -> Unit,
    onClusterLog: () -> Unit,
    onOptions: () -> Unit,
    onAcme: () -> Unit,
    onAccess: (AccessPage) -> Unit,
) {
    AnProxScaffold(title = stringResource(R.string.datacenter_title), subtitle = title, onBack = onBack) { inner ->
        LazyColumn(contentPadding = contentPadding(inner), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Short titles only, in four small groups: what a datacenter holds, its network and
            // security, the cluster itself, and who may do what.
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Backup, stringResource(R.string.backupjobs_title), null, onBackupJobs)
                    MenuRow(Icons.Outlined.Storage, stringResource(R.string.storage_title), null, onStorage)
                    MenuRow(Icons.Outlined.ContentCopy, stringResource(R.string.datacenter_replication), null, onReplication)
                    MenuRow(Icons.Outlined.Sync, stringResource(R.string.ha_title), null, onHa)
                    MenuRow(Icons.Outlined.Workspaces, stringResource(R.string.datacenter_pools), null, onPools)
                }
            }
            item { SectionHeader(stringResource(R.string.datacenter_network_section)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Shield, stringResource(R.string.firewall_title), null, onFirewall)
                    MenuRow(Icons.Outlined.Lan, stringResource(R.string.sdn_title), null, onSdn)
                }
            }
            item { SectionHeader(stringResource(R.string.datacenter_cluster_section)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Hub, stringResource(R.string.ceph_title), null, onCeph)
                    MenuRow(Icons.Outlined.History, stringResource(R.string.clusterlog_title), null, onClusterLog)
                    MenuRow(Icons.Outlined.VerifiedUser, stringResource(R.string.acme_title), null, onAcme)
                    MenuRow(Icons.Outlined.Tune, stringResource(R.string.dcoptions_title), null, onOptions)
                }
            }
            item { SectionHeader(stringResource(R.string.access_section)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Person, stringResource(R.string.access_users), null) { onAccess(AccessPage.USERS) }
                    MenuRow(Icons.Outlined.Group, stringResource(R.string.access_groups), null) { onAccess(AccessPage.GROUPS) }
                    MenuRow(Icons.Outlined.Badge, stringResource(R.string.access_roles), null) { onAccess(AccessPage.ROLES) }
                    MenuRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.access_acl), null) { onAccess(AccessPage.ACL) }
                    MenuRow(Icons.Outlined.Domain, stringResource(R.string.access_realms), null) { onAccess(AccessPage.REALMS) }
                }
            }
        }
    }
}
