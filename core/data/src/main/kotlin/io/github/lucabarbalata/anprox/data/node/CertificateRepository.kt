package io.github.lucabarbalata.anprox.data.node

import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.pve.AcmeAccount
import io.github.lucabarbalata.anprox.model.pve.AcmeAccountForm
import io.github.lucabarbalata.anprox.model.pve.AcmeDirectory
import io.github.lucabarbalata.anprox.model.pve.AcmeDnsApi
import io.github.lucabarbalata.anprox.model.pve.AcmeDomain
import io.github.lucabarbalata.anprox.model.pve.AcmeMeta
import io.github.lucabarbalata.anprox.model.pve.AcmePlugin
import io.github.lucabarbalata.anprox.model.pve.AcmePluginForm
import io.github.lucabarbalata.anprox.model.pve.CertificateInfo
import io.github.lucabarbalata.anprox.model.pve.CustomCertificateForm
import io.github.lucabarbalata.anprox.model.pve.NodeAcmeConfig

/**
 * A node's certificates and how they're renewed (milestone M9): the certificates in use, a custom
 * one uploaded by hand, and ACME (Let's Encrypt) with the node's domains and the cluster's
 * accounts and challenge plugins. Proxmox VE only; Backup Server certificates come later.
 */
class CertificateRepository(private val hub: ServerHub) {

    suspend fun certificates(serverId: String, node: String): List<CertificateInfo> =
        hub.use(serverId) { it.pve { certificates(node) } }
            .sortedBy { listOf(CertificateInfo.Role.IN_USE, CertificateInfo.Role.NODE, CertificateInfo.Role.CLUSTER_CA).indexOf(it.role) }

    /** Replaces the web interface's certificate and restarts its proxy, so the new one is used. */
    suspend fun uploadCustom(serverId: String, node: String, form: CustomCertificateForm) {
        hub.use(serverId) { it.pveNoData { uploadCustomCertificate(node, form.params()) } }
    }

    /** Back to the certificate the cluster's CA signed for the node. */
    suspend fun removeCustom(serverId: String, node: String) {
        hub.use(serverId) { it.pveNoData { removeCustomCertificate(node) } }
    }

    suspend fun acmeConfig(serverId: String, node: String): NodeAcmeConfig =
        NodeAcmeConfig.from(hub.use(serverId) { it.pve { nodeConfig(node) } })

    suspend fun addDomain(serverId: String, node: String, config: NodeAcmeConfig, domain: String, plugin: String, alias: String?) {
        hub.use(serverId) { it.pveNoData { setNodeConfig(node, config.addDomainParams(domain, plugin, alias)) } }
    }

    suspend fun removeDomain(serverId: String, node: String, config: NodeAcmeConfig, domain: AcmeDomain) {
        hub.use(serverId) { it.pveNoData { setNodeConfig(node, config.removeDomainParams(domain)) } }
    }

    suspend fun setAccount(serverId: String, node: String, config: NodeAcmeConfig, account: String) {
        hub.use(serverId) { it.pveNoData { setNodeConfig(node, config.accountParams(account)) } }
    }

    /**
     * Orders a certificate for the node's domains ([force] replaces a custom one); the task's
     * UPID. The node restarts its web proxy at the end, so the connection may drop for a moment.
     */
    suspend fun order(serverId: String, node: String, force: Boolean): String =
        hub.use(serverId) { it.pve { orderAcmeCertificate(node, if (force) 1 else 0) } }

    /** Renews it; Proxmox refuses more than 30 days before it expires unless [force]. */
    suspend fun renew(serverId: String, node: String, force: Boolean): String =
        hub.use(serverId) { it.pve { renewAcmeCertificate(node, if (force) 1 else 0) } }

    // ------------------------------------------------------------------ cluster: accounts and plugins

    suspend fun accountNames(serverId: String): List<String> = hub.use(serverId) { it.pve { acmeAccounts() } }.map { it.name }

    suspend fun accounts(serverId: String): List<AcmeAccount> = hub.use(serverId) { c ->
        c.pve { acmeAccounts() }.map { ref -> AcmeAccount.from(ref.name, c.pve { acmeAccount(ref.name) }) }
    }

    suspend fun directories(serverId: String): List<AcmeDirectory> = hub.use(serverId) { it.pve { acmeDirectories() } }

    suspend fun meta(serverId: String, directory: String): AcmeMeta = hub.use(serverId) { it.pve { acmeMeta(directory) } }

    /** Registers the account with the CA; the task's UPID. */
    suspend fun register(serverId: String, form: AcmeAccountForm, meta: AcmeMeta?): String =
        hub.use(serverId) { it.pve { registerAcmeAccount(form.params(meta)) } }

    /** Deactivates the account at the CA and forgets it; the task's UPID. */
    suspend fun deactivate(serverId: String, name: String): String = hub.use(serverId) { it.pve { deactivateAcmeAccount(name) } }

    suspend fun plugins(serverId: String): List<AcmePlugin> =
        hub.use(serverId) { it.pve { acmePlugins() } }.map(AcmePlugin::from).sortedBy { it.id }

    suspend fun dnsApis(serverId: String): List<AcmeDnsApi> =
        hub.use(serverId) { it.pve { acmeChallengeSchema() } }.mapNotNull(AcmeDnsApi::from).sortedBy { it.name.lowercase() }

    suspend fun createPlugin(serverId: String, form: AcmePluginForm) {
        hub.use(serverId) { it.pveNoData { createAcmePlugin(form.params(create = true)) } }
    }

    suspend fun updatePlugin(serverId: String, plugin: AcmePlugin, form: AcmePluginForm) {
        hub.use(serverId) { it.pveNoData { updateAcmePlugin(plugin.id, form.params(create = false, digest = plugin.digest)) } }
    }

    suspend fun deletePlugin(serverId: String, id: String) {
        hub.use(serverId) { it.pveNoData { deleteAcmePlugin(id) } }
    }
}
