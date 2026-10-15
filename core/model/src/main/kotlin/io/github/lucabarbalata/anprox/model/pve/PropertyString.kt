package io.github.lucabarbalata.anprox.model.pve

/**
 * A Proxmox property string, the format of most hardware entries in a guest's config:
 * `local-lvm:vm-100-disk-0,size=32G,iothread=1` or `virtio=BC:24:11:2A:3B:4C,bridge=vmbr0`.
 *
 * A part without `=` is the value of the format's default key (`file` for disks, `current` for
 * memory…). Order and unknown keys are kept, so writing a string back changes only what was
 * edited, and settings the app doesn't know about survive.
 */
class PropertyString private constructor(private val parts: List<Part>) {

    private data class Part(val key: String, val value: String, val implicit: Boolean) {
        override fun toString() = if (implicit) value else "$key=$value"
    }

    operator fun get(key: String): String? = parts.firstOrNull { it.key == key }?.value

    operator fun contains(key: String): Boolean = parts.any { it.key == key }

    /** The keys in order; an implicit first part reports the default key (or its own text). */
    val keys: List<String> get() = parts.map { it.key }

    /** The key of the first part, e.g. the model of a network device (`virtio=…` or `virtio`). */
    val firstKey: String? get() = parts.firstOrNull()?.key

    /** Whether [key] was written without `key=` (e.g. `virtio` without a MAC address). */
    fun isImplicit(key: String): Boolean = parts.firstOrNull { it.key == key }?.implicit == true

    /** A copy with [key] set to [value] (in place, or last if new), or removed if [value] is null. */
    fun with(key: String, value: String?): PropertyString {
        val at = parts.indexOfFirst { it.key == key }
        return when {
            value == null && at < 0 -> this
            value == null -> PropertyString(parts.filterIndexed { i, _ -> i != at })
            at < 0 -> PropertyString(parts + Part(key, value, implicit = false))
            else -> PropertyString(parts.mapIndexed { i, p -> if (i == at) p.copy(value = value, implicit = false) else p })
        }
    }

    /** Several [with] calls; null values remove the key. */
    fun with(vararg changes: Pair<String, String?>): PropertyString = changes.fold(this) { s, (k, v) -> s.with(k, v) }

    /**
     * A copy with [key] moved to the front and set to [value]. An [implicit] part is written as a
     * bare value: `local-lvm:32,…` or `virtio,…`.
     */
    fun withFirst(key: String, value: String, implicit: Boolean = false): PropertyString =
        PropertyString(listOf(Part(key, value, implicit)) + parts.filter { it.key != key })

    override fun toString(): String = parts.joinToString(",")

    override fun equals(other: Any?): Boolean = other is PropertyString && other.toString() == toString()

    override fun hashCode(): Int = toString().hashCode()

    companion object {
        val EMPTY = PropertyString(emptyList())

        /** Parses [text]; bare parts belong to [defaultKey] (or are their own key when null). */
        fun parse(text: String?, defaultKey: String? = null): PropertyString {
            if (text.isNullOrBlank()) return EMPTY
            val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
                val eq = part.indexOf('=')
                if (eq > 0) Part(part.substring(0, eq).trim(), part.substring(eq + 1).trim(), implicit = false)
                else Part(defaultKey ?: part, part, implicit = true)
            }
            return PropertyString(parts)
        }

        /** `key=value` parts in order; null values are left out. */
        fun of(vararg pairs: Pair<String, String?>): PropertyString =
            PropertyString(pairs.mapNotNull { (k, v) -> v?.let { Part(k, it, implicit = false) } })
    }
}

/** Disk sizes as Proxmox writes them: `32G`, `512M`, `1.5T`, or plain bytes. */
object DiskSize {
    private val SIZE = Regex("^(\\d+(?:\\.\\d+)?)\\s*([KMGT]?)$", RegexOption.IGNORE_CASE)

    fun parse(text: String?): Long? {
        val m = SIZE.matchEntire(text?.trim() ?: return null) ?: return null
        val number = m.groupValues[1].toDoubleOrNull() ?: return null
        val shift = when (m.groupValues[2].uppercase()) {
            "K" -> 10
            "M" -> 20
            "G" -> 30
            "T" -> 40
            else -> 0
        }
        return (number * (1L shl shift)).toLong()
    }

    const val GIB = 1L shl 30
}
