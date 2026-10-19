package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.obj
import io.github.lucabarbalata.anprox.model.json.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The everyday datacenter options (`GET/PUT /cluster/options`, `datacenter.cfg`). Text fields
 * hold what the user typed; empty means Proxmox's default, and emptying a field deletes the key.
 *
 * Proxmox answers `migration`, `ha` and `next-id` as objects (it parses `datacenter.cfg`), but
 * older versions and the config file use property strings; both are read, property strings are
 * written.
 */
data class DatacenterOptions(
    /** `html5` (noVNC), `xtermjs`, `vv` (SPICE) or `applet`; empty is the browser default. */
    val console: String = "",
    val keyboard: String = "",
    val language: String = "",
    val httpProxy: String = "",
    val emailFrom: String = "",
    val macPrefix: String = "",
    val maxWorkers: String = "",
    /** `secure` or `insecure`; empty is `secure`. */
    val migrationType: String = "",
    /** CIDR of the network migrations use. */
    val migrationNetwork: String = "",
    /** `conditional`, `freeze`, `failover` or `migrate`; empty is `conditional`. */
    val haShutdownPolicy: String = "",
    val nextIdLower: String = "",
    val nextIdUpper: String = "",
    val description: String = "",
    val digest: String? = null,
) {
    enum class Problem { HTTP_PROXY, EMAIL, MAC_PREFIX, MAX_WORKERS, MIGRATION_NETWORK, NEXT_ID }

    /** What Proxmox would refuse, checked before sending. */
    fun problem(): Problem? = when {
        httpProxy.isNotBlank() && !HTTP_PROXY.matches(httpProxy.trim()) -> Problem.HTTP_PROXY
        emailFrom.isNotBlank() && !EMAIL.matches(emailFrom.trim()) -> Problem.EMAIL
        macPrefix.isNotBlank() && !MAC_PREFIX.matches(macPrefix.trim()) -> Problem.MAC_PREFIX
        maxWorkers.isNotBlank() && maxWorkers.trim().toIntOrNull()?.takeIf { it >= 1 } == null -> Problem.MAX_WORKERS
        migrationNetwork.isNotBlank() && !isCidr(migrationNetwork.trim()) -> Problem.MIGRATION_NETWORK
        !nextIdValid() -> Problem.NEXT_ID
        else -> null
    }

    private fun nextIdValid(): Boolean {
        val lower = nextIdLower.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return false }
        val upper = nextIdUpper.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return false }
        if (lower != null && lower !in 100..999_999_999) return false
        if (upper != null && upper !in 100..1_000_000_000) return false
        return lower == null || upper == null || lower < upper
    }

    companion object {
        val CONSOLES = listOf("", "html5", "xtermjs", "vv")
        val MIGRATION_TYPES = listOf("", "secure", "insecure")
        val HA_POLICIES = listOf("", "conditional", "freeze", "failover", "migrate")

        private val HTTP_PROXY = Regex("^https?://\\S+$")
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+$")
        /** Proxmox's `mac-prefix`: up to three hex bytes, the first one unicast (even). */
        private val MAC_PREFIX = Regex("^[0-9a-fA-F][02468aceACE](:[0-9a-fA-F]{2}){0,2}:?$")

        private fun isCidr(text: String): Boolean {
            val ip = text.substringBefore('/')
            val prefix = text.substringAfter('/', "").toIntOrNull() ?: return false
            return if (':' in ip) {
                prefix in 0..128 && Regex("^[0-9a-fA-F:]+$").matches(ip)
            } else {
                val parts = ip.split('.')
                prefix in 0..32 && parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
            }
        }

        fun fromJson(o: JsonObject): DatacenterOptions {
            /** A format property: an object from the API, or a property string. */
            fun part(key: String, field: String): String {
                o.obj(key)?.let { return it.str(field).orEmpty() }
                val text = (o[key] as? JsonPrimitive)?.contentOrNull
                return PropertyString.parse(text, defaultKey = if (key == "migration") "type" else null)[field].orEmpty()
            }
            return DatacenterOptions(
                console = o.str("console").orEmpty(),
                keyboard = o.str("keyboard").orEmpty(),
                language = o.str("language").orEmpty(),
                httpProxy = o.str("http_proxy").orEmpty(),
                emailFrom = o.str("email_from").orEmpty(),
                macPrefix = o.str("mac_prefix").orEmpty(),
                maxWorkers = o.str("max_workers").orEmpty(),
                migrationType = part("migration", "type"),
                migrationNetwork = part("migration", "network"),
                haShutdownPolicy = part("ha", "shutdown_policy"),
                nextIdLower = part("next-id", "lower"),
                nextIdUpper = part("next-id", "upper"),
                description = o.str("description").orEmpty(),
                digest = o.str("digest"),
            )
        }

        /** The `PUT /cluster/options` form from [old] to [new]: changed keys, emptied ones in `delete`. */
        fun changes(old: DatacenterOptions, new: DatacenterOptions): Map<String, String> {
            val set = LinkedHashMap<String, String>()
            val delete = ArrayList<String>()
            fun put(key: String, before: String, after: String) {
                if (before.trim() == after.trim()) return
                if (after.isBlank()) delete += key else set[key] = after.trim()
            }
            fun putFormat(key: String, before: PropertyString, after: PropertyString) {
                if (before == after) return
                if (after == PropertyString.EMPTY) delete += key else set[key] = after.toString()
            }
            fun prop(vararg pairs: Pair<String, String>) = PropertyString.of(*pairs.map { (k, v) -> k to v.trim().ifEmpty { null } }.toTypedArray())

            put("console", old.console, new.console)
            put("keyboard", old.keyboard, new.keyboard)
            put("language", old.language, new.language)
            put("http_proxy", old.httpProxy, new.httpProxy)
            put("email_from", old.emailFrom, new.emailFrom)
            put("mac_prefix", old.macPrefix, new.macPrefix)
            put("max_workers", old.maxWorkers, new.maxWorkers)
            putFormat(
                "migration",
                prop("type" to old.migrationType, "network" to old.migrationNetwork),
                prop("type" to new.migrationType, "network" to new.migrationNetwork),
            )
            putFormat("ha", prop("shutdown_policy" to old.haShutdownPolicy), prop("shutdown_policy" to new.haShutdownPolicy))
            putFormat("next-id", prop("lower" to old.nextIdLower, "upper" to old.nextIdUpper), prop("lower" to new.nextIdLower, "upper" to new.nextIdUpper))
            if (old.description != new.description) {
                if (new.description.isBlank()) delete += "description" else set["description"] = new.description
            }
            if (set.isEmpty() && delete.isEmpty()) return emptyMap()
            if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
            old.digest?.let { set["digest"] = it }
            return set
        }
    }
}
