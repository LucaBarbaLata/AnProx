package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.PveBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of `GET /nodes/{node}/apt/update`: a package with a newer version available (Q22). */
@Serializable
data class AptUpdate(
    @SerialName("Package") val name: String,
    @SerialName("Title") val title: String? = null,
    @SerialName("OldVersion") val oldVersion: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Origin") val origin: String? = null,
    @SerialName("Priority") val priority: String? = null,
    @SerialName("Section") val section: String? = null,
)

/** One row of `GET /nodes/{node}/apt/versions`: an installed Proxmox package. */
@Serializable
data class PackageVersion(
    @SerialName("Package") val name: String,
    @SerialName("Version") val version: String? = null,
    @SerialName("OldVersion") val oldVersion: String? = null,
    @SerialName("CurrentState") val state: String? = null,
)

/** One APT source (a line of a `.list` file, or a stanza of a `.sources` file). */
@Serializable
data class AptRepository(
    @SerialName("Types") val types: List<String> = emptyList(),
    @SerialName("URIs") val uris: List<String> = emptyList(),
    @SerialName("Suites") val suites: List<String> = emptyList(),
    @SerialName("Components") val components: List<String> = emptyList(),
    @SerialName("Comment") val comment: String? = null,
    @SerialName("Enabled")
    @Serializable(with = PveBooleanSerializer::class)
    val enabled: Boolean = true,
) {
    /** `deb http://download.proxmox.com/debian/pve bookworm pve-no-subscription`, as in a `.list` file. */
    val line: String get() = (types + uris + suites + components).joinToString(" ")
}

@Serializable
data class AptRepositoryFile(
    val path: String,
    @SerialName("file-type") val fileType: String? = null,
    val repositories: List<AptRepository> = emptyList(),
)

/** A file APT couldn't read. */
@Serializable
data class AptRepositoryError(val path: String, val error: String)

/** Proxmox's notes on a repository: `origin` (who provides it) or `warning`. [index] is its position in [path]. */
@Serializable
data class AptRepositoryInfo(
    val path: String,
    val index: Int,
    val kind: String,
    val message: String,
    val property: String? = null,
)

/** A repository Proxmox knows and can add: enterprise, no-subscription, test, and Ceph's. */
@Serializable
data class StandardRepository(
    val handle: String,
    val name: String,
    /** 1 enabled, 0 configured but disabled, null not configured. */
    val status: Int? = null,
    val description: String? = null,
)

/** `GET /nodes/{node}/apt/repositories`. */
@Serializable
data class AptRepositories(
    val digest: String = "",
    val files: List<AptRepositoryFile> = emptyList(),
    val errors: List<AptRepositoryError> = emptyList(),
    val infos: List<AptRepositoryInfo> = emptyList(),
    @SerialName("standard-repos") val standard: List<StandardRepository> = emptyList(),
) {
    fun infosFor(path: String, index: Int): List<AptRepositoryInfo> = infos.filter { it.path == path && it.index == index }

    /** Warnings about the setup as a whole, or about one repository. */
    val warnings: List<AptRepositoryInfo> get() = infos.filter { it.kind == "warning" }

    /** Standard repositories not configured yet, which can be added. */
    val addable: List<StandardRepository> get() = standard.filter { it.status == null }
}

/** `GET /nodes/{node}/subscription`. */
@Serializable
data class NodeSubscription(
    /** `active`, `notfound`, `invalid`, `expired`, `suspended`, `new`. */
    val status: String = "notfound",
    val level: String? = null,
    @SerialName("productname") val product: String? = null,
    @SerialName("nextduedate") val nextDueDate: String? = null,
    val message: String? = null,
) {
    val active: Boolean get() = status.equals("active", ignoreCase = true)
}

/** One row of `GET /nodes/{node}/services`. */
@Serializable
data class NodeService(
    val service: String = "",
    val name: String = "",
    val desc: String? = null,
    /** `running`, `stopped` or `unknown`. */
    val state: String? = null,
    @SerialName("active-state") val activeState: String? = null,
    /** `enabled`, `disabled`, `static`, `masked`, `not-found`. */
    @SerialName("unit-state") val unitState: String? = null,
) {
    val id: String get() = service.ifEmpty { name }
    val running: Boolean get() = state == "running" || activeState == "active"
    val missing: Boolean get() = unitState == "not-found" || unitState == "masked"
}

/** What can be done to a service (`POST /nodes/{node}/services/{service}/{action}`). */
enum class ServiceAction(val apiName: String) {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
    RELOAD("reload"),
}

/** Journal lines from `GET /nodes/{node}/journal`, without the `-- cursor:` markers around them. */
object Journal {
    fun lines(raw: List<String>): List<String> = raw.filterNot { it.startsWith("-- cursor:") || it.startsWith("-- No entries") }
}

/** `GET /nodes/{node}/dns`. */
@Serializable
data class NodeDns(val search: String? = null, val dns1: String? = null, val dns2: String? = null, val dns3: String? = null)

/** The DNS editor: a search domain and up to three servers. */
data class NodeDnsForm(val search: String = "", val servers: List<String> = listOf("", "", "")) {
    enum class Problem { SEARCH, SERVER }

    fun problem(): Problem? = when {
        search.isBlank() -> Problem.SEARCH
        servers.any { it.isNotBlank() && !(IpAddress.isIpv4(it.trim()) || IpAddress.isIpv6(it.trim())) } -> Problem.SERVER
        else -> null
    }

    /** `PUT /nodes/{node}/dns`; cleared servers are deleted. */
    fun params(): Map<String, String> = buildMap {
        put("search", search.trim())
        val delete = mutableListOf<String>()
        servers.forEachIndexed { i, s -> if (s.isNotBlank()) put("dns${i + 1}", s.trim()) else delete += "dns${i + 1}" }
        if (delete.isNotEmpty()) put("delete", delete.joinToString(","))
    }

    companion object {
        fun from(d: NodeDns) = NodeDnsForm(d.search.orEmpty(), listOf(d.dns1.orEmpty(), d.dns2.orEmpty(), d.dns3.orEmpty()))
    }
}

/** `GET /nodes/{node}/hosts`: `/etc/hosts` as text, with its digest. */
@Serializable
data class NodeHosts(val data: String = "", val digest: String? = null)

/** `GET /nodes/{node}/time`. [time] and [localtime] are epoch seconds (local time as if it were UTC). */
@Serializable
data class NodeTime(val timezone: String = "UTC", val time: Long? = null, val localtime: Long? = null)
