package io.github.lucabarbalata.anprox.model.favourites

import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import kotlinx.serialization.Serializable

/**
 * A starred guest: a server, a type and a VMID. It has no node, so a favourite survives
 * migrations; the node is looked up when it's needed.
 */
data class FavouriteRef(val serverId: String, val type: GuestType, val vmid: Int) {
    /** How the app stores it: `serverId|qemu|100`. */
    val key: String get() = "$serverId|${type.apiName}|$vmid"

    companion object {
        fun parse(key: String): FavouriteRef? {
            val vmid = key.substringAfterLast('|', "").toIntOrNull() ?: return null
            val rest = key.substringBeforeLast('|')
            val type = GuestType.fromApi(rest.substringAfterLast('|', "")) ?: return null
            val serverId = rest.substringBeforeLast('|', "").takeIf { it.isNotEmpty() } ?: return null
            return FavouriteRef(serverId, type, vmid)
        }
    }
}

/** How a favourite looked at the last refresh. */
enum class FavouriteState {
    RUNNING,
    STOPPED,
    PAUSED,

    /** The server answered but doesn't have this guest any more. */
    GONE,

    /** The server didn't answer. */
    UNREACHABLE,

    /** The user logged out of the server, or the session ran out and can't be renewed. */
    SIGNED_OUT,
}

/** One favourite on the home-screen widget (questionnaire Q28). */
@Serializable
data class FavouriteStatus(
    val serverId: String,
    val serverName: String,
    val type: String,
    val vmid: Int,
    val state: FavouriteState,
    val name: String? = null,
    val node: String? = null,
    val template: Boolean = false,
    /** A lock such as `backup` or `migrate`: the guest is busy. */
    val lock: String? = null,
) {
    val key: String get() = "$serverId|$type|$vmid"

    val title: String get() = name?.takeIf { it.isNotBlank() } ?: vmid.toString()

    /** What the widget's button does: start, resume or shut down; null when there's nothing to do. */
    val action: PowerAction?
        get() = when {
            template || lock != null -> null
            state == FavouriteState.RUNNING -> PowerAction.SHUTDOWN
            state == FavouriteState.STOPPED -> PowerAction.START
            state == FavouriteState.PAUSED -> PowerAction.RESUME
            else -> null
        }
}

/** The favourites as the last refresh saw them, ordered by name. */
@Serializable
data class FavouritesSnapshot(val atMillis: Long, val guests: List<FavouriteStatus> = emptyList())
