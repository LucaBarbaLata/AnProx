package io.github.lucabarbalata.anprox.data.store

import io.github.lucabarbalata.anprox.model.auth.AuthSession
import io.github.lucabarbalata.anprox.model.profile.ServerProfile
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for saved servers. :app implements it with Jetpack DataStore; tests use
 * [InMemoryProfileStore]. Keeping it an interface keeps this module free of Android code.
 */
interface ProfileStore {
    val profiles: Flow<List<ServerProfile>>
    suspend fun upsert(profile: ServerProfile)
    suspend fun delete(id: String)
}

/**
 * Persistence for secrets. :app encrypts everything with a key held in the Android Keystore
 * (hardware-backed where available). Nothing secret ever goes into [ProfileStore].
 */
interface SecretStore {
    suspend fun password(profileId: String): String?
    suspend fun setPassword(profileId: String, password: String?)
    suspend fun session(profileId: String): AuthSession?
    suspend fun setSession(profileId: String, session: AuthSession?)
    suspend fun clear(profileId: String)
}
