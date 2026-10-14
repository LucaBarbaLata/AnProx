# Changelog

All notable changes to AnProx are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased] (milestone M1, "Daily driver", in progress)

### Added
- **Snapshots** for VMs and containers (Guest → Tools → Snapshots): the snapshot tree with dates,
  descriptions and a RAM badge, and a "Now" marker for the current state. **Take snapshot**
  suggests a valid name, checks it the way Proxmox does, and can include the RAM of a running VM.
  **Roll back** asks you to type the snapshot name. **Delete** asks for confirmation. Each
  operation follows its task and offers **View log**.

- **Performance charts** on every VM, container and node screen: CPU, memory, network and disk
  I/O for guests; CPU with IO wait, load average, memory and network for nodes. Pick the last
  hour, day, week, month or year. Touch or drag across a chart to read the values at that
  moment. Stopped periods show as gaps. Charts refresh while visible: every minute for the hour
  view, every five minutes otherwise.

- **App lock** (More → Settings → Security): ask for your fingerprint, face or the phone's screen
  lock when AnProx opens and after it has been in the background (immediately, after 1 or
  5 minutes). With it on, rolling back a snapshot also asks for it ("Check again before
  dangerous actions", can be switched off). The lock screen covers open dialogs too, and turning
  any of this off requires authenticating.

- **Backups** (Guest → Tools → Backups, or tap a backup storage in Resources): every backup of a
  guest across the storages its node can see, newest first, with size, notes, a "Protected" badge
  and the Backup Server's verification result. **Back up now** picks a storage (Backup Server
  first), the mode (snapshot, suspend or stop) and notes (`{{guestname}}` etc.). **Restore**
  either as a new guest (the next free ID is suggested and the copy gets new MAC addresses) or
  over the existing guest (it must be stopped; type its name to confirm, plus the fingerprint
  check when the app lock is on). A storage that can't be read is reported without hiding the
  others.

- **Network card** on running guests: the operating system and IP addresses reported by the QEMU
  guest agent (VMs) or by the container itself. Tap an address to copy it. Without the agent, the
  card explains how to enable it.

- **Guest options** (Guest → Tools → Options): name (hostname for containers), tags, notes, start
  at boot, protection and, for VMs, the QEMU guest agent switch. Only what you changed is sent,
  together with Proxmox's config checksum, so an edit made meanwhile in the web UI is refused
  instead of overwritten. Leaving with unsaved changes asks first.

### Changed
- The guest screen's tools section lists what works now; everything else is under "Coming later".
- Backup storages in the Resources list open their backups.

### Fixed
- Release notes now show the real signing certificate fingerprint. 0.1.0-beta.1's notes showed a
  placeholder; the correct value is in the README.

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
