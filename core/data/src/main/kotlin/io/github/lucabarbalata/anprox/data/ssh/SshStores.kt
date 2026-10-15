package io.github.lucabarbalata.anprox.data.ssh

import io.github.lucabarbalata.anprox.model.ssh.KnownHost
import io.github.lucabarbalata.anprox.model.ssh.SshKeyInfo

/** A private key as it was created or imported, and its passphrase if it has one. */
data class SshKeySecret(val privateKey: String, val passphrase: String? = null) {
    override fun toString(): String = "SshKeySecret(…)"
}

/** The app's SSH keys. :app keeps the private halves encrypted with the Android Keystore. */
interface SshKeyStore {
    suspend fun keys(): List<SshKeyInfo>
    suspend fun secret(id: String): SshKeySecret?
    suspend fun save(info: SshKeyInfo, secret: SshKeySecret)
    suspend fun delete(id: String)
}

/** Host keys the user accepted, one per `host:port`. */
interface KnownHostStore {
    suspend fun get(host: String, port: Int): KnownHost?
    suspend fun put(host: KnownHost)
    suspend fun remove(host: String, port: Int)
    suspend fun all(): List<KnownHost>
}

class InMemorySshKeyStore : SshKeyStore {
    private val keys = LinkedHashMap<String, Pair<SshKeyInfo, SshKeySecret>>()

    override suspend fun keys(): List<SshKeyInfo> = synchronized(keys) { keys.values.map { it.first } }

    override suspend fun secret(id: String): SshKeySecret? = synchronized(keys) { keys[id]?.second }

    override suspend fun save(info: SshKeyInfo, secret: SshKeySecret) {
        synchronized(keys) { keys[info.id] = info to secret }
    }

    override suspend fun delete(id: String) {
        synchronized(keys) { keys.remove(id) }
    }
}

class InMemoryKnownHostStore : KnownHostStore {
    private val hosts = LinkedHashMap<String, KnownHost>()

    override suspend fun get(host: String, port: Int): KnownHost? = synchronized(hosts) { hosts["$host|$port"] }

    override suspend fun put(host: KnownHost) {
        synchronized(hosts) { hosts["${host.host}|${host.port}"] = host }
    }

    override suspend fun remove(host: String, port: Int) {
        synchronized(hosts) { hosts.remove("$host|$port") }
    }

    override suspend fun all(): List<KnownHost> = synchronized(hosts) { hosts.values.toList() }
}
