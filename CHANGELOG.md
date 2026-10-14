# Changelog

All notable changes to AnProx are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [0.1.0-beta.1] — 2026-10-14 (milestone M0, "Foundation")

### Added
- **Servers:** unlimited Proxmox VE and Proxmox Backup Server profiles; multiple addresses per
  server with ordered automatic failover; per-address custom HTTP headers; reverse-proxy
  sub-paths; per-server "Allow insecure certificate" switch.
- **Sign-in:** realm picker (PAM, PVE/PBS, LDAP, AD); two-factor with TOTP, recovery keys and
  Yubico OTP; "Remember password"; automatic ticket renewal and silent re-login; one-tap fix
  for self-signed certificates.
- **Security:** passwords and session tickets encrypted with an AES-256-GCM key held in the
  Android Keystore; backups disabled; HTTPS only.
- **Overview dashboard** across all servers: guest and node totals, a "Needs attention" list
  (quorum, offline nodes, storage ≥ 85 %, expired sessions), cluster cards with CPU/RAM gauges and
  per-node usage, Backup Server datastore usage with a "full in" estimate.
- **Resources:** search and filters (VMs, containers, nodes, storage, running) across servers;
  list/detail split view on tablets.
- **Guests:** live status gauges and details; start, shut down, reboot, stop, pause, resume and
  reset with tiered confirmation; result snackbar with a link to the task log.
- **Nodes:** live usage, CPU/kernel/version info, guests on the node.
- **Tasks:** merged task timeline with running/failed filters and a live task log.
- **Design:** Midnight Glass design system with system/light/dark themes and pure-black AMOLED
  mode; floating bottom bar on phones, side rail on tablets; bundled Inter and JetBrains Mono fonts;
  original launcher icon with a themed (monochrome) layer.
- **Settings:** theme, pure black, live refresh interval. About screen with license and privacy notes.
- **Beta feedback:** if the app crashes, a report is saved on the device only and offered for
  sharing on the next start; **More → About → Report a bug** opens a GitHub issue form; a "Beta"
  badge on the About screen.
- **Developer:** pure-Kotlin core modules, a Ktor mock Proxmox server, 45 automated tests,
  GitHub Actions CI with downloadable debug APKs, Dependabot, and full documentation in `docs/`.
- **Release engineering:** version scheme with ordered pre-release codes, release signing from
  repository secrets, a Release workflow (dry run, verification, GitHub pre-releases), an emulator
  end-to-end tour of the minified release APK on Android 8.0 and 14, store metadata for
  IzzyOnDroid/F-Droid, and a separate "AnProx Debug" launcher name for debug builds.
