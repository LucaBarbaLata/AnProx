# Testing

The strategy follows Q38: an **in-repo mock Proxmox server** for automated tests, plus the owner
testing debug builds on **real hardware**.

## The pyramid

| Layer | Where | Runs on | Count |
|---|---|---|---|
| Unit: parsing, formatting, URL rules, UPID/TFA parsing, snapshot tree and names, chart scales, backup listings, guest agent replies, guest options | `core/model/src/test` | JVM | 38 |
| Integration: HTTP client ↔ mock server over real HTTPS (auth, TFA, failover, TLS, errors, renewal, PBS) | `core/network/src/test` | JVM | 16 |
| Integration: multi-server hub, sessions, dashboard snapshots, guest actions, guest snapshots, performance history, app lock rules, backup and restore, guest network info, guest options | `core/data/src/test` | JVM | 40 |
| Android lint | `:app:lintDebug` | CI | — |
| Build: debug and R8-minified release | `:app:assemble*` | CI | — |
| **End-to-end tour** of the minified release APK on emulators against the mock server | `scripts/e2e/tour.py` | CI (Android 8.0 + 14), Release workflow | 11 steps |
| UI / screenshot tests | planned (M1) | — | — |
| Manual on real hardware | [checklist below](#manual-test-checklist-real-hardware) | owner | — |

Run everything locally:

```bash
./gradlew test                         # all JVM tests
./gradlew :core:network:test           # one module
./gradlew -Panprox.jvmOnly=true test   # without the Android SDK (pure-Kotlin modules only)
```

Test reports: `core/*/build/reports/tests/test/index.html`.

> **Pitfall (found in M0):** JUnit silently skips a `@Test` function whose return type isn't `Unit`.
> `fun x() = runBlocking { …; assertIs<Foo>(y) }` returns `Foo`, so the test never runs. End such
> tests with `Unit`.

## The mock server (`:tools:mock-server`)

A Ktor application that speaks the real Proxmox wire format: `/api2/json` paths, `{"data": …}`
envelopes, ticket cookies, CSRF checks, two-factor partial tickets, error reasons in the HTTP
status line, asynchronous tasks with UPIDs and logs, and a self-signed certificate.

### Fake cluster "homelab" (PVE 9.0.10)

| Node | State | Guests |
|---|---|---|
| pve1 | online, 16 cores, 64 GiB | VM 100 `web` (running, HA), VM 101 `db` (running, HA), template 9000, CT 200 `pihole` |
| pve2 | online, 12 cores, 48 GiB | VM 102 `win11` (stopped), VM 103 `k8s-master`, CT 201 `nginx-proxy`, CT 202 `backup-client` (stopped) |
| pve3 | **offline** | VM 104 `lab-vm` |

Storage: `local`, `local-zfs` (pve1 at **92 %** to trigger the dashboard warning), shared `nas-nfs` and `pbs`.
Backups: `web` has two Backup Server backups (one protected, both verified) and one on `local`;
`db` has one whose verification failed; `win11` has one on `nas-nfs`; `pihole` and `nginx-proxy`
have one each; `k8s-master` has none. Back up now works on any guest. Restoring as new needs a
free ID (`/cluster/nextid` → 105 at first); replacing needs the guest stopped and drops its snapshots.
Performance history: 70 samples per timeframe, like Proxmox, as smooth repeatable curves. Stopped
guests only have data for the older part of each timeframe (gaps), templates have none, and the
offline node `pve3` answers 595 like a real unreachable node.
Snapshots: `web` has a small tree (`before-upgrade` with RAM → `after-upgrade` → now, plus a
`kernel-test` branch), `win11` has `fresh-install`, and `pihole` has `pre-update`.
Task history includes a failed migration and a backup with warnings.

### Fake Backup Server (PBS 4.0.14)

Datastores `store1` (70 %) and `store2` (**92 %**, "full in ~12 days"), plus GC/verify/sync task
history including a failed sync.

### Accounts (password `anprox`)

| User | Access | Second factor |
|---|---|---|
| `root@pam` | full | none |
| `admin@pve` | full | TOTP: `123456`; recovery keys `aaaa-bbbb-cccc`, `dddd-eeee-ffff` (single use) |
| `auditor@pve` | read-only (power actions → 403) | none |

PVE realms offered: `pam`, `pve`, `corp` (AD) and `sso` (OpenID, to exercise the "coming in M1" path).

### Run it by hand

```bash
./gradlew :tools:mock-server:run
```

This starts PVE on **8006** and PBS on **8007**, listening on all interfaces, with tasks taking
about 2 s.

| Device | Address to add in AnProx |
|---|---|
| Android emulator | `https://10.0.2.2:8006` (PVE), `https://10.0.2.2:8007` (PBS) |
| Phone on the same Wi-Fi | `https://<computer-LAN-IP>:8006` |

Turn on **Allow insecure certificate**, because the mock's certificate is self-signed. To test
failover, add a dead address first (for example `https://10.255.255.1:8006`) and the mock's
address second.

### Using it in tests

```kotlin
val server = MockProxmoxServer(ProxmoxProduct.PVE).start()   // random free port, HTTPS
val profile = ServerProfile(id = "t", name = "T", endpoints = listOf(Endpoint(server.baseUrl)),
                            username = "root", realm = "pam", allowInsecureTls = true)
val conn = ProxmoxConnection(profile)
conn.login("anprox")
server.state.ageSessions(3 * 60 * 60 * 1000L)   // simulate an expired ticket
server.lastRequestHeaders                        // inspect what the client sent
server.stop()
```

`MockProxmoxServer(pathPrefix = "/pve")` imitates a reverse proxy on a sub-path.

## End-to-end tour (emulator)

`scripts/e2e/tour.py` drives the **real, R8-minified release APK** with `adb` and `uiautomator`,
so no test code is compiled into the app. `scripts/e2e/run.sh` starts the mock servers on the host
(reachable from the emulator as `10.0.2.2`) and runs the tour:

1. Launch → the welcome screen appears.
2. Add a Proxmox VE server (bare host, insecure certificate) → sign in → dashboard shows the cluster and the offline node.
3. Add a Proxmox Backup Server (default port 8007 added automatically) → sign in → datastores on the dashboard.
4. Resources list across both servers.
5. A running VM's **Performance** charts → **Week** → the Disk I/O chart shows values; its **Network** card shows the guest agent's IP and OS.
6. Open a stopped VM → **Start** → "finished" snackbar → **View log** shows `TASK OK` → the VM now offers **Shut down**.
7. Snapshots of the now-running VM → **Take snapshot** with RAM → "VM snapshot finished" → the new snapshot is listed.
8. Backups of the `pihole` container → **Back up now** to the Backup Server → "Backup finished" → **Restore** the new backup as a new container (ID 105) → "CT restore finished".
9. Options of the `nginx-proxy` container → turn on **Start at boot** → **Save** → "Options saved".
10. Tasks tab shows the new task.
11. Settings → Light theme; turning on the app lock explains that the emulator has no screen lock → dashboard in light mode.

After every step the script checks that the app process is alive and that logcat has no
`FATAL EXCEPTION` for the app. Screenshots of each step, plus UI dumps and logcat on failure,
are uploaded as the `e2e-api-<level>` artifact. Run it locally against an emulator or a USB device:

```bash
./gradlew :app:assembleRelease :tools:mock-server:installDist -Panprox.signWithDebugKey=true
scripts/e2e/run.sh app/build/outputs/apk/release/app-release.apk
```

(On a physical phone, set `ANPROX_MOCK_HOST` to your computer's LAN IP.)

> The tour finds elements by their visible English text. If you rename a string that it uses,
> update `tour.py` in the same change. Tap anything inside a scrolling list via
> `tap_node(scroll_to(...))`: it brings the item to mid-screen first, because edge-to-edge
> content under the system navigation bar can't be tapped (the bar takes the touch).

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

1. `./gradlew test`: all JVM tests.
2. `./gradlew :app:lintDebug`: Android lint (errors fail the build).
3. `./gradlew :app:assembleDebug :app:assembleRelease`: catches R8/minification problems.
4. Uploads the **debug APK** (`anprox-debug-apk`) and all test/lint reports as artifacts.
5. `e2e` jobs (Android 8.0 and 14): the end-to-end tour above, on the release APK signed with the debug key.

## Manual test checklist (real hardware)

Run this before closing a milestone. Note the PVE/PBS versions you tested against in the
[dev log](devlog.md).

**Servers & login**
- [ ] Add a PVE server by bare IP: the address becomes `https://IP:8006`.
- [ ] Self-signed cert: login shows the certificate error with **Allow**; after allowing, login works.
- [ ] Wrong password: clear error, no crash.
- [ ] Realm list loads; switching realm works (PAM ↔ PVE ↔ LDAP/AD if available).
- [ ] TOTP account: second-factor step appears; a wrong code returns to password with a message; the right code signs in.
- [ ] Recovery key works once; the remaining count is shown.
- [ ] "Remember password": kill the app, wait > 2 h (or restart the server's pveproxy), reopen: silent re-login.
- [ ] Without "remember": after expiry the dashboard shows "Session expired"; tapping it signs in.
- [ ] Two addresses (LAN first, VPN/public second): at home it uses LAN; on mobile data with VPN it fails over; back on Wi-Fi it returns to LAN.
- [ ] Reverse proxy on a sub-path (`https://example.com/pve`) works.
- [ ] Custom header (for example for an auth proxy) reaches the proxy.
- [ ] PBS server: login and datastore usage shown on the dashboard.

**Dashboard / resources / guests**
- [ ] Totals match the web UI; offline node and storage over 85 % appear under "Needs attention".
- [ ] Resource search finds by name, ID, node and tag; filters and counts are correct.
- [ ] Tablet or landscape: rail navigation; list + detail side by side.
- [ ] Start a stopped VM: spinner, "VM start finished" snackbar, **View log** opens the log.
- [ ] Stop asks for confirmation; Reset (VM only) asks for confirmation.
- [ ] Read-only user: power action shows "Permission denied …".
- [ ] Node screen: gauges, CPU model, kernel, guests list.

**Performance charts**
- [ ] A running VM's CPU and memory charts roughly match the web UI's Summary graphs for the same timeframe.
- [ ] Hour/Day/Week/Month/Year all load; a VM that was off for a while shows gaps, not zeros.
- [ ] Touching or dragging across a chart shows the time and values; tapping the same spot again returns to the latest values.
- [ ] Node charts: CPU with IO wait, load average, memory, network.
- [ ] TalkBack reads each chart as a summary (now, average, peak).

**Network card (guest agent)**
- [ ] A running VM with qemu-guest-agent installed and enabled shows its OS and IP addresses (no 127.x/fe80 noise).
- [ ] Tapping an address copies it ("Copied …").
- [ ] A VM without the agent explains how to enable it; a running container shows its IPs without an agent.

**Options** (use a test VM)
- [ ] Guest → Options shows the same name, tags, notes, Start at boot and Protection as the web UI (Options and Summary tabs).
- [ ] Rename the VM, add two tags and a note → Save → "Options saved"; the web UI shows the changes.
- [ ] A container shows "Hostname" instead of "Name"; renaming changes the hostname.
- [ ] An invalid name (e.g. `web server`) or tag (e.g. `-x`) shows an error and Save stays disabled.
- [ ] Turn on Protection → Save; the web UI refuses to remove the VM. Turn it off again.
- [ ] Change something in the web UI while the Options screen is open, then Save in the app: Proxmox refuses it (config changed); Reload shows the new values.
- [ ] Edit a field, press Back → "Discard changes?".

**Backups** (use a test VM)
- [ ] Guest → Backups lists the same backups as the web UI's Backup tab (all storages), newest first; protected and verified badges match.
- [ ] Back up now to a Backup Server storage and to a file storage; both tasks finish and the notes show the guest name.
- [ ] Tapping a backup storage in Resources lists every guest's backups on it.
- [ ] Restore as a new guest: the suggested ID is free; the copy appears stopped with new MAC addresses.
- [ ] Restore over a running guest is refused with a clear message; after stopping it, typing the name restores it.
- [ ] Read-only user: Back up now and Restore show "Permission denied …".
- [ ] An unreachable Backup Server storage shows a warning but the other storages' backups still list.

**App lock** (More → Settings → Security)
- [ ] Without a phone screen lock: turning the app lock on explains that a screen lock is needed.
- [ ] Turning it on asks for the fingerprint/screen lock; cancelling leaves it off.
- [ ] Kill and reopen the app: the lock screen appears and the prompt opens by itself; Back leaves the app.
- [ ] "Lock again after leaving the app": Immediately locks on every return; After 1 min doesn't lock after 20 s but does after 70 s.
- [ ] Open a snapshot's Roll back dialog, leave the app until it locks, come back: the lock screen covers the dialog.
- [ ] Roll back a snapshot: after typing the name, the fingerprint prompt appears; cancelling it doesn't roll back.
- [ ] Turning the lock or "Check again before dangerous actions" off asks for authentication.
- [ ] Remove the phone's screen lock in Android settings, return to AnProx: the app lock has turned itself off.

**Snapshots** (use a test VM)
- [ ] Guest → Snapshots shows the same tree as the web UI (Snapshots tab), with "Now" in the right place.
- [ ] Take snapshot on a running VM with **Include RAM**: the task finishes and the snapshot shows the RAM badge.
- [ ] Take snapshot on a container (no RAM option) and on a stopped VM (RAM option explained, not offered).
- [ ] An invalid name (`1abc`, `a b`) is caught before sending; a duplicate name is rejected.
- [ ] Roll back requires typing the snapshot name; afterwards the VM is in the snapshot's state.
- [ ] Delete removes the snapshot; its children move up to its parent.
- [ ] Read-only user: taking a snapshot shows "Permission denied …".

**Tasks / settings**
- [ ] Tasks list merges servers; the Failed filter works; the log auto-scrolls while running.
- [ ] Theme: System / Light / Dark / Pure black all render correctly, and status bar icons stay readable.
- [ ] Refresh interval changes how often the dashboard updates.
- [ ] TalkBack: gauges read as "CPU 42 percent"; buttons have labels.
