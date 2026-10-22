package io.github.lucabarbalata.anprox.model.pve

import io.github.lucabarbalata.anprox.model.json.bool
import io.github.lucabarbalata.anprox.model.json.int
import io.github.lucabarbalata.anprox.model.json.obj
import io.github.lucabarbalata.anprox.model.json.str
import io.github.lucabarbalata.anprox.model.json.strings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * One of a node's certificates (`GET /nodes/{node}/certificates/info`): the cluster's own CA
 * (`pve-root-ca.pem`), the node certificate it signed (`pve-ssl.pem`) and, when there is one, the
 * custom or ACME certificate the web interface uses instead (`pveproxy-ssl.pem`).
 */
@Serializable
data class CertificateInfo(
    val filename: String? = null,
    val fingerprint: String? = null,
    /** OpenSSL's one-line form, e.g. `/OU=PVE Cluster Node/O=Proxmox Virtual Environment/CN=pve1.lan`. */
    val subject: String? = null,
    val issuer: String? = null,
    val notbefore: Long? = null,
    val notafter: Long? = null,
    val san: List<String> = emptyList(),
    @SerialName("public-key-type") val publicKeyType: String? = null,
    @SerialName("public-key-bits") val publicKeyBits: Int? = null,
) {
    enum class Role { CLUSTER_CA, NODE, IN_USE }

    val role: Role get() = when (filename) {
        "pve-root-ca.pem" -> Role.CLUSTER_CA
        "pveproxy-ssl.pem" -> Role.IN_USE
        else -> Role.NODE
    }

    val commonName: String? get() = commonName(subject)
    val issuerName: String? get() = commonName(issuer) ?: organisation(issuer)

    /** Days left until it expires at [nowSeconds]; negative once it has. */
    fun daysLeft(nowSeconds: Long): Long? = notafter?.let { Math.floorDiv(it - nowSeconds, DAY) }

    /** Expires within [days] (30 is when Proxmox starts renewing an ACME certificate). */
    fun expiresWithin(days: Int, nowSeconds: Long): Boolean = notafter?.let { it - nowSeconds < days * DAY } == true

    companion object {
        private const val DAY = 86_400L

        /** `CN` from either `/O=x/CN=y` or `O = x, CN = y`. */
        fun commonName(name: String?): String? = field(name, "CN")

        private fun organisation(name: String?): String? = field(name, "O")

        private fun field(name: String?, key: String): String? {
            if (name.isNullOrBlank()) return null
            val parts = if (name.startsWith("/")) name.split('/') else name.split(',')
            return parts.map { it.trim() }
                .firstOrNull { it.substringBefore('=').trim() == key }
                ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

/** What uploading a custom certificate needs: the PEM chain, and the key unless one is kept. */
data class CustomCertificateForm(val certificates: String = "", val key: String = "") {
    enum class Problem { CERTIFICATE, KEY }

    fun problem(): Problem? = when {
        !certificates.contains("-----BEGIN CERTIFICATE-----") -> Problem.CERTIFICATE
        key.isNotBlank() && !Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----").containsMatchIn(key) -> Problem.KEY
        else -> null
    }

    /**
     * `POST /nodes/{node}/certificates/custom`. It replaces an ACME certificate too (`force`), and
     * restarts the web proxy so the new one is used (`restart`), as the web interface does.
     */
    fun params(): Map<String, String> = buildMap {
        put("certificates", certificates.trim() + "\n")
        if (key.isNotBlank()) put("key", key.trim() + "\n")
        put("force", "1")
        put("restart", "1")
    }
}

/**
 * A domain on the node's ACME certificate, from `acmedomain0` … `acmedomain5` in the node config
 * (`domain=pve.example.com,plugin=cloudflare,alias=_acme.example.net`), or from the older
 * `acme: domains=a;b` list, whose domains are always validated by the standalone HTTP plugin.
 * [key] is where it lives: `acmedomainN` or `acme`.
 */
data class AcmeDomain(val domain: String, val plugin: String = STANDALONE, val alias: String? = null, val key: String) {
    val isStandalone: Boolean get() = plugin == STANDALONE

    companion object {
        const val STANDALONE = "standalone"
    }
}

/** The node's ACME settings (`GET /nodes/{node}/config`). */
data class NodeAcmeConfig(
    /** The cluster ACME account the node orders with; Proxmox uses `default` when none is set. */
    val account: String? = null,
    val domains: List<AcmeDomain> = emptyList(),
    /** The raw `acme` property, kept so a change keeps what the app doesn't edit. */
    val acme: String? = null,
    val usedKeys: Set<String> = emptySet(),
    val digest: String? = null,
) {
    /** The next free `acmedomainN`, or null when all six are taken. */
    val freeKey: String? get() = (0..MAX_INDEX).map { "acmedomain$it" }.firstOrNull { it !in usedKeys }

    val accountOrDefault: String get() = account ?: DEFAULT_ACCOUNT

    /** `PUT /nodes/{node}/config` that adds [domain] (with its [plugin] and DNS [alias]). */
    fun addDomainParams(domain: String, plugin: String, alias: String?): Map<String, String> = buildMap {
        val key = freeKey ?: error("No free ACME domain slot")
        put(key, PropertyString.of("domain" to domain.trim().lowercase(), "plugin" to plugin, "alias" to alias?.trim()?.takeIf { it.isNotEmpty() }).toString())
        // Without an `acme` line Proxmox has no account to order with; name the default.
        if (acme == null) put("acme", PropertyString.of("account" to DEFAULT_ACCOUNT).toString())
        digest?.let { put("digest", it) }
    }

    /** `PUT /nodes/{node}/config` that removes [domain]. */
    fun removeDomainParams(domain: AcmeDomain): Map<String, String> = buildMap {
        if (domain.key == "acme") {
            val rest = domains.filter { it.key == "acme" && it.domain != domain.domain }.joinToString(";") { it.domain }
            val left = PropertyString.parse(acme).with("domains", rest.ifEmpty { null }).toString()
            if (left.isEmpty()) put("delete", "acme") else put("acme", left)
        } else {
            put("delete", domain.key)
        }
        digest?.let { put("digest", it) }
    }

    /** `PUT /nodes/{node}/config` that makes the node order with [name]. */
    fun accountParams(name: String): Map<String, String> = buildMap {
        put("acme", PropertyString.parse(acme).with("account", name).toString())
        digest?.let { put("digest", it) }
    }

    companion object {
        const val DEFAULT_ACCOUNT = "default"
        const val MAX_INDEX = 5

        fun from(config: JsonObject): NodeAcmeConfig {
            val acme = config.str("acme")
            val parsed = PropertyString.parse(acme)
            val domains = mutableListOf<AcmeDomain>()
            parsed["domains"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach {
                domains += AcmeDomain(it.lowercase(), key = "acme")
            }
            val used = mutableSetOf<String>()
            for (i in 0..MAX_INDEX) {
                val key = "acmedomain$i"
                val value = config.str(key) ?: continue
                used += key
                val p = PropertyString.parse(value, defaultKey = "domain")
                val domain = p["domain"] ?: continue
                domains += AcmeDomain(domain.lowercase(), p["plugin"] ?: AcmeDomain.STANDALONE, p["alias"], key)
            }
            return NodeAcmeConfig(parsed["account"], domains, acme, used, config.str("digest"))
        }
    }
}

/** A registered ACME account (`GET /cluster/acme/account/{name}`). */
data class AcmeAccount(
    val name: String,
    /** E-mail addresses, without `mailto:`. */
    val contacts: List<String> = emptyList(),
    val status: String? = null,
    val directory: String? = null,
    val location: String? = null,
    val tos: String? = null,
    val createdAt: String? = null,
) {
    companion object {
        fun from(name: String, o: JsonObject): AcmeAccount {
            val account = o.obj("account")
            return AcmeAccount(
                name = name,
                contacts = account?.strings("contact").orEmpty().map { it.removePrefix("mailto:") },
                status = account?.str("status"),
                directory = o.str("directory"),
                location = o.str("location"),
                tos = o.str("tos"),
                createdAt = account?.str("createdAt"),
            )
        }
    }
}

@Serializable
data class AcmeAccountName(val name: String)

/** A CA that speaks ACME (`GET /cluster/acme/directories`): Let's Encrypt and its staging server. */
@Serializable
data class AcmeDirectory(val name: String, val url: String)

/** What a CA says about itself (`GET /cluster/acme/meta?directory=`). */
@Serializable
data class AcmeMeta(
    val termsOfService: String? = null,
    val website: String? = null,
    val externalAccountRequired: Boolean = false,
)

/** Registering an ACME account: `POST /cluster/acme/account` (a task). */
data class AcmeAccountForm(
    val name: String = NodeAcmeConfig.DEFAULT_ACCOUNT,
    val email: String = "",
    val directory: String = LETS_ENCRYPT,
    val acceptTos: Boolean = false,
    val eabKid: String = "",
    val eabHmacKey: String = "",
) {
    enum class Problem { NAME, EMAIL, TOS, EAB }

    fun problem(meta: AcmeMeta?): Problem? = when {
        !Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(name.trim()) -> Problem.NAME
        email.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.let { list -> list.isEmpty() || list.any { !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it) } } -> Problem.EMAIL
        meta?.termsOfService != null && !acceptTos -> Problem.TOS
        meta?.externalAccountRequired == true && (eabKid.isBlank() || eabHmacKey.isBlank()) -> Problem.EAB
        else -> null
    }

    fun params(meta: AcmeMeta?): Map<String, String> = buildMap {
        put("name", name.trim())
        put("contact", email.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))
        put("directory", directory.trim())
        // Sending the terms' address is how the CA is told they were accepted.
        if (acceptTos) meta?.termsOfService?.let { put("tos_url", it) }
        if (eabKid.isNotBlank()) {
            put("eab-kid", eabKid.trim())
            put("eab-hmac-key", eabHmacKey.trim())
        }
    }

    companion object {
        const val LETS_ENCRYPT = "https://acme-v02.api.letsencrypt.org/directory"
    }
}

/**
 * A challenge plugin (`GET /cluster/acme/plugins`): `standalone` answers HTTP-01 on port 80 of the
 * node; a `dns` plugin answers DNS-01 through a DNS provider's API ([api], e.g. `cf`) with the
 * credentials in [data] (`KEY=value` lines; Proxmox stores them base64-encoded).
 */
data class AcmePlugin(
    val id: String,
    val type: String,
    val api: String? = null,
    val data: String = "",
    val validationDelay: Int? = null,
    val disabled: Boolean = false,
    val digest: String? = null,
) {
    val isDns: Boolean get() = type == "dns"

    companion object {
        fun from(o: JsonObject): AcmePlugin = AcmePlugin(
            id = o.str("plugin") ?: o.str("id").orEmpty(),
            type = o.str("type") ?: "dns",
            api = o.str("api"),
            data = decodeData(o.str("data")),
            validationDelay = o.int("validation-delay"),
            disabled = o.bool("disable"),
            digest = o.str("digest"),
        )

        /** Proxmox keeps the data base64-encoded; anything that isn't is shown as it is. */
        fun decodeData(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return try {
                String(Base64.getDecoder().decode(raw.trim()), Charsets.UTF_8).takeIf { text -> text.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() } } ?: raw
            } catch (_: IllegalArgumentException) {
                raw
            }
        }

        fun encodeData(text: String): String {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            return Base64.getEncoder().encodeToString(lines.toByteArray(Charsets.UTF_8))
        }
    }
}

/** A DNS provider the server knows (`GET /cluster/acme/challenge-schema`), with the fields it asks for. */
data class AcmeDnsApi(val id: String, val name: String, val fields: List<String> = emptyList()) {
    companion object {
        fun from(o: JsonObject): AcmeDnsApi? {
            val id = o.str("id") ?: return null
            if (o.str("type") != null && o.str("type") != "dns") return null
            val fields = o.obj("schema")?.obj("fields")?.keys?.toList().orEmpty()
            return AcmeDnsApi(id, o.str("name") ?: id, fields)
        }
    }
}

/** Adding (or changing) a DNS challenge plugin: `POST /cluster/acme/plugins` or `PUT …/{id}`. */
data class AcmePluginForm(val id: String = "", val api: String = "", val data: String = "", val validationDelay: String = "30") {
    enum class Problem { ID, API, DATA, DELAY }

    fun problem(): Problem? = when {
        !Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(id.trim()) -> Problem.ID
        api.isBlank() -> Problem.API
        data.lines().map { it.trim() }.filter { it.isNotEmpty() }.any { '=' !in it || it.startsWith("=") } -> Problem.DATA
        validationDelay.isNotBlank() && validationDelay.trim().toIntOrNull()?.let { it in 0..172_800 } != true -> Problem.DELAY
        else -> null
    }

    /** [create] adds the plugin (with its `id` and `type`); otherwise it updates [digest]'s version. */
    fun params(create: Boolean, digest: String? = null): Map<String, String> = buildMap {
        if (create) {
            put("id", id.trim())
            put("type", "dns")
        }
        put("api", api.trim())
        put("data", AcmePlugin.encodeData(data))
        if (validationDelay.isNotBlank()) put("validation-delay", validationDelay.trim())
        if (!create) digest?.let { put("digest", it) }
    }

    companion object {
        fun from(p: AcmePlugin) = AcmePluginForm(p.id, p.api.orEmpty(), p.data, p.validationDelay?.toString() ?: "30")
    }
}
