package io.github.lucabarbalata.anprox.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lucabarbalata.anprox.R
import io.github.lucabarbalata.anprox.data.hub.OpenIdStart
import io.github.lucabarbalata.anprox.data.hub.ServerHub
import io.github.lucabarbalata.anprox.model.ProxmoxProduct
import io.github.lucabarbalata.anprox.model.auth.OpenIdCallback
import io.github.lucabarbalata.anprox.model.auth.Realm
import io.github.lucabarbalata.anprox.model.auth.TfaKind
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import io.github.lucabarbalata.anprox.ui.common.UiText
import io.github.lucabarbalata.anprox.ui.common.toUiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginStep { PASSWORD, SECOND_FACTOR }

data class LoginState(
    val serverName: String = "",
    val product: ProxmoxProduct = ProxmoxProduct.PVE,
    val username: String = "",
    val realms: List<Realm> = emptyList(),
    val realmsLoading: Boolean = true,
    val realm: String = "pam",
    val password: String = "",
    val remember: Boolean = false,
    val step: LoginStep = LoginStep.PASSWORD,
    val tfaKinds: List<TfaKind> = emptyList(),
    val tfaKind: TfaKind = TfaKind.TOTP,
    val recoveryKeysLeft: Int? = null,
    val code: String = "",
    val busy: Boolean = false,
    val error: UiText? = null,
    /** The certificate was rejected; offer the "allow insecure" switch. */
    val certificateUntrusted: Boolean = false,
    val allowInsecure: Boolean = false,
    val done: Boolean = false,
    /** Set while the identity provider's page is open (OpenID Connect realms). */
    val openId: OpenIdStart? = null,
) {
    val selectedRealm: Realm? get() = realms.firstOrNull { it.realm == realm }
    val isOpenIdRealm: Boolean get() = selectedRealm?.isOpenId == true
}

/**
 * Two-step login (questionnaire Q9): password (+ realm), then — if the account has one — a
 * second factor. OpenID Connect realms sign in on the identity provider's page instead
 * ([OpenIdCallback]).
 */
class LoginViewModel(
    private val serverId: String,
    private val hub: ServerHub,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var partialTicket: String? = null

    init {
        viewModelScope.launch {
            hub.awaitLoaded()
            val p = profile() ?: return@launch
            _state.update {
                it.copy(
                    serverName = p.name,
                    product = p.product,
                    username = p.username,
                    realm = p.realm,
                    remember = p.rememberPassword,
                    allowInsecure = p.allowInsecureTls,
                )
            }
            loadRealms()
        }
    }

    private fun profile(): ServerProfile? = hub.server(serverId)?.profile

    private suspend fun loadRealms() {
        _state.update { it.copy(realmsLoading = true) }
        try {
            val realms = hub.realms(serverId)
            _state.update { s ->
                s.copy(
                    realms = realms,
                    realmsLoading = false,
                    // Keep the saved realm if the server offers it, else fall back to its default.
                    realm = if (realms.any { it.realm == s.realm }) s.realm else (realms.firstOrNull { it.default } ?: realms.firstOrNull())?.realm ?: s.realm,
                    error = null,
                    certificateUntrusted = false,
                )
            }
        } catch (e: ApiException) {
            _state.update {
                it.copy(realmsLoading = false, error = e.toUiText(), certificateUntrusted = e is ApiException.UntrustedCertificate)
            }
        }
    }

    fun setUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun setRealm(v: String) = _state.update { it.copy(realm = v, error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun setRemember(v: Boolean) = _state.update { it.copy(remember = v) }
    fun setCode(v: String) = _state.update { it.copy(code = v, error = null) }
    fun setTfaKind(v: TfaKind) = _state.update { it.copy(tfaKind = v, code = "", error = null) }

    /** Turns on "allow insecure certificate" for this server and retries. */
    fun allowInsecureAndRetry() {
        viewModelScope.launch {
            val p = profile() ?: return@launch
            updateProfile(p.copy(allowInsecureTls = true))
            _state.update { it.copy(allowInsecure = true, certificateUntrusted = false, error = null) }
            loadRealms()
        }
    }

    fun retry() {
        viewModelScope.launch { loadRealms() }
    }

    /** Opens the identity provider's sign-in page for the chosen OpenID Connect realm. */
    fun startOpenId() {
        val s = _state.value
        if (s.busy || !s.isOpenIdRealm) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val start = hub.openIdStart(serverId, s.realm)
                _state.update { it.copy(busy = false, openId = start) }
            } catch (e: ApiException) {
                _state.update {
                    it.copy(busy = false, error = e.toUiText(), certificateUntrusted = e is ApiException.UntrustedCertificate)
                }
            }
        }
    }

    /** The identity provider sent the browser back: sign in with what it carried. */
    fun openIdReturned(callback: OpenIdCallback) {
        val start = _state.value.openId ?: return
        when (callback) {
            is OpenIdCallback.Error -> _state.update {
                it.copy(openId = null, error = UiText.res(R.string.login_openid_refused, callback.description ?: callback.error))
            }
            is OpenIdCallback.Code -> {
                _state.update { it.copy(openId = null, busy = true, error = null) }
                viewModelScope.launch {
                    try {
                        hub.openIdLogin(serverId, callback.code, callback.state, start.redirectUrl)
                        _state.update { it.copy(busy = false, done = true) }
                    } catch (e: ApiException) {
                        _state.update { it.copy(busy = false, error = e.toUiText()) }
                    }
                }
            }
        }
    }

    /** The sign-in page could not be shown (no connection, a certificate the app won't accept). */
    fun openIdFailed(reason: String) = _state.update { it.copy(openId = null, error = UiText.res(R.string.login_openid_page_failed, reason)) }

    fun cancelOpenId() = _state.update { it.copy(openId = null) }

    fun submitPassword() {
        val s = _state.value
        if (s.isOpenIdRealm) return startOpenId()
        if (s.busy || s.password.isEmpty() || s.username.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val p = profile() ?: run {
                    _state.update { it.copy(busy = false) }
                    return@launch
                }
                if (p.username != s.username.trim() || p.realm != s.realm) {
                    updateProfile(p.copy(username = s.username.trim(), realm = s.realm))
                }
                when (val result = hub.login(serverId, s.password, s.remember)) {
                    is LoginResult.Success -> _state.update { it.copy(busy = false, done = true) }
                    is LoginResult.NeedsTfa -> {
                        val supported = result.challenge.availableKinds.filter { it != TfaKind.WEBAUTHN }
                        if (supported.isEmpty()) {
                            _state.update { it.copy(busy = false, error = UiText.res(R.string.login_webauthn_only)) }
                        } else {
                            partialTicket = result.partialTicket
                            _state.update {
                                it.copy(
                                    busy = false,
                                    step = LoginStep.SECOND_FACTOR,
                                    tfaKinds = supported,
                                    tfaKind = supported.first(),
                                    recoveryKeysLeft = result.challenge.recoveryKeysLeft?.takeIf { n -> n < 100 },
                                    code = "",
                                )
                            }
                        }
                    }
                }
            } catch (e: ApiException) {
                _state.update {
                    it.copy(busy = false, error = e.toUiText(), certificateUntrusted = e is ApiException.UntrustedCertificate)
                }
            }
        }
    }

    fun submitCode() {
        val s = _state.value
        val ticket = partialTicket ?: return
        if (s.busy || s.code.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                hub.completeTfa(serverId, ticket, s.tfaKind, s.code)
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                // The challenge may be single-use: go back to the password step (password is kept).
                partialTicket = null
                val message = if (e is ApiException.Unauthorized) UiText.res(R.string.login_code_rejected) else e.toUiText()
                _state.update { it.copy(busy = false, step = LoginStep.PASSWORD, error = message) }
            }
        }
    }

    fun backToPassword() {
        partialTicket = null
        _state.update { it.copy(step = LoginStep.PASSWORD, code = "", error = null) }
    }

    /** Saves [updated] and waits until the hub uses it (new realm/TLS setting takes effect). */
    private suspend fun updateProfile(updated: ServerProfile) {
        hub.save(updated)
        hub.servers.first { list -> list.any { it.profile == updated } }
    }
}
