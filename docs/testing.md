# Testing

The strategy follows Q38: an **in-repo mock Proxmox server** for automated tests, plus the owner
testing debug builds on **real hardware**.

## The pyramid

| Layer | Where | Runs on | Count |
|---|---|---|---|
| Unit: parsing, formatting, URL rules, UPID/TFA parsing, snapshot tree and names, chart scales, backup listings, guest agent replies, guest options, clone and migration rules, console endpoints and terminal protocol, property strings and hardware forms (VM and container), IP address checks, new VM and container forms, cloud-init (SSH key encoding, IP configs), SPICE files, alerts, app links, backup jobs, storage content, firewall, high availability, replication, pools, storage setup, firewall groups, aliases and IP sets, network interfaces | `core/model/src/test` | JVM | 99 |
| Integration: HTTP client ↔ mock server over real HTTPS (auth, TFA, failover, TLS, errors, renewal, PBS) | `core/network/src/test` | JVM | 16 |
| Integration: multi-server hub, sessions, dashboard snapshots, guest actions, guest snapshots, performance history, app lock rules, backup and restore, guest network info, guest options, clone/template/migrate, console sessions (terminal and VNC handshake), node power, hardware (disks, drives, network, pending changes, container volumes), creating VMs and containers, cloud-init, SPICE, alerts, task notifications, backup jobs, storage, firewall, high availability, replication, pools, storage setup, encrypted server export, firewall groups, aliases, IP sets and log, node updates, repositories, services, journal, DNS, hosts, time, network and disks | `core/data/src/test` | JVM | 104 |
| Android lint | `:app:lintDebug` | CI | — |
| Build: debug and R8-minified release | `:app:assemble*` | CI | — |
| **End-to-end tour** of the minified release APK on emulators against the mock server | `scripts/e2e/tour.py` | CI (Android 8.0 + 14), Release workflow | 33 steps |
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

This starts PVE on **8006**, PBS on **8007** and an SSH server on **2222**, listening on all
interfaces, with tasks taking about 2 s. The SSH server takes any user with the password
`anprox`, and keys added with the app's **Add key**; its shell answers `hostname`, `whoami`,
`uname -a`, `echo` and `exit`.

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
5. A running VM's **Network** card shows the guest agent's IP and OS; the star adds it to the favourites; its **Performance** charts → **Week** → the Disk I/O chart shows values.
6. `k8s-master` → **Guest agent** → "Writable" → **Freeze filesystems** (confirm) → "Filesystems frozen: 2" → **Thaw filesystems** → "Filesystems thawed: 2" → **Trim filesystems** → what each filesystem freed.
7. Open a stopped VM → **Start** → "finished" snackbar → **View log** shows `TASK OK` → the VM now offers **Shut down**.
8. Snapshots of the now-running VM → **Take snapshot** with RAM → "VM snapshot finished" → the new snapshot is listed → the seeded `fresh-install` snapshot's menu → **Edit description** → **Save** → the new description shows under it.
9. Backups of the `pihole` container → **Back up now** to the Backup Server → "Backup finished" → **Restore** the new backup as a new container (ID 105) → "CT restore finished".
10. Options of the `nginx-proxy` container → turn on **Start at boot** → the form is dirty → **Save** → clean again ("No unsaved changes."; the snackbar is too short to rely on).
11. **Hardware** of the running `win11` VM → **Add disk** (SCSI, first storage, 32 GiB) → "SCSI 1" appears after the task → **Memory** → turn off **Ballooning** → **Save** → "After restart" (a running VM keeps the change for its next start).
12. **Console** of `nginx-proxy` → "Connected" (the app's own connection to the mock termproxy through the self-signed certificate) → **Esc** and **Keyboard** on the key row.
13. **Console** of `k8s-master` → noVNC (bundled) finishes the VNC handshake with the generated password → "Connected · k8s-master". (An emulator with an Android System WebView older than 80 shows the update message instead, in both console steps.)
14. **Cloud-init** of `k8s-master` → the user `ubuntu` and net0's static address from the VM's config.
15. Long-press `backup-client`, tap `nginx-proxy` → "2 selected" → **Start** → the confirmation lists only the stopped one → the selection clears when the tasks end → `backup-client` shows **Running**.
16. **Clone** the Ubuntu template (linked by default) → **Open it** → **Migrate** the clone to `pve2` → the guest screen now says `pve2` → **Delete VM** → type its ID → the guest screen closes.
17. Tasks tab shows the newest tasks ("VM destroy", "VM migrate").
18. Node `pve1` → **Create VM** with the suggested settings (next free ID, first storage, 32 GiB) → "Open it" → the new, stopped VM.
19. `anprox://alerts` (the link notifications and shortcuts use) opens **Alerts** → switch on **Background alerts** → **Check now** → "Servers checked: 2" → a notification "Node pve3 is offline" is posted (read with `dumpsys notification`; on Android 13+ the tour grants the notification permission first).
20. Dashboard → **Datacenter** → **Backup jobs** → the mock's nightly job ("Every day at 21:00") → **Add backup job** → Proxmox's preview of the next runs → **Weekdays at 21:00** → **Create job** → it is listed → **Run now** on the nightly job → "Backup job finished".
21. Datacenter → **Storage** → `local` on `pve1` → its ISO images → **From URL** → a URL (the file name is filled in) → **Download** → the new ISO is listed once the node's task ends → **Usage history** at the end → **Year** → the chart shows used space → **CT templates** → **Template catalogue** → the distributions are already there → download Proxmox Mail Gateway.
22. Datacenter → **Storage** → **Add storage** → NFS, ID `media`, server `10.0.0.50` → **Find exports** → `/export/media` → **Create storage** → its pencil shows the export.
23. Datacenter → **Firewall** → the mock's rules → **Add rule** (TCP, port 9100) → it is listed first → **Security groups** → `webserver` shows its HTTPS rule → **IP sets** shows the blocklist → **Options** → **Firewall on** → **Save**.
24. Datacenter → **High availability** → "Manager: pve1" → `100 · web` (managed in the mock) → **Ignored** → **Save** → "HA settings saved".
25. Datacenter → **Replication** → `103 · k8s-master → pve1` and the failing job to the offline pve3 → **Add replication job** → **Add** → `100 · web → pve2` is listed.
26. Datacenter → **Resource pools** → `prod` ("Guests: 3 · Storages: 1") → `lab` (`104 · lab-vm`) → **Add guests** → `103 · k8s-master` → **Add (1)** → it is listed in the pool.
27. Datacenter → **Users** → **Add user** `dave` (pve realm, two passwords, group `ops`) → **Create user** → `dave@pve` → **Add API token** `ci` → "API token created" with `dave@pve!ci` and the secret, once → **Permissions** → **Add permission** `/storage`, group `auditors`, role `Operator` → it is listed → **Realms** → **Sync** `corp` as a preview → **View log** lists `adding user 'bob@corp'`.
28. Datacenter → **Ceph** → "2 osds down" (pve3 is offline in the mock) → the **noout** switch → "noout set" → **OSDs** → `osd.5` (down and out) offers **Mark in** → **Pools** → **Create pool** `fast` → "Pool fast created".
29. Datacenter → **SDN** → "1 change not applied yet" (the mock's new VNet `iot`) → `iot` → **Add subnet** `10.40.0.0/24`, gateway `10.40.0.1` → it is listed → **Apply** → "SDN changes applied" → **Cluster log** ends with the apply task → **Datacenter options** → console **xterm.js** → **Save**.
30. Dashboard → **Manage Backup Server** → `store1` → `ct/200` (Verified) → the newest snapshot → **Browse files** → `root.pxar.didx` → `etc` lists `hosts` → **Prune** previews "Keeps 2, removes 0" → **Jobs** → `s-offsite` (failed in the mock) → **Run now** → "s-offsite finished".
31. Resources → `pve1` → **Updates** (pve-manager is upgradable) → **Repositories** shows the enterprise warning → add **No-Subscription** → **Services** → start `ksmtuned` → **Network** → a new bridge is pending → **Revert** → **System log** shows the journal → **Start all** → "Start all guests finished" → **Task history** lists it → **Failed** hides it.
32. Settings → **SSH keys** → **New key** "Tour phone" → Resources → `pve1` → **SSH** → host `10.0.2.2`, port `2222`, password → **Trust this server?** → **Trust** → "Connected" → **Add key** → "Tour phone" → **Change connection** → **Connect** with the key, without a host key question.
33. Settings → Light theme; turning on the app lock explains that the emulator has no screen lock → dashboard in light mode, with the starred VM under Favourites.

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

## Real-server smoke test

`RealServerSmokeTest` (in `:core:data`) makes every read the app makes against a real Proxmox VE
and checks that the answers parse: dashboard, node (status, history, tasks, updates,
repositories, services, network, disks, DNS, time, journal, firewall), storages and their content
and history, a few guests of each kind (status, options, snapshots, addresses, hardware,
cloud-init, history, backups, firewall), and the datacenter (options, cluster log, backup jobs,
firewall, HA, replication, pools, access control, SDN, Ceph). It changes nothing on the server.
Features the server doesn't have (no Ceph, no guest agent) are reported, not failed.

```bash
ANPROX_PVE_URL=https://pve.example:8006 ANPROX_PVE_USER=anprox@pve ANPROX_PVE_PASSWORD=… \
  ./gradlew -Panprox.jvmOnly=true :core:data:test --tests '*RealServerSmokeTest*' -i
```

Use a dedicated user with the **PVEAuditor** role on `/` and without a second factor; set
`ANPROX_PVE_INSECURE=0` if the server has a trusted certificate. Without the variables the
real-server test is skipped, and the same checks run against the mock instead, so they stay in
working order. Each check prints ✓, – (not on this server) or ✗.

### Write test (test servers only)

`RealServerWriteTest` does what the app's buttons do: it creates a VM (options, hardware, a
snapshot with a description and a rollback, a firewall rule, start and stop, a backup restored
as a new VM, a full clone, a pool), a container from a template already on the node, and a user
and group, checking each through the app's repositories. It only touches objects named
`anprox-test…`, removes leftovers of an earlier run first and deletes everything at the end.
It needs a user allowed to do all that (root@pam on a test server) and `ANPROX_PVE_WRITE=1` on
top of the variables above; without them the same steps run against the mock.

```bash
ANPROX_PVE_URL=… ANPROX_PVE_USER=root@pam ANPROX_PVE_PASSWORD=… ANPROX_PVE_WRITE=1 \
  ./gradlew -Panprox.jvmOnly=true :core:data:test --tests '*RealServerWriteTest*' -i
```

**From GitHub Actions:** the manual workflow **Real server tests** (`.github/workflows/real-server.yml`)
runs both (by hand, or by pushing a change to `.github/real-server-run`; a line `write` there turns the write test on) with the repository secrets `ANPROX_PVE_URL`, `ANPROX_PVE_USER` and
`ANPROX_PVE_PASSWORD` (`@pam` is added to a user without a realm). Tick **write** to run the write
test too. GitHub's runners reach port 8006 directly. It never runs on push or pull requests.

> From a Claude Code cloud session the server must answer on **port 443**: the environment's
> outbound traffic only gets through on 443, so `:8006` times out even when it is open. On the
> server, `iptables -t nat -I PREROUTING -p tcp --dport 443 -j REDIRECT --to-ports 8006` (or a
> reverse proxy) does it; then use `ANPROX_PVE_URL=https://<address>` without a port. The host
> also has to be under the environment's allowed domains.

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

**Consoles**
- [ ] A running VM → Console: "Connected · <name>" and its screen (noVNC), scaled to fit. Tap to click; the **Keyboard** button types; Esc, Tab, arrows, Enter and Ctrl+Alt+Del work; landscape fits the screen.
- [ ] A container → Console shows a login prompt; tapping the terminal or **Keyboard** shows the keyboard; Esc, Tab, Ctrl+C, Ctrl+D and the arrows work (e.g. in `top` or `vi`).
- [ ] Node → Shell opens a root shell (as root@pam).
- [ ] Works with a self-signed certificate ("Allow insecure certificate" on) and behind a reverse proxy.
- [ ] A stopped VM's Console says it isn't running; Reconnect after starting it works.
- [ ] Leave a console open for a long time, then Reconnect: it opens again without signing in.

**Bulk actions** (use test guests)
- [ ] Long-press a guest in Resources: "1 selected"; taps add/remove guests; Back or the arrow ends the selection.
- [ ] Select two stopped guests → Start: both start; the summary says "Started 2 of 2 guests".
- [ ] Select running and stopped guests → Shut down lists only the running ones.
- [ ] Templates and nodes can't be selected.

**Favourites**
- [ ] The star on a guest's screen adds it to Overview → Favourites; its state and CPU update there; tapping it opens the guest.
- [ ] Unstarring removes it; favourites survive an app restart.

**Node power** (only on a node you can afford to restart)
- [ ] Node → Reboot asks you to type the node name; the node restarts and comes back online in the app.

**Clone, template, migrate** (use a test VM)
- [ ] Clone a stopped VM (full) on the same node: the next free ID is suggested; the task finishes; **Open it** shows the copy.
- [ ] Clone to another node: with the disks on local storage Proxmox refuses unless you pick a target storage on that node.
- [ ] Convert a stopped test VM to a template (type its name); it then offers **Linked clone**, which is fast.
- [ ] Migrate a running VM to another node: "Live migration" is explained; the VM keeps running; the guest screen follows it to the new node.
- [ ] Migrate a running container: "Restart migration" is explained; it's briefly offline and runs on the new node afterwards.
- [ ] A VM with a PCI/USB device shows the host-device warning before migrating.

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

**Permissions** (Datacenter → Users, Groups, Roles, Permissions, Realms; use a test user)
- [ ] Users matches the web UI (Datacenter → Permissions → Users), including disabled and expired accounts.
- [ ] Add user `test` in the pve realm with a password and a group → sign in to the web UI as `test@pve` with it.
- [ ] Change its password; signed in as someone other than root@pam, Proxmox asks for your own password too.
- [ ] Add an API token: the secret is shown once; `curl -k -H 'Authorization: PVEAPIToken=…' https://host:8006/api2/json/version` works with it; delete the token and the same call fails.
- [ ] Authenticator app: **Open in authenticator app** adds the account (or type the secret); the 6-digit code is accepted; the web UI login now asks for it. A wrong code keeps the dialog open with Proxmox's message.
- [ ] Recovery keys: 10 keys, shown once; one of them signs in to the web UI.
- [ ] Disable, then remove a second factor; the web UI agrees.
- [ ] A user's **What … may do** matches the web UI's Permissions → "Effective permissions".
- [ ] Groups: create one with a comment, add a user to it on the user's editor, delete it.
- [ ] Roles: built-in ones are read-only; create a custom role with two privileges, edit it, delete it.
- [ ] Permissions: add `/vms/100` for the test user with PVEVMUser (propagate on), then remove it.
- [ ] Realms (if you have LDAP or AD): edit the comment; **Sync** as a preview → the task log lists what would change; a real sync adds the users.

**Ceph** (Datacenter → Ceph; only on a cluster with Ceph, ideally a test cluster)
- [ ] Without Ceph the screen says it isn't set up (no error).
- [ ] Health, capacity, OSD/monitor/manager counts and PG states match the web UI (Datacenter → Ceph); tapping a health check shows its details.
- [ ] Set **noout** → the web UI shows the flag and a health warning; clear it again.
- [ ] OSDs by node match the web UI; **Mark out** then **Mark in** an OSD; **Scrub** starts (the cluster log shows it).
- [ ] Pools: create a small test pool (with **Add as storage**) → it and its RBD storage appear; change min. size; delete it (type its name) with its storage.
- [ ] Monitors, managers and metadata servers match the web UI; **Restart** a manager → the task finishes and it comes back.
- [ ] Configuration shows ceph.conf; the CRUSH map and the cluster log load.

**SDN** (Datacenter → SDN; use a test zone, guests on changed VNets can lose their network briefly)
- [ ] Zones, VNets and subnets match the web UI (Datacenter → SDN), including what is pending there.
- [ ] Add a simple zone `test` with automatic DHCP, a VNet `testnet` in it and a subnet 10.99.0.0/24 with gateway 10.99.0.1, SNAT and a DHCP range → "3 changes not applied yet" → **Apply** → the task log shows each node; the web UI shows them applied.
- [ ] Attach a test container's NIC to `testnet` (web UI) → it gets an address from the range; the IPAM `pve` lists it.
- [ ] Change the VNet's alias → marked Changed → **Discard** (Proxmox VE 8.4+) → back as it was.
- [ ] Delete the subnet, the VNet and the zone → marked to remove → **Apply** → gone.

**Backup Server** (Dashboard → Manage Backup Server)
- [ ] CPU, memory, disk and datastores match the Backup Server's dashboard.
- [ ] A datastore: usage, counts, last garbage collection and deduplication factor match its Summary; **Garbage collect** runs as a task.
- [ ] Namespaces and backup groups match its Content tab; create and delete a test namespace.
- [ ] A snapshot: edit its notes, protect it (Forget is then refused), verify it → Verified.
- [ ] **Browse files** of a container backup; save a file (open it on the phone) and a folder (a zip).
- [ ] **Prune** a test group: the preview matches the web UI's prune simulator for the same numbers.
- [ ] Jobs: last results match the web UI; **Run now** a verify job; its log opens.
- [ ] Remotes: add one with a wrong fingerprint (refused when a sync job uses it), delete it.
- [ ] Updates, Repositories, Services and System log show the Backup Server's own (proxmox-backup-server, proxmox-backup-proxy).
- [ ] Tasks tab: a Backup Server task opens its log.
