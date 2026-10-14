# Requirements

Derived from the [questionnaire](questionnaire.md). Each requirement has an ID, the question(s)
it comes from, and the milestone that delivers it ([roadmap](roadmap.md)).

## Vision

> A fully capable Proxmox app for Android, with every function in an easy and beautiful GUI.

**Primary users:** professional sysadmins and MSPs (Q8) who run several clusters and Backup
Servers, need dense, fast information, and must be able to act safely from a phone.

**Success looks like:** an admin can triage an incident from their phone, using the combined
dashboard to find the problem, opening the guest or node, fixing it with a power action, console
or backup, and confirming the result in the task log, without reaching for a laptop.

## Personas

| Persona | Needs |
|---|---|
| **Morgan, MSP engineer:** 12 customer clusters, 3 PBS boxes, on call | Combined dashboard, fast switching, clear alerts, safe actions, works over VPN |
| **Sam, in-house sysadmin:** 1 production + 1 lab cluster with SSO and TFA | OIDC/TFA sign-in, permission-aware UI, consoles, backups |
| **Alex, power homelabber:** 3-node cluster at home, reached through a reverse proxy | Easy first setup, failover LAN↔public, beautiful UI |

## Functional requirements

### Servers & connectivity

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-SRV-1 | Save any number of PVE and PBS servers | Q4, Q6 | M0 ✅ |
| FR-SRV-2 | Each server has 1..n addresses tried in order; remember the last working one; retry the primary after a network change | Q14 | M0 ✅ |
| FR-SRV-3 | Per-address custom HTTP headers | Q13, Q14 | M0 ✅ |
| FR-SRV-4 | Support reverse-proxy sub-paths (`https://host/pve`) | Q13 | M0 ✅ |
| FR-SRV-5 | Per-server "allow insecure certificate" switch, off by default, with a warning | Q10 | M0 ✅ |
| FR-SRV-6 | Accept bare IPs/hosts and add `https://` and the product's default port | — | M0 ✅ |
| FR-SRV-7 | Export/import server profiles (encrypted) | — | M4 🗓 |

### Authentication & security

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-AUTH-1 | Password login for PAM, PVE/PBS, LDAP and AD realms, chosen from the server's realm list | Q9 | M0 ✅ |
| FR-AUTH-2 | Second factor: TOTP, recovery key, Yubico OTP | Q9 | M0 ✅ |
| FR-AUTH-3 | Second factor: WebAuthn / security keys | Q9 | M1 🗓 |
| FR-AUTH-4 | OpenID Connect SSO | Q9 | M1 🗓 |
| FR-AUTH-5 | Automatic ticket renewal; silent re-login with a remembered password | Q9 | M0 ✅ |
| FR-AUTH-6 | Optional app lock (biometric/PIN) | Q11 | M1 🗓 |
| FR-AUTH-7 | Biometric re-authentication before destructive actions | Q11 | M1 🗓 |
| FR-AUTH-8 | Tiered confirmation: simple confirm for stop/reboot, type-the-name for delete/destroy/purge/rollback | Q12 | M0 ✅ (power actions); applied to each destructive feature as it lands |

### Monitoring

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-MON-1 | Combined dashboard across all servers: totals, attention list, per-server cards | Q6 | M0 ✅ |
| FR-MON-2 | Live gauges for cluster, node and guest CPU/RAM/disk | Q25 | M0 ✅ |
| FR-MON-3 | Interactive RRD charts (zoom, scrub, ranges; CPU, RAM, net, disk, PSI) | Q25 | M1 🗓 |
| FR-MON-4 | Background polling alerts with WorkManager | Q26, Q27 | M3 🗓 |
| FR-MON-5 | Optional real-time push via PVE notification webhooks → ntfy/Gotify → UnifiedPush | Q26 | M3 🗓 |
| FR-MON-6 | Alert types: node/guest down, quorum loss, failed tasks/backups, resource thresholds, Ceph health | Q27 | M3 🗓 |
| FR-MON-7 | Show stale data with its age when a refresh fails | default | M0 ✅ |

### Guests

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-GST-1 | Search/filter guests, nodes and storage across servers | — | M0 ✅ |
| FR-GST-2 | Power actions with task follow-up | — | M0 ✅ |
| FR-GST-3 | Bulk actions (multi-select) | default | M1 🗓 |
| FR-GST-4 | Snapshots: create (with RAM), rollback, delete, tree | Q18 | M1 🗓 |
| FR-GST-5 | QEMU guest agent: IPs, OS info, fs-freeze, password, exec | Q18 | M1 🗓 |
| FR-GST-6 | Clone, templates, cloud-init | Q18 | M2 🗓 |
| FR-GST-7 | Hardware editing, common settings first, then advanced | Q19 | M2 🗓 / M9 🗓 |
| FR-GST-8 | Creation wizard: simple, with an "Advanced" toggle exposing every option | Q17 | M2 🗓 |
| FR-GST-9 | Migration (live/offline) | — | M2 🗓 |
| FR-GST-10 | Consoles: noVNC, xterm.js, SPICE handoff, built-in SSH | Q15 | M2 🗓 |

### Backups

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-BAK-1 | Backup now and restore | Q20 | M1 🗓 |
| FR-BAK-2 | Scheduled backup jobs | Q20 | M4 🗓 |
| FR-BAK-3 | File-level restore from PBS | Q20 | M8 🗓 |
| FR-BAK-4 | PBS datastore management: GC, prune, verify, sync, namespaces | Q20 | M8 🗓 (usage ✅ M0) |

### Datacenter & nodes

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-DC-1 | HA: resources, rules/groups, status, maintenance | Q21 | M4 🗓 |
| FR-DC-2 | Storage replication | Q21 | M4 🗓 |
| FR-DC-3 | Ceph management | Q21 | M6 🗓 |
| FR-DC-4 | SDN management | Q21 | M6 🗓 |
| FR-DC-5 | Firewall, full management at every level | Q24 | M4 🗓 |
| FR-DC-6 | Users, groups, roles, ACLs, realms, tokens, TFA, full management | Q23 | M7 🗓 |
| FR-NODE-1 | Node overview: usage, hardware, versions, guests | — | M0 ✅ |
| FR-NODE-2 | Updates and repositories | Q22 | M5 🗓 |
| FR-NODE-3 | Disks and storage initialisation | Q22 | M5 🗓 |
| FR-NODE-4 | Network interfaces with pending diff and apply/revert | Q22 | M5 🗓 |
| FR-NODE-5 | Certificates & ACME | (not chosen in Q22) | M9 🗓 |

### Tasks

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-TSK-1 | Merged task timeline across servers with filters | — | M0 ✅ |
| FR-TSK-2 | Live task log | — | M0 ✅ (PVE); PBS 🗓 M1 |
| FR-TSK-3 | Ongoing notification for long tasks started in the app | Q28 | M1 🗓 |

### Android integration

| ID | Requirement | Q | M |
|---|---|---|---|
| FR-AND-1 | Home-screen widgets | Q28 | M3 🗓 |
| FR-AND-2 | Quick Settings tiles | Q28 | M3 🗓 |
| FR-AND-3 | App shortcuts and deep links | Q28 | M3 🗓 |

## Non-functional requirements

| ID | Requirement | Q | Status |
|---|---|---|---|
| NFR-1 | Native Kotlin + Jetpack Compose | Q1 | ✅ |
| NFR-2 | Android 8.0 (API 26) minimum; target the latest API | Q2 | ✅ (compile/target 36) |
| NFR-3 | Adaptive phone and tablet/foldable layouts | Q3 | ✅ (rail + split view) |
| NFR-4 | PVE 9 / PBS 4 first-class, PVE 8 / PBS 3 best-effort; detect the version and hide unsupported features | Q5 | 🔸 (version shown; feature gating as features land) |
| NFR-5 | Translation-ready: every user-visible string in resources | Q16 | ✅ |
| NFR-6 | Bespoke "Midnight Glass" design; light/dark/AMOLED | Q29, Q30, Q37 | ✅ |
| NFR-7 | Bottom bar on phones, rail on tablets | Q31 | ✅ |
| NFR-8 | Zero telemetry; no Google Play Services/Firebase; F-Droid compatible | Q34, Q36 | ✅ |
| NFR-9 | GPL-3.0 | Q33 | ✅ |
| NFR-10 | Secrets encrypted with an Android Keystore key; no backups of secrets | Q11 | ✅ |
| NFR-11 | Accessibility: WCAG 2.1 AA target (contrast, TalkBack, 48 dp targets, no colour-only status) | default | 🔸 (built in; audit in M9) |
| NFR-12 | No network activity in the background unless the user enables alerts | Q26 | ✅ |
| NFR-13 | Automated tests against a mock Proxmox; CI on every push | Q38 | ✅ |
| NFR-14 | Documentation for learners: architecture explained, decisions recorded | Q39 | ✅ |
| NFR-15 | Distribution through GitHub Releases (signed APK) and F-Droid/IzzyOnDroid | Q34 | 🗓 M9 |
