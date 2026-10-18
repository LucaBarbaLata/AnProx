package io.github.lucabarbalata.anprox.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Task
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.core.designsystem.component.AdaptiveNavScaffold
import io.github.lucabarbalata.anprox.core.designsystem.component.NavItem
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.diagnostics.CrashReportDialog
import io.github.lucabarbalata.anprox.model.link.AppLink
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.RealmType
import io.github.lucabarbalata.anprox.ui.access.AclScreen
import io.github.lucabarbalata.anprox.ui.access.GroupsScreen
import io.github.lucabarbalata.anprox.ui.access.RealmEditorScreen
import io.github.lucabarbalata.anprox.ui.access.RealmsScreen
import io.github.lucabarbalata.anprox.ui.access.RolesScreen
import io.github.lucabarbalata.anprox.ui.access.UserEditorScreen
import io.github.lucabarbalata.anprox.ui.access.UserScreen
import io.github.lucabarbalata.anprox.ui.access.UsersScreen
import io.github.lucabarbalata.anprox.ui.alerts.AlertsScreen
import io.github.lucabarbalata.anprox.ui.backups.BackupScope
import io.github.lucabarbalata.anprox.ui.backups.BackupsScreen
import io.github.lucabarbalata.anprox.ui.ceph.CephOsdsScreen
import io.github.lucabarbalata.anprox.ui.ceph.CephPage
import io.github.lucabarbalata.anprox.ui.ceph.CephPoolsScreen
import io.github.lucabarbalata.anprox.ui.ceph.CephScreen
import io.github.lucabarbalata.anprox.ui.ceph.CephServicesScreen
import io.github.lucabarbalata.anprox.ui.ceph.CephText
import io.github.lucabarbalata.anprox.ui.ceph.CephTextScreen
import io.github.lucabarbalata.anprox.ui.cloudinit.CloudInitScreen
import io.github.lucabarbalata.anprox.ui.console.ConsoleScreen
import io.github.lucabarbalata.anprox.ui.create.CreateGuestScreen
import io.github.lucabarbalata.anprox.ui.dashboard.DashboardScreen
import io.github.lucabarbalata.anprox.ui.datacenter.AccessPage
import io.github.lucabarbalata.anprox.ui.datacenter.BackupJobEditorScreen
import io.github.lucabarbalata.anprox.ui.datacenter.BackupJobsScreen
import io.github.lucabarbalata.anprox.ui.datacenter.DatacenterScreen
import io.github.lucabarbalata.anprox.ui.datacenter.FirewallAliasesScreen
import io.github.lucabarbalata.anprox.ui.datacenter.FirewallExtra
import io.github.lucabarbalata.anprox.ui.datacenter.FirewallIpSetsScreen
import io.github.lucabarbalata.anprox.ui.datacenter.FirewallLogScreen
import io.github.lucabarbalata.anprox.ui.datacenter.FirewallScreen
import io.github.lucabarbalata.anprox.ui.datacenter.HaScreen
import io.github.lucabarbalata.anprox.ui.datacenter.PoolScreen
import io.github.lucabarbalata.anprox.ui.datacenter.PoolsScreen
import io.github.lucabarbalata.anprox.ui.datacenter.ReplicationScreen
import io.github.lucabarbalata.anprox.ui.datacenter.SecurityGroupsScreen
import io.github.lucabarbalata.anprox.ui.datacenter.StorageContentScreen
import io.github.lucabarbalata.anprox.ui.datacenter.StorageEditorScreen
import io.github.lucabarbalata.anprox.ui.datacenter.StorageListScreen
import io.github.lucabarbalata.anprox.ui.guest.CloneScreen
import io.github.lucabarbalata.anprox.ui.guest.GuestScreen
import io.github.lucabarbalata.anprox.ui.guest.GuestTool
import io.github.lucabarbalata.anprox.ui.guest.MigrateScreen
import io.github.lucabarbalata.anprox.ui.guest.OptionsScreen
import io.github.lucabarbalata.anprox.ui.hardware.HardwareScreen
import io.github.lucabarbalata.anprox.ui.login.LoginScreen
import io.github.lucabarbalata.anprox.ui.navigation.AboutRoute
import io.github.lucabarbalata.anprox.ui.navigation.AclRoute
import io.github.lucabarbalata.anprox.ui.navigation.AlertsRoute
import io.github.lucabarbalata.anprox.ui.navigation.AppLinks
import io.github.lucabarbalata.anprox.ui.navigation.BackupJobEditorRoute
import io.github.lucabarbalata.anprox.ui.navigation.BackupJobsRoute
import io.github.lucabarbalata.anprox.ui.navigation.BackupsRoute
import io.github.lucabarbalata.anprox.ui.navigation.CephOsdsRoute
import io.github.lucabarbalata.anprox.ui.navigation.CephPoolsRoute
import io.github.lucabarbalata.anprox.ui.navigation.CephRoute
import io.github.lucabarbalata.anprox.ui.navigation.CephServicesRoute
import io.github.lucabarbalata.anprox.ui.navigation.CephTextRoute
import io.github.lucabarbalata.anprox.ui.navigation.CloneRoute
import io.github.lucabarbalata.anprox.ui.navigation.CloudInitRoute
import io.github.lucabarbalata.anprox.ui.navigation.ConsoleRoute
import io.github.lucabarbalata.anprox.ui.navigation.CreateGuestRoute
import io.github.lucabarbalata.anprox.ui.navigation.DashboardRoute
import io.github.lucabarbalata.anprox.ui.navigation.DatacenterRoute
import io.github.lucabarbalata.anprox.ui.navigation.FirewallExtraRoute
import io.github.lucabarbalata.anprox.ui.navigation.FirewallRoute
import io.github.lucabarbalata.anprox.ui.navigation.GroupsRoute
import io.github.lucabarbalata.anprox.ui.navigation.GuestRoute
import io.github.lucabarbalata.anprox.ui.navigation.HaRoute
import io.github.lucabarbalata.anprox.ui.navigation.HardwareRoute
import io.github.lucabarbalata.anprox.ui.navigation.LoginRoute
import io.github.lucabarbalata.anprox.ui.navigation.MigrateRoute
import io.github.lucabarbalata.anprox.ui.navigation.MoreRoute
import io.github.lucabarbalata.anprox.ui.navigation.NodeAdminRoute
import io.github.lucabarbalata.anprox.ui.navigation.NodeRoute
import io.github.lucabarbalata.anprox.ui.navigation.OptionsRoute
import io.github.lucabarbalata.anprox.ui.navigation.PoolRoute
import io.github.lucabarbalata.anprox.ui.navigation.PoolsRoute
import io.github.lucabarbalata.anprox.ui.navigation.RealmEditorRoute
import io.github.lucabarbalata.anprox.ui.navigation.RealmsRoute
import io.github.lucabarbalata.anprox.ui.navigation.ReplicationRoute
import io.github.lucabarbalata.anprox.ui.navigation.ResourcesRoute
import io.github.lucabarbalata.anprox.ui.navigation.RolesRoute
import io.github.lucabarbalata.anprox.ui.navigation.ServerEditorRoute
import io.github.lucabarbalata.anprox.ui.navigation.ServersRoute
import io.github.lucabarbalata.anprox.ui.navigation.SettingsRoute
import io.github.lucabarbalata.anprox.ui.navigation.SnapshotsRoute
import io.github.lucabarbalata.anprox.ui.navigation.SshKeysRoute
import io.github.lucabarbalata.anprox.ui.navigation.SshRoute
import io.github.lucabarbalata.anprox.ui.navigation.StorageContentRoute
import io.github.lucabarbalata.anprox.ui.navigation.StorageEditorRoute
import io.github.lucabarbalata.anprox.ui.navigation.StorageListRoute
import io.github.lucabarbalata.anprox.ui.navigation.StorageRoute
import io.github.lucabarbalata.anprox.ui.navigation.TaskLogRoute
import io.github.lucabarbalata.anprox.ui.navigation.TasksRoute
import io.github.lucabarbalata.anprox.ui.navigation.UserEditorRoute
import io.github.lucabarbalata.anprox.ui.navigation.UserRoute
import io.github.lucabarbalata.anprox.ui.navigation.UsersRoute
import io.github.lucabarbalata.anprox.ui.navigation.firewallScope
import io.github.lucabarbalata.anprox.ui.node.DisksScreen
import io.github.lucabarbalata.anprox.ui.node.JournalScreen
import io.github.lucabarbalata.anprox.ui.node.NetworkScreen
import io.github.lucabarbalata.anprox.ui.node.NodeAdminPage
import io.github.lucabarbalata.anprox.ui.node.NodeScreen
import io.github.lucabarbalata.anprox.ui.node.RepositoriesScreen
import io.github.lucabarbalata.anprox.ui.node.ServicesScreen
import io.github.lucabarbalata.anprox.ui.node.SystemSettingsScreen
import io.github.lucabarbalata.anprox.ui.node.UpdatesScreen
import io.github.lucabarbalata.anprox.ui.resources.ResourcesScreen
import io.github.lucabarbalata.anprox.ui.servers.ServerEditorScreen
import io.github.lucabarbalata.anprox.ui.servers.ServersScreen
import io.github.lucabarbalata.anprox.ui.settings.AboutScreen
import io.github.lucabarbalata.anprox.ui.settings.MoreScreen
import io.github.lucabarbalata.anprox.ui.settings.SettingsScreen
import io.github.lucabarbalata.anprox.ui.snapshots.SnapshotsScreen
import io.github.lucabarbalata.anprox.ui.ssh.SshKeysScreen
import io.github.lucabarbalata.anprox.ui.ssh.SshScreen
import io.github.lucabarbalata.anprox.ui.tasks.TaskLogScreen
import io.github.lucabarbalata.anprox.ui.tasks.TasksScreen
import org.koin.compose.koinInject

private enum class Tab(val route: Any) { DASHBOARD(DashboardRoute), RESOURCES(ResourcesRoute), TASKS(TasksRoute), MORE(MoreRoute) }

private fun NavDestination?.tab(): Tab? = this?.hierarchy?.let { h ->
    when {
        h.any { it.hasRoute(DashboardRoute::class) } -> Tab.DASHBOARD
        h.any { it.hasRoute(ResourcesRoute::class) } -> Tab.RESOURCES
        h.any { it.hasRoute(TasksRoute::class) } -> Tab.TASKS
        h.any { it.hasRoute(MoreRoute::class) } -> Tab.MORE
        else -> null
    }
}

private fun NavHostController.selectTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition =
    { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } }
private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition =
    { fadeOut(tween(160)) }
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition =
    { fadeIn(tween(220)) }
private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition =
    { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 } }

/** App shell: adaptive navigation (Q31) around the navigation graph. */
@Composable
fun AnProxApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination.tab()

    val items = listOf(
        NavItem(Tab.DASHBOARD.name, stringResource(R.string.tab_dashboard), Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
        NavItem(Tab.RESOURCES.name, stringResource(R.string.tab_resources), Icons.Outlined.Dns, Icons.Filled.Dns),
        NavItem(Tab.TASKS.name, stringResource(R.string.tab_tasks), Icons.Outlined.Task, Icons.Filled.Task),
        NavItem(Tab.MORE.name, stringResource(R.string.tab_more), Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
    )

    val back: () -> Unit = { nav.popBackStack() }
    val openTask: (String, String, String) -> Unit = { serverId, node, upid -> nav.navigate(TaskLogRoute(serverId, node, upid)) }
    val openNode: (String, String) -> Unit = { serverId, node -> nav.navigate(NodeRoute(serverId, node)) }
    val openGuest: (GuestRef) -> Unit = { ref -> nav.navigate(GuestRoute(ref.serverId, ref.node, ref.type.apiName, ref.vmid)) }
    val openTool: (GuestTool, GuestRef, String) -> Unit = { tool, ref, name ->
        val type = ref.type.apiName
        when (tool) {
            GuestTool.SNAPSHOTS -> nav.navigate(SnapshotsRoute(ref.serverId, ref.node, type, ref.vmid, name))
            GuestTool.BACKUPS -> nav.navigate(BackupsRoute(ref.serverId, ref.node, type, ref.vmid, name))
            GuestTool.HARDWARE -> nav.navigate(HardwareRoute(ref.serverId, ref.node, type, ref.vmid, name))
            GuestTool.CLOUD_INIT -> nav.navigate(CloudInitRoute(ref.serverId, ref.node, ref.vmid, name))
            GuestTool.OPTIONS -> nav.navigate(OptionsRoute(ref.serverId, ref.node, type, ref.vmid, name))
            GuestTool.FIREWALL -> nav.navigate(FirewallRoute(ref.serverId, "guest", name, ref.node, type, ref.vmid))
            GuestTool.CONSOLE -> {
                val kind = if (ref.type == GuestType.QEMU) ConsoleKind.VM else ConsoleKind.CONTAINER
                nav.navigate(ConsoleRoute(ref.serverId, ref.node, kind.name, ref.vmid, name))
            }
            GuestTool.SSH -> nav.navigate(SshRoute(ref.serverId, ref.node, ref.vmid, type, name))
            GuestTool.CLONE -> nav.navigate(CloneRoute(ref.serverId, ref.node, type, ref.vmid, name))
            GuestTool.MIGRATE -> nav.navigate(MigrateRoute(ref.serverId, ref.node, type, ref.vmid, name))
        }
    }
    // After a migration the guest lives on another node: replace its old screen (phones). On
    // tablets the Resources list follows the guest by itself.
    val onMigrated: (GuestRef) -> Unit = { moved ->
        nav.popBackStack()
        val below = nav.currentBackStackEntry
        if (below != null && below.destination.hasRoute(GuestRoute::class) && below.toRoute<GuestRoute>().vmid == moved.vmid) {
            nav.popBackStack()
            openGuest(moved)
        }
    }
    val openStorage: (String, String, String) -> Unit = { serverId, node, storage -> nav.navigate(StorageRoute(serverId, node, storage)) }

    // A notification, shortcut or widget asked for a screen. Waits until the graph is set up.
    val links: AppLinks = koinInject()
    val link by links.next.collectAsStateWithLifecycle()
    val ready = backStack != null
    LaunchedEffect(link, ready) {
        val target = link ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        when (target) {
            AppLink.Dashboard -> nav.selectTab(Tab.DASHBOARD)
            AppLink.Resources -> nav.selectTab(Tab.RESOURCES)
            AppLink.Tasks -> nav.selectTab(Tab.TASKS)
            AppLink.Alerts -> {
                nav.selectTab(Tab.MORE)
                nav.navigate(AlertsRoute) { launchSingleTop = true }
            }
            is AppLink.Guest -> nav.navigate(GuestRoute(target.serverId, target.node, target.type, target.vmid))
            is AppLink.Node -> nav.navigate(NodeRoute(target.serverId, target.node))
            is AppLink.Task -> nav.navigate(TaskLogRoute(target.serverId, target.node, target.upid))
        }
        links.opened(target)
    }

    AdaptiveNavScaffold(
        items = items,
        selectedKey = current?.name,
        onSelect = { item -> nav.selectTab(Tab.valueOf(item.key)) },
        showNavigation = current != null,
    ) {
        NavHost(
            navController = nav,
            startDestination = DashboardRoute,
            enterTransition = enter,
            exitTransition = exit,
            popEnterTransition = popEnter,
            popExitTransition = popExit,
        ) {
            composable<DashboardRoute> {
                DashboardScreen(
                    onAddServer = { nav.navigate(ServerEditorRoute()) },
                    onLogin = { id -> nav.navigate(LoginRoute(id)) },
                    onNode = openNode,
                    onResources = { nav.selectTab(Tab.RESOURCES) },
                    onGuest = openGuest,
                    onDatacenter = { serverId, title -> nav.navigate(DatacenterRoute(serverId, title)) },
                )
            }
            composable<DatacenterRoute> { entry ->
                val route = entry.toRoute<DatacenterRoute>()
                DatacenterScreen(
                    route.title,
                    onBack = back,
                    onBackupJobs = { nav.navigate(BackupJobsRoute(route.serverId)) },
                    onStorage = { nav.navigate(StorageListRoute(route.serverId)) },
                    onFirewall = { nav.navigate(FirewallRoute(route.serverId, "dc", route.title)) },
                    onHa = { nav.navigate(HaRoute(route.serverId)) },
                    onReplication = { nav.navigate(ReplicationRoute(route.serverId)) },
                    onPools = { nav.navigate(PoolsRoute(route.serverId)) },
                    onCeph = { nav.navigate(CephRoute(route.serverId)) },
                    onAccess = { page ->
                        when (page) {
                            AccessPage.USERS -> nav.navigate(UsersRoute(route.serverId))
                            AccessPage.GROUPS -> nav.navigate(GroupsRoute(route.serverId))
                            AccessPage.ROLES -> nav.navigate(RolesRoute(route.serverId))
                            AccessPage.ACL -> nav.navigate(AclRoute(route.serverId))
                            AccessPage.REALMS -> nav.navigate(RealmsRoute(route.serverId))
                        }
                    },
                )
            }
            composable<CephRoute> { entry ->
                val route = entry.toRoute<CephRoute>()
                CephScreen(
                    route.serverId,
                    onBack = back,
                    onPage = { page ->
                        when (page) {
                            CephPage.OSDS -> nav.navigate(CephOsdsRoute(route.serverId))
                            CephPage.POOLS -> nav.navigate(CephPoolsRoute(route.serverId))
                            CephPage.SERVICES -> nav.navigate(CephServicesRoute(route.serverId))
                            CephPage.CONFIG -> nav.navigate(CephTextRoute(route.serverId, CephText.CONFIG.name))
                            CephPage.CRUSH -> nav.navigate(CephTextRoute(route.serverId, CephText.CRUSH.name))
                            CephPage.LOG -> nav.navigate(CephTextRoute(route.serverId, CephText.LOG.name))
                        }
                    },
                    onTask = openTask,
                )
            }
            composable<CephOsdsRoute> { entry -> CephOsdsScreen(entry.toRoute<CephOsdsRoute>().serverId, onBack = back, onTask = openTask) }
            composable<CephPoolsRoute> { entry -> CephPoolsScreen(entry.toRoute<CephPoolsRoute>().serverId, onBack = back, onTask = openTask) }
            composable<CephServicesRoute> { entry -> CephServicesScreen(entry.toRoute<CephServicesRoute>().serverId, onBack = back, onTask = openTask) }
            composable<CephTextRoute> { entry ->
                val route = entry.toRoute<CephTextRoute>()
                CephTextScreen(route.serverId, CephText.entries.firstOrNull { it.name == route.kind } ?: CephText.CONFIG, onBack = back)
            }
            composable<UsersRoute> { entry ->
                val route = entry.toRoute<UsersRoute>()
                UsersScreen(
                    route.serverId,
                    onBack = back,
                    onUser = { id -> nav.navigate(UserRoute(route.serverId, id)) },
                    onAdd = { nav.navigate(UserEditorRoute(route.serverId)) },
                )
            }
            composable<UserRoute> { entry ->
                val route = entry.toRoute<UserRoute>()
                UserScreen(route.serverId, route.userId, onBack = back, onEdit = { nav.navigate(UserEditorRoute(route.serverId, route.userId)) })
            }
            composable<UserEditorRoute> { entry ->
                val route = entry.toRoute<UserEditorRoute>()
                UserEditorScreen(route.serverId, route.userId.ifEmpty { null }, onDone = back)
            }
            composable<GroupsRoute> { entry -> GroupsScreen(entry.toRoute<GroupsRoute>().serverId, onBack = back) }
            composable<RolesRoute> { entry -> RolesScreen(entry.toRoute<RolesRoute>().serverId, onBack = back) }
            composable<AclRoute> { entry -> AclScreen(entry.toRoute<AclRoute>().serverId, onBack = back) }
            composable<RealmsRoute> { entry ->
                val route = entry.toRoute<RealmsRoute>()
                RealmsScreen(
                    route.serverId,
                    onBack = back,
                    onAdd = { type -> nav.navigate(RealmEditorRoute(route.serverId, type = type.apiName)) },
                    onEdit = { realm -> nav.navigate(RealmEditorRoute(route.serverId, realm = realm)) },
                    onTask = openTask,
                )
            }
            composable<RealmEditorRoute> { entry ->
                val route = entry.toRoute<RealmEditorRoute>()
                RealmEditorScreen(route.serverId, route.realm.ifEmpty { null }, RealmType.fromApi(route.type) ?: RealmType.LDAP, onDone = back)
            }
            composable<HaRoute> { entry ->
                HaScreen(entry.toRoute<HaRoute>().serverId, onBack = back, onTask = openTask)
            }
            composable<ReplicationRoute> { entry ->
                ReplicationScreen(entry.toRoute<ReplicationRoute>().serverId, onBack = back, onTask = openTask)
            }
            composable<PoolsRoute> { entry ->
                val route = entry.toRoute<PoolsRoute>()
                PoolsScreen(route.serverId, onBack = back, onPool = { id -> nav.navigate(PoolRoute(route.serverId, id)) }, onTask = openTask)
            }
            composable<PoolRoute> { entry ->
                val route = entry.toRoute<PoolRoute>()
                PoolScreen(
                    route.serverId,
                    route.poolId,
                    onBack = back,
                    onGuest = { node, type, vmid -> openGuest(GuestRef(route.serverId, node, type, vmid)) },
                    onTask = openTask,
                )
            }
            composable<FirewallRoute> { entry ->
                val r = entry.toRoute<FirewallRoute>()
                FirewallScreen(
                    r.serverId,
                    firewallScope(r.kind, r.node, r.type, r.vmid, r.group),
                    r.title,
                    onBack = back,
                    onTask = openTask,
                    onExtra = { extra -> nav.navigate(FirewallExtraRoute(r.serverId, extra.name, r.kind, r.title, r.node, r.type, r.vmid)) },
                )
            }
            composable<FirewallExtraRoute> { entry ->
                val r = entry.toRoute<FirewallExtraRoute>()
                val scope = firewallScope(r.kind, r.node, r.type, r.vmid)
                when (FirewallExtra.valueOf(r.what)) {
                    FirewallExtra.GROUPS -> SecurityGroupsScreen(
                        r.serverId,
                        r.title,
                        onBack = back,
                        onGroup = { group -> nav.navigate(FirewallRoute(r.serverId, "group", r.title, group = group)) },
                        onTask = openTask,
                    )
                    FirewallExtra.ALIASES -> FirewallAliasesScreen(r.serverId, scope, r.title, onBack = back, onTask = openTask)
                    FirewallExtra.IPSETS -> FirewallIpSetsScreen(r.serverId, scope, r.title, onBack = back, onTask = openTask)
                    FirewallExtra.LOG -> FirewallLogScreen(r.serverId, scope, r.title, onBack = back, onTask = openTask)
                }
            }
            composable<StorageListRoute> { entry ->
                val route = entry.toRoute<StorageListRoute>()
                StorageListScreen(
                    route.serverId,
                    onBack = back,
                    onOpen = { node, storage -> nav.navigate(StorageContentRoute(route.serverId, node, storage)) },
                    onEdit = { id -> nav.navigate(StorageEditorRoute(route.serverId, id.orEmpty())) },
                )
            }
            composable<StorageEditorRoute> { entry ->
                val route = entry.toRoute<StorageEditorRoute>()
                StorageEditorScreen(route.serverId, route.storageId.ifEmpty { null }, onDone = back)
            }
            composable<StorageContentRoute> { entry ->
                val r = entry.toRoute<StorageContentRoute>()
                StorageContentScreen(
                    serverId = r.serverId,
                    node = r.node,
                    storage = r.storage,
                    onBack = back,
                    onBackups = { nav.navigate(StorageRoute(r.serverId, r.node, r.storage)) },
                    onTask = openTask,
                )
            }
            composable<BackupJobsRoute> { entry ->
                val route = entry.toRoute<BackupJobsRoute>()
                BackupJobsScreen(
                    serverId = route.serverId,
                    onBack = back,
                    onEdit = { jobId -> nav.navigate(BackupJobEditorRoute(route.serverId, jobId.orEmpty())) },
                    onTask = openTask,
                )
            }
            composable<BackupJobEditorRoute> { entry ->
                val route = entry.toRoute<BackupJobEditorRoute>()
                BackupJobEditorScreen(route.serverId, route.jobId.ifEmpty { null }, onBack = back)
            }
            composable<ResourcesRoute> {
                ResourcesScreen(
                    onGuest = openGuest,
                    onNode = openNode,
                    onStorage = openStorage,
                    onTask = openTask,
                    onTool = openTool,
                )
            }
            composable<TasksRoute> { TasksScreen(onTask = openTask) }
            composable<MoreRoute> {
                MoreScreen(
                    onServers = { nav.navigate(ServersRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onAlerts = { nav.navigate(AlertsRoute) },
                    onAbout = { nav.navigate(AboutRoute) },
                )
            }
            composable<ServersRoute> {
                ServersScreen(
                    onBack = back,
                    onAdd = { nav.navigate(ServerEditorRoute()) },
                    onEdit = { id -> nav.navigate(ServerEditorRoute(id)) },
                    onLogin = { id -> nav.navigate(LoginRoute(id)) },
                )
            }
            composable<ServerEditorRoute> { entry ->
                val route = entry.toRoute<ServerEditorRoute>()
                ServerEditorScreen(
                    serverId = route.serverId,
                    onBack = back,
                    onSaved = { id, isNew ->
                        nav.popBackStack()
                        if (isNew) nav.navigate(LoginRoute(id))
                    },
                )
            }
            composable<LoginRoute> { entry ->
                val route = entry.toRoute<LoginRoute>()
                LoginScreen(serverId = route.serverId, onBack = back, onLoggedIn = back)
            }
            composable<GuestRoute> { entry ->
                val r = entry.toRoute<GuestRoute>()
                GuestScreen(
                    GuestRef(r.serverId, r.node, GuestType.fromApi(r.type) ?: GuestType.QEMU, r.vmid),
                    onBack = back,
                    onTask = openTask,
                    onTool = openTool,
                )
            }
            composable<BackupsRoute> { entry ->
                val r = entry.toRoute<BackupsRoute>()
                val ref = GuestRef(r.serverId, r.node, GuestType.fromApi(r.type) ?: GuestType.QEMU, r.vmid)
                BackupsScreen(BackupScope.Guest(ref, r.name), onBack = back, onTask = openTask)
            }
            composable<OptionsRoute> { entry ->
                val r = entry.toRoute<OptionsRoute>()
                val ref = GuestRef(r.serverId, r.node, GuestType.fromApi(r.type) ?: GuestType.QEMU, r.vmid)
                OptionsScreen(ref, guestName = r.name, onBack = back)
            }
            composable<CreateGuestRoute> { entry ->
                val r = entry.toRoute<CreateGuestRoute>()
                CreateGuestScreen(
                    serverId = r.serverId,
                    node = r.node,
                    type = GuestType.fromApi(r.type) ?: GuestType.QEMU,
                    onBack = back,
                    onTask = openTask,
                    onOpenGuest = { ref ->
                        nav.popBackStack()
                        openGuest(ref)
                    },
                )
            }
            composable<CloudInitRoute> { entry ->
                val r = entry.toRoute<CloudInitRoute>()
                CloudInitScreen(GuestRef(r.serverId, r.node, GuestType.QEMU, r.vmid), guestName = r.name, onBack = back, onTask = openTask)
            }
            composable<HardwareRoute> { entry ->
                val r = entry.toRoute<HardwareRoute>()
                val ref = GuestRef(r.serverId, r.node, GuestType.fromApi(r.type) ?: GuestType.QEMU, r.vmid)
                HardwareScreen(ref, guestName = r.name, onBack = back, onTask = openTask)
            }
            composable<CloneRoute> { entry ->
                val r = entry.toRoute<CloneRoute>()
                val ref = GuestRef(r.serverId, r.node, GuestType.fromApi(r.type) ?: GuestType.QEMU, r.vmid)
                CloneScreen(ref, guestName = r.name, onBack = back, onTask = openTask, onOpenGuest = openGuest)
            }
            composable<MigrateRoute> { entry ->
                val r = entry.toRoute<MigrateRoute>()
                val ref = GuestRef(r.serverId, r.node, GuestType.fromApi(r.type) ?: GuestType.QEMU, r.vmid)
                MigrateScreen(ref, guestName = r.name, onBack = back, onTask = openTask, onMigrated = onMigrated)
            }
            composable<ConsoleRoute> { entry ->
                val r = entry.toRoute<ConsoleRoute>()
                val kind = ConsoleKind.valueOf(r.kind)
                val target = ConsoleTarget(kind, r.node, r.vmid.takeIf { kind != ConsoleKind.NODE }, r.name, r.command.ifEmpty { null })
                ConsoleScreen(r.serverId, target, onBack = back)
            }
            composable<StorageRoute> { entry ->
                val r = entry.toRoute<StorageRoute>()
                BackupsScreen(BackupScope.Storage(r.serverId, r.node, r.storage), onBack = back, onTask = openTask)
            }
            composable<SnapshotsRoute> { entry ->
                val r = entry.toRoute<SnapshotsRoute>()
                SnapshotsScreen(
                    GuestRef(r.serverId, r.node, GuestType.fromApi(r.type) ?: GuestType.QEMU, r.vmid),
                    guestName = r.name,
                    onBack = back,
                    onTask = openTask,
                )
            }
            composable<NodeRoute> { entry ->
                val r = entry.toRoute<NodeRoute>()
                NodeScreen(
                    serverId = r.serverId,
                    node = r.node,
                    onBack = back,
                    onGuest = openGuest,
                    onShell = { nav.navigate(ConsoleRoute(r.serverId, r.node, ConsoleKind.NODE.name, 0, r.node)) },
                    onSsh = { nav.navigate(SshRoute(r.serverId, r.node, 0, "", r.node)) },
                    onCreate = { type -> nav.navigate(CreateGuestRoute(r.serverId, r.node, type.apiName)) },
                    onFirewall = { nav.navigate(FirewallRoute(r.serverId, "node", r.node, r.node)) },
                    onAdmin = { page -> nav.navigate(NodeAdminRoute(r.serverId, r.node, page.name)) },
                )
            }
            composable<NodeAdminRoute> { entry ->
                val r = entry.toRoute<NodeAdminRoute>()
                when (NodeAdminPage.valueOf(r.page)) {
                    NodeAdminPage.UPDATES -> UpdatesScreen(
                        r.serverId,
                        r.node,
                        onBack = back,
                        onRepositories = { nav.navigate(NodeAdminRoute(r.serverId, r.node, NodeAdminPage.REPOSITORIES.name)) },
                        onUpgrade = { nav.navigate(ConsoleRoute(r.serverId, r.node, ConsoleKind.NODE.name, 0, r.node, command = "upgrade")) },
                        onTask = openTask,
                    )
                    NodeAdminPage.REPOSITORIES -> RepositoriesScreen(r.serverId, r.node, onBack = back, onTask = openTask)
                    NodeAdminPage.SERVICES -> ServicesScreen(r.serverId, r.node, onBack = back, onTask = openTask)
                    NodeAdminPage.JOURNAL -> JournalScreen(r.serverId, r.node, onBack = back)
                    NodeAdminPage.SYSTEM -> SystemSettingsScreen(r.serverId, r.node, onBack = back, onTask = openTask)
                    NodeAdminPage.NETWORK -> NetworkScreen(r.serverId, r.node, onBack = back, onTask = openTask)
                    NodeAdminPage.DISKS -> DisksScreen(r.serverId, r.node, onBack = back, onTask = openTask)
                }
            }
            composable<TaskLogRoute> { entry ->
                val r = entry.toRoute<TaskLogRoute>()
                TaskLogScreen(serverId = r.serverId, node = r.node, upid = r.upid, onBack = back)
            }
            composable<SettingsRoute> { SettingsScreen(onBack = back, onSshKeys = { nav.navigate(SshKeysRoute) }) }
            composable<SshRoute> { entry ->
                val r = entry.toRoute<SshRoute>()
                SshScreen(r.serverId, r.node, r.vmid, r.type, r.name, onBack = back, onKeys = { nav.navigate(SshKeysRoute) })
            }
            composable<SshKeysRoute> { SshKeysScreen(onBack = back) }
            composable<AlertsRoute> { AlertsScreen(onBack = back) }
            composable<AboutRoute> { AboutScreen(onBack = back) }
        }
    }

    // Offers to share the report if the previous run crashed (privacy-preserving, see CrashReporter).
    CrashReportDialog()
}
