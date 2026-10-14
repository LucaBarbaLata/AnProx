# Testing

The strategy follows Q38: an **in-repo mock Proxmox server** for automated tests, plus the owner
testing debug builds on **real hardware**.

## The pyramid

| Layer | Where | Runs on | Count (M0) |
|---|---|---|---|
| Unit: parsing, formatting, URL rules, UPID/TFA parsing | `core/model/src/test` | JVM | 20 |
| Integration: HTTP client ↔ mock server over real HTTPS (auth, TFA, failover, TLS, errors, renewal, PBS) | `core/network/src/test` | JVM | 16 |
| Integration: multi-server hub, sessions, snapshots, guest actions | `core/data/src/test` | JVM | 9 |
| Android lint | `:app:lintDebug` | CI | — |
| Build: debug and R8-minified release | `:app:assemble*` | CI | — |
| **End-to-end tour** of the minified release APK on emulators against the mock server | `scripts/e2e/tour.py` | CI (Android 8.0 + 14), Release workflow | 7 steps |
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
5. Open a stopped VM → **Start** → "finished" snackbar → **View log** shows `TASK OK` → the VM now offers **Shut down**.
6. Tasks tab shows the new task.
7. Settings → Light theme → dashboard in light mode.

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

**Tasks / settings**
- [ ] Tasks list merges servers; the Failed filter works; the log auto-scrolls while running.
- [ ] Theme: System / Light / Dark / Pure black all render correctly, and status bar icons stay readable.
- [ ] Refresh interval changes how often the dashboard updates.
- [ ] TalkBack: gauges read as "CPU 42 percent"; buttons have labels.
