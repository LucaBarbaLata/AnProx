package io.github.lucabarbalata.anprox.data.favourites

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.AuthState
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.favourites.FavouriteRef
import io.github.lucabarbalata.anprox.model.favourites.FavouriteState
import io.github.lucabarbalata.anprox.model.favourites.FavouriteStatus
import io.github.lucabarbalata.anprox.model.pve.ClusterResource
import io.github.lucabarbalata.anprox.model.pve.PowerAction
import io.github.lucabarbalata.anprox.network.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Favourite guests for the home-screen widget (questionnaire Q28): their state with one request
 * per server, and power actions wherever the guest runs now.
 */
class FavouriteRepository(private val hub: ServerHub, private val guests: GuestRepository) {

    /** Every favourite, ordered by name. Favourites of servers that were removed are left out. */
    suspend fun statuses(refs: Collection<FavouriteRef>): List<FavouriteStatus> {
        hub.awaitLoaded()
        return coroutineScope {
            refs.groupBy { it.serverId }.map { (serverId, list) -> async { forServer(serverId, list) } }.awaitAll()
        }.flatten().sortedWith(compareBy({ it.title.lowercase() }, { it.serverName.lowercase() }, { it.vmid }))
    }

    private suspend fun forServer(serverId: String, refs: List<FavouriteRef>): List<FavouriteStatus> {
        val server = hub.server(serverId)?.takeIf { it.profile.product == ProxmoxProduct.PVE } ?: return emptyList()
        val name = server.profile.name
        fun all(state: FavouriteState) = refs.map { FavouriteStatus(serverId, name, it.type.apiName, it.vmid, state) }
        if (server.auth == AuthState.LOGGED_OUT) return all(FavouriteState.SIGNED_OUT)
        val resources = try {
            guestResources(serverId)
        } catch (e: ApiException) {
            return all(if (e is ApiException.Unauthorized) FavouriteState.SIGNED_OUT else FavouriteState.UNREACHABLE)
        }
        return refs.map { ref ->
            val r = resources.firstOrNull { it.vmid == ref.vmid && it.type == ref.type.apiName }
                ?: return@map FavouriteStatus(serverId, name, ref.type.apiName, ref.vmid, FavouriteState.GONE)
            FavouriteStatus(
                serverId = serverId,
                serverName = name,
                type = ref.type.apiName,
                vmid = ref.vmid,
                state = when (r.status) {
                    "running" -> FavouriteState.RUNNING
                    "paused" -> FavouriteState.PAUSED
                    "stopped" -> FavouriteState.STOPPED
                    // A guest on an offline node: Proxmox doesn't know.
                    else -> FavouriteState.UNREACHABLE
                },
                name = r.name,
                node = r.node,
                template = r.template,
                lock = r.lock?.takeIf { it.isNotBlank() },
            )
        }
    }

    private suspend fun guestResources(serverId: String): List<ClusterResource> =
        hub.use(serverId) { it.pve { clusterResources("vm") } }

    /**
     * Runs [action] on the favourite on the node it is on now (it may have moved since the last
     * refresh). Returns the guest it acted on and the task UPID.
     */
    suspend fun power(ref: FavouriteRef, action: PowerAction): Pair<GuestRef, String> {
        val node = guestResources(ref.serverId)
            .firstOrNull { it.vmid == ref.vmid && it.type == ref.type.apiName }?.node
            ?: throw ApiException.NotFound("${ref.type.label} ${ref.vmid} doesn't exist any more")
        val guest = GuestRef(ref.serverId, node, ref.type, ref.vmid)
        return guest to guests.power(guest, action)
    }
}
