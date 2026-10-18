package io.github.lucabarbalata.anprox.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.datacenter.AccessRepository
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.pve.ApiToken
import io.github.lucabarbalata.anprox.model.pve.EffectivePermissions
import io.github.lucabarbalata.anprox.model.pve.ExpiryDate
import io.github.lucabarbalata.anprox.model.pve.NewApiToken
import io.github.lucabarbalata.anprox.model.pve.PveGroup
import io.github.lucabarbalata.anprox.model.pve.PveUser
import io.github.lucabarbalata.anprox.model.pve.TfaEntry
import io.github.lucabarbalata.anprox.model.pve.TokenForm
import io.github.lucabarbalata.anprox.model.pve.TotpSetup
import io.github.lucabarbalata.anprox.model.pve.UserForm
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.ui.common.TaskResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Only root@pam changes passwords and second factors without confirming its own password. */
private const val ROOT = "root@pam"

/** The "change password" dialog. */
data class PasswordDraft(val password: String = "", val confirm: String = "", val yourPassword: String = "") {
    val tooShort: Boolean get() = password.length < UserForm.MIN_PASSWORD
    val mismatch: Boolean get() = confirm.isNotEmpty() && confirm != password
    fun valid(needsYourPassword: Boolean): Boolean = !tooShort && password == confirm && (!needsYourPassword || yourPassword.isNotEmpty())
}

/** The "new API token" dialog; [expire] is a date or empty. */
data class TokenDraft(val form: TokenForm = TokenForm(), val expire: String = "") {
    val badExpiry: Boolean get() = expire.isNotBlank() && ExpiryDate.parse(expire) == null
    val valid: Boolean get() = form.valid && !badExpiry
}

/** Adding an authenticator app: the secret, then the code it shows. */
data class TotpDraft(
    val setup: TotpSetup,
    val description: String = "",
    val code: String = "",
    val yourPassword: String = "",
    /** Why the last try failed (usually a mistyped or expired code); shown in the dialog. */
    val error: UiText? = null,
) {
    /** The secret in blocks of four, easier to type into an app by hand. */
    val groupedSecret: String get() = setup.secret.chunked(4).joinToString(" ")
    fun valid(needsYourPassword: Boolean): Boolean = code.trim().length == 6 && code.trim().all(Char::isDigit) && (!needsYourPassword || yourPassword.isNotEmpty())
}

/** Second-factor changes that need confirming (and, for most users, their own password). */
sealed interface TfaAction {
    data class Toggle(val entry: TfaEntry) : TfaAction
    data class Delete(val entry: TfaEntry) : TfaAction
    data object Recovery : TfaAction
}

data class TfaConfirm(val action: TfaAction, val yourPassword: String = "")

data class UserUiState(
    val loading: Boolean = true,
    val error: UiText? = null,
    val user: PveUser? = null,
    val tokens: List<ApiToken> = emptyList(),
    val tfa: List<TfaEntry> = emptyList(),
    val tfaError: UiText? = null,
    val permissions: EffectivePermissions? = null,
    val permissionsError: UiText? = null,
    /** Changes need the password of the signed-in user (everyone but root@pam). */
    val needsYourPassword: Boolean = true,
    val password: PasswordDraft? = null,
    val token: TokenDraft? = null,
    /** A token just made: its secret is shown once. */
    val createdToken: NewApiToken? = null,
    val confirmDeleteToken: ApiToken? = null,
    val totp: TotpDraft? = null,
    val tfaConfirm: TfaConfirm? = null,
    /** Recovery keys just made: shown once. */
    val recoveryKeys: List<String>? = null,
    val confirmDelete: Boolean = false,
    val busy: Boolean = false,
    val result: TaskResult? = null,
    /** The user was deleted: the screen closes. */
    val deleted: Boolean = false,
) {
    val canChangePassword: Boolean get() = user?.realm == "pve" || user?.realm == "pam"
    val canDelete: Boolean get() = user != null && user.id != ROOT
}

/** One user: details, password, API tokens, second factors, what they may do. */
class UserViewModel(
    private val serverId: String,
    private val userId: String,
    private val repo: AccessRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(UserUiState(needsYourPassword = repo.currentUser(serverId) != ROOT))
    val state: StateFlow<UserUiState> = ui.asStateFlow()

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val usersCall = async { repo.users(serverId) }
                    val tokensCall = async { repo.tokens(serverId, userId) }
                    val user = usersCall.await().firstOrNull { it.id == userId } ?: throw ApiException.NotFound(userId)
                    val tokens = tokensCall.await()
                    ui.update { it.copy(loading = false, user = user, tokens = tokens) }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
        // Each needs its own privileges; a refusal only hides that section.
        viewModelScope.launch {
            try {
                val tfa = repo.tfa(serverId).firstOrNull { it.userId == userId }?.entries.orEmpty()
                ui.update { it.copy(tfa = tfa, tfaError = null) }
            } catch (e: ApiException) {
                ui.update { it.copy(tfaError = e.toUiText()) }
            }
        }
        viewModelScope.launch {
            try {
                val permissions = repo.permissions(serverId, userId)
                ui.update { it.copy(permissions = permissions, permissionsError = null) }
            } catch (e: ApiException) {
                ui.update { it.copy(permissionsError = e.toUiText()) }
            }
        }
    }

    // ---- password

    fun startPassword() {
        ui.update { it.copy(password = PasswordDraft()) }
    }

    fun editPassword(change: PasswordDraft.() -> PasswordDraft) {
        ui.update { s -> s.copy(password = s.password?.change()) }
    }

    fun closePassword() {
        ui.update { it.copy(password = null) }
    }

    fun savePassword() {
        val s = ui.value
        val draft = s.password ?: return
        if (!draft.valid(s.needsYourPassword)) return
        ui.update { it.copy(password = null) }
        run(UiText.res(R.string.access_password_changed)) { repo.changePassword(serverId, userId, draft.password, draft.yourPassword) }
    }

    fun unlockTfa() {
        run(UiText.res(R.string.access_tfa_unlocked)) { repo.unlockTfa(serverId, userId) }
    }

    // ---- API tokens

    fun startToken() {
        ui.update { it.copy(token = TokenDraft()) }
    }

    fun editToken(change: TokenDraft.() -> TokenDraft) {
        ui.update { s -> s.copy(token = s.token?.change()) }
    }

    fun closeToken() {
        ui.update { it.copy(token = null) }
    }

    fun createToken() {
        val draft = ui.value.token ?: return
        if (!draft.valid || ui.value.busy) return
        val form = draft.form.copy(expire = ExpiryDate.parse(draft.expire))
        ui.update { it.copy(token = null, busy = true) }
        viewModelScope.launch {
            try {
                val token = repo.createToken(serverId, userId, form)
                ui.update { it.copy(busy = false, createdToken = token) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
            load()
        }
    }

    fun closeCreatedToken() {
        ui.update { it.copy(createdToken = null) }
    }

    fun requestDeleteToken(token: ApiToken) {
        ui.update { it.copy(confirmDeleteToken = token) }
    }

    fun dismissDeleteToken() {
        ui.update { it.copy(confirmDeleteToken = null) }
    }

    fun deleteToken() {
        val token = ui.value.confirmDeleteToken ?: return
        ui.update { it.copy(confirmDeleteToken = null) }
        run(UiText.res(R.string.access_deleted, token.id)) { repo.deleteToken(serverId, userId, token.id) }
    }

    // ---- second factors

    fun startTotp() {
        ui.update { it.copy(totp = TotpDraft(TotpSetup.random(userId))) }
    }

    fun editTotp(change: TotpDraft.() -> TotpDraft) {
        ui.update { s -> s.copy(totp = s.totp?.change()?.copy(error = null)) }
    }

    fun closeTotp() {
        ui.update { it.copy(totp = null) }
    }

    fun addTotp() {
        val s = ui.value
        val draft = s.totp ?: return
        if (!draft.valid(s.needsYourPassword) || s.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repo.addTotp(serverId, draft.setup, draft.description, draft.code, draft.yourPassword)
                ui.update { it.copy(busy = false, totp = null, result = TaskResult(UiText.res(R.string.access_totp_added), null, ok = true)) }
                load()
            } catch (e: ApiException) {
                // Keep the dialog open to try another code.
                ui.update { it.copy(busy = false, totp = draft.copy(code = "", error = e.toUiText())) }
            }
        }
    }

    fun requestTfa(action: TfaAction) {
        ui.update { it.copy(tfaConfirm = TfaConfirm(action)) }
    }

    fun editTfaPassword(password: String) {
        ui.update { s -> s.copy(tfaConfirm = s.tfaConfirm?.copy(yourPassword = password)) }
    }

    fun dismissTfa() {
        ui.update { it.copy(tfaConfirm = null) }
    }

    fun confirmTfa() {
        val s = ui.value
        val confirm = s.tfaConfirm ?: return
        if (s.needsYourPassword && confirm.yourPassword.isEmpty()) return
        ui.update { it.copy(tfaConfirm = null) }
        val password = confirm.yourPassword
        when (val action = confirm.action) {
            is TfaAction.Toggle -> run(UiText.res(R.string.access_saved)) {
                repo.setTfaEnabled(serverId, userId, action.entry.id, !action.entry.enabled, password)
            }
            is TfaAction.Delete -> run(UiText.res(R.string.access_tfa_removed)) { repo.deleteTfa(serverId, userId, action.entry.id, password) }
            TfaAction.Recovery -> {
                if (s.busy) return
                ui.update { it.copy(busy = true) }
                viewModelScope.launch {
                    try {
                        val keys = repo.addRecoveryKeys(serverId, userId, password)
                        ui.update { it.copy(busy = false, recoveryKeys = keys) }
                    } catch (e: ApiException) {
                        ui.update { it.copy(busy = false, result = TaskResult(e.toUiText(), null, ok = false)) }
                    }
                    load()
                }
            }
        }
    }

    fun closeRecoveryKeys() {
        ui.update { it.copy(recoveryKeys = null) }
    }

    // ---- deleting the user

    fun requestDelete() {
        ui.update { it.copy(confirmDelete = true) }
    }

    fun dismissDelete() {
        ui.update { it.copy(confirmDelete = false) }
    }

    fun delete() {
        ui.update { it.copy(confirmDelete = false, busy = true) }
        viewModelScope.launch {
            try {
                repo.deleteUser(serverId, userId)
                ui.update { it.copy(busy = false, deleted = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, result = TaskResult(e.toUiText(), null, ok = false)) }
            }
        }
    }

    fun consumeResult() {
        ui.update { it.copy(result = null) }
    }

    private fun run(done: UiText, call: suspend () -> Unit) {
        if (ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = try {
                call()
                TaskResult(done, null, ok = true)
            } catch (e: ApiException) {
                TaskResult(e.toUiText(), null, ok = false)
            }
            ui.update { it.copy(busy = false, result = result) }
            load()
        }
    }
}

data class UserEditorState(
    val creating: Boolean,
    val loading: Boolean = true,
    val error: UiText? = null,
    val original: PveUser? = null,
    val form: UserForm = UserForm(),
    /** The expiry date as typed (`YYYY-MM-DD`), empty for never. */
    val expire: String = "",
    val groups: List<PveGroup> = emptyList(),
    val realms: List<Realm> = emptyList(),
    val busy: Boolean = false,
    val saveError: UiText? = null,
    /** Saved: the screen closes. */
    val done: Boolean = false,
) {
    val badExpiry: Boolean get() = expire.isNotBlank() && ExpiryDate.parse(expire) == null
    val problem: UserForm.Problem? get() = form.problem(creating)
    val canSave: Boolean get() = !loading && !busy && error == null && problem == null && !badExpiry
}

/** Datacenter → Permissions → Users → add a user ([userId] null) or edit one. */
class UserEditorViewModel(
    private val serverId: String,
    private val userId: String?,
    private val repo: AccessRepository,
) : ViewModel() {
    private val ui = MutableStateFlow(UserEditorState(creating = userId == null))
    val state: StateFlow<UserEditorState> = ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val groupsCall = async { repo.groups(serverId) }
                    val realmsCall = async { repo.realms(serverId) }
                    val userCall = async { userId?.let { id -> repo.users(serverId).firstOrNull { it.id == id } ?: throw ApiException.NotFound(id) } }
                    val groups = groupsCall.await()
                    val realms = realmsCall.await()
                    val user = userCall.await()
                    ui.update { s ->
                        s.copy(
                            loading = false,
                            groups = groups,
                            realms = realms,
                            original = user,
                            form = user?.let(UserForm::from) ?: s.form,
                            expire = user?.expire?.takeIf { it > 0 }?.let { ExpiryDate.format(it) } ?: s.expire,
                        )
                    }
                }
            } catch (e: ApiException) {
                ui.update { it.copy(loading = false, error = e.toUiText()) }
            }
        }
    }

    fun edit(change: UserForm.() -> UserForm) {
        ui.update { it.copy(form = it.form.change(), saveError = null) }
    }

    fun setExpire(text: String) {
        ui.update { it.copy(expire = text, saveError = null) }
    }

    fun toggleGroup(group: String) {
        edit { copy(groups = if (group in groups) groups - group else groups + group) }
    }

    fun save() {
        val s = ui.value
        if (!s.canSave) return
        val form = s.form.copy(expire = ExpiryDate.parse(s.expire))
        ui.update { it.copy(busy = true, saveError = null) }
        viewModelScope.launch {
            try {
                val original = s.original
                if (original == null) repo.createUser(serverId, form) else repo.updateUser(serverId, original, form)
                ui.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                ui.update { it.copy(busy = false, saveError = e.toUiText()) }
            }
        }
    }
}
