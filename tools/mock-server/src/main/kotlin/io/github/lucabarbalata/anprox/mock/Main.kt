package io.github.lucabarbalata.anprox.mock

import io.github.lucabarbalata.anprox.model.ProxmoxProduct

/**
 * `./gradlew :tools:mock-server:run` — starts a fake PVE on 8006 and a fake PBS on 8007,
 * both HTTPS with self-signed certificates, and an SSH server on 2222, listening on all interfaces.
 */
fun main() {
    val pve = MockProxmoxServer(ProxmoxProduct.PVE, requestedPort = 8006, host = "0.0.0.0", taskDurationMillis = 2_000).start()
    pve.state.ceph.installed = true
    val pbs = MockProxmoxServer(ProxmoxProduct.PBS, requestedPort = 8007, host = "0.0.0.0", taskDurationMillis = 2_000).start()
    val ssh = MockSshServer(requestedPort = 2222, host = "0.0.0.0").start()
    println(
        """

        AnProx mock servers are running
        ───────────────────────────────
          Proxmox VE      https://<this-computer>:${pve.port}
          Backup Server   https://<this-computer>:${pbs.port}
          SSH             <this-computer> port ${ssh.port} (any user, password "anprox")
          (Android emulator: use 10.0.2.2 as the host)

          Turn on "Allow insecure certificate" — the certificates are self-signed.

          Accounts (password "anprox"):
            root@pam      full access
            admin@pve     full access + TOTP (code 123456, recovery keys aaaa-bbbb-cccc / dddd-eeee-ffff)
            auditor@pve   read-only

          Ceph is set up on the Proxmox VE cluster; pve3 is offline, so it reports HEALTH_WARN.

        Press Ctrl+C to stop.
        """.trimIndent(),
    )
    Thread.currentThread().join()
}
