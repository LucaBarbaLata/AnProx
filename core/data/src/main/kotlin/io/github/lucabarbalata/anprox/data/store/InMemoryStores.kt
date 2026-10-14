package io.github.lucabarbalata.anprox.data.store

import io.github.lucabarbalata.anprox.model.auth.AuthSession
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Non-persistent [ProfileStore] for tests and Compose previews. */
class InMemoryProfileStore(initial: List<ServerProfile> = emptyList()) : ProfileStore {
    private val state = MutableStateFlow(initial)
    override val profiles: Flow<List<ServerProfile>> = state

    override suspend fun upsert(profile: ServerProfile) =
        state.update { list -> list.filterNot { it.id == profile.id } + profile }

    override suspend fun delete(id: String) = state.update { list -> list.filterNot { it.id == id } }
}

/** Non-persistent [SecretStore] for tests and Compose previews. */
class InMemorySecretStore : SecretStore {
    private val passwords = ConcurrentHashMap<String, String>()
    private val sessions = ConcurrentHashMap<String, AuthSession>()

    override suspend fun password(profileId: String) = passwords[profileId]
    override suspend fun setPassword(profileId: String, password: String?) {
        if (password == null) passwords.remove(profileId) else passwords[profileId] = password
    }

    override suspend fun session(profileId: String) = sessions[profileId]
    override suspend fun setSession(profileId: String, session: AuthSession?) {
        if (session == null) sessions.remove(profileId) else sessions[profileId] = session
    }

    override suspend fun clear(profileId: String) {
        passwords.remove(profileId)
        sessions.remove(profileId)
    }
}
