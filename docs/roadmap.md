# Roadmap

"Every function, in an easy and beautiful GUI" is reached in phases (Q7): each milestone ships a
usable app and adds a coherent slice of the Proxmox API. Milestones are ordered by everyday
value for the main audience, pro sysadmins and MSPs (Q8).

Status legend: ✅ done · 🚧 in progress · 🗓 planned

| Milestone | Theme | Status |
|---|---|---|
| [M0](#m0--foundation) | Foundation: servers, login, dashboard, resources, power, tasks | ✅ released as **0.1.0-beta.1** (M1 work so far: **0.1.0-beta.2**) |
| [M1](#m1--daily-driver) | Daily driver: app lock, SSO, snapshots, backups, charts, notifications | 🚧 snapshots, charts, app lock, backups, guest IPs, bulk actions, favourites done |
| [M2](#m2--consoles--guest-configuration) | Consoles and guest configuration | 🚧 consoles, options, clone, templates, migration done |
| [M3](#m3--alerts--android-integration) | Alerts and Android integration | 🗓 |
| [M4](#m4--datacenter) | Datacenter: storage, backup jobs, HA, replication, firewall | 🗓 |
| [M5](#m5--node-administration) | Node administration | 🗓 |
| [M6](#m6--ceph--sdn) | Ceph and SDN | 🗓 |
| [M7](#m7--access-control) | Users, groups, roles, ACLs, realms, tokens, TFA | 🗓 |
| [M8](#m8--backup-server-complete) | Proxmox Backup Server complete | 🗓 |
| [M9](#m9--parity--10) | Parity sweep, polish, translations → 1.0 | 🗓 |

Exit criteria for every milestone:
1. CI is green (tests, lint, debug and release builds).
2. New endpoints are covered by mock-server integration tests.
3. [api-coverage.md](api-coverage.md), [CHANGELOG.md](../CHANGELOG.md) and this file are updated.
4. The owner has run the [manual checklist](testing.md#manual-test-checklist-real-hardware) on real hardware.

---

## M0 — Foundation
*Status: ✅ code complete and tested by the owner on real hardware.*

| Area | Delivered | Questionnaire |
|---|---|---|
| Project | Kotlin + Compose, min API 26, multi-module, version catalog, CI, Dependabot, GPL-3.0 | Q1, Q2, Q33, Q38 |
| Servers | Unlimited PVE and PBS servers; several addresses each with ordered failover; per-address custom headers; reverse-proxy sub-paths; per-server "allow insecure certificate" | Q4, Q6, Q10, Q13, Q14 |
| Login | Password with realm picker (PAM/PVE/PBS/LDAP/AD); TOTP, recovery key and Yubico second factor; remember password (Keystore-encrypted); automatic ticket renewal and silent re-login | Q9 |
| Dashboard | Combined totals, an attention list (quorum, offline nodes, storage ≥ 85 %, expired sessions), cluster cards with CPU/RAM gauges and node rows, PBS datastore usage with "full in" estimates | Q6, Q25 (basic) |
| Resources | Search and filters across all servers; list/detail split on tablets | Q3 |
| Guests | Live status; start, shut down, reboot, stop, pause, resume, reset; tiered confirmation; task follow-up | Q12 |
| Nodes | Live usage, hardware and kernel info, guests on the node | — |
| Tasks | Merged timeline across servers, filters, live task log | — |
| Design | Midnight Glass design system; light, dark and AMOLED; bottom bar and side rail | Q29–Q31, Q37 |
| Privacy | Zero telemetry; translation-ready strings | Q16, Q36 |

### Beta 0.1.0-beta.1 (M0 release): ✅ released
Release engineering is in place ([releasing.md](releasing.md)). Gates:
- [x] Release workflow with dry run, signature/version verification and GitHub pre-releases
- [x] End-to-end tour of the minified release APK on Android 8.0 and 14 emulators (CI)
- [x] Local crash reports, "Report a bug", issue templates, [beta testing guide](beta-testing.md)
- [x] Store metadata (Fastlane) for IzzyOnDroid/F-Droid
- [x] Owner: create the release key and add the four repository secrets
- [x] Owner: run the manual checklist on real PVE/PBS hardware (2026-10-14: all good)
- [x] Owner: run the Release workflow (dry run, then publish): [released 2026-10-14](https://github.com/LucaBarbaLata/AnProx/releases/tag/v0.1.0-beta.1)

### Beta 0.1.0-beta.2: ✅ released 2026-10-15, owner testing on real hardware
Everything marked ✅ in M1 and M2 below. Gates:
- [x] CI green: tests, lint, debug and release builds, and the emulator tour on Android 8.0 and 14 (10 steps: the steps for options, consoles, bulk actions and clone/migrate were written but not run until after beta.2, see the devlog)
- [x] `CHANGELOG.md` section, store changelog (`fastlane/…/changelogs/10022.txt`), README
- [x] [Test guide](beta2-test-guide.md) for the owner, Proxmox VE focused (no Backup Server available)
- [ ] Owner: run the test guide on real hardware (Proxmox VE 8; 9 later)
- [x] Owner: ran the Release workflow: [released 2026-10-15](https://github.com/LucaBarbaLata/AnProx/releases/tag/v0.1.0-beta.2) (tests, signed build and emulator tour passed)

## M1 — Daily driver
*Status: 🚧 in progress. Done items are marked ✅ and ship in 0.1.0-beta.2.*

- ✅ **App lock** (Q11): optional biometric/screen-lock check on launch and after a chosen time
  away, and re-authentication before `TYPE_TO_CONFIRM` actions ([ADR 0013](adr/0013-app-lock.md)).
- **OpenID Connect** sign-in (Q9) through a browser tab or WebView, plus **WebAuthn** second factor.
- ✅ **Snapshots** (Q18): list and tree, create (with RAM), roll back, delete. (Editing a
  snapshot's description is still to do.)
- ✅ **Backup now and restore** (Q20): run vzdump on demand, restore to the same or a new VMID, browse backups
  per guest and per storage. (Choosing the restore target storage and deleting backups come with M4 storage.)
- ✅ **Interactive charts** (Q25): RRD data for nodes and guests with hour/day/week/month/year
  ranges and touch scrubbing. (Storage charts and pinch-zoom are still to do.)
- 🔸 **Guest agent** (Q18): ✅ IP addresses (tap to copy) and OS info for VMs, IP addresses for containers;
  fs-freeze/thaw and set user password are still to do.
- ✅ **Bulk actions** on the resource list (long-press to select; start, shut down, stop).
- **Live task notifications** (Q28) for long operations started from the app.
- ✅ **Favourites** pinned to the top of the dashboard (star on the guest screen).

## M2 — Consoles & guest configuration
- ✅ **noVNC console** for VMs, **xterm.js terminal** for nodes and containers, with a keyboard
  button and an extra key row (Q15; pulled into 0.1.0-beta.2). Rebuilt after beta.2: the app
  connects through its own HTTP client and both front ends are bundled
  ([ADR 0014](adr/0014-consoles-through-the-app.md)).
- **SPICE handoff**: download the `.vv` file and open it in aSPICE or another SPICE viewer (Q15).
- **Built-in SSH client** with key management (Q15).
- ✅ **Hardware editing**, common settings first (Q19): CPU, memory/balloon, disks
  (add/grow/move/detach, unused volumes), network devices, CD/DVD, boot order; container
  resources, root disk and mount points, network with IPv4/IPv6. Changes a running VM only picks
  up at its next start are marked "After restart".
- ✅ **Options** (pulled into 0.1.0-beta.2): name, start at boot, protection, tags, description, QEMU agent.
  (Start order and the remaining options are still to do.)
- ✅ **Clone and templates** (Q18, pulled into 0.1.0-beta.2): full and linked clones to any node and storage, convert to template.
- **Cloud-init** (Q18): user, SSH keys, IP.
- **Creation wizard** for VMs and containers (Q17): a simple flow with an "Advanced" toggle.
- ✅ **Migration** (pulled into 0.1.0-beta.2): live for VMs (copying local disks), restart mode for containers, offline when stopped; uses Proxmox's pre-flight check.

## M3 — Alerts & Android integration
- **Background polling** with WorkManager (Q26) for the alert types in Q27: node or guest down,
  quorum lost, failed tasks and backups, CPU/RAM/storage thresholds, Ceph health.
- **Real-time push** (Q26): a setup guide for PVE notification webhooks → ntfy/Gotify →
  UnifiedPush, with no Google services.
- **Home-screen widgets** (Glance): cluster health, favourite guests with power buttons, storage (Q28).
- **Quick Settings tiles** and **app shortcuts/deep links** (Q28).

## M4 — Datacenter
- Storage: add/edit/remove every storage type, content browser, upload ISO/templates, download from URL.
- **Backup jobs** (Q20): create/edit schedules, retention, notes templates, job details.
- **High Availability** (Q21): resources, node affinity rules/groups, CRM/LRM status, maintenance mode.
- **Storage replication** (Q21): jobs, schedule, status, run now.
- **Firewall, full management** (Q24): datacenter/node/guest rules, security groups, IP sets, aliases, options, log.
- Resource pools and tags.
- Encrypted export/import of server profiles (move to a new phone without retyping everything).

## M5 — Node administration
- **Updates and repositories** (Q22): refresh, upgradable list with changelogs, upgrade in a terminal, repository management.
- **Disks** (Q22): SMART, wipe, initialise GPT, create ZFS/LVM/LVM-thin/directory storage.
- **Network interfaces** (Q22): bridges, bonds, VLANs, a pending-changes diff, apply/revert.
- Services, syslog/journal, DNS/hosts/time, power (reboot/shutdown node), subscription status.

## M6 — Ceph & SDN
- **Ceph** (Q21): health and status, OSDs (in/out, up/down, create/destroy), pools, monitors,
  managers, CephFS, configuration, crush map, log.
- **SDN** (Q21): zones, VNets, subnets, IPAM, DHCP, controllers, fabrics, pending changes and apply.

## M7 — Access control
- **Full permissions management** (Q23): users, groups, roles, ACLs, realms (incl. sync),
  API tokens, TFA management for any user, password changes.

## M8 — Backup Server complete
- **Datastores** (Q20): create, configure, maintenance mode, usage history.
- **Content**: snapshots per namespace, notes, protection, verify state; **file-level restore** (browse
  and download single files) (Q20).
- **Jobs**: garbage collection, prune, verify, sync (push/pull), tape (basic); namespaces; remotes.
- PBS node administration (shares the M5 components).

## M9 — Parity & 1.0
- Remaining endpoints from [api-coverage.md](api-coverage.md): ACME/certificates, metric servers,
  notification targets and matchers, PCI/USB/directory mappings, advanced hardware (passthrough,
  NUMA, CPU flags), guest config history, cluster join/create.
- Accessibility audit (WCAG 2.1 AA), performance pass, translation platform (community translations).
- Release engineering: signed releases on GitHub, F-Droid/IzzyOnDroid metadata (Q34), donation links (Q35).
