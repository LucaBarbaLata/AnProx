package io.github.lucabarbalata.anprox.model.link

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A place in the app that notifications, shortcuts and widgets can open (questionnaire Q28),
 * written as an `anprox://` link:
 *
 * - `anprox://dashboard`, `anprox://resources`, `anprox://tasks`, `anprox://alerts`
 * - `anprox://guest?server=…&node=…&type=qemu&vmid=100`
 * - `anprox://node?server=…&node=…`
 * - `anprox://task?server=…&node=…&upid=…`
 *
 * Values are URL-encoded, so node names and UPIDs (which contain `:` and `@`) survive.
 */
sealed interface AppLink {
    data object Dashboard : AppLink
    data object Resources : AppLink
    data object Tasks : AppLink
    data object Alerts : AppLink
    data class Guest(val serverId: String, val node: String, val type: String, val vmid: Int) : AppLink
    data class Node(val serverId: String, val node: String) : AppLink
    data class Task(val serverId: String, val node: String, val upid: String) : AppLink

    fun toUri(): String = when (this) {
        Dashboard -> "$SCHEME://dashboard"
        Resources -> "$SCHEME://resources"
        Tasks -> "$SCHEME://tasks"
        Alerts -> "$SCHEME://alerts"
        is Guest -> link("guest", "server" to serverId, "node" to node, "type" to type, "vmid" to vmid.toString())
        is Node -> link("node", "server" to serverId, "node" to node)
        is Task -> link("task", "server" to serverId, "node" to node, "upid" to upid)
    }

    companion object {
        const val SCHEME = "anprox"
        private val GUEST_TYPES = setOf("qemu", "lxc")

        /** The link in [uri], or null for anything that isn't a complete AnProx link. */
        fun parse(uri: String?): AppLink? {
            if (uri == null) return null
            val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
            if (parsed.scheme != SCHEME) return null
            val query = parsed.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2)
                decode(k) to decode(v)
            }
            fun need(key: String) = query[key]?.takeIf { it.isNotBlank() }
            return when (parsed.host) {
                "dashboard" -> Dashboard
                "resources" -> Resources
                "tasks" -> Tasks
                "alerts" -> Alerts
                "guest" -> {
                    val type = need("type")?.takeIf { it in GUEST_TYPES } ?: return null
                    val vmid = need("vmid")?.toIntOrNull() ?: return null
                    Guest(need("server") ?: return null, need("node") ?: return null, type, vmid)
                }
                "node" -> Node(need("server") ?: return null, need("node") ?: return null)
                "task" -> Task(need("server") ?: return null, need("node") ?: return null, need("upid") ?: return null)
                else -> null
            }
        }

        private fun link(host: String, vararg params: Pair<String, String>): String =
            "$SCHEME://$host?" + params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        // The String-charset overloads: the Charset ones need Android 13.
        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
