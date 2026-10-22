package io.github.lucabarbalata.anprox.ui.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRepository
import io.github.lucabarbalata.anprox.data.node.CertificateRepository
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
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.Upid
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------ node certificates

/** A domain being added to the node's ACME certificate. */
data class DomainDraft(val domain: String = "", val plugin: String = AcmeDomain.STANDALONE, val alias: String = "") {
    val valid: Boolean get() = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$").matches(domain.trim())
}

/** Questions asked before a change that replaces or removes the certificate in use. */
enum class CertificateConfirm { ORDER_REPLACES_CUSTOM, RENEW_EARLY, REMOVE_CUSTOM }

data class CertificatesState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val certificates: List<CertificateInfo> = emptyList(),
    val acme: NodeAcmeConfig? = null,
    /** Account names and DNS plugins of the cluster; empty when the user may not read them. */
    val accounts: List<String> = emptyList(),
    val plugins: List<AcmePlugin> = emptyList(),
    val busy: Boolean = false,
    val addingDomain: DomainDraft? = null,
    val upload: CustomCertificateForm? = null,
    val confirm: CertificateConfirm? = null,
    val result: TaskResult? = null,
) {
    val inUse: CertificateInfo? get() = certificates.firstOrNull { it.role == CertificateInfo.Role.IN_USE }
}

/**
 * Node → Certificates: the certificates the node has, its ACME domains and account, ordering and
 * renewing through ACME, and a custom certificate uploaded by hand.
 */
class CertificatesViewModel(
    private val serverId: String,
    private val node: String,
    private val repo: CertificateRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(CertificatesState())
    val state: StateFlow<CertificatesState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val certs = async { repo.certificates(serverId, node) }
                    val acme = async { repo.acmeConfig(serverId, node) }
                    // Reading the cluster's accounts and plugins needs Sys.Audit on /.
                    val accounts = async { orEmpty { repo.accountNames(serverId) } }
                    val plugins = async { orEmpty { repo.plugins(serverId) } }
                    ui.update {
                        it.copy(loading = false, certificates = certs.await(), acme = acme.await(), accounts = accounts.await(), plugins = plugins.await())
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    private suspend fun <T> orEmpty(call: suspend () -> List<T>): List<T> = try {
        call()
    } catch (_: ApiException.Forbidden) {
        emptyList()
    }

    /** Orders a certificate for the node's domains; asks first when it would replace a custom one. */
    fun order() {
        if (ui.value.inUse != null && ui.value.acme?.domains.orEmpty().none { it.domain == ui.value.inUse?.commonName }) {
            ui.update { it.copy(confirm = CertificateConfirm.ORDER_REPLACES_CUSTOM) }
        } else {
            runTask(R.string.cert_ordered, R.string.cert_order_failed) { repo.order(serverId, node, force = ui.value.inUse != null) }
        }
    }

    /** Renews now; Proxmox only does that in the last 30 days unless forced, so it asks first. */
    fun renew() {
        val inUse = ui.value.inUse ?: return
        if (!inUse.expiresWithin(30, System.currentTimeMillis() / 1000)) {
            ui.update { it.copy(confirm = CertificateConfirm.RENEW_EARLY) }
        } else {
            runTask(R.string.cert_renewed, R.string.cert_renew_failed) { repo.renew(serverId, node, force = false) }
        }
    }

    fun confirm() {
        val confirm = ui.value.confirm ?: return
        ui.update { it.copy(confirm = null) }
        when (confirm) {
            CertificateConfirm.ORDER_REPLACES_CUSTOM -> runTask(R.string.cert_ordered, R.string.cert_order_failed) { repo.order(serverId, node, force = true) }
            CertificateConfirm.RENEW_EARLY -> runTask(R.string.cert_renewed, R.string.cert_renew_failed) { repo.renew(serverId, node, force = true) }
            CertificateConfirm.REMOVE_CUSTOM -> change(R.string.cert_custom_removed) { repo.removeCustom(serverId, node) }
        }
    }

    fun dismissConfirm() = ui.update { it.copy(confirm = null) }

    fun requestRemoveCustom() = ui.update { it.copy(confirm = CertificateConfirm.REMOVE_CUSTOM) }

    fun startAddDomain() = ui.update { it.copy(addingDomain = DomainDraft()) }

    fun editDomain(change: DomainDraft.() -> DomainDraft) = ui.update { s -> s.copy(addingDomain = s.addingDomain?.change()) }

    fun cancelDomain() = ui.update { it.copy(addingDomain = null) }

    fun saveDomain() {
        val draft = ui.value.addingDomain ?: return
        val acme = ui.value.acme ?: return
        if (!draft.valid || acme.freeKey == null) return
        ui.update { it.copy(addingDomain = null) }
        change(R.string.cert_domain_added) {
            repo.addDomain(serverId, node, acme, draft.domain, draft.plugin, draft.alias.takeIf { draft.plugin != AcmeDomain.STANDALONE })
        }
    }

    fun removeDomain(domain: AcmeDomain) {
        val acme = ui.value.acme ?: return
        change(R.string.cert_domain_removed) { repo.removeDomain(serverId, node, acme, domain) }
    }

    fun setAccount(name: String) {
        val acme = ui.value.acme ?: return
        if (name == acme.accountOrDefault) return
        change(R.string.cert_account_set) { repo.setAccount(serverId, node, acme, name) }
    }

    fun startUpload() = ui.update { it.copy(upload = CustomCertificateForm()) }

    fun editUpload(change: CustomCertificateForm.() -> CustomCertificateForm) = ui.update { s -> s.copy(upload = s.upload?.change()) }

    fun cancelUpload() = ui.update { it.copy(upload = null) }

    fun saveUpload() {
        val form = ui.value.upload ?: return
        if (form.problem() != null) return
        ui.update { it.copy(upload = null) }
        change(R.string.cert_uploaded) { repo.uploadCustom(serverId, node, form) }
    }

    fun consumeResult() = ui.update { it.copy(result = null) }

    private fun change(done: Int, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(UiText.res(done), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    private fun runTask(done: Int, failed: Int, start: suspend () -> String) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                val upid = start()
                val ok = awaitThroughRestart(upid).succeeded
                TaskResult(UiText.res(if (ok) done else failed), upid, ok)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    /**
     * The task ends by restarting the node's web proxy, so a status request can meet a closed
     * connection; it is asked again a few times before giving up.
     */
    private suspend fun awaitThroughRestart(upid: String): TaskStatus {
        var attempt = 0
        while (true) {
            try {
                return guests.awaitTask(serverId, Upid.parse(upid)?.node ?: node, upid)
            } catch (e: ApiException.Unreachable) {
                if (++attempt >= RESTART_RETRIES) throw e
                delay(2_000)
            }
        }
    }

    private companion object {
        const val RESTART_RETRIES = 5
    }
}

// ------------------------------------------------------------------------------ cluster ACME

/** A DNS plugin being added ([existing] null) or changed. */
data class PluginEdit(val form: AcmePluginForm, val existing: AcmePlugin? = null)

data class AcmeState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val accounts: List<AcmeAccount> = emptyList(),
    val plugins: List<AcmePlugin> = emptyList(),
    val dnsApis: List<AcmeDnsApi> = emptyList(),
    val directories: List<AcmeDirectory> = emptyList(),
    val busy: Boolean = false,
    /** The registration form while it is open, and what the chosen CA says about itself. */
    val register: AcmeAccountForm? = null,
    val meta: AcmeMeta? = null,
    val pluginEdit: PluginEdit? = null,
    val deactivate: AcmeAccount? = null,
    val deletePlugin: AcmePlugin? = null,
    val result: TaskResult? = null,
)

/** Datacenter → Certificates (ACME): the cluster's ACME accounts and challenge plugins. */
class AcmeViewModel(
    private val serverId: String,
    private val repo: CertificateRepository,
    private val guests: GuestRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(AcmeState())
    val state: StateFlow<AcmeState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val accounts = async { repo.accounts(serverId) }
                    val plugins = async { repo.plugins(serverId) }
                    val apis = async { repo.dnsApis(serverId) }
                    val directories = async { repo.directories(serverId) }
                    ui.update {
                        it.copy(loading = false, accounts = accounts.await(), plugins = plugins.await(), dnsApis = apis.await(), directories = directories.await())
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun startRegister() {
        val name = if (ui.value.accounts.none { it.name == NodeAcmeConfig.DEFAULT_ACCOUNT }) NodeAcmeConfig.DEFAULT_ACCOUNT else ""
        val directory = ui.value.directories.firstOrNull()?.url ?: AcmeAccountForm.LETS_ENCRYPT
        ui.update { it.copy(register = AcmeAccountForm(name = name, directory = directory), meta = null) }
        loadMeta(directory)
    }

    fun editRegister(change: AcmeAccountForm.() -> AcmeAccountForm) {
        val before = ui.value.register ?: return
        val after = before.change()
        ui.update { it.copy(register = after) }
        if (after.directory != before.directory) loadMeta(after.directory)
    }

    private fun loadMeta(directory: String) {
        ui.update { it.copy(meta = null) }
        viewModelScope.launch {
            val meta = try {
                repo.meta(serverId, directory)
            } catch (_: ApiException) {
                null
            }
            ui.update { s -> if (s.register?.directory == directory) s.copy(meta = meta) else s }
        }
    }

    fun cancelRegister() = ui.update { it.copy(register = null, meta = null) }

    fun saveRegister() {
        val form = ui.value.register ?: return
        val meta = ui.value.meta
        if (form.problem(meta) != null) return
        ui.update { it.copy(register = null) }
        runTask(R.string.acme_registered, R.string.acme_register_failed) { repo.register(serverId, form, meta) }
    }

    fun requestDeactivate(account: AcmeAccount) = ui.update { it.copy(deactivate = account) }

    fun dismissDeactivate() = ui.update { it.copy(deactivate = null) }

    fun deactivate() {
        val account = ui.value.deactivate ?: return
        ui.update { it.copy(deactivate = null) }
        runTask(R.string.acme_deactivated, R.string.acme_deactivate_failed) { repo.deactivate(serverId, account.name) }
    }

    fun startAddPlugin() = ui.update { it.copy(pluginEdit = PluginEdit(AcmePluginForm(api = it.dnsApis.firstOrNull()?.id.orEmpty()))) }

    fun startEditPlugin(plugin: AcmePlugin) = ui.update { it.copy(pluginEdit = PluginEdit(AcmePluginForm.from(plugin), plugin)) }

    fun editPlugin(change: AcmePluginForm.() -> AcmePluginForm) = ui.update { s -> s.copy(pluginEdit = s.pluginEdit?.let { e -> e.copy(form = e.form.change()) }) }

    fun cancelPlugin() = ui.update { it.copy(pluginEdit = null) }

    fun savePlugin() {
        val edit = ui.value.pluginEdit ?: return
        if (edit.form.problem() != null) return
        ui.update { it.copy(pluginEdit = null) }
        change(if (edit.existing == null) R.string.acme_plugin_added else R.string.acme_plugin_saved) {
            if (edit.existing == null) repo.createPlugin(serverId, edit.form) else repo.updatePlugin(serverId, edit.existing, edit.form)
        }
    }

    fun requestDeletePlugin(plugin: AcmePlugin) = ui.update { it.copy(deletePlugin = plugin) }

    fun dismissDeletePlugin() = ui.update { it.copy(deletePlugin = null) }

    fun deletePlugin() {
        val plugin = ui.value.deletePlugin ?: return
        ui.update { it.copy(deletePlugin = null) }
        change(R.string.acme_plugin_deleted) { repo.deletePlugin(serverId, plugin.id) }
    }

    fun consumeResult() = ui.update { it.copy(result = null) }

    private fun change(done: Int, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(UiText.res(done), null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }

    private fun runTask(done: Int, failed: Int, start: suspend () -> String) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                val upid = start()
                val node = Upid.parse(upid)?.node ?: ""
                val ok = guests.awaitTask(serverId, node, upid).succeeded
                TaskResult(UiText.res(if (ok) done else failed), upid, ok)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}
