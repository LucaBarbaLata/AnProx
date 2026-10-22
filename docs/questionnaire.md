# Project questionnaire (kick-off, 2026-10-14)

Before any code was written, the project owner answered 40 questions that pin down
scope, technology, security, design and process. This file is the **source of truth**
for those decisions. Every other document links back here, and any later change of
mind should be recorded at the bottom under "Amendments" rather than by editing the
original answers.

Legend: ✅ = chosen option. Options not chosen are kept so the reasoning stays visible.

---

## Round 1 — Foundation

### Q1. Which technology should the app be built with?
- ✅ **Kotlin + Jetpack Compose** — fully native Android, Material components, best integration with widgets, notifications, biometrics and background work.
- Kotlin Multiplatform + Compose Multiplatform
- Flutter (Dart)
- React Native (TypeScript)

### Q2. What is the oldest Android version the app should support?
- ✅ **Android 8.0 / API 26** — runs on almost every active device; modern APIs (notification channels, `java.time`, variable fonts) are available.
- Android 10 / API 29
- Android 12 / API 31
- Android 7.0 / API 24

### Q3. Which device types should the UI be designed for? *(multi-select)*
- ✅ **Phones**
- ✅ **Tablets & foldables** (adaptive two-pane layouts)
- ChromeOS / desktop windows
- Android TV / Wear OS

### Q4. Which Proxmox products should the app manage? *(multi-select)*
- ✅ **Proxmox VE (PVE)**
- ✅ **Proxmox Backup Server (PBS)**
- Proxmox Mail Gateway (PMG)
- Proxmox Datacenter Manager (PDM)

## Round 2 — Scope

### Q5. Which versions must be supported?
- ✅ **PVE 9 / PBS 4 first, PVE 8 / PBS 3 best-effort** — the app detects the server version and hides features the server lacks.
- Latest only
- PVE 8+9 / PBS 3+4 fully
- PVE 7 and newer

### Q6. How many servers or clusters at once?
- ✅ **Many, plus a combined dashboard** — any number of PVE clusters and PBS servers, switchable, with one "everything" overview.
- Many, one at a time
- One server or cluster only

### Q7. How do we reach "every function"?
- ✅ **Phased milestones to full parity** — release a solid everyday app early, then add features milestone by milestone until it matches the web UI.
- Full parity before first release
- Only what I use

### Q8. Who is the main user?
- Just me
- Homelabbers & enthusiasts
- ✅ **Pro sysadmins / MSPs** — dense information, many clusters, permission-aware, built for speed.
- Everyone (balanced)

## Round 3 — Security

### Q9. Which sign-in methods? *(multi-select)*
- ✅ **Username + password (realms)** — PAM, PVE/PBS, LDAP, AD; ticket + CSRF token.
- API tokens *(not selected — see note below)*
- ✅ **Two-factor** — TOTP, recovery keys, WebAuthn/YubiKey where Android allows.
- ✅ **OpenID Connect SSO** — via browser redirect.

> Note: API tokens were deliberately **not** selected. Authentication headers are added in a
> single place (`AuthInterceptor` in `:core:network`), so token support would be a small change
> later, but no UI or storage for tokens will be built unless the owner asks.

### Q10. How should self-signed certificates be handled?
- Pin on first use + optional custom CA
- Pin on first use only
- ✅ **Simple "allow insecure" toggle**
- Publicly trusted certs only

> Implementation: the toggle is **per server**, **off by default**, and clearly
> labelled. When on, certificate *and* hostname checks are skipped for that server only.
> The risks are documented in [security.md](security.md).

### Q11. Should the app protect itself on the phone?
- ✅ **Optional app lock + re-auth for dangerous actions** — biometric/PIN on launch (optional) plus a biometric check before delete/rollback/destroy.
- Optional app lock only
- Always require biometric
- No app lock

### Q12. How are destructive actions confirmed?
- ✅ **Tiered confirmation** — stop/reboot → simple confirm; delete/destroy/purge → type the guest name or ID.
- Always type the name
- Simple dialog for everything
- Swipe-to-confirm slider

## Round 4 — Connectivity & console

### Q13. How do you reach your servers? *(multi-select)*
- ✅ **Local LAN / Wi-Fi**
- ✅ **VPN** (WireGuard, Tailscale, OpenVPN, ZeroTier)
- ✅ **Reverse proxy / public domain** (possibly on a sub-path)
- Cloudflare Tunnel / Access

### Q14. Should a server entry support several addresses?
- ✅ **Yes, with auto-failover** — e.g. LAN IP first, fall back to the VPN/public URL. Custom HTTP headers per address.
- Yes, switched manually
- No, one URL per server

### Q15. Which console and shell options? *(multi-select)*
- ✅ **noVNC graphical console (VMs)**
- ✅ **xterm.js terminal (nodes & LXC)**
- ✅ **Built-in SSH client** (with key management)
- ✅ **SPICE handoff to an external app** (`.vv` file → aSPICE etc.)

### Q16. Which languages?
- ✅ **English, translation-ready** — every string lives in `strings.xml` from day one.
- English + specific languages
- Community translations

## Round 5 — Guests (VMs & containers)

### Q17. How deep should the creation wizard go?
- ✅ **Simple + "Advanced" toggle** — short guided flow; Advanced reveals every web-UI option.
- Full web-UI wizard always
- Presets only

### Q18. Must-have guest operations for the first release? *(multi-select)*
- ✅ **Snapshots** (create incl. RAM, rollback, delete, tree)
- ✅ **Clone, templates & cloud-init**
- Migration *(not a first-release must-have; scheduled for a later milestone)*
- ✅ **QEMU guest agent** (IPs, OS info, fs-freeze, password reset, exec)

### Q19. How far should hardware editing go?
- ✅ **Common first, advanced later** — CPU, RAM, disks (add/resize/move), NICs, CD-ROM, boot order first; PCI/USB passthrough, NUMA, CPU flags later.
- Everything from the start
- View-only at first

### Q20. Which backup features matter most? *(multi-select)*
- ✅ **Backup now + restore**
- ✅ **Scheduled backup jobs**
- ✅ **File-level restore (PBS)**
- ✅ **PBS datastore management** (GC, prune, verify, sync, namespaces)

## Round 6 — Datacenter & node administration

### Q21. Cluster-level features? *(multi-select)*
- ✅ **High Availability**
- ✅ **Storage replication**
- ✅ **Ceph**
- ✅ **SDN**

### Q22. Node administration features? *(multi-select)*
- ✅ **Updates & repositories**
- ✅ **Disks & storage init** (SMART, wipe, ZFS, LVM, LVM-thin, directory)
- ✅ **Network interfaces** (bridges, bonds, VLANs, pending diff, apply/revert)
- Certificates & ACME *(not selected for now; part of the eventual full-parity milestone)*

### Q23. Users & permissions management?
- ✅ **Full management** — everything the web UI's Permissions section can do.
- Own account + view the rest
- Skip for now

### Q24. Firewall management?
- ✅ **Full management** — rules at every level, IP sets, aliases, security groups, options.
- View + toggle, full later
- Skip for now

## Round 7 — Monitoring & system integration

### Q25. How rich should charts be?
- ✅ **Interactive charts** — pinch-zoom, scrubbing, hour/day/week/month/year, CPU/RAM/net/disk/PSI for nodes, guests, storage.
- Sparklines + current values
- Numbers only

### Q26. How do alerts reach you when the app is closed?
- ✅ **Polling + optional real-time push** — WorkManager polling (~15 min, no server needed) plus optional instant push via PVE notification webhooks → ntfy/Gotify → UnifiedPush.
- Background polling only
- Real-time push only
- No background alerts

### Q27. Which events trigger alerts? *(multi-select)*
- ✅ **Node / guest down** (incl. quorum loss)
- ✅ **Failed tasks & backups**
- ✅ **Resource thresholds** (CPU/RAM/storage, Ceph WARN/ERR)
- Updates & cert expiry

### Q28. Android system integrations? *(multi-select)*
- ✅ **Home-screen widgets**
- ✅ **Quick Settings tiles**
- ✅ **App shortcuts & deep links**
- ✅ **Live task notifications**

## Round 8 — Design

### Q29. Visual style?
- Material 3 Expressive + Material You
- Material 3 + fixed palette
- ✅ **Custom bespoke design**
- Dense "ops console" look

### Q30. Theme modes?
- ✅ **System + light/dark + AMOLED black**
- Dark only
- Light & dark, no AMOLED

### Q31. Main navigation pattern?
- ✅ **Bottom bar + rail on tablets** — Dashboard · Resources · Tasks · More; becomes a side rail with two panes on tablets.
- Tree view like the web UI
- Search-first + drawer

### Q32. Name & brand?
- ✅ **"AnProx", with an original icon** — must not use Proxmox's logo; the app is unofficial and not affiliated with Proxmox Server Solutions GmbH.
- "AnProx", owner provides the logo
- A different name

## Round 9 — Release & business

### Q33. License?
- ✅ **GPL-3.0**
- AGPL-3.0
- Apache-2.0
- Proprietary

### Q34. Distribution channels? *(multi-select)*
- Google Play
- ✅ **F-Droid / IzzyOnDroid** → no proprietary Google libraries (no Play Services, no Firebase).
- ✅ **GitHub Releases (signed APK)** → also works with Obtainium.
- Private sideload only

### Q35. Business model?
- Completely free
- ✅ **Free + donations** (GitHub Sponsors / Ko-fi / Liberapay links in About)
- Free + one-time Pro unlock
- Paid / subscription

### Q36. Crash reporting & analytics?
- ✅ **None, fully private** — zero telemetry; the app only talks to the user's own servers. A local crash log can be exported manually.
- Opt-in crash reports
- Firebase Crashlytics + Analytics

## Round 10 — Look, testing, process

### Q37. Which bespoke design direction?
- ✅ **Midnight Glass** — deep navy-charcoal background, frosted translucent cards, soft gradient glows, luminous status colors. Premium and calm.
- Warm Industrial
- Aurora
- Swiss Minimal

### Q38. What can development test against?
- ✅ **Mock API in repo + owner tests on real hardware** — a fake PVE/PBS API server and fixtures for automated tests; the owner installs debug APKs on a real cluster.
- Owner exposes a test PVE later
- Nested PVE in CI

### Q39. Owner's role in development?
- Owner / tester
- Android developer
- ✅ **Learning Android** — docs explain architecture and decisions in detail.

### Q40. What happens after the questionnaire?
- ✅ **Docs + scaffold + first working slice** — full documentation, project skeleton with CI, and a working first slice (add server, log in with 2FA, dashboard).
- Docs & plan only
- Build as much as possible

---

## Decisions made by default (not asked)

These were judged to have an obvious default and were not asked, to keep the
questionnaire at 40. Each can be changed — record it under Amendments.

| Topic | Default | Why |
|---|---|---|
| Offline behaviour | Show last-known state with a "stale" indicator; actions require connectivity | Useful on flaky mobile links, cheap to build |
| Bulk actions | Multi-select start/stop/shutdown/reboot on resource lists | Pro/MSP audience (Q8) |
| Accessibility | WCAG 2.1 AA: TalkBack labels, 48dp touch targets, contrast ≥ 4.5:1, status never by color alone | Good practice; required for a polished app |
| Documentation format | Markdown in `/docs`, Architecture Decision Records in `/docs/adr` | Versioned with the code, readable on GitHub |
| CI/CD | GitHub Actions: build, unit tests, lint, debug APK artifact on every push | Needed because the dev container cannot reach Google's Maven (see [devlog](devlog.md)) |
| Dependency updates | Dependabot for Gradle and GitHub Actions | Keeps the version catalog current |

## Amendments

Add dated entries here when an answer changes or needs clarifying.

- 2026-10-14, Q11 (clarification, no change in scope): the "PIN" is the phone's own screen lock
  (PIN, pattern or password) through Android's biometric prompt, not a separate AnProx PIN. The
  extra check before dangerous actions applies to type-to-confirm actions while the app lock is
  on, and can be switched off. See [ADR 0013](adr/0013-app-lock.md).
- 2026-10-21, Q37 (refined): before 1.0 the owner found the design "cluttered and overwhelming"
  and, from five directions on a design canvas, chose to keep the current theme but uncluttered
  ("aplică tema curentă dar uncluttered"): the same Midnight palette and fonts, with flat solid
  cards instead of glass and glows, one accent, and screens that open on their common case and
  fold the rest. See [ADR 0016](adr/0016-calm-midnight.md) and [design-system.md](design-system.md).
- 2026-10-21, Q9 (clarification): **OpenID Connect** is done in an in-app WebView rather than a
  browser tab, because the identity provider sends the browser back to the server's own address
  (the one the web interface registered), not to the app. **WebAuthn** is not possible: an
  Android app may only use it for a site that publishes a Digital Asset Links file naming the
  app, which no self-hosted Proxmox server does. TOTP, recovery keys and Yubico OTP remain. See
  [security.md](security.md).
