# Glossary

## Proxmox

| Term | Meaning |
|---|---|
| **PVE** | Proxmox Virtual Environment: the hypervisor platform (KVM VMs + LXC containers). |
| **PBS** | Proxmox Backup Server: deduplicating backup storage for PVE and hosts. |
| **Node** | One physical (or virtual) server running PVE. |
| **Cluster** | Several PVE nodes managed together, sharing configuration through corosync. |
| **Quorum** | A majority of cluster nodes can see each other. Without it, the cluster refuses most changes to stay consistent. |
| **Guest** | A VM or a container. |
| **VM / QEMU / KVM** | A full virtual machine, with its own kernel. API path segment `qemu`. |
| **CT / LXC** | A Linux container sharing the host kernel. API path segment `lxc`. |
| **VMID** | The numeric ID of a guest (100, 101, …), unique per cluster. |
| **Template** | A guest marked read-only, used as a source for clones. |
| **Realm** | Where a user is authenticated: `pam` (Linux users), `pve`/`pbs` (built-in), LDAP, AD, OpenID. A user ID is `user@realm`. |
| **Ticket** | The session token returned by `POST /access/ticket`. It's sent as a cookie and lives 2 hours. |
| **CSRF prevention token** | Returned with the ticket and required as a header on every write request. |
| **TFA** | Two-factor authentication: TOTP (authenticator app), recovery keys, WebAuthn (security keys), Yubico OTP. |
| **Partial ticket** | A ticket that says "password OK, second factor still needed", with the challenge embedded after `!tfa!`. |
| **UPID** | Unique Process ID of a task, e.g. `UPID:pve1:0003A2F1:01B2C3D4:65A1B2C3:qmstart:100:root@pam:`. |
| **Task** | A long-running operation (start, backup, migrate…) with a status and a log. |
| **vzdump** | PVE's backup tool. A "vzdump" task is a backup. |
| **RRD** | Round-robin database: the performance history behind the charts. |
| **HA** | High Availability: PVE restarts managed guests on other nodes if a node fails. |
| **Replication** | Periodic ZFS snapshots shipped to another node. |
| **Ceph** | Distributed storage built into PVE (OSDs, monitors, pools). |
| **SDN** | Software-Defined Networking: zones, VNets, subnets managed cluster-wide. |
| **Datastore** | A PBS storage location holding backup snapshots. |
| **GC / prune / verify / sync** | PBS maintenance: garbage collection, deleting old snapshots, checking integrity, copying between servers. |
| **noVNC / xterm.js / SPICE** | The three console types: graphical (VNC in a browser), terminal, and SPICE (needs a native viewer). |
| **Guest agent** | A helper inside a VM (qemu-guest-agent) that reports IPs, can freeze filesystems, etc. |
| **Self-signed certificate** | A TLS certificate not issued by a trusted authority. Proxmox creates one on install. |

## Android & Kotlin

| Term | Meaning |
|---|---|
| **Jetpack Compose** | Android's modern UI toolkit: UI is written as Kotlin functions annotated `@Composable`. |
| **Composable** | A function that describes UI. Compose re-runs ("recomposes") it when its inputs change. |
| **State / recomposition** | When a `State` value read by a composable changes, Compose redraws just that part. |
| **ViewModel** | An object that holds a screen's state and survives rotation. It's cleared when the screen closes. |
| **StateFlow / Flow** | Kotlin streams of values. `StateFlow` always has a current value, which makes it ideal for UI state. |
| **Coroutine** | A lightweight background task. `suspend` functions can pause without blocking a thread. |
| **viewModelScope** | A coroutine scope that is cancelled when the ViewModel is cleared. |
| **collectAsStateWithLifecycle** | Turns a Flow into Compose state, and pauses collection when the app is in the background. |
| **UDF (unidirectional data flow)** | State flows down to the UI and events flow up to the ViewModel. |
| **Dependency injection (DI)** | Objects get what they need passed in instead of creating it. AnProx uses **Koin**. |
| **Koin** | A lightweight DI library: `single { }` creates one instance, `viewModel { }` one per screen. |
| **Retrofit** | Turns an annotated Kotlin interface into HTTP calls. |
| **OkHttp / interceptor** | The HTTP client. Interceptors are middleware that see every request (AnProx uses them for failover and auth). |
| **kotlinx.serialization** | Converts JSON to and from Kotlin classes annotated `@Serializable`, using code generated at compile time. |
| **DataStore** | Jetpack's key-value / document storage, the replacement for SharedPreferences. |
| **Android Keystore** | System storage for cryptographic keys, often hardware-backed. Keys can't be exported. |
| **R8 / minification** | Shrinks and obfuscates release builds. "Keep rules" stop it removing what reflection needs. |
| **Gradle / version catalog** | The build tool. `gradle/libs.versions.toml` lists every dependency version in one place. |
| **AGP** | Android Gradle Plugin: teaches Gradle how to build Android apps. |
| **compileSdk / minSdk / targetSdk** | API level compiled against / oldest supported / behaviour the app opts into. |
| **Edge-to-edge** | Drawing behind the status and navigation bars, then padding content with "insets". |
| **Lint** | A static analysis tool that flags Android-specific bugs and bad practices. |
