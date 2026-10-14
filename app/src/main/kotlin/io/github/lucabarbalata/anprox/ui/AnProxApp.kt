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
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
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
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.ui.dashboard.DashboardScreen
import io.github.lucabarbalata.anprox.ui.guest.GuestScreen
import io.github.lucabarbalata.anprox.ui.login.LoginScreen
import io.github.lucabarbalata.anprox.ui.navigation.AboutRoute
import io.github.lucabarbalata.anprox.ui.navigation.DashboardRoute
import io.github.lucabarbalata.anprox.ui.navigation.GuestRoute
import io.github.lucabarbalata.anprox.ui.navigation.LoginRoute
import io.github.lucabarbalata.anprox.ui.navigation.MoreRoute
import io.github.lucabarbalata.anprox.ui.navigation.NodeRoute
import io.github.lucabarbalata.anprox.ui.navigation.ResourcesRoute
import io.github.lucabarbalata.anprox.ui.navigation.ServerEditorRoute
import io.github.lucabarbalata.anprox.ui.navigation.ServersRoute
import io.github.lucabarbalata.anprox.ui.navigation.SettingsRoute
import io.github.lucabarbalata.anprox.ui.navigation.SnapshotsRoute
import io.github.lucabarbalata.anprox.ui.navigation.TaskLogRoute
import io.github.lucabarbalata.anprox.ui.navigation.TasksRoute
import io.github.lucabarbalata.anprox.ui.node.NodeScreen
import io.github.lucabarbalata.anprox.ui.resources.ResourcesScreen
import io.github.lucabarbalata.anprox.ui.servers.ServerEditorScreen
import io.github.lucabarbalata.anprox.ui.servers.ServersScreen
import io.github.lucabarbalata.anprox.ui.settings.AboutScreen
import io.github.lucabarbalata.anprox.ui.settings.MoreScreen
import io.github.lucabarbalata.anprox.ui.settings.SettingsScreen
import io.github.lucabarbalata.anprox.ui.snapshots.SnapshotsScreen
import io.github.lucabarbalata.anprox.ui.tasks.TaskLogScreen
import io.github.lucabarbalata.anprox.ui.tasks.TasksScreen

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
    val openSnapshots: (GuestRef, String) -> Unit = { ref, name ->
        nav.navigate(SnapshotsRoute(ref.serverId, ref.node, ref.type.apiName, ref.vmid, name))
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
                )
            }
            composable<ResourcesRoute> {
                ResourcesScreen(onGuest = openGuest, onNode = openNode, onTask = openTask, onSnapshots = openSnapshots)
            }
            composable<TasksRoute> { TasksScreen(onTask = openTask) }
            composable<MoreRoute> {
                MoreScreen(
                    onServers = { nav.navigate(ServersRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
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
                    onSnapshots = openSnapshots,
                )
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
                NodeScreen(serverId = r.serverId, node = r.node, onBack = back, onGuest = openGuest)
            }
            composable<TaskLogRoute> { entry ->
                val r = entry.toRoute<TaskLogRoute>()
                TaskLogScreen(serverId = r.serverId, node = r.node, upid = r.upid, onBack = back)
            }
            composable<SettingsRoute> { SettingsScreen(onBack = back) }
            composable<AboutRoute> { AboutScreen(onBack = back) }
        }
    }

    // Offers to share the report if the previous run crashed (privacy-preserving, see CrashReporter).
    CrashReportDialog()
}
