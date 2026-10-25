# AnProx

**A free, open-source Proxmox client for Android, built for sysadmins and MSPs who run many clusters.**
It manages Proxmox VE clusters and Proxmox Backup Servers from one combined dashboard.
The goal is every function of the web UI in a fast, beautiful, native app.

> AnProx is an independent project. It is not affiliated with or endorsed by Proxmox Server
> Solutions GmbH. Proxmox is a registered trademark of Proxmox Server Solutions GmbH.

| | |
|---|---|
| Platform | Android 8.0+ (phones, tablets, foldables) |
| Built with | Kotlin, Jetpack Compose, coroutines, OkHttp/Retrofit, Koin |
| Servers | Proxmox VE 9 (8 best-effort), Proxmox Backup Server 4 (3 best-effort) |
| Design | "Midnight", a calm bespoke dark-first design with light, dark and AMOLED themes |
| Privacy | No analytics, no ads, no Google services. The app only talks to your own servers. |
| License | [GPL-3.0](LICENSE) |
| Status | **Beta**: [0.1.0-beta.2](https://github.com/LucaBarbaLata/AnProx/releases/tag/v0.1.0-beta.2) (milestone M1, daily driver, plus consoles and guest management). See the [roadmap](docs/roadmap.md). |

## What works today

- **Many servers, one overview.** Add any number of PVE clusters and PBS servers. The dashboard
  totals guests and nodes across all of them and flags what needs attention: lost quorum,
  offline nodes, storage above 85 %, and expired sessions.
- **Reachable anywhere.** Each server can have several addresses (LAN, VPN, public or reverse proxy
  sub-path), tried in order with automatic failover. You can add custom HTTP headers per address.
- **Secure sign-in.** Password login for PAM, PVE/PBS, LDAP and AD realms, with two-factor
  authentication (TOTP, recovery keys, Yubico OTP). Passwords and session tickets are encrypted
  with a hardware-backed Android Keystore key. A per-server switch allows self-signed certificates.
- **Resources.** Search and filter every VM, container, node and storage across all servers.
  On tablets the list and the detail sit side by side.
- **Guests.** Live CPU, RAM and disk gauges, details, and power actions (start, shut down, reboot,
  stop, pause, resume, reset). Dangerous actions need confirmation.
- **Snapshots** *(new in beta.2)*. The snapshot tree of every VM and container: take one (with RAM
  for running VMs), roll back, or delete.
- **Backups** *(new in beta.2)*. Back up a VM or container now, browse backups per guest or storage,
  and restore as a new guest or over the original.
- **App lock** *(new in beta.2)*. Optional fingerprint, face or screen lock check on opening the app
  and before rollbacks.
- **Performance charts** *(new in beta.2)*. CPU, memory, network and disk history of every node and
  guest, from the last hour to the last year, with touch to read exact values.
- **Consoles** *(new in beta.2)*. The VM's screen (noVNC), container and node terminals (xterm.js)
  with an extra key row for Esc, Tab, Ctrl+C and the arrows.
- **Guest management** *(new in beta.2)*. IP addresses and OS from the guest agent; edit name, tags,
  notes, start at boot and protection; clone (full or linked), convert to template, and migrate
  to another node (live for VMs).
- **Bulk actions and favourites** *(new in beta.2)*. Select several guests to start, shut down or
  stop them together; star guests to pin them to the overview.
- **Nodes.** Live usage, hardware and kernel info, and the guests running on the node.
- **Tasks.** A merged timeline of every server's tasks with live, auto-scrolling logs.
- **Themes.** System, light or dark, plus pure-black AMOLED and an adjustable live refresh rate.

Coming in **0.1.0-beta.3** (in the CI test builds now):

- **A calmer design.** The same Midnight colours with less on each screen: one card per server
  on the overview, a summary and a grid of tools for guests and nodes, folded sections.
- **Single sign-on.** Sign in through an OpenID Connect realm (Proxmox VE and Backup Server).
- **Certificates and notifications.** A node's certificates, ACME (Let's Encrypt) orders and
  renewals with HTTP or DNS plugins, your own certificate; notification targets (e-mail, SMTP,
  Gotify, webhooks) and matchers, with a test send.
- **Guest configuration.** Hardware (CPU, memory, disks, CD/DVD, network, boot order; container
  resources and mount points), cloud-init, start and shutdown order, and a wizard to create VMs
  and containers. Delete a VM or container (you type its ID).
- **Guest agent tools.** Freeze and thaw filesystems, trim them, and set a user's password
  inside the guest.
- **Consoles that work on real servers.** Rebuilt to run through the app's own connection: the
  VM screen, container consoles and the node shell.
- **SSH client.** Sign in to nodes and guests with a password or a key made on the phone, with
  host key checks; SPICE hand-off to a viewer app.
- **Alerts and Android integration.** Background checks with notifications (node or guest down,
  failed backups and tasks, thresholds, Ceph), live task notifications, two home-screen widgets
  (status, and favourite guests with power buttons), a Quick Settings tile and app shortcuts.
- **Datacenter.** Backup jobs, storage (browse, upload, download from URL, add and edit
  storages, usage history), firewall (rules, security groups, aliases, IP sets, log), high
  availability, replication, resource pools, datacenter options, the cluster log, and moving
  your servers to another phone.
- **Permissions.** Users, groups, roles, permissions (ACLs), realms with sync, API tokens,
  second factors and passwords, on Proxmox VE and on the Backup Server.
- **Ceph and SDN.** Ceph health, OSDs, pools, monitors, managers, CephFS, flags and
  configuration; SDN zones, VNets, subnets, controllers and IPAM, with pending changes and apply.
- **Node administration.** Updates and repositories, services, the system log, network
  (bridges, bonds, VLANs with apply/revert), disks with SMART and storage creation, DNS, hosts,
  time zone and subscription, task history, the template catalogue, and start, shut down or
  migrate all guests at once.
- **Proxmox Backup Server, complete.** Datastores and their contents, namespaces, verify, prune,
  garbage collection and sync jobs (run them now), remotes, notes and protection, and restoring
  single files from container and host backups.
- **Backups and tasks.** Restore to another storage, edit snapshot descriptions, stop a running
  task; a task that meets a locked guest says to try again shortly. Long task logs keep their end.
- **Accessibility.** Larger touch targets, errors and states read out by TalkBack, and screens
  that hold up at large font sizes.

Before each beta, automated tests run every read and write the app makes against a real
Proxmox VE 9 and Proxmox Backup Server 4 (see [testing.md](docs/testing.md)). Next is the road to 1.0: the
remaining API areas (hardware mappings, metric servers, cluster setup), a TalkBack run on a
phone and community translations. See the [roadmap](docs/roadmap.md) and the
[API coverage tracker](docs/api-coverage.md).

## Try it

### Install the beta

Download `AnProx-<version>.apk` from the latest pre-release on the
[Releases page](https://github.com/LucaBarbaLata/AnProx/releases) and open it on your phone.
For automatic updates, add this repository to [Obtainium](https://github.com/ImranR98/Obtainium)
with *Include prereleases* enabled. The [beta testing guide](docs/beta-testing.md) explains what to
try and how to report problems.

Every official AnProx APK is signed with the same key. Its certificate SHA-256 fingerprint is:

```
4A:9A:C7:8A:3D:51:75:C1:1B:3D:0B:12:6E:A3:ED:22:01:B0:DE:EE:12:0D:17:EE:64:60:11:42:B2:67:49:65
```

[AppVerifier](https://github.com/soupslurpr/AppVerifier) can check it on your phone. If an APK
claiming to be AnProx has a different fingerprint, don't install it.

### Install a development build

Every push builds a debug APK ("AnProx Debug") in GitHub Actions. Open the latest successful
[CI run](https://github.com/LucaBarbaLata/AnProx/actions), download the `anprox-debug-apk`
artifact, unzip it and install the APK. It installs alongside the release version.

### Try it without a Proxmox server

The repo includes a fake Proxmox server, so you can explore the app safely:

```bash
./gradlew :tools:mock-server:run
```

In the app, add a server at `https://10.0.2.2:8006` (from the Android emulator) or
`https://<your-computer-ip>:8006` (from a phone on the same Wi-Fi). Turn on
**Allow insecure certificate**, then sign in as `root@pam` with password `anprox`.
To try two-factor login, use `admin@pve` with TOTP code `123456`.
[docs/testing.md](docs/testing.md) has all the accounts.

### Build it yourself

[docs/getting-started.md](docs/getting-started.md) walks you through it from zero:
Android Studio, cloning, running on an emulator or phone, and the mock server.

```bash
./gradlew test               # unit + integration tests
./gradlew :app:assembleDebug # APK in app/build/outputs/apk/debug/
```

## Documentation

All documentation lives in [`docs/`](docs/README.md):

| Document | What's in it |
|---|---|
| [Questionnaire](docs/questionnaire.md) | The 40 kick-off decisions, which are the source of truth for scope |
| [Requirements](docs/requirements.md) | What the app must do, with IDs that map to milestones |
| [Architecture](docs/architecture.md) | How the code is organised and why, written for Android learners |
| [Design system](docs/design-system.md) | Midnight Glass tokens, components and accessibility rules |
| [Roadmap](docs/roadmap.md) | Milestones M0 to M9 on the way to full web-UI parity |
| [API coverage](docs/api-coverage.md) | Every Proxmox API area and its implementation status |
| [Security](docs/security.md) | Threat model, credential storage, the TLS switch |
| [Testing](docs/testing.md) | Test strategy, the mock server, the emulator end-to-end tour, the manual test checklist |
| [Beta testing](docs/beta-testing.md) | Installing the beta, what to try, reporting bugs |
| [Releasing](docs/releasing.md) | Signing key setup, version numbers, the Release workflow, F-Droid/IzzyOnDroid |
| [Getting started](docs/getting-started.md) | Set up, build and run, step by step |
| [Glossary](docs/glossary.md) | Proxmox and Android terms explained |
| [Decision records](docs/adr/README.md) | Why each major technical choice was made |
| [Dev log](docs/devlog.md) | Chronological notes from development sessions |

## Support the project

AnProx is free and always will be. If it saves you time, consider supporting development
(donation links will be added here before the first release). Bug reports and pull requests are
just as welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright © 2026 the AnProx contributors.
Licensed under the [GNU General Public License v3.0](LICENSE).
Bundled fonts (Inter, JetBrains Mono) are under the SIL Open Font License. See
[docs/third-party](docs/third-party). The consoles bundle xterm.js (MIT) and noVNC (MPL 2.0); the
SSH client uses JSch (BSD) and Bouncy Castle (MIT), whose licenses ship inside the app.
