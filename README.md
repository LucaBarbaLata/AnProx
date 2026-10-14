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
| Design | "Midnight Glass", a bespoke dark-first design with light, dark and AMOLED themes |
| Privacy | No analytics, no ads, no Google services. The app only talks to your own servers. |
| License | [GPL-3.0](LICENSE) |
| Status | **Beta**: [0.1.0-beta.1](https://github.com/LucaBarbaLata/AnProx/releases/tag/v0.1.0-beta.1) (milestone M0, foundation). M1 is in progress. See the [roadmap](docs/roadmap.md). |

## What works today (M0)

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
- **Snapshots** *(next beta)*. The snapshot tree of every VM and container: take one (with RAM
  for running VMs), roll back, or delete.
- **Performance charts** *(next beta)*. CPU, memory, network and disk history of every node and
  guest, from the last hour to the last year, with touch to read exact values.
- **Nodes.** Live usage, hardware and kernel info, and the guests running on the node.
- **Tasks.** A merged timeline of every server's tasks with live, auto-scrolling logs.
- **Themes.** System, light or dark, plus pure-black AMOLED and an adjustable live refresh rate.

Everything else, from consoles and snapshots to backups, Ceph, SDN, firewall, permissions and
widgets, is planned in milestones. See the [roadmap](docs/roadmap.md) and the
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
[docs/third-party](docs/third-party).
