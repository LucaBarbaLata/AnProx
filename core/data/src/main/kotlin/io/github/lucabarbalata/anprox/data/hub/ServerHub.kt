package io.github.lucabarbalata.anprox.data.hub

import io.github.lucabarbalata.anprox.data.store.ProfileStore
import io.github.lucabarbalata.anprox.data.store.SecretStore
import io.github.lucabarbalata.anprox.model.auth.AuthSession
import io.github.lucabarbalata.anprox.model.auth.TfaKind
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import io.github.lucabarbalata.anprox.network.ApiException
import io.github.lucabarbalata.anprox.network.ProxmoxConnection
import io.github.lucabarbalata.anprox.network.auth.LoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a server stands, authentication-wise. */
enum class AuthState {
    /** Never logged in, or the user logged out. */
    LOGGED_OUT,

    /** A valid session exists. */
    LOGGED_IN,

    /** The session expired and could not be renewed silently (e.g. two-factor is required). */
    SESSION_EXPIRED,
}

/** What the UI needs to know about one saved server. */
data class ServerState(
    val profile: ServerProfile,
    val auth: AuthState,
) {
    val id: String get() = profile.id
}

/**
 * The heart of multi-server support (questionnaire Q6): one [ProxmoxConnection] per saved
 * server, kept in sync with [ProfileStore], with sessions persisted in [SecretStore].
 *
 * Every API call from the app goes through [use], which transparently logs in again with the
 * remembered password when the server rejects an expired session.
 */
class ServerHub(
    private val profileStore: ProfileStore,
    private val secretStore: SecretStore,
    private val scope: CoroutineScope,
    private val connectionFactory: (ServerProfile) -> ProxmoxConnection = { ProxmoxConnection(it) },
) {
    private class Runtime(val profile: ServerProfile, val connection: ProxmoxConnection, val auth: AuthState)

    private val runtimes = MutableStateFlow<Map<String, Runtime>>(emptyMap())
    private val syncLock = Mutex()
    private val loaded = MutableStateFlow(false)

    /** False until the saved profiles have been read once (avoids flashing an empty state). */
    val isLoaded: StateFlow<Boolean> = loaded

    /** Saved servers in display order, with their auth state. */
    val servers: StateFlow<List<ServerState>> = runtimes
        .map { states(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The same list as [servers], read directly. [servers] is updated on [scope] a moment after
     * a change, so code that runs right after [awaitLoaded] or a login (a background worker in a
     * freshly started process) uses this instead.
     */
    fun serversNow(): List<ServerState> = states(runtimes.value)

    private fun states(map: Map<String, Runtime>): List<ServerState> = map.values
        .sortedWith(compareBy({ it.profile.sortOrder }, { it.profile.name.lowercase() }))
        .map { ServerState(it.profile, it.auth) }

    init {
        scope.launch {
            profileStore.profiles.collect { sync(it) }
        }
    }

    /** Suspends until the saved profiles have been loaded once. */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    fun server(id: String): ServerState? = runtimes.value[id]?.let { ServerState(it.profile, it.auth) }

    private suspend fun sync(profiles: List<ServerProfile>) = syncLock.withLock {
        val current = runtimes.value
        val next = profiles.associate { profile ->
            val existing = current[profile.id]
            profile.id to if (existing != null && existing.profile.connectionKey() == profile.connectionKey()) {
                Runtime(profile, existing.connection, existing.auth)
            } else {
                val connection = connectionFactory(profile)
                connection.restore(secretStore.session(profile.id))
                connection.onSessionChanged = { session -> persistSession(profile.id, session) }
                val auth = if (connection.session != null) AuthState.LOGGED_IN else AuthState.LOGGED_OUT
                Runtime(profile, connection, auth)
            }
        }
        runtimes.value = next
        loaded.value = true
    }

    private fun persistSession(profileId: String, session: AuthSession?) {
        scope.launch { secretStore.setSession(profileId, session) }
    }

    private fun setAuth(id: String, auth: AuthState) = runtimes.update { map ->
        val r = map[id] ?: return@update map
        map + (id to Runtime(r.profile, r.connection, auth))
    }

    private fun runtime(id: String): Runtime =
        runtimes.value[id] ?: throw IllegalArgumentException("Unknown server $id")

    // ---------------------------------------------------------------- profiles

    suspend fun save(profile: ServerProfile) = profileStore.upsert(profile)

    suspend fun delete(id: String) {
        secretStore.clear(id)
        profileStore.delete(id)
    }

    // ---------------------------------------------------------------- authentication

    /** Realms for the login screen of server [id]. */
    suspend fun realms(id: String) = runtime(id).connection.realms()

    /**
     * First login step. When [remember] is true and the login succeeds (now or after the second
     * factor), the password is stored encrypted for silent re-login.
     */
    suspend fun login(id: String, password: String, remember: Boolean): LoginResult {
        val r = runtime(id)
        val result = r.connection.login(password)
        rememberPassword(id, if (remember) password else null)
        if (result is LoginResult.Success) setAuth(id, AuthState.LOGGED_IN)
        return result
    }

    suspend fun completeTfa(id: String, partialTicket: String, kind: TfaKind, answer: String): AuthSession {
        val session = runtime(id).connection.completeTfa(partialTicket, kind, answer)
        setAuth(id, AuthState.LOGGED_IN)
        return session
    }

    suspend fun logout(id: String) {
        runtime(id).connection.logout()
        secretStore.setPassword(id, null)
        setAuth(id, AuthState.LOGGED_OUT)
    }

    private suspend fun rememberPassword(id: String, password: String?) {
        secretStore.setPassword(id, password)
        val profile = runtime(id).profile
        if (profile.rememberPassword != (password != null)) {
            profileStore.upsert(profile.copy(rememberPassword = password != null))
        }
    }

    /** Call when the device's network changes so each server retries its primary endpoint first. */
    fun onNetworkChanged() = runtimes.value.values.forEach { it.connection.endpoints.reset() }

    // ---------------------------------------------------------------- API access

    /**
     * Runs [block] with server [id]'s connection. If the session has expired and the password is
     * remembered, logs in again silently and retries once. If that is impossible (no password,
     * or two-factor required) the server is marked [AuthState.SESSION_EXPIRED] and the
     * [ApiException.Unauthorized] is rethrown so the UI can ask the user.
     */
    suspend fun <T> use(id: String, block: suspend (ProxmoxConnection) -> T): T {
        val r = runtime(id)
        if (r.connection.session == null && !reloginSilently(id)) {
            throw ApiException.Unauthorized("Not logged in")
        }
        return try {
            block(r.connection)
        } catch (e: ApiException.Unauthorized) {
            if (!reloginSilently(id)) throw e
            block(r.connection)
        }
    }

    private suspend fun reloginSilently(id: String): Boolean {
        val r = runtime(id)
        val password = secretStore.password(id)
        if (password == null) {
            if (r.auth == AuthState.LOGGED_IN) setAuth(id, AuthState.SESSION_EXPIRED)
            return false
        }
        return when (r.connection.login(password)) {
            is LoginResult.Success -> {
                setAuth(id, AuthState.LOGGED_IN)
                true
            }
            is LoginResult.NeedsTfa -> {
                setAuth(id, AuthState.SESSION_EXPIRED)
                false
            }
        }
    }
}

/** Two profiles with the same key can share a connection (and its session). */
private fun ServerProfile.connectionKey() = listOf(product, endpoints, username, realm, allowInsecureTls)
