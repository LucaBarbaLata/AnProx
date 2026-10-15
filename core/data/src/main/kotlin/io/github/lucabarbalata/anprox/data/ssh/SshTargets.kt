package io.github.lucabarbalata.anprox.data.ssh

import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.network.ApiException

/** Where a node or guest is probably reachable over SSH, to fill in the connection form. */
class SshTargets(private val hub: ServerHub, private val guests: GuestRepository) {

    /**
     * A guest: its first address the guest agent (or the container) reports, IPv4 first, skipping
     * container and VPN bridges. A node: its cluster address, or else the host the app reaches
     * the API at. Null when nothing is known.
     */
    suspend fun suggestedHost(serverId: String, node: String, guest: GuestRef? = null): String? {
        if (guest != null) {
            val nics = try {
                guests.networkInfo(guest).nics
            } catch (_: ApiException) {
                return null
            }.filterNot { nic -> BRIDGES.any { nic.name.startsWith(it) } }
            return nics.firstNotNullOfOrNull { it.ipv4.firstOrNull() } ?: nics.firstNotNullOfOrNull { it.ipv6.firstOrNull() }
        }
        val fromCluster = try {
            hub.use(serverId) { it.pve { clusterStatus() } }.firstOrNull { it.type == "node" && it.name == node }?.ip
        } catch (_: ApiException) {
            null
        }
        return fromCluster?.takeIf { it.isNotBlank() } ?: apiHost(serverId)
    }

    private fun apiHost(serverId: String): String? {
        val url = hub.server(serverId)?.profile?.endpoints?.firstOrNull()?.url ?: return null
        return url.substringAfter("://").substringBefore('/').let { authority ->
            if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
        }.takeIf { it.isNotBlank() }
    }

    private companion object {
        val BRIDGES = listOf("docker", "br-", "veth", "virbr", "cni", "flannel", "cali", "tailscale", "wg", "zt")
    }
}
