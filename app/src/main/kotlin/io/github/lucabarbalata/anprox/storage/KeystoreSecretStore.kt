package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lucabarbalata.anprox.data.store.SecretStore
import io.github.lucabarbalata.anprox.model.auth.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Passwords and session tickets, each encrypted with [KeystoreCipher] before it is written.
 * Values that can no longer be decrypted (e.g. the key was wiped) read as "absent".
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val store = context.applicationContext.secretsDataStore
    private val cipher = KeystoreCipher("anprox_secrets_v1")

    override suspend fun password(profileId: String): String? = read("password/$profileId")

    override suspend fun setPassword(profileId: String, password: String?) = write("password/$profileId", password)

    override suspend fun session(profileId: String): AuthSession? =
        read("session/$profileId")?.let { runCatching { StorageJson.decodeFromString<AuthSession>(it) }.getOrNull() }

    override suspend fun setSession(profileId: String, session: AuthSession?) =
        write("session/$profileId", session?.let { StorageJson.encodeToString(it) })

    override suspend fun clear(profileId: String) {
        write("password/$profileId", null)
        write("session/$profileId", null)
    }

    private suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val encrypted = store.data.first()[stringPreferencesKey(name)] ?: return@withContext null
        runCatching { cipher.decrypt(encrypted) }.getOrNull()
    }

    private suspend fun write(name: String, value: String?) {
        withContext(Dispatchers.IO) {
            val key = stringPreferencesKey(name)
            val encrypted = value?.let { cipher.encrypt(it) }
            store.edit { prefs -> if (encrypted == null) prefs.remove(key) else prefs[key] = encrypted }
        }
    }
}
