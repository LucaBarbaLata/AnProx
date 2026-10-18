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
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
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
    onAccess: (AccessPage) -> Unit,
) {
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
                    MenuRow(Icons.Outlined.Hub, stringResource(R.string.ceph_title), stringResource(R.string.ceph_open_body), onCeph)
                    MenuRow(Icons.Outlined.Lan, stringResource(R.string.sdn_title), stringResource(R.string.sdn_open_body), onSdn)
                }
            }
            item { SectionHeader(stringResource(R.string.access_section)) }
            item {
                GlassCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    MenuRow(Icons.Outlined.Person, stringResource(R.string.access_users), stringResource(R.string.access_users_body)) { onAccess(AccessPage.USERS) }
                    MenuRow(Icons.Outlined.Group, stringResource(R.string.access_groups), stringResource(R.string.access_groups_body)) { onAccess(AccessPage.GROUPS) }
                    MenuRow(Icons.Outlined.Badge, stringResource(R.string.access_roles), stringResource(R.string.access_roles_body)) { onAccess(AccessPage.ROLES) }
                    MenuRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.access_acl), stringResource(R.string.access_acl_body)) { onAccess(AccessPage.ACL) }
                    MenuRow(Icons.Outlined.Domain, stringResource(R.string.access_realms), stringResource(R.string.access_realms_body)) { onAccess(AccessPage.REALMS) }
                }
            }
        }
    }
}
