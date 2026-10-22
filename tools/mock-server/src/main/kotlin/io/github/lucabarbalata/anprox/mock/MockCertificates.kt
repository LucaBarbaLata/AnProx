package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.pve.AcmeAccountName
import io.github.lucabarbalata.anprox.model.pve.AcmeDirectory
import io.github.lucabarbalata.anprox.model.pve.AcmeMeta
import io.github.lucabarbalata.anprox.model.pve.CertificateInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/**
 * Certificates and ACME (`/nodes/{node}/certificates/…`, `/nodes/{node}/config`,
 * `/cluster/acme/…`), written from pve-manager's `PVE/API2/{Certificates,ACME,ACMEAccount,
 * ACMEPlugin,NodeConfig}.pm`.
 *
 * Every node starts with the cluster CA and its own certificate; pve1 also has a Let's Encrypt
 * certificate for `pve1.example.com` that expires in 20 days (so renewing is allowed), ordered
 * with the `default` account through the `cloudflare` DNS plugin.
 */
internal class MockCertificates(nodes: List<String>, private val nowSeconds: () -> Long) {
    private class NodeCerts(var custom: CertificateInfo?, val config: MutableMap<String, String>)

    private val nodes: Map<String, NodeCerts> = nodes.associateWith { name ->
        val now = nowSeconds()
        val custom = if (name == "pve1") letsEncrypt("pve1.example.com", now - 70 * DAY, now + 20 * DAY) else null
        val config = if (name == "pve1") mutableMapOf("acme" to "account=default", "acmedomain0" to "domain=pve1.example.com,plugin=cloudflare") else mutableMapOf()
        NodeCerts(custom, config)
    }

    private val accounts = linkedMapOf(
        "default" to Account(listOf("mailto:admin@example.com"), DIRECTORIES[0].url, "valid"),
    )
    private val plugins = linkedMapOf(
        "standalone" to mutableMapOf("type" to "standalone"),
        "cloudflare" to mutableMapOf("type" to "dns", "api" to "cf", "data" to java.util.Base64.getEncoder().encodeToString("CF_Token=mock-token\nCF_Account_ID=1234".toByteArray())),
    )
    private var configVersion = 0

    private data class Account(val contact: List<String>, val directory: String, val status: String)

    fun exists(node: String) = node in nodes

    fun info(node: String): List<CertificateInfo> = synchronized(this) {
        val n = nodes[node] ?: return emptyList()
        val now = nowSeconds()
        listOfNotNull(
            CertificateInfo(
                filename = "pve-root-ca.pem",
                fingerprint = fingerprint("ca"),
                subject = "/CN=Proxmox Virtual Environment/OU=8b3c0d6a-77e2-4f6e/O=PVE Cluster Manager CA",
                issuer = "/CN=Proxmox Virtual Environment/OU=8b3c0d6a-77e2-4f6e/O=PVE Cluster Manager CA",
                notbefore = now - 400 * DAY,
                notafter = now + 3250 * DAY,
                publicKeyType = "rsaEncryption",
                publicKeyBits = 4096,
            ),
            CertificateInfo(
                filename = "pve-ssl.pem",
                fingerprint = fingerprint("node-$node"),
                subject = "/OU=PVE Cluster Node/O=Proxmox Virtual Environment/CN=$node.lan",
                issuer = "/CN=Proxmox Virtual Environment/OU=8b3c0d6a-77e2-4f6e/O=PVE Cluster Manager CA",
                notbefore = now - 400 * DAY,
                notafter = now + 330 * DAY,
                san = listOf("127.0.0.1", "0000:0000:0000:0000:0000:0000:0000:0001", "localhost", "$node.lan", node),
                publicKeyType = "rsaEncryption",
                publicKeyBits = 2048,
            ),
            n.custom,
        )
    }

    /** `POST …/certificates/custom`. Null on success, or an error message. */
    fun uploadCustom(node: String, certificates: String, key: String?, force: Boolean): Pair<CertificateInfo?, String?> = synchronized(this) {
        val n = nodes[node] ?: return null to "no such node"
        if (!certificates.contains("-----BEGIN CERTIFICATE-----")) return null to "certificates: unable to parse PEM certificate"
        if (key.isNullOrBlank() && n.custom == null) return null to "key: Attempted to upload custom certificate without (existing) key."
        if (n.custom != null && !force) return null to "Custom certificate exists but 'force' is not set."
        val now = nowSeconds()
        // The mock doesn't read the PEM: every upload looks like the same certificate.
        val info = CertificateInfo(
            filename = "pveproxy-ssl.pem",
            fingerprint = fingerprint(certificates),
            subject = "/CN=uploaded-$node.example.com",
            issuer = "/C=US/O=Example CA/CN=Example Issuing CA",
            notbefore = now,
            notafter = now + 365 * DAY,
            san = listOf("uploaded-$node.example.com"),
            publicKeyType = "id-ecPublicKey",
            publicKeyBits = 256,
        )
        n.custom = info
        info to null
    }

    fun removeCustom(node: String) = synchronized(this) { nodes[node]?.custom = null }

    fun config(node: String): JsonObject? = synchronized(this) {
        val n = nodes[node] ?: return null
        buildJsonObject {
            n.config.forEach { (k, v) -> put(k, v) }
            put("digest", digest(node))
        }
    }

    /** `PUT /nodes/{node}/config`. Null on success, or an error message. */
    fun setConfig(node: String, params: Map<String, String>): String? = synchronized(this) {
        val n = nodes[node] ?: return "no such node"
        params["digest"]?.let { if (it != digest(node)) return "detected modified configuration - file changed by other user? Try again." }
        val keys = (0..5).map { "acmedomain$it" } + listOf("acme", "description", "wakeonlan", "startall-onboot-delay")
        params["delete"]?.split(',', ';', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { k ->
            if (k !in keys) return "delete: invalid format - unknown property '$k'"
            n.config.remove(k)
        }
        params.filterKeys { it != "delete" && it != "digest" }.forEach { (k, v) ->
            if (k !in keys) return "$k: property is not defined in schema and the schema does not allow additional properties"
            if (k.startsWith("acmedomain")) {
                val domain = v.split(',').map { it.trim() }.firstOrNull { it.startsWith("domain=") || '=' !in it }?.substringAfter("domain=")
                    ?: return "$k: missing domain"
                if (!Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(domain)) return "$k: value '$domain' does not look like a valid domain name!"
                val plugin = v.split(',').firstOrNull { it.trim().startsWith("plugin=") }?.substringAfter("plugin=")?.trim() ?: "standalone"
                if (plugin !in plugins) return "$k: plugin '$plugin' does not exist"
            }
            n.config[k] = v
        }
        configVersion++
        null
    }

    /** `POST …/acme/certificate`: checks what Proxmox checks, then "orders". Null or an error. */
    fun orderCheck(node: String, force: Boolean): String? = synchronized(this) {
        val n = nodes[node] ?: return "no such node"
        if (!force && n.custom != null) return "force: Custom certificate exists but 'force' is not set."
        if (n.config.keys.none { it.startsWith("acmedomain") } && n.config["acme"]?.contains("domains=") != true) return "ACME domain list in node configuration is missing!"
        val account = n.config["acme"]?.split(',')?.firstOrNull { it.startsWith("account=") }?.substringAfter('=') ?: "default"
        if (account !in accounts) return "ACME account config file '$account' does not exist."
        null
    }

    /** `PUT …/acme/certificate` refuses a certificate with more than 30 days left, unless forced. */
    fun renewCheck(node: String, force: Boolean): String? = synchronized(this) {
        val n = nodes[node] ?: return "no such node"
        val custom = n.custom ?: return "No current (custom) certificate found, please order a new certificate!"
        if (!force && (custom.notafter ?: 0) - nowSeconds() > 30 * DAY) return "force: Certificate does not expire within the next 30 days, and 'force' is not set."
        orderCheck(node, force = true)
    }

    /** What an order or renewal task leaves behind: a fresh Let's Encrypt certificate. */
    fun issue(node: String) = synchronized(this) {
        val n = nodes[node] ?: return
        val domains = n.config.filterKeys { it.startsWith("acmedomain") }.values.map { v ->
            v.split(',').first { it.startsWith("domain=") || '=' !in it }.substringAfter("domain=")
        } + (n.config["acme"]?.split(',')?.firstOrNull { it.startsWith("domains=") }?.substringAfter('=')?.split(';').orEmpty())
        val now = nowSeconds()
        n.custom = letsEncrypt(domains.firstOrNull() ?: "$node.example.com", now, now + 90 * DAY, domains)
    }

    fun orderLog(node: String): List<String> = listOf(
        "Loading ACME account details",
        "Placing ACME order",
        "Order URL: https://acme-v02.api.letsencrypt.org/acme/order/1/2",
        "Getting authorization details from 'https://acme-v02.api.letsencrypt.org/acme/authz/1/3'",
        "The validation for ${nodes[node]?.config?.values?.firstOrNull { it.startsWith("domain=") }?.substringAfter("domain=")?.substringBefore(',') ?: node} is pending!",
        "[Wed Oct 21 10:00:00 UTC 2026] Using CF Token",
        "Add TXT record: _acme-challenge",
        "Sleeping 30 seconds to wait for TXT record",
        "Checking order status",
        "valid!",
        "Creating CSR",
        "Checking order status",
        "valid!",
        "Downloading certificate",
        "Setting pveproxy certificate and key",
        "Restarting pveproxy",
    )

    // ------------------------------------------------------------------ cluster ACME

    fun accountNames(): List<AcmeAccountName> = synchronized(this) { accounts.keys.map { AcmeAccountName(it) } }

    fun account(name: String): JsonObject? = synchronized(this) {
        val a = accounts[name] ?: return null
        buildJsonObject {
            putJsonObject("account") {
                putJsonArray("contact") { a.contact.forEach { add(JsonPrimitive(it)) } }
                put("status", a.status)
                put("createdAt", "2026-07-01T09:00:00Z")
            }
            put("directory", a.directory)
            put("location", "https://acme-v02.api.letsencrypt.org/acme/acct/${name.hashCode() and 0xffffff}")
            put("tos", TOS)
        }
    }

    /** `POST /cluster/acme/account`: the checks that happen before the task starts. */
    fun registerCheck(name: String, contact: String, directory: String): String? = synchronized(this) {
        if (!Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(name)) return "name: invalid format"
        if (name in accounts) return "name: ACME account config file '$name' already exists."
        if (contact.isBlank()) return "contact: property is missing and it is not optional"
        if (DIRECTORIES.none { it.url == directory } && !directory.startsWith("https://")) return "directory: invalid format"
        null
    }

    /** What the registration task leaves behind. */
    fun register(name: String, contact: String, directory: String) = synchronized(this) {
        accounts[name] = Account(contact.split(',').map { "mailto:${it.trim()}" }, directory, "valid")
    }

    fun deactivate(name: String): Boolean = synchronized(this) { accounts.remove(name) != null }

    fun accountExists(name: String) = synchronized(this) { name in accounts }

    fun meta(directory: String): AcmeMeta = AcmeMeta(termsOfService = if (directory.contains("letsencrypt")) TOS else null, website = "https://letsencrypt.org", externalAccountRequired = !directory.contains("letsencrypt"))

    fun plugins(): List<JsonObject> = synchronized(this) {
        plugins.map { (id, p) -> pluginJson(id, p) }
    }

    private fun pluginJson(id: String, p: Map<String, String>) = buildJsonObject {
        put("plugin", id)
        p.forEach { (k, v) -> put(k, v) }
        put("digest", pluginsDigest())
    }

    fun createPlugin(params: Map<String, String>): String? = synchronized(this) {
        val id = params["id"].orEmpty()
        if (!Regex("^[A-Za-z][A-Za-z0-9_.-]*$").matches(id)) return "id: invalid format"
        if (id in plugins) return "plugin ID '$id' already defined"
        val type = params["type"] ?: return "type: property is missing and it is not optional"
        if (type !in setOf("dns", "standalone")) return "type: value '$type' does not have a value in the enumeration 'dns, standalone'"
        if (type == "dns" && params["api"].isNullOrBlank()) return "api: property is missing and it is not optional"
        params["api"]?.let { api -> if (DNS_APIS.none { it.first == api }) return "api: value '$api' does not have a value in the enumeration" }
        plugins[id] = params.filterKeys { it in setOf("type", "api", "data", "validation-delay", "nodes", "disable") }.toMutableMap()
        null
    }

    fun updatePlugin(id: String, params: Map<String, String>): String? = synchronized(this) {
        val p = plugins[id] ?: return "plugin '$id' not defined"
        params["digest"]?.let { if (it != pluginsDigest()) return "detected modified configuration - file changed by other user? Try again." }
        params["api"]?.let { api -> if (DNS_APIS.none { it.first == api }) return "api: value '$api' does not have a value in the enumeration" }
        params.filterKeys { it in setOf("api", "data", "validation-delay", "nodes", "disable") }.forEach { (k, v) -> p[k] = v }
        params["delete"]?.split(',')?.forEach { p.remove(it.trim()) }
        null
    }

    fun deletePlugin(id: String): String? = synchronized(this) {
        if (id !in plugins) return "plugin '$id' not defined"
        plugins.remove(id)
        null
    }

    fun challengeSchema(): JsonArray = JsonArray(
        DNS_APIS.map { (id, name, fields) ->
            buildJsonObject {
                put("id", id)
                put("name", name)
                put("type", "dns")
                putJsonObject("schema") {
                    putJsonObject("fields") { fields.forEach { f -> putJsonObject(f) { put("type", "string") } } }
                }
            }
        },
    )

    private fun digest(node: String): String = sha1("$node:$configVersion:${nodes[node]?.config}")
    private fun pluginsDigest(): String = sha1(plugins.toString())

    private fun letsEncrypt(domain: String, from: Long, until: Long, sans: List<String> = listOf(domain)) = CertificateInfo(
        filename = "pveproxy-ssl.pem",
        fingerprint = fingerprint("$domain:$from"),
        subject = "/CN=$domain",
        issuer = "/C=US/O=Let's Encrypt/CN=R11",
        notbefore = from,
        notafter = until,
        san = sans,
        publicKeyType = "rsaEncryption",
        publicKeyBits = 2048,
    )

    companion object {
        private const val DAY = 86_400L
        const val TOS = "https://letsencrypt.org/documents/LE-SA-v1.5-February-24-2025.pdf"
        val DIRECTORIES = listOf(
            AcmeDirectory("Let's Encrypt V2", "https://acme-v02.api.letsencrypt.org/directory"),
            AcmeDirectory("Let's Encrypt V2 Staging", "https://acme-staging-v02.api.letsencrypt.org/directory"),
        )
        private val DNS_APIS = listOf(
            Triple("cf", "Cloudflare Managed DNS", listOf("CF_Account_ID", "CF_Token", "CF_Zone_ID")),
            Triple("hetzner", "Hetzner", listOf("HETZNER_Token")),
            Triple("ovh", "OVH", listOf("OVH_AK", "OVH_AS", "OVH_CK", "OVH_END_POINT")),
        )

        private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun fingerprint(seed: String) = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }
}
