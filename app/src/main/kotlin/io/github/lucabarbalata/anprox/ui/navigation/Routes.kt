package io.github.lucabarbalata.anprox.ui.navigation

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
@Serializable data class NodeRoute(val serverId: String, val node: String)
@Serializable data class TaskLogRoute(val serverId: String, val node: String, val upid: String)

// Settings
@Serializable data object SettingsRoute
@Serializable data object AboutRoute
