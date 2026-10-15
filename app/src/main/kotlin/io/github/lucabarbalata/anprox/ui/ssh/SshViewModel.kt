package io.github.lucabarbalata.anprox.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.guest.GuestRef
import io.github.lucabarbalata.anprox.data.ssh.SshClient
import io.github.lucabarbalata.anprox.data.ssh.SshException
import io.github.lucabarbalata.anprox.data.ssh.SshShell
import io.github.lucabarbalata.anprox.data.ssh.SshTargets
import io.github.lucabarbalata.anprox.model.pve.ConsoleKind
import io.github.lucabarbalata.anprox.model.pve.ConsoleTarget
import io.github.lucabarbalata.anprox.model.pve.GuestType
import io.github.lucabarbalata.anprox.model.ssh.HostKeyPrompt
import io.github.lucabarbalata.anprox.model.ssh.SshAuth
import io.github.lucabarbalata.anprox.model.ssh.SshConnectForm
import io.github.lucabarbalata.anprox.model.ssh.SshKeyInfo
import io.github.lucabarbalata.anprox.storage.SshLastConnection
import io.github.lucabarbalata.anprox.storage.SshStore
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import io.github.lucabarbalata.anprox.ui.console.ConsoleOpener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SshState(
    /** Who the screen is about, for the title: a node or a guest's name. */
    val label: String,
    val form: SshConnectForm = SshConnectForm(),
    val keys: List<SshKeyInfo> = emptyList(),
    /** Looking up the node's or guest's address. */
    val resolving: Boolean = true,
    /** The form was sent; the terminal shows. */
    val terminal: Boolean = false,
    val showProblems: Boolean = false,
    /** A host key waiting for the user's decision. */
    val prompt: HostKeyPrompt? = null,
    /** Signed in with a password: offer to add a key to the server. */
    val passwordSession: Boolean = false,
    val choosingKey: Boolean = false,
    val installing: Boolean = false,
    val message: UiText? = null,
) {
    val problem: SshConnectForm.Problem? get() = form.problem()

    /** What the console screen shows in its top bar. */
    val consoleTarget: ConsoleTarget
        get() = ConsoleTarget(ConsoleKind.SSH, form.host.trim(), name = "${form.user.trim()}@${form.host.trim()}")
}

/**
 * The SSH screen (questionnaire Q15): the connection form, host key questions and the "add my
 * key" action. The terminal itself is a [io.github.lucabarbalata.anprox.ui.console.ConsoleViewModel]
 * fed by [opener], so it behaves like the Proxmox terminals (key row, rotation, reconnect).
 */
class SshViewModel(
    private val serverId: String,
    private val node: String,
    /** 0 for the node itself. */
    private val vmid: Int,
    private val guestType: String,
    label: String,
    private val ssh: SshClient,
    private val targets: SshTargets,
    private val store: SshStore,
) : ViewModel() {
    private val ui = MutableStateFlow(SshState(label))
    private val rememberKey = "$serverId|$node|$vmid"
    private var answer: CompletableDeferred<Boolean>? = null

    @Volatile private var shell: SshShell? = null

    val state: StateFlow<SshState> = combine(ui, store.keyList) { s, keys ->
        s.copy(keys = keys.sortedBy { it.name.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ui.value)

    init {
        viewModelScope.launch {
            val last = store.lastConnection(rememberKey)
            val keys = store.keys()
            val form = if (last != null) {
                SshConnectForm(last.host, last.port.toString(), last.user, useKey = last.keyId != null && keys.any { it.id == last.keyId }, keyId = last.keyId)
            } else {
                val guest = GuestType.fromApi(guestType)?.takeIf { vmid != 0 }?.let { GuestRef(serverId, node, it, vmid) }
                val host = targets.suggestedHost(serverId, node, guest).orEmpty()
                // With a key on the phone, use it by default; otherwise a password.
                SshConnectForm(host = host, useKey = keys.isNotEmpty(), keyId = keys.firstOrNull()?.id)
            }
            ui.update { it.copy(form = form, resolving = false) }
        }
    }

    fun edit(change: SshConnectForm.() -> SshConnectForm) = ui.update { it.copy(form = it.form.change()) }

    /** Checks the form; true when the terminal may open. */
    fun start(): Boolean {
        val s = ui.value
        if (s.problem != null) {
            ui.update { it.copy(showProblems = true) }
            return false
        }
        val f = s.form
        viewModelScope.launch {
            store.setLastConnection(rememberKey, SshLastConnection(f.target().host, f.target().port, f.target().user, f.keyId.takeIf { f.useKey }))
        }
        ui.update { it.copy(terminal = true, passwordSession = false) }
        return true
    }

    /** Back to the form (the terminal's session is ended by the screen). */
    fun editConnection() {
        shell = null
        ui.update { it.copy(terminal = false, passwordSession = false) }
    }

    /** For the terminal: signs in with the form as it is now. */
    val opener: ConsoleOpener = object : ConsoleOpener {
        override suspend fun open(): SshShell {
            val form = ui.value.form
            val opened = ssh.connect(form.target(), form.auth()) { prompt -> ask(prompt) }
            shell = opened
            ui.update { it.copy(passwordSession = form.auth() is SshAuth.Password) }
            return opened
        }
    }

    private suspend fun ask(prompt: HostKeyPrompt): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        answer = deferred
        ui.update { it.copy(prompt = prompt) }
        return try {
            deferred.await()
        } finally {
            ui.update { it.copy(prompt = null) }
        }
    }

    fun answerHostKey(trust: Boolean) {
        answer?.complete(trust)
        answer = null
    }

    fun chooseKey() = ui.update { it.copy(choosingKey = true) }

    fun dismissChooseKey() = ui.update { it.copy(choosingKey = false) }

    /** Adds [key] to the server's `authorized_keys` and signs in with it next time. */
    fun installKey(key: SshKeyInfo) {
        val session = shell ?: return
        ui.update { it.copy(choosingKey = false, installing = true) }
        viewModelScope.launch {
            try {
                ssh.installKey(session, key)
                val f = ui.value.form.copy(useKey = true, keyId = key.id, password = "")
                store.setLastConnection(rememberKey, SshLastConnection(f.target().host, f.target().port, f.target().user, key.id))
                ui.update { it.copy(form = f, installing = false, passwordSession = false, message = UiText.res(R.string.ssh_key_installed, key.name)) }
            } catch (e: SshException) {
                ui.update { it.copy(installing = false, message = e.toUiText()) }
            }
        }
    }

    fun consumeMessage() = ui.update { it.copy(message = null) }

    override fun onCleared() {
        answer?.complete(false)
    }
}
