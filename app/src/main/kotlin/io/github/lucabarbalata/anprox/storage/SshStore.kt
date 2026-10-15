package io.github.lucabarbalata.anprox.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lucabarbalata.anprox.data.ssh.KnownHostStore
import io.github.lucabarbalata.anprox.data.ssh.SshKeySecret
import io.github.lucabarbalata.anprox.data.ssh.SshKeyStore
import io.github.lucabarbalata.anprox.model.ssh.KnownHost
import io.github.lucabarbalata.anprox.model.ssh.SshKeyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException

/** How the user last connected to a node or guest, to fill the form next time. No password. */
@Serializable
data class SshLastConnection(val host: String, val port: Int, val user: String, val keyId: String? = null)

/**
 * The SSH client's data (questionnaire Q15) in its own DataStore file: the keys (private halves
 * and passphrases encrypted with [KeystoreCipher]), the accepted host keys, and the last
 * connection per node or guest.
 */
class SshStore(context: Context) : SshKeyStore, KnownHostStore {
    private val store = context.applicationContext.sshDataStore
    private val cipher = KeystoreCipher("anprox_ssh_v1")
    private val keysKey = stringPreferencesKey("keys")
    private val hostsKey = stringPreferencesKey("known_hosts")

    private fun secretKey(id: String) = stringPreferencesKey("secret_$id")

    private fun lastKey(target: String) = stringPreferencesKey("last_$target")

    private val data = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val keyList: Flow<List<SshKeyInfo>> = data.map { prefs ->
        prefs[keysKey]?.let { runCatching { StorageJson.decodeFromString<List<SshKeyInfo>>(it) }.getOrNull() }.orEmpty()
    }

    val knownHosts: Flow<List<KnownHost>> = data.map { prefs ->
        prefs[hostsKey]?.let { runCatching { StorageJson.decodeFromString<List<KnownHost>>(it) }.getOrNull() }.orEmpty()
    }

    override suspend fun keys(): List<SshKeyInfo> = keyList.first()

    override suspend fun secret(id: String): SshKeySecret? = withContext(Dispatchers.IO) {
        val encrypted = data.first()[secretKey(id)] ?: return@withContext null
        runCatching { StorageJson.decodeFromString<StoredSecret>(cipher.decrypt(encrypted)) }.getOrNull()
            ?.let { SshKeySecret(it.privateKey, it.passphrase) }
    }

    override suspend fun save(info: SshKeyInfo, secret: SshKeySecret) {
        withContext(Dispatchers.IO) {
            val encrypted = cipher.encrypt(StorageJson.encodeToString(StoredSecret(secret.privateKey, secret.passphrase)))
            store.edit { prefs ->
                val list = prefs[keysKey]?.let { runCatching { StorageJson.decodeFromString<List<SshKeyInfo>>(it) }.getOrNull() }.orEmpty()
                prefs[keysKey] = StorageJson.encodeToString(list.filterNot { it.id == info.id } + info)
                prefs[secretKey(info.id)] = encrypted
            }
        }
    }

    override suspend fun delete(id: String) {
        store.edit { prefs ->
            val list = prefs[keysKey]?.let { runCatching { StorageJson.decodeFromString<List<SshKeyInfo>>(it) }.getOrNull() }.orEmpty()
            prefs[keysKey] = StorageJson.encodeToString(list.filterNot { it.id == id })
            prefs.remove(secretKey(id))
        }
    }

    override suspend fun get(host: String, port: Int): KnownHost? = all().firstOrNull { it.host == host && it.port == port }

    override suspend fun put(host: KnownHost) = editHosts { list -> list.filterNot { it.host == host.host && it.port == host.port } + host }

    override suspend fun remove(host: String, port: Int) = editHosts { list -> list.filterNot { it.host == host && it.port == port } }

    override suspend fun all(): List<KnownHost> = knownHosts.first()

    private suspend fun editHosts(change: (List<KnownHost>) -> List<KnownHost>) {
        store.edit { prefs ->
            val list = prefs[hostsKey]?.let { runCatching { StorageJson.decodeFromString<List<KnownHost>>(it) }.getOrNull() }.orEmpty()
            prefs[hostsKey] = StorageJson.encodeToString(change(list))
        }
    }

    suspend fun lastConnection(target: String): SshLastConnection? =
        data.first()[lastKey(target)]?.let { runCatching { StorageJson.decodeFromString<SshLastConnection>(it) }.getOrNull() }

    suspend fun setLastConnection(target: String, connection: SshLastConnection) {
        store.edit { it[lastKey(target)] = StorageJson.encodeToString(connection) }
    }

    @Serializable
    private data class StoredSecret(val privateKey: String, val passphrase: String? = null)
}
