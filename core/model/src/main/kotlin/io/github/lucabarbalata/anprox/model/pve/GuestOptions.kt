package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.toPveBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The everyday options of a VM or container (questionnaire Q19, "common settings first"), read
 * from `GET …/{qemu|lxc}/{vmid}/config` and written with `PUT …/config`.
 *
 * @property agent VMs only: whether the QEMU guest agent is enabled (`agent: 1` or `enabled=1,…`).
 * @property digest the config's checksum; sending it back makes Proxmox refuse the change if
 *   someone else edited the guest in the meantime.
 */
data class GuestOptions(
    val name: String = "",
    val onBoot: Boolean = false,
    val protection: Boolean = false,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val agent: Boolean? = null,
    val digest: String? = null,
) {
    enum class Problem { NAME, TAG }

    /** What's wrong with the user's input, or null. Same rules as Proxmox (`dns-name`, `pve-tag`). */
    fun problem(): Problem? = when {
        name.isNotEmpty() && !NAME.matches(name) -> Problem.NAME
        tags.any { !TAG.matches(it) } -> Problem.TAG
        else -> null
    }

    companion object {
        private val NAME = Regex("^(?:(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])\\.)*(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])$")
        private val TAG = Regex("^[a-zA-Z0-9_][a-zA-Z0-9_+.-]*$")

        /** A VM name or container hostname Proxmox accepts (`dns-name`). */
        fun isValidName(name: String): Boolean = NAME.matches(name)

        /** "prod; web,db  backup" → [prod, web, db, backup]. */
        fun parseTags(text: String): List<String> = text.split(';', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun fromConfig(type: GuestType, config: JsonObject): GuestOptions {
            fun string(key: String) = (config[key] as? JsonPrimitive)?.contentOrNull
            fun flag(key: String) = (config[key] as? JsonPrimitive)?.toPveBoolean() ?: false
            return GuestOptions(
                name = string(if (type == GuestType.QEMU) "name" else "hostname").orEmpty(),
                onBoot = flag("onboot"),
                protection = flag("protection"),
                description = string("description").orEmpty(),
                tags = parseTags(string("tags").orEmpty()),
                // "1", "0", or a property string like "enabled=1,fstrim_cloned_disks=1" (or just "1,fstrim…").
                agent = if (type == GuestType.QEMU) string("agent")?.let(::agentEnabled) ?: false else null,
                digest = string("digest"),
            )
        }

        private fun agentEnabled(value: String): Boolean {
            val first = value.split(',').first().trim()
            val enabled = if ('=' in first) value.split(',').firstOrNull { it.trim().startsWith("enabled=") }?.substringAfter('=') else first
            return JsonPrimitive(enabled ?: "0").toPveBoolean()
        }

        /**
         * The `PUT …/config` form for going from [old] to [new]: only changed fields, emptied text
         * fields in `delete`, and the [old] digest. Empty map if nothing changed.
         */
        fun changes(type: GuestType, old: GuestOptions, new: GuestOptions): Map<String, String> {
            val set = LinkedHashMap<String, String>()
            val delete = ArrayList<String>()
            fun text(key: String, before: String, after: String) {
                if (before == after) return
                if (after.isEmpty()) delete += key else set[key] = after
            }
            text(if (type == GuestType.QEMU) "name" else "hostname", old.name, new.name.trim())
            text("description", old.description, new.description)
            text("tags", old.tags.joinToString(";"), new.tags.joinToString(";"))
            if (old.onBoot != new.onBoot) set["onboot"] = if (new.onBoot) "1" else "0"
            if (old.protection != new.protection) set["protection"] = if (new.protection) "1" else "0"
            if (type == GuestType.QEMU && new.agent != null && old.agent != new.agent) set["agent"] = if (new.agent) "1" else "0"
            if (set.isEmpty() && delete.isEmpty()) return emptyMap()
            if (delete.isNotEmpty()) set["delete"] = delete.joinToString(",")
            old.digest?.let { set["digest"] = it }
            return set
        }
    }
}
