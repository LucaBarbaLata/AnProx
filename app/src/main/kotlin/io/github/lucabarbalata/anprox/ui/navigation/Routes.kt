package io.github.lucabarbalata.anprox.ui.navigation

import io.github.lucabarbalata.anprox.model.pve.FirewallScope
import io.github.lucabarbalata.anprox.model.pve.GuestType
import kotlinx.serialization.Serializable

/*
 * Type-safe navigation destinations (Navigation Compose 2.8+). Each route is a @Serializable
 * class; its properties are the screen's arguments.
 */

// Top-level tabs (bottom bar / rail)
@Serializable data object DashboardRoute
@Serializable data object ResourcesRoute
@Serializable data object TasksRoute
@Serializable data object MoreRoute

// Servers & login
@Serializable data object ServersRoute

/** [serverId] empty = add a new server. */
@Serializable data class ServerEditorRoute(val serverId: String = "")
@Serializable data class LoginRoute(val serverId: String)

// Details
@Serializable data class GuestRoute(val serverId: String, val node: String, val type: String, val vmid: Int)
/** [name] is only shown in the subtitle. */
@Serializable data class SnapshotsRoute(val serverId: String, val node: String, val type: String, val vmid: Int, val name: String)
@Serializable data class AgentRoute(val serverId: String, val node: String, val vmid: Int, val name: String)
@Serializable data class OptionsRoute(val serverId: String, val node: String, val type: String, val vmid: Int, val name: String)
@Serializable data class HardwareRoute(val serverId: String, val node: String, val type: String, val vmid: Int, val name: String)
@Serializable data class CloudInitRoute(val serverId: String, val node: String, val vmid: Int, val name: String)
/** [kind] is a [io.github.lucabarbalata.anprox.model.pve.ConsoleKind] name; [vmid] is 0 for a node shell. */
// command: node shells only, e.g. "upgrade" (empty: a login shell).
@Serializable data class ConsoleRoute(val serverId: String, val node: String, val kind: String, val vmid: Int, val name: String, val command: String = "")
/** SSH with the app's own client: [vmid] is 0 for the node itself, [type] is `qemu`/`lxc` for a guest. */
@Serializable data class SshRoute(val serverId: String, val node: String, val vmid: Int, val type: String, val name: String)
@Serializable data object SshKeysRoute
// page: a NodeAdminPage name.
// backupServer: the node of a Proxmox Backup Server (`localhost`), which has no shell for upgrades.
@Serializable data class NodeAdminRoute(val serverId: String, val node: String, val page: String, val backupServer: Boolean = false)
@Serializable data class CloneRoute(val serverId: String, val node: String, val type: String, val vmid: Int, val name: String)
@Serializable data class MigrateRoute(val serverId: String, val node: String, val type: String, val vmid: Int, val name: String)
@Serializable data class BackupsRoute(val serverId: String, val node: String, val type: String, val vmid: Int, val name: String)
/** A storage's backups, seen from [node]. */
@Serializable data class StorageRoute(val serverId: String, val node: String, val storage: String)
@Serializable data class NodeRoute(val serverId: String, val node: String)
/** [type] is `qemu` or `lxc`. */
@Serializable data class CreateGuestRoute(val serverId: String, val node: String, val type: String)
@Serializable data class TaskLogRoute(val serverId: String, val node: String, val upid: String)

// Datacenter (M4)
@Serializable data class DatacenterRoute(val serverId: String, val title: String)
@Serializable data class BackupJobsRoute(val serverId: String)
// "" = a new job
@Serializable data class BackupJobEditorRoute(val serverId: String, val jobId: String = "")
@Serializable data class StorageListRoute(val serverId: String)
@Serializable data class StorageContentRoute(val serverId: String, val node: String, val storage: String)
@Serializable data class TemplateCatalogRoute(val serverId: String, val node: String, val storage: String)
// storageId "" adds a new storage.
@Serializable data class StorageEditorRoute(val serverId: String, val storageId: String = "")
@Serializable data class HaRoute(val serverId: String)
@Serializable data class ReplicationRoute(val serverId: String)
@Serializable data class PoolsRoute(val serverId: String)
@Serializable data class PoolRoute(val serverId: String, val poolId: String)
// Ceph (M6)
@Serializable data class CephRoute(val serverId: String)
@Serializable data class CephOsdsRoute(val serverId: String)
@Serializable data class CephPoolsRoute(val serverId: String)
@Serializable data class CephServicesRoute(val serverId: String)
// kind: a CephText name (CONFIG, CRUSH, LOG).
@Serializable data class CephTextRoute(val serverId: String, val kind: String)
// SDN (M6)
@Serializable data class SdnRoute(val serverId: String)
@Serializable data class ClusterLogRoute(val serverId: String)
@Serializable data class SdnVnetRoute(val serverId: String, val vnet: String)
@Serializable data class SdnIpamRoute(val serverId: String, val ipam: String)
// Backup Server (M8). ns "" is the root namespace.
@Serializable data class BackupServerRoute(val serverId: String, val title: String)
@Serializable data class DatastoreRoute(val serverId: String, val store: String)
@Serializable data class BackupGroupRoute(val serverId: String, val store: String, val ns: String, val type: String, val id: String)
@Serializable data class FileRestoreRoute(val serverId: String, val store: String, val ns: String, val type: String, val id: String, val time: Long)
@Serializable data class PbsJobsRoute(val serverId: String)
@Serializable data class PbsRemotesRoute(val serverId: String)
// Permissions (M7)
@Serializable data class UsersRoute(val serverId: String)
@Serializable data class UserRoute(val serverId: String, val userId: String)
// userId "" adds a new user.
@Serializable data class UserEditorRoute(val serverId: String, val userId: String = "")
@Serializable data class GroupsRoute(val serverId: String)
@Serializable data class RolesRoute(val serverId: String)
@Serializable data class AclRoute(val serverId: String)
@Serializable data class RealmsRoute(val serverId: String)
// realm "" adds a new realm of [type] (`ldap`, `ad`, `openid`).
@Serializable data class RealmEditorRoute(val serverId: String, val realm: String = "", val type: String = "ldap")
// kind: "dc", "node" or "guest"; node, type and vmid as far as the kind needs them.
@Serializable data class FirewallRoute(val serverId: String, val kind: String, val title: String, val node: String = "", val type: String = "", val vmid: Int = 0, val group: String = "")
// what: a FirewallExtra name (GROUPS, ALIASES, IPSETS, LOG) of the firewall described like FirewallRoute.
@Serializable data class FirewallExtraRoute(val serverId: String, val what: String, val kind: String, val title: String, val node: String = "", val type: String = "", val vmid: Int = 0)

/** The firewall a [FirewallRoute] or [FirewallExtraRoute] is about. */
fun firewallScope(kind: String, node: String, type: String, vmid: Int, group: String = ""): FirewallScope = when (kind) {
    "node" -> FirewallScope.Node(node)
    "guest" -> FirewallScope.Guest(node, GuestType.fromApi(type) ?: GuestType.QEMU, vmid)
    "group" -> FirewallScope.Group(group)
    else -> FirewallScope.Datacenter
}

// Settings
@Serializable data object SettingsRoute
@Serializable data object AlertsRoute
@Serializable data object AboutRoute
