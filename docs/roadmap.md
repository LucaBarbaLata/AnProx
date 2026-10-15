# Roadmap

"Every function, in an easy and beautiful GUI" is reached in phases (Q7): each milestone ships a
usable app and adds a coherent slice of the Proxmox API. Milestones are ordered by everyday
value for the main audience, pro sysadmins and MSPs (Q8).

Status legend: ✅ done · 🚧 in progress · 🗓 planned

| Milestone | Theme | Status |
|---|---|---|
| [M0](#m0--foundation) | Foundation: servers, login, dashboard, resources, power, tasks | ✅ released as **0.1.0-beta.1** (M1 work so far: **0.1.0-beta.2**) |
| [M1](#m1--daily-driver) | Daily driver: app lock, SSO, snapshots, backups, charts, notifications | 🚧 snapshots, charts, app lock, backups, guest IPs, bulk actions, favourites done |
| [M2](#m2--consoles--guest-configuration) | Consoles and guest configuration | 🚧 all but the built-in SSH client |
| [M3](#m3--alerts--android-integration) | Alerts and Android integration | ✅ (beta.3 candidate) |
| [M4](#m4--datacenter) | Datacenter: storage, backup jobs, HA, replication, firewall | ✅ (beta.3 candidate) |
| [M5](#m5--node-administration) | Node administration | ✅ (beta.3 candidate) |
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
- ✅ **SPICE handoff**: the `.vv` file opens in aSPICE or another SPICE viewer (Q15).
- **Built-in SSH client** with key management (Q15).
- ✅ **Hardware editing**, common settings first (Q19): CPU, memory/balloon, disks
  (add/grow/move/detach, unused volumes), network devices, CD/DVD, boot order; container
  resources, root disk and mount points, network with IPv4/IPv6. Changes a running VM only picks
  up at its next start are marked "After restart".
- ✅ **Options** (pulled into 0.1.0-beta.2): name, start at boot, protection, tags, description, QEMU agent.
  (Start order and the remaining options are still to do.)
- ✅ **Clone and templates** (Q18, pulled into 0.1.0-beta.2): full and linked clones to any node and storage, convert to template.
- ✅ **Cloud-init** (Q18): user, password, SSH keys, IP per network device, DNS, upgrade on first
  start; adds the cloud-init drive if the VM has none; regenerate the image.
- ✅ **Creation wizard** for VMs and containers (Q17): a simple flow with an "Advanced" toggle,
  from a node's screen (Node → Create VM / Create container).
- ✅ **Migration** (pulled into 0.1.0-beta.2): live for VMs (copying local disks), restart mode for containers, offline when stopped; uses Proxmox's pre-flight check.

## M3 — Alerts & Android integration
*Status: ✅ done (favourite guests on the widget still to come).*
- ✅ **Background polling** with WorkManager (Q26) for the alert types in Q27: node or guest down,
  quorum lost, failed tasks and backups, CPU/RAM/storage thresholds, Ceph health. More → Alerts:
  interval (15 min to 3 h), kinds, limits, **Check now**, the last check's summary.
- ✅ **Real-time push** (Q26): [setup guide](push-notifications.md) for Proxmox's notification
  targets → ntfy (webhook) or Gotify, with no Google services. Receiving UnifiedPush in AnProx
  itself comes after 1.0.
- ✅ **Live task notifications** (Q28): tasks started from the app, followed until they end.
- ✅ **App shortcuts and in-app links** (Q28): Resources, Tasks, Alerts; notifications open the
  node, guest or task they are about (`anprox://` links, explicit intents only).
- ✅ **Home-screen widget** (Glance, Q28): every server's nodes, running guests and current
  problems from the last background check. Still to come: favourite guests with power buttons.
- ✅ **Quick Settings tile** (Q28): the last check at a glance; opens the dashboard.

## M4 — Datacenter
*Status: ✅ done.* Dashboard → a cluster's **Datacenter** button.
- ✅ **Backup jobs** (Q20): list with next run, enable/disable, run now, create/edit/delete
  (schedule presets or any calendar event with Proxmox's preview of the next runs, storage,
  node, all/selected/all-but/pool, mode, compression, retention, notes template, comment), and
  the guests no job covers.
- ✅ **Storage browser** (Datacenter → Storage): every storage with its state and usage per
  node; what it holds by type (ISO images, CT templates, VM disks, CT volumes, snippets, import,
  backups); the node downloads ISO images and templates from a URL (with a checksum); upload from
  the phone with progress; delete ISO images and templates.
- ✅ **Storage configuration** (Datacenter → Storage → Add storage, or the pencil on a storage):
  directory, NFS, SMB/CIFS, Proxmox Backup Server, LVM, LVM-thin and ZFS, with the node's scans
  to find exports, shares, datastores, volume groups, thin pools and ZFS pools; content, nodes,
  enabled, shared, backup retention; remove any storage. Other types (iSCSI, Ceph, ESXi) can
  be removed but are edited in the web interface for now.
- ✅ **High availability** (Q21, Datacenter → High availability): the manager's and each
  node's status and quorum; add a guest, set its requested state (started, stopped, disabled,
  ignored), group, restart and relocate limits and comment; move it to another node through the
  HA manager; remove it.
- ✅ **High availability, the rest** (Q21): groups (PVE 8: nodes with priorities, restricted, no
  failback) and rules (PVE 9: prefer nodes, keep guests together or apart). Node maintenance
  mode has no API in PVE 8; run `ha-manager crm-command node-maintenance enable <node>` in the
  node's shell.
- ✅ **Storage replication** (Q21, Datacenter → Replication): every job with its state (in
  sync, failed with the error, never run, off), last and next sync; add (guest, target node,
  schedule presets or any calendar event, rate limit, comment), edit, switch off, run now, the
  last run's log, delete (optionally keeping the copy on the target).
- ✅ **Firewall rules and options** (Q24) for the datacenter (Datacenter → Firewall), nodes
  (Node → Firewall) and guests (Tools → Firewall): add, edit, enable, reorder and delete rules
  (direction, action or security group, macro, protocol, addresses, ports, interface, log);
  enable, policies, log levels, DHCP/NDP/RA and MAC/IP filters.
- ✅ **Firewall, the rest** (Q24): security groups (create, delete, edit their rules),
  aliases and IP sets (datacenter and guests), the firewall log of nodes and guests (filter,
  dropped and rejected packets highlighted). Below a firewall's rules.
- ✅ **Resource pools** (Datacenter → Resource pools): every pool with its guests and storages;
  create, add and remove guests and storages, edit the comment, delete an empty pool. Tags are
  edited per guest (Tools → Options).
- ✅ **Encrypted export/import of servers** (More → Settings → Move to another phone):
  passphrase-protected file (PBKDF2 + AES-GCM, see [security](security.md)), saved passwords
  optional and behind the screen lock, sessions never exported.

## M5 — Node administration
*Status: ✅ done.* Node → Administration.
- ✅ **Updates and repositories** (Q22): subscription status, refresh the package lists (a
  task), upgradable packages with changelogs, installed versions, the upgrade in a terminal;
  repositories with Proxmox's warnings, switch on/off, add no-subscription/enterprise/test.
- ✅ **Services** (start, stop, restart, reload; the ones the connection depends on ask first)
  and the **system log** (newest 500 journal lines, filter).
- ✅ **Disks** (Q22): health, wear, SMART, wipe (type the device), initialize GPT, create
  directory/LVM/LVM-thin/ZFS storage (ZFS with RAID level and compression).
- ✅ **Network interfaces** (Q22): bridges, bonds, VLANs and ports (addresses, gateways, ports,
  VLAN awareness, bond mode and hash policy, MTU, comment); the pending diff; apply (type the
  node's name) and revert.
- ✅ **DNS, hosts and time**: search domain and servers, /etc/hosts, time zone.
- ✅ Entering or removing a subscription key (Updates screen). Node power is done (M0/M1).

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
