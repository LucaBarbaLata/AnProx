package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.Serializable

/** One row of `GET …/firewall/aliases`: a name for an address or network, usable in rules. */
@Serializable
data class FirewallAlias(val name: String, val cidr: String, val comment: String? = null, val digest: String? = null)

/** One row of `GET …/firewall/ipset`: a named list of addresses. */
@Serializable
data class FirewallIpSet(val name: String, val comment: String? = null, val digest: String? = null)

/** One row of `GET …/firewall/ipset/{name}`. [nomatch] excludes the address from the set. */
@Serializable
data class IpSetEntry(
    val cidr: String,
    val comment: String? = null,
    @Serializable(with = PveBooleanSerializer::class)
    val nomatch: Boolean = false,
    val digest: String? = null,
)

/** Names and addresses the firewall accepts for groups, aliases and IP sets. */
object FirewallNames {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9\\-_]+$")

    /** Security groups, aliases and IP sets: a letter, then letters, digits, `-` or `_` (2+ characters). */
    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** An address or network: `10.0.0.5`, `10.0.0.0/24`, `fd00::/64`. */
    fun isAddress(text: String): Boolean =
        IpAddress.isIpv4(text) || IpAddress.isIpv4Cidr(text) || IpAddress.isIpv6(text) || IpAddress.isIpv6Cidr(text)

    /** An IP set entry: an address, or an alias (optionally `dc/` or `guest/` scoped). */
    fun isIpSetEntry(text: String): Boolean =
        isAddress(text) || isValidName(text.removePrefix("dc/").removePrefix("guest/"))
}

/** Adding or editing an alias. [original] is null when adding. */
data class FirewallAliasForm(val name: String = "", val cidr: String = "", val comment: String = "") {
    enum class Problem { NAME, ADDRESS }

    fun problem(): Problem? = when {
        !FirewallNames.isValidName(name.trim()) -> Problem.NAME
        !FirewallNames.isAddress(cidr.trim()) -> Problem.ADDRESS
        else -> null
    }

    fun createParams(): Map<String, String> = buildMap {
        put("name", name.trim())
        put("cidr", cidr.trim())
        comment.trim().takeIf { it.isNotEmpty() }?.let { put("comment", it) }
    }

    /** Renaming goes through `rename`; the digest refuses a change made meanwhile. */
    fun updateParams(original: FirewallAlias): Map<String, String> = buildMap {
        put("cidr", cidr.trim())
        put("comment", comment.trim())
        if (name.trim() != original.name) put("rename", name.trim())
        original.digest?.let { put("digest", it) }
    }

    companion object {
        fun from(a: FirewallAlias) = FirewallAliasForm(a.name, a.cidr, a.comment.orEmpty())
    }
}
