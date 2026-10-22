# Dev log

Chronological notes from development sessions: what was done, what went wrong, and how it was
solved. Newest first.

---

## 2026-10-21 — Session 6: a calmer design before 1.0

The owner found the app "cluttered and overwhelming" and asked for several directions to choose
from. A design canvas showed five (A–E) on three screens each (Overview, Resources, a VM); the
owner picked **E**, "the current theme, uncluttered" ([ADR 0016](adr/0016-calm-midnight.md)).
It went in three steps, each pushed and checked by CI and the emulator tour:

1. **Building blocks** (`:core:designsystem`): a flat page, solid cards without border or
   highlight, solid buttons and bars, status dots without halo (a stopped guest is a ring),
   charts in the accent and a grey, sentence-case section headers, a quieter `MenuRow`, lighter
   tertiary text. CI caught one Kotlin trap: adding `danger: Boolean = false` *after* `onClick`
   in `MenuRow` made every trailing lambda bind to `onClick`'s neighbour, 82 errors in one go.
   `MenuRow` keeps its old signature and gained an overload with `danger` before `onClick`.
2. **Guest screen:** a summary card with Console and the power action that fits the state (the
   rest in a ⋯ menu), a `ToolGrid` (four per row, More after seven), and Network, Performance and
   Details folded (`CollapsibleHeader`). The tour opens folded tools with `open_tool()`, which
   taps More only when the tool isn't already showing.
3. **Overview, Resources, Node, Datacenter, More:** one card per server with three numbers and
   its own warnings; list rows in one surface with a hairline between them; the node gets the
   guest screen's pattern (summary, tool grid, ⋯ for all guests, power in the top bar); menus
   lose their explanations. A row's dot carries the state as a content description when the
   right side shows a number, which also keeps the tour's `row_shows(…, "Running")` working.
   68 strings that only described menu entries or old sections went away; the tour now taps titles
   ("Users", "Permissions", "Ceph", "SDN") instead of those descriptions; the Datacenter's
   "Permissions" header became "Users and permissions" so "Permissions" is unique.

**OpenID Connect.** Written from the web interface's own flow (`LoginWindow.js` sends
`location.origin` as `redirect-url`): `POST /access/openid/auth-url` gives the provider's page,
the provider sends the browser back to the server's origin with `code` and `state`, and
`POST /access/openid/login` trades them for an ordinary ticket. PBS has the same two calls.
- A browser tab can't be used: the way back goes to the server's address, which the provider
  has registered, not to the app. So the page opens in a WebView that checks every main-frame
  navigation, redirects included (`shouldOverrideUrlLoading` sees those since Android 7), with
  `OpenIdCallback.parse` and stops before the address loads.
- The account is only known after sign-in (`alice@sso`). The hub stores the session *before* it
  writes the profile's new user and realm, because a profile with a different user gets a new
  connection, which restores its session from the store.
- The mock's `sso` realm has an identity provider at `/mock-idp/authorize` that sends the browser
  straight back. Tests: 4 for parsing the way back, 2 connection tests (both products, ticket
  renewal, a spent code, a wrong redirect address, a non-OpenID realm), 1 hub test, and the
  tour's last step, which adds a server and signs in through `sso`.
- WebAuthn stays out: Android lets an app use it only for a site whose Digital Asset Links file
  names the app ([security.md](security.md#why-not-webauthn-security-keys-passkeys)).

**Certificates and ACME.** Written from pve-manager's `PVE/API2/{Certificates,ACME,
ACMEAccount,ACMEPlugin,NodeConfig}.pm`:
- `GET …/certificates/info` lists `pve-root-ca.pem`, `pve-ssl.pem` and, when there is one,
  `pveproxy-ssl.pem`, the certificate the web interface serves (custom or ACME). Names come as
  OpenSSL's one-line `/O=…/CN=…`.
- The node's ACME setup lives in the node config: `acme: account=…,domains=a;b` (the older form,
  always the standalone HTTP plugin) and `acmedomain0` … `acmedomain5`
  (`domain=…,plugin=…,alias=…`). Changes go through `PUT /nodes/{node}/config` with the digest.
- Ordering (`POST …/acme/certificate`) refuses when a custom certificate exists unless `force`;
  renewing (`PUT`) refuses more than 30 days before expiry unless `force`. The app asks before
  either. Both end by restarting pveproxy, so the app retries the task status a few times when
  the connection drops.
- DNS plugin credentials are stored base64-encoded (`data`); the app shows and edits them as
  `KEY=value` lines. The provider list and each provider's fields come from
  `GET /cluster/acme/challenge-schema`.
- Mock: pve1 has a Let's Encrypt certificate with 20 days left, an `acmedomain0` through the
  `cloudflare` plugin and the `default` account. 3 model tests, 4 integration tests (renewal,
  domains with a stale digest, order with and without force, accounts with and without the
  terms, plugins, a read-only user), and a tour step after node administration.

**Notifications.** Written from pve-manager's `PVE/API2/Cluster/Notifications.pm`. Array
parameters (`mailto`, `target`, `match-field`, webhook `header` and `secret`) repeat their key in
the form body, which Retrofit's `@FieldMap` can't do, so these calls take a `FormFields` list
(`network/api/Forms.kt` builds an OkHttp `FormBody`). Webhook headers and secrets are property
strings with base64 values (`name=X-Token,value=YWJj`) and the body is base64 too; the app shows
plain text. Proxmox never returns passwords, tokens or secret values, so an edit leaves them out
unless they are typed again. A matcher's `match-field` and `match-calendar` entries contain commas
and spaces, so they are not split like other lists. Mock, 2 model tests, 3 integration tests and a
tour step (a test through the Gotify target).

**Dependencies.** The five Dependabot pull requests (Gradle 8.14.6, Bouncy Castle 1.86, JSch
2.28.8, MINA SSHD 2.20.0, JUnit 5.14.4) had passed CI on their own; none touches an ADR 0002 pin.
They went in as one commit on the branch, which closes them.

---

## 2026-10-18 — Session 5: the rest of the app (M7, M6, M8 and leftovers), while the owner prepares a real host

The owner asked to "complete the rest of the app and do as many tests as possible" while they
set up a real Proxmox host for testing.

**M7: access control.** Datacenter → Users, Groups, Roles, Permissions and Realms.
- Model (`model/pve/Access.kt`): users, API tokens, groups, roles, ACL entries, realm forms
  (LDAP, AD, OpenID) and sync options, second factors, `TotpSetup` (a random 160-bit secret,
  RFC 4648 Base32 and the `otpauth://` link), `ExpiryDate` and effective permissions. A small
  `model/json/JsonFields.kt` reads Proxmox's loosely typed JSON (numbers as strings, booleans as
  0/1, lists as arrays or comma-separated strings). Forms send `delete=` for fields that were
  emptied, the way the web UI does, and never send a realm's username claim on update (Proxmox
  refuses to change it).
- Password and second-factor changes: since Proxmox VE 8.1 everyone but root@pam must confirm
  them with their *own* password (`confirmation-password`, or `password` for `/access/tfa`). The
  dialogs ask for it only when the signed-in user isn't root@pam.
- Mock (`MockAccess`): users, tokens, groups, roles, ACL, realms, a directory sync that runs as a
  task with a log (and changes nothing in a dry run), and TOTP checked with a real RFC 6238
  implementation (`MockTotp`), so the tests type the code an authenticator app would show.
  Signing in as a user created in the mock works with the password set for it.
- 6 model tests (Base32 test vectors, the `otpauth` link, forms, ACL, realms, dates) and 5
  integration tests (user lifecycle including signing in with the changed password, tokens,
  groups/roles/ACL, realms and sync, second factors).
- Tour step 26: a user with two password fields, an API token shown once, a role for a group,
  and a sync preview whose task log lists the user it would add.
- Not done: WebAuthn registration (needs the browser's credential API), scheduled realm sync
  jobs, and hiding controls the signed-in user may not use (all M9).

**M6: Ceph.** Datacenter → Ceph with OSDs, pools, monitors/managers/metadata servers and CephFS,
configuration, CRUSH map and log. Written from Proxmox's own API code
(`PVE/API2/Ceph*.pm`) rather than the docs:
- Proxmox serves Ceph per node (`/nodes/{node}/ceph/…`, proxied to that node). Reads go to the
  first online node; destroying an OSD and starting or stopping a daemon go to the daemon's
  host, since Proxmox runs them there (`proxyto => 'node'`, and destroy checks the OSD belongs
  to the node).
- `ceph status` changed shape over the releases: before Quincy the OSD map is nested one level
  deeper (`osdmap.osdmap`). `CephOverview` reads both. Proxmox adds `health detail` to the
  status, so checks carry their detail lines.
- OSD `percent_used` is 0–100, a pool's is a fraction (0–1), as Ceph reports them.
- A cluster without Ceph answers 500 with "binary not installed: /usr/bin/ceph-mon" (or
  "rados_connect failed"); the screen shows "Ceph isn't set up" for those instead of an error.
- `buildMap { put("size", size) }` put the *map's* size: the same shadowing as `keys` in M7.
- Mock (`MockCeph`): three nodes, two OSDs each, pve3 offline (so OSDs and a monitor are down and
  health is HEALTH_WARN), pools, monitors, managers, metadata servers, a CephFS, flags,
  ceph.conf, a CRUSH map and a log. OSD creation uses the node's unused disks from the M5 disk
  list and marks them used. Ceph is off by default so the existing alert tests keep their exact
  alert lists; the runnable mock (and so the emulator tour) turns it on.
- 5 model tests and 6 integration tests; tour step 27.

**M6: SDN.** Datacenter → SDN, written from `pve-network`'s API and plugin code:
- Zones, VNets, subnets and controllers are edited in a pending configuration; the nodes only
  get it on `PUT /cluster/sdn` (a task). Lists are read with `pending=1`: a changed entry keeps
  its running values at the top and lists what changes under `pending` (a removed option as the
  string `deleted`), a new one has only its ID and type at the top. `sdnEffective()` overlays the
  two, so the screens show what the network will be, with a New / Changed / To remove pill.
- IPAMs and DNS servers have no pending state, and Proxmox *refuses* `pending=1` for them (the
  schema doesn't allow it); the mock does the same so the client can't regress.
- A subnet's ID is `zone-network-prefix` (`lan-10.10.0.0-24`), not its CIDR.
- VNet tags depend on the zone: VLAN 1–4094 for VLAN and QinQ zones, a VXLAN ID up to 16777215
  for VXLAN and EVPN, none for simple zones; the form checks this before Proxmox does.
- **Discard** uses `POST /cluster/sdn/rollback`, which only Proxmox VE 8.4 and later have; older
  versions answer with an error that the snackbar shows.
- Mock (`MockSdn`): running and edited configurations with Proxmox's pending computation, a
  running simple zone with DHCP and a VLAN zone, two VNets with subnets, an EVPN controller, the
  `pve` IPAM with a few addresses, and one pending VNet; apply is a task that copies the edited
  configuration over.
- 5 model tests and 3 integration tests; tour step 28. `CheckRow` and `PasswordField` moved to
  `ui/common` for the SDN forms.

**Tour.** CI run for M7: Android 8.0 passed all 28 steps; on Android 14 every new step passed, but
the SSH step connected to `10.0.2.21` instead of `10.0.2.2`: clearing the prefilled `10.0.0.11`
had left its last "1" (the tap put the cursor before it and MOVE_END didn't land). `replace_field`
now deletes backwards from the end and forwards from the start, and starts over until the field
reads exactly the new value.

**M8: the Backup Server.** Written from `proxmox-backup`'s API code (`src/api2/admin/datastore.rs`
and friends):
- The node-administration API (updates, repositories, subscription, services, journal) is the
  same on a Backup Server, at `nodes/localhost/…`, so `NodeAdminRepository` picks `PbsApi` or
  `PveApi` by the server's product and the M5 screens serve both. Only **Upgrade** is left out,
  since the app's node shell is Proxmox VE's.
- Task status and logs now come from the right API for each product (`GuestRepository`,
  `ProxmoxConnection.awaitTask`), so every task the app follows, and the Tasks tab, work for
  Backup Server tasks.
- File restore uses the snapshot's catalog, which only container and host backups have (and not
  when the catalog is encrypted); a VM backup is disk images. Paths go back and forth as base64.
  `pxar-file-download` answers with the raw bytes (a zip for a folder), so the connection got a
  `pbsRaw` call without the `{"data": …}` envelope; the app streams it into a file the user picks
  with the system's save dialog.
- A datastore's status counts snapshots across all namespaces.
- PBS subscription keys look like `pbsc-…`; the key check now accepts both products.
- Mock (`MockPbs`): two datastores with namespaces, backups of VMs, a container (with a browsable
  catalog and file contents) and a host, verify states, protection, garbage collection, a prune
  that keeps per hour/day/week/month/year like PBS, maintenance modes, jobs and a remote. The
  Backup Server mock answers the node-administration calls for `localhost`.
- 4 model tests and 5 integration tests; tour step 29.
- CI: the tour crashed the app right after **Run now** on a Backup Server job. The live task
  notifications (`TaskTracker`) still polled the task with the Proxmox VE call, and the
  connection's product check threw an `IllegalStateException` that nothing caught on the app's
  scope. The tracker now asks the right product, and a call for the wrong product is an
  `ApiException.Protocol` like any other API failure (+1 test).
- CI, next run: the tour's new agent step tapped "Guest agent: Yes" in the guest's Details card
  instead of the Tools entry, so it now looks for the entry's description. The storage step
  looked for "Usage history", but section headers are drawn in upper case.

**Real-server smoke test.** Before the owner's host is reachable: `RealServerSmokeTest` runs
every read the app makes against a real Proxmox VE when `ANPROX_PVE_URL`, `ANPROX_PVE_USER` and
`ANPROX_PVE_PASSWORD` are set (read-only; see testing.md), and against the mock otherwise.

**Write test and the port-443 finding.** The owner allowed create/delete tests on the test host
with root@pam, so `RealServerWriteTest` creates, changes and deletes its own `anprox-test…`
objects (gated by `ANPROX_PVE_WRITE=1`, mock otherwise). Reaching the host failed although it
answered from a phone on mobile data: the cloud environment's egress proxy accepts the CONNECT,
but only port 443 gets through. `github.com:22` times out the same way after 7 s, while hosts not
on the allow list get a 403 at once. The fix is to serve Proxmox on 443 (a NAT redirect to 8006).

**First run against the owner's test host** (Proxmox VE 9.2.21, one node, run by the Real server
tests workflow with the repository secrets): every read passed, and the write test created a VM
on local-lvm, changed its options and hardware, took, described, rolled back and deleted a
snapshot, added and removed a firewall rule, started and stopped it (KVM available), backed it up
to `local` and restored it as a new VM, cloned it, used a pool, created a user and group, and
cleaned everything up. Differences from the mock that the app already handled: PVE 9 returns
more RRD samples (60 per hour, 1440 per day for storages), and HA groups are gone (null, HA
rules instead). The node had no container template, so the write test now downloads Alpine
from the catalogue when needed (and deletes it afterwards); the workflow prints one
PASSED/SKIPPED/FAILED line per test.

**Write test, extended.** The write test now covers almost every write the app makes:
templates and linked clones, disks (add, grow, detach, delete), a snapshot with RAM, a backup job
with run now, HA, upload and download from a URL, a directory storage, datacenter/node firewall
rules, security groups, aliases and IP sets, an SDN zone and VNet (never applied), a pending
bridge that is reverted, apt refresh, datacenter options, roles, ACLs, API tokens and a password
change. The mock caught one mistake in the test itself: aliases and IP sets share `cluster.fw`,
so an alias read before creating an IP set carries a stale digest and Proxmox refuses the delete;
the test reads it again first (the app's screens reload after every change, so they don't hit
this). The HA resource stays "ignored" so a standalone test node never arms its watchdog, and
the backup job keeps all backups so it can't prune the one the cleanup deletes.

**Extended write test, first real run.** On the test host (PVE 9.2.21) everything new passed
except three steps. The HTTPS download from `download.proxmox.com` got a certificate for another
name, so the test now uses plain HTTP like Proxmox's own apt and template sources (the server
fetches the URL, not the app). Stopping the VM after the snapshot with RAM timed out on the
guest's lock; the error from the `finally` hid whatever failed first, and the backup job that
followed on the same VM failed with "job errors". The test now reports a failed task with the
end of its log, keeps the first error of a step, waits out a held lock when stopping (naming the
tasks that hold it), and runs start/stop around a block with `whileRunning`.

**Backup Server real-server tests.** The owner set up a Proxmox Backup Server VM on the test
host, reachable from GitHub Actions, with a dedicated admin user and the server as a `pbs`
storage in Proxmox VE. `RealServerPbsTest` runs every read the app makes against it and, with
write steps on, a namespace, garbage collection, maintenance mode, a remote, a verify job and an
apt refresh. The backup checks (`checkPbsSnapshot`: notes, protection, verify, file restore, a
prune preview) are shared: the Proxmox VE write test backs up its own test container to the
`pbs` storage and runs them on exactly that backup, so they don't depend on what the datastore
already holds, and the cleanup deletes it. The PBS secrets are optional in the workflow.
Run 8: Proxmox VE reached the Backup Server over the LAN (the container backup to `pbs` worked and
was deleted again), but GitHub's runners timed out on `ANPROX_PBS_URL`, so the server isn't
reachable from the internet yet. The PBS sign-in in the Proxmox VE write test sat outside a
step, so the timeout ended that whole test after the container section; it is a step of its own
now, and `signIn` reports a failed sign-in as an exception, not an assertion a step can't catch.
Run 9, with the Backup Server reachable (PBS 4.2, one datastore): every Proxmox VE step passed,
the Backup Server write steps passed, and on the container backup made through Proxmox VE the
notes, protection, verify and prune preview passed. Two findings. A real bug: PBS 4.2 sends
`standard-repos[].status` as `true`/`false` where Proxmox VE sends `1`/`0`, so the Backup
Server's Repositories screen couldn't read the answer; the field is a lenient `Boolean?` now.
And the folder download (`etc` as a zip) arrived, but `ZipInputStream` stopped with "invalid
stored block lengths". The app only writes those bytes to the file the user picks, so the test
now reads the zip like an unzip tool does (central directory, every non-empty file's length and
CRC) and reports separately where a streaming reader stops, to tell a damaged download from a
quirk of the Backup Server's zip writer.
Run 10: all green on both servers, CI too. The repositories fix holds on PBS 4.2. The `etc`
zip has 191 entries and every non-empty file's length and CRC are right, so the download is
intact; a streaming reader stops at the very first entry, `etc/`, a folder the Backup Server
writes as deflated with a data descriptor but no compressed data. Tools that read the central
directory (most unzip programs) open it; ones that read front to back may not. That's the
Backup Server's zip writer, the same file its web interface offers, not the app.

**Run 6: all green on the test host.** Every read and every write step passes on the real
server, consoles included. The lock after a rollback with RAM is held by
`/usr/bin/perl /usr/sbin/qm cleanup 100 1 0` (qmeventd's cleanup for the QEMU process the rollback
replaced); it ran 14 s when the node shell looked and let go within the test's retry. That is
Proxmox's behaviour, so the app now recognises the failure (`TaskStatus.isLockTimeout`) and says
to try again shortly, in the snackbar and on the task log screen. The container's `stty size`
did answer `30 100`: its lines end in `\r\r\n` (the container's terminal and lxc-console's each
add a carriage return), which the test's pattern now accepts, so the container resize is checked
too.

**Runs 4 and 5 on the test host.** All three consoles work on the real server through the
app's own connection: the VM's screen over VNC (QEMU's desktop "QEMU (anprox-test-vm)", 640x480,
showing the Proxmox firmware logo), root login in an Alpine container's console, and the node
shell, where `stty size` confirms the 120x40 resize and `pveversion` runs. With HTTP the
download works, and with the earlier failure out of the way the backup job's "run now" passes
too. What remains: right after rolling back a snapshot with RAM, deleting it (or stopping the
VM) gets "can't lock file … lock-100.conf - got timeout" while no task is running, and works
some 20–35 s later. The test now asks the node shell which process holds the lock (`lslocks`,
`ps`) and tries again, so the next run names it.

**Consoles on the real server.** The write test now opens all three consoles through
`ConsoleRepository`, as the app does, and plays the part of the bundled pages
(`ConsoleProbe.kt`): noVNC's RFB handshake with the generated VNC password, a full frame in raw
encoding (with DesktopSize, since a VM that has nothing to boot resets every second under
Proxmox's `reboot-timeout=1000`) saved as a PNG, and a key press; the container's login, a resize
and a command; the node shell's resize checked with `stty size`. The mock terminal now answers
`stty size`, `echo $((a*b))` and `pveversion`, and the mock VM screen has two light bars, so a
blank frame no longer looks like a working one.

**Leftovers from M1 and M2.**
- Snapshot descriptions: `PUT …/snapshot/{name}/config` with `description` (empty removes it).
  A plain config write, not a task, so the screen reports it with a snackbar without a log.
- Storage charts: `GET /nodes/{node}/storage/{storage}/rrddata` returns `used` and `total` in
  bytes per sample; `MetricsSource.Storage` adds them to the charts the node and guest screens
  already use. The mock's history grows towards today's usage.
- Guest agent tools: `fsfreeze-status`, `-freeze`, `-thaw` and `fstrim` are `POST
  …/agent/{command}`; the reply is `{"result": …}` with a string, a count or an object, so they
  share one call returning `AgentResult<JsonElement>`. `set-user-password` needs 5 to 1024
  characters and an existing user. The mock refuses a second freeze and a trim while frozen.
- Start order: `startup` is a property string whose first part may be a bare number
  (`3,up=10` means order 3). An empty form deletes the key.
- The guest screen's "Coming later: built-in SSH client (M2)" card was out of date and is gone.
- Deleting guests (`DELETE …/{qemu|lxc}/{vmid}` with `purge` and `destroy-unreferenced-disks`)
  was still missing. Proxmox refuses a running or protected guest, and an HA-managed one unless
  `purge` is set; the mock does the same and drops the guest from its pool. Stopping a task is
  `DELETE …/tasks/{upid}`; the task then ends as "interrupted by signal".
- Node-wide `startall`, `stopall` and `migrateall` are one task each. `startall` only starts
  guests with start at boot unless `force=1`, which surprised me; the dialog offers both.
- The template catalogue is `GET /nodes/{node}/aplinfo` (the field `package` needs
  `@SerialName`, it's a Kotlin keyword) and `POST` with `storage` and `template` for a download
  task.
- `GET /cluster/options` answers `migration`, `ha` and `next-id` as objects (Proxmox parses
  `datacenter.cfg`), while `PUT` takes property strings, and `migration` may start with a bare
  type (`secure,network=…`). `DatacenterOptions` reads both shapes and writes property strings.
- Node task history is `GET /nodes/{node}/tasks` with `start`, `limit`, `errors` and
  `source=all` (without it Proxmox lists only finished tasks); the Backup Server takes the same
  paging at `nodes/localhost/tasks`. A restore's `storage` parameter moves all disks there.
- `GET /cluster/log` comes from pmxcfs with numeric `uid` and `pid`; newest first, so the
  repository sorts it oldest first for a log-style screen.
- 6 model tests and 16 integration tests; tour step 6 (agent) and additions to the snapshot,
  clone (delete the clone again), tasks, storage (usage chart, template catalogue), SDN (cluster log,
  datacenter options) and node (Start all) steps.

---

## 2026-10-15 — Session 4: consoles rebuilt

**0.1.0-beta.2 released** by the owner (Release run 3, commit `4881937`). The session couldn't
push the tag (the push was refused), so the owner ran **Actions → Release** themselves.

**Bug report from the owner's phone (Proxmox VE 8):** the node shell showed no content and no
keyboard, and noVNC reconnected in an endless loop on every VM.

- **Cause.** beta.2 loaded Proxmox's own console pages in a WebView with the session cookie set.
  The pages loaded, but their WebSocket never connected. The most likely reason is that the
  WebView's "proceed despite the certificate" answer doesn't cover WebSockets. The mock server
  and the emulator tour didn't catch it because they only checked that the page loaded.
- **Fix ([ADR 0014](adr/0014-consoles-through-the-app.md)).** The app now asks for the console
  ticket (`termproxy` / `vncproxy` with `generate-password=1`) and opens `vncwebsocket` with its
  own OkHttp client (`ProxmoxConnection.openWebSocket`), so certificates, custom headers,
  failover and the cookie are the same as for API calls. The terminal protocol is in Kotlin
  (`TermProxyProtocol`). noVNC and xterm.js are bundled as two local pages
  (`tools/console-web/` → `app/src/main/assets/console/`), and bytes cross a JavaScript bridge as
  base64. A **Keyboard** button focuses the page and shows the IME; the VM screen got its own key
  row.
- **Mock server.** termproxy/vncproxy, a terminal that echoes input (`\r` gives a new prompt,
  ESC shows `^[`) and a small RFB 3.8 server with VNC authentication (DES with bit-reversed key
  bytes, as noVNC does) that sends one 320x200 frame. JVM tests run the whole chain over HTTPS
  with a self-signed certificate and a sub-path.
- **Gotchas.**
  - noVNC 1.7 has a top-level `await` (an H.264 probe), which an IIFE bundle can't contain. The
    build rewrites that one line and fails if it can't find it.
  - Browsers drop a WebSocket whose server doesn't echo the `binary` subprotocol. Real Proxmox
    echoes it, the mock didn't, so the Chromium smoke test failed with 1006 until the mock routes
    used `protocol = "binary"`. OkHttp doesn't check the echo, so JVM tests passed either way.
  - `pkill -f MainKt` in the container also killed the shell running it. Kill the mock by PID.
- **Checked here:** 110 JVM tests, and a headless Chromium run of both pages against the mock
  (terminal echo, VNC handshake and frame). The Android screen is verified by CI and the emulator
  tour, which now opens a container console and a VM console.

**M2: hardware editing.** Built along the architecture recipe:
| Layer | What |
|---|---|
| Model | `PropertyString` (Proxmox's `key=value,…` format; keeps order and unknown keys so writing back changes only what was edited), `DiskSize`, `VmHardware` (CPU, memory and ballooning, disks by bus, CD/DVD, cloud-init, EFI/TPM, unused volumes, network, boot order including the legacy `cdn` form) and `CtHardware` (cores, memory, swap, root disk, mount points, network). Forms: `VmCpuMemory`, `NewVmDisk`, `VmDiskOps`, `VmNetSettings`, `CtResources`, `NewMountPoint`, `CtVolumeOps`, `CtNetSettings`, each validating like Proxmox and sending only changed keys with the digest. `IpAddress` checks IPv4/IPv6 with prefixes without `InetAddress` (which may resolve names). |
| API | `pending`, `POST …/config` (disk allocation as a task), `resize`, `move_disk` / `move_volume`, node bridges |
| Mock | `MockHardware`: a per-guest config with allocation (`storage:size`), detach to `unusedN`, delete, attach, resize, move, generated MACs, bridges, ISO images, and cold-plug keys of running VMs kept as pending until the next start |
| Data | `HardwareRepository`: config plus pending keys, apply / resize / move, bridges, ISO images across storages, host CPU count |
| UI | `ui/hardware/`: sections with "After restart" badges, a busy card, and dialogs for each change |
| Tests | +11 model, +6 against the mock (127 JVM tests); a tour step adds a disk and leaves a pending memory change |

Gotcha: a test ending in `assertFailsWith` returns the exception, so JUnit skipped it (the
pitfall in `testing.md`). A small script now compares the `@Test` count in each class with the
number of tests that ran.

**M2: creating VMs and containers.** `NewVm` and `NewContainer` (`:core:model`) hold the form,
check it like Proxmox (ID in use, name, free space, vCPUs per node, password length, SSH key
format, network) and build the `POST /nodes/{node}/{qemu,lxc}` form with the Proxmox VE 8
wizard's defaults; Windows gets SATA, e1000e, OVMF, q35, an EFI disk and a TPM. The mock creates
guests through the same hardware code as config changes (allocation, generated MACs) and lists
container templates. The form lives on `ui/create/` and is opened from a node's Tools. Shared
form pieces (chips, number fields, storage and bridge pickers) moved to `ui/common/FormParts.kt`.
+5 model and +4 data tests; the tour creates a VM with the suggested settings.

**M2: cloud-init.** `CloudInit`, `CloudInitIp` and `CloudInitForm` read and write `ciuser`,
`cipassword` (Proxmox only says whether one is set), `sshkeys` (stored URL-encoded: the app
encodes spaces as `%20` like the web UI, and decodes with `+` kept), `ipconfigN` per network
device, `nameserver`, `searchdomain` and `ciupgrade`. A VM without a drive gets one with
`storage:cloudinit`; `PUT …/cloudinit` regenerates the image. The tour opens the cloud-init
screen of `k8s-master`. That completes the M2 items except SPICE and the built-in SSH client.

**M2: SPICE handoff.** `POST …/spiceproxy` with `proxy` set to the host the app talks to (the
node's own address may not be reachable from the phone), rendered as a `[virt-viewer]` file
with the CA's newlines escaped, written to the cache and opened with `ACTION_VIEW` through a
FileProvider limited to `cache/spice/`. The mock answers only for running VMs with a `qxl`
display (win11). Left in M2: the built-in SSH client.

**Tour, again.** With the five steps wired in, two waited for snackbars that are only 4 s long
(missed between two UI dumps on the slow emulator) and one looked for a section header, which is
drawn in upper case. The steps now wait for something that stays on screen.

**The tour skipped five steps.** Reading the CI log of the console fix showed 10 steps, not 15.
The steps for options, the two consoles, bulk actions and clone/migrate were defined in
`tour.py` (since the options commit, `2d8076f`) but never added to the list `main()` runs. CI
was green without running them, and beta.2 shipped with those features tested only by JVM tests
and by hand. They are in the list now. Lesson: when adding a step, check the step count in the CI
log.

**Tour on Android 14.** After tapping **Keyboard** in the container console, every UI dump
failed with an empty message. uiautomator waits for the screen to be idle, and the focused
terminal's blinking cursor keeps it busy; the first Back also only hid the keyboard. The dump
helper now includes stderr (where newer Android writes the reason) and `leave_console()` presses
Back until the console's top bar is gone, treating a failed dump as "still there". The bulk step
checks the guest's row for **Running** instead of a snackbar.

**M3: background alerts.** Built along the recipe:
| Layer | What |
|---|---|
| Model | `AlertEngine.evaluate(previous, now, settings)`: pure, so the rules are JVM-tested. Nodes going offline, lost quorum, running guests that stopped with no stop/shutdown/migrate/destroy task (and none still running), failed tasks and backups since the last check, CPU/memory/storage limits with 5 points of hysteresis, Ceph health getting worse. New tasks are picked by the server's clock (`lastTaskEnd`), so a phone clock that is off doesn't skip or repeat any. The first check reports current problems but no old tasks. |
| API | `GET /cluster/ceph/status` (a cluster without Ceph answers with an error, which means "no Ceph") |
| Data | `AlertRepository`: one `Observation` per signed-in server from the dashboard snapshot, cluster tasks and Ceph (PBS: datastores and tasks), memory per server through `AlertMemoryStore`, memory older than 12 h ignored. A node counts as offline only when both `/cluster/status` and `/cluster/resources` say so. |
| Mock | Ceph health switch, and test hooks for a guest crash and a just-failed task |
| App | `AlertWorker` (WorkManager, every 15 min to 3 h, network required), `AlertScheduler`, `AlertNotifier` (four channels so each kind can be silenced in Android's settings; one notification per problem, replaced rather than repeated), `AlertStore` (DataStore), More → **Alerts** with **Check now** and the last check's summary; `POST_NOTIFICATIONS` is asked for when alerts are switched on |
| Tests | +7 model, +5 against the mock PVE and PBS (155 JVM tests); a tour step switches alerts on, runs a check and finds the notification with `dumpsys notification` |

A flaky test found a real race: `ServerHub.servers` is a `stateIn` flow, updated a moment after
the profiles load or a login, so a worker in a freshly started process could see no servers.
`ServerHub.serversNow()` reads the same list directly.

**M3: links, live tasks, shortcuts.** `AppLink` (`:core:model`, JVM-tested) is a small
`anprox://` link format for the places notifications, shortcuts and widgets open: a guest, a
node, a task log, a tab, the alert settings. `MainActivity` hands the intent's link to
`AppLinks`, and the navigation graph opens it once it is set up. There is no intent filter, so
only explicit intents carry links; a link only opens a screen. Alert notifications now open the
node, guest or task they are about (stopped-guest alerts carry the guest's node and type for
that). `GuestRepository.awaitTask` tells a `TaskObserver` about each task; the app's
`TaskTracker` follows it on the app scope (so leaving the screen doesn't lose it), shows a silent
ongoing notification after 3 s and, if the app is in the background when the task ends, says how
it ended. Three dynamic shortcuts (Resources, Tasks, Alerts) are published from the activity. The
tour opens the Alerts screen with `am start -d anprox://alerts`.

**M3: tile and widget.** Each check now also keeps a `ServerSummary` per server (nodes online,
guests running, storages, current problems; unreachable servers included) in the last run. The
Quick Settings tile (`StatusTileService`) and the Glance widget (`StatusWidget`, a new
dependency: `androidx.glance:glance-appwidget` 1.1.1) both read it and are refreshed by the
worker after every check; both open the dashboard. The widget draws with fixed Midnight Glass
colours, since widgets can't use the app's Compose theme.

**M4: backup jobs.** `BackupJob` is read from raw JSON objects, because Proxmox VE 8 sends
`prune-backups` as a property string and newer versions as an object, and jobs can carry options
the app doesn't edit. `BackupJobForm` builds the create and edit calls; an edit sends every
value and deletes what no longer applies (switching from a list to "all" deletes `vmid`), and
leaves unknown options alone. The schedule preview is Proxmox's own (`schedule-analyze`), so the
app never has to parse calendar events; the mock has a small parser for the presets and simple
events. "Run now" posts the job's settings to `vzdump` on each node it covers, like the web UI,
and limits a "selected guests" job to the guests on each node. The Datacenter screen opens from
the cluster card on the dashboard and lists the rest of M4 as planned.

**M4: storage.** `StorageRepository` combines `GET /storage` (the configuration) with the
storage rows of `/cluster/resources` (state and usage per node), lists content by type, and
handles `download-url` (the node fetches the file, so a big ISO never passes through the phone),
`query-url-metadata` and deletes. Uploads stream from the Storage Access Framework through an
`UploadBody` that re-opens the file for each attempt and reports progress; the `content` field
goes before the file part, which Proxmox requires. VM disks and container volumes are listed
but not deletable here (that is the Hardware screen's job). A mock bug turned up on the way: the
Windows VM's seeded config took its ISO by list index, which broke once a test deleted an ISO.

**M4: firewall.** The datacenter, node and guest firewalls share one API shape, so a
`FirewallScope` (its API path) drives one repository and one screen; Retrofit takes the path as an
encoded `@Path`. Rules carry the firewall's digest: every edit, toggle, move (`moveto`) and delete
sends it back, so a change made in the web UI meanwhile is refused. The rule form deletes cleared
fields on edit (a rule switched to a macro drops its protocol and port) and checks what Proxmox
would refuse (ports without a protocol or macro). Options are kept as key/value pairs with
Proxmox's defaults filled in for display; only changed ones are sent.

**M4: high availability.** `HaResource` keeps the `sid` (`vm:100`, `ct:101`) as Proxmox sends
it and reads the VMID from it; `HaStatusEntry` covers the quorum, master, LRM and service lines
of `status/current`. `HaResourceForm` sends only changed keys and deletes cleared ones on edit
(group, comment). Groups are read when the server has them: Proxmox VE 9 replaced them with
rules, so a failed groups request just hides the picker. Moving a managed guest goes through
`POST /cluster/ha/resources/{sid}/migrate` (the HA manager moves it; there is no task to
follow). The mock's HA manager lives in `MockHa`; a managed guest's `hastate` in
`/cluster/resources` now comes from it. The field was first called `ha`, which shadowed
`Guest.ha` inside `MockState`'s `with` blocks and broke the build of the mock.

**M4: storage replication.** Jobs and their runs come from two places: `/cluster/replication`
holds the configuration, and each node reports the runs of the guests it hosts
(`/nodes/{node}/replication`), so the repository asks the online nodes that run a replicated
guest, in parallel, and tolerates one that fails. Both are read from raw JSON objects because
numbers arrive as numbers or text. A new job's id is `<guest>-<n>` with the guest's lowest
free number. "Run now" (`schedule_now`) has no task: Proxmox starts the job within a minute,
so the screen says so and shows the log on request. The mock seeds a synced job and one that
fails because its target (pve3) is offline.

**M4: resource pools.** Proxmox VE 8.1 moved pools from `/pools/{poolid}` to
`/pools?poolid=…` (so names can hold slashes for nested pools) and deprecated the old paths.
`PoolRepository` uses the new form and falls back to the old one when the server answers 501
(no `PUT`/`DELETE /pools`), 404, or rejects the `poolid` parameter, which is how Proxmox VE 8.0
and older react. The mock's pools moved out of `MockJobs` into `MockPools`; backup jobs
selecting a pool read its members from there, guests carry their pool in `/cluster/resources`
and each pool is listed there too, as Proxmox does.

**M4: storage configuration.** `StorageSetupForm` covers the seven common types; values Proxmox
fixes once a storage exists (path, server, export, share, datastore, volume group, thin pool,
pool) are only sent on create and shown read-only on edit, as the web UI does. Edits send the
digest and delete only keys that are set, so Proxmox doesn't reject a `delete` of an unknown
key. A storage's own retention has no "inherit" choice: no `prune-backups` means keep all, so
the backup-job `RetentionCard` gained an `inherit` switch. Passwords are never read back; an
empty field keeps the stored one. The scan endpoints take the server's password as a query
parameter (that's Proxmox's API); it only travels over the app's TLS connection. In the mock,
storages became a mutable list (`CopyOnWriteArrayList`, as some readers aren't under the lock)
rebuilt from `MockStorageConfig` when a storage is added, edited or removed.

**M4: moving servers to another phone.** `ProfileCrypto` (`:core:data`, plain JVM) uses only
what Android 8.0 ships: `PBKDF2WithHmacSHA256` (added in API 26), AES/GCM and `java.util.Base64`
(also API 26). The envelope's header is the GCM additional data, so a file with a lowered cost
doesn't open; the cost read from a file is capped. Tests run with 1,000 iterations through a
constructor parameter; the app uses 600,000. The UI uses the system file pickers
(`CreateDocument` / `OpenDocument`), so no storage permission. Not in the emulator tour: the
system pickers' UI differs between Android versions.

**M5: updates, repositories, services, system log.** `termproxy` takes `cmd=upgrade`, so the
upgrade runs in the app's existing terminal (`ConsoleTarget.command`) rather than as a blind
task: apt asks questions (config files, kernel prompts) that need a human. Repositories come
with Proxmox's own `infos` (origin, warnings) and `standard-repos` (handles with a status:
null means not configured, so it can be added); changes send the digest. Services run as
tasks, and the app waits for them like guest actions. The journal endpoint wraps its lines in
`-- cursor:` markers, which `Journal.lines` drops.

**M5: disks.** `disks/list` says what holds each disk (`used`), so only unused ones are
offered for new storage; ZFS layouts check their minimum disk counts before Proxmox would.
Wiping asks for the device path typed out. SMART comes as attributes for ATA disks and as text
for NVMe. In the mock, creating storage marks the disks used when the task ends and, with
`add_storage`, adds the storage to the configuration limited to that node, as Proxmox does.

**M5: network.** Proxmox writes edits to `/etc/network/interfaces.new` and `GET …/network`
returns the interfaces as they will be, with the unified diff in the envelope's `changes`
(`ApiEnvelope.changes`). Apply is `PUT …/network` (ifreload, a task); revert is `DELETE`. An
interface that isn't applied yet has `exists` false, shown as "Not applied". Applying asks for
the node's name, since a wrong gateway or bridge port can cut the node off. The mock keeps a
current and a pending copy per node, and lists bridges from it for the guests' network
devices too.

**M4: the rest of the firewall.** A security group's rules sit at the group's own path
(`/cluster/firewall/groups/{group}/{pos}`), not under `…/rules`, so `FirewallScope` gained a
`rulesPath` and a `Group` case, and the rules screen serves groups unchanged (minus options, and
minus inserting another group). IP set entries are addressed by CIDR in the path, so
`10.0.0.0/24` travels as `10.0.0.0%2F24` (Retrofit encodes `@Path` values). The log API pages
from the start of the file; the first call asks for one line to learn `total`, then fetches
the last 500 (`ApiEnvelope` gained `total`, and `ProxmoxConnection.pveEnvelope` returns the
whole envelope). That completes M4.

**Consoles on a real Proxmox VE 8: still blank.** The owner's first test of the rebuilt consoles:
the node shell takes input (commands run) but shows no output, and the VM screen shows nothing.
Both pages work in headless Chromium when fed bytes the way the app feeds them, and the tour's
"Connected" status never proved that anything was drawn. So this round adds evidence instead of
guesses: a **Console details** dialog (bytes and messages received and sent, page loads, the
WebView version, recent events such as the page's size and the terminal size) and the page's
JavaScript errors in logcat (tag `AnProxConsole`) and on screen; the tour prints the WebView's
bounds, its text, pixels from its middle and those details into the job log. The pages also
gained polyfills for WebView 80–96 (`Array.prototype.at`, `replaceAll`, `WeakRef`, which noVNC
and xterm.js use) and the terminal refits when its container changes size, not only on window
resize. The download dialog's URL field took a keyboard's word completion (`.iso` became
`.isolated` on the Android 14 emulator, the "lost last letter" seen before); it now asks for no
suggestions.

The evidence came from the first run: on the Android 14 emulator the terminal received its 69
bytes but measured **46×1**, and the VM screen received 250 KiB but measured **412×0**, while the
page itself was 412×704. Headless Chromium without the stylesheets gives 45×24, so the CSS was
there and full-height elements collapsed. Cause: `AndroidView` leaves a view without layout
params as `WRAP_CONTENT`, and a WebView whose height wraps its content lays the page out with a
zero-height viewport (Chromium's "force zero layout height"), even though `innerHeight` and the
view's own size are right. The console WebView now gets `MATCH_PARENT` layout params, and the
tour fails if the terminal has fewer than 5 rows or the VM screen is under 100 px high.

**M2: the SSH client.** JSch (mwiede fork) with Bouncy Castle, see [ADR 0015](adr/0015-ssh-client.md).
Two surprises: JSch chooses between its JDK and Bouncy Castle classes by Java version, and
Android's `java.specification.version` reads as an old one, so `JschSetup` picks the Bouncy
Castle ones everywhere, to make the JVM tests run what the phone runs; and JSch throws
`UnsupportedOperationException` when asked to write an Ed25519 private key, so `SshKeys` writes
new ones itself in OpenSSH's `openssh-key-v1` format (no cipher, no KDF) and reads them back
through JSch to check. Host keys: JSch asks a `HostKeyRepository` during the key exchange, on the
thread that called `connect`, so `TrustOnFirstUse` blocks that thread until the user answers in
the dialog. A known host's key type is put first in `server_host_key`, so a server with several
host keys keeps showing the one that was trusted. The console screen now takes any
`ConsoleConnection`, so the SSH shell gets the xterm.js page, key row and rotation handling for
free. The mock gained an Apache MINA SSHD server (port 2222 when run by hand) with a toy shell and
the `ssh-copy-id`-like command, so the tests cover password and key sign-in, a changed host key,
a wrong password and a closed port. With this, M2 is complete.

**M3: favourites on the home screen.** A second Glance widget, `FavouritesWidget`, lists the
starred guests. `FavouriteRepository` (`:core:data`) asks each server for `cluster/resources?type=vm`
once, in parallel, and marks a favourite gone, unreachable or signed out when that's all it can
say; power actions look the node up again first, since the guest may have moved.
`FavouritesWorker` does the network work (refresh, action, waiting for the task) and
`WidgetStore` keeps what the widget draws, which the widget follows with `collectAsState` so a
live Glance session doesn't keep drawing what `provideGlance` read when it started. Shutting down
takes a second tap within 8 s (Q12); with the app lock on, the buttons open the guest in the app
instead (Q11). It refreshes after each background check, when favourites or the lock change,
when the app goes to the background and on its ↻ button. Android sends `APPWIDGET_UPDATE` when
an app's components are switched on or off, which WorkManager does when its queue empties, so
refreshing from `onUpdate` could loop; it is limited to once every two minutes.

**Tour, again.** On Android 8 the alerts screen's last card is below the fold, and uiautomator
lists it with empty bounds; on Android 14 the **Open it** button after creating a VM ended up
under the result snackbar at the end of the form. The create and clone screens now scroll to
their result, and the tour waits for the snackbar to close.

**Text fields dropped keystrokes on Android 14.** The tour typed a URL into the download dialog
and the last character went missing. Screens keep form values in a ViewModel's StateFlow, which
reaches Compose a frame later, and that late value fed back into the field overwrote what had
just been typed. `GlassTextField` now keeps its own copy while the user types and takes the
screen's value once typing pauses for 300 ms, so app-made changes (a suggested file name, a
filtered number, a cleared password) still appear. Every form in the app uses it.
It wasn't the whole story: a later run lost the final "o" again, and this time the ViewModel
never saw it either, so the key event itself was lost on the emulator. The tour's `fill` now
reads the field back and types whatever is missing (and says so in the log). The field change
stays, since the overwrite it prevents is real.
The next run lost it again *after* the check had seen the whole URL, so the check now wants the
text right twice, 1.5 s apart, retries when a dump catches no text fields at all, and the
download step waits for the file name the app derives from the URL before tapping Download.
The run after that still saw it: the file name read `tiny-test.iso` (so the ViewModel had the
whole URL), and a second or two later both fields went back to `tiny-test.is`. Only the URL
field's own change callback writes the URL, so something replayed an older text-field state.
It shows up only with `adb shell input text` (key events, with every keyboard app disabled) on
the Android 14 emulator, never on Android 8, and hasn't been reproduced with a real keyboard. It
stays on the list to look into; the download step now puts the letter back and retries (and logs
that it did) instead of failing the whole tour. The
same run showed `SectionHeader` draws its text in capitals ("GUESTS (1)"), so the pools step looks
for guest rows instead of headers.

Not alerted on purpose: a server that can't be reached. A phone that leaves the home network
would otherwise notify every 15 minutes; the Alerts screen lists unreachable servers instead.
A session that stops working is notified once ("Sign in to … again").

---

## 2026-10-14 — Session 3: M1 starts with snapshots

**Beta 0.1.0-beta.1 released.** The owner tested the app on **Proxmox VE 8** ("everything works
great"). They have no Backup Server, so PBS support is still only tested against the mock. A
Proxmox VE 9 test will follow later. The owner created the release key, added the secrets and
published with the Release workflow (run 2) from commit `e38c64b`, before the M1 work below was
pushed. `anprox.versionName` is now `0.1.0-beta.2`.

- **Bug found in the release notes:** the "Signing certificate SHA-256" line showed the text
  `certificate SHA-256 digest` instead of the hash. Newer `apksigner` versions print
  `V2 Signer: certificate SHA-256 digest: <hex>`, and splitting at the first `": "` took the
  wrong field. The workflow now takes the 64 hex digits and fails if it can't find them. The
  real fingerprint (from the release log) is in the README.

**Snapshots (first M1 feature),** built along the architecture recipe:
| Layer | What |
|---|---|
| Model | `GuestSnapshot`, `SnapshotTree.flatten` (depth-first, `current` last, orphans become roots, cycle-safe), `SnapshotName` (Proxmox `pve-configid` rules, 40 characters, `current` reserved, a locale-proof suggested name) |
| API | `PveApi.snapshots` / `createSnapshot` / `rollbackSnapshot` / `deleteSnapshot` |
| Mock | Seeded trees for `web`, `win11` and `pihole`. Rollback restores the running state only for RAM snapshots, and deleting re-parents children, as in Proxmox. |
| Data | `GuestRepository` snapshot functions. `vmstate` is only sent for VMs. |
| UI | `ui/snapshots/`: tree list, take dialog (suggested name, live validation, Include RAM for running VMs), roll back (type the name) and delete (confirm), busy card, task snackbar with View log |
| Tests | +12 JVM tests (57 in total) and a snapshot step in the emulator tour |

Small refactors on the way: `MenuRow` moved to `ui/common`, and the task-result snackbar was
extracted to `ui/common/TaskResult.kt` (used by the guest and snapshot screens).

**Performance charts (second M1 feature):**
| Layer | What |
|---|---|
| Model | `RrdPoint` (every field optional: Proxmox leaves out what it has no data for), `RrdTimeframe`, `ChartScale` (round axis tops, binary for bytes), `SeriesStats` (now/average/peak, skipping gaps) |
| API | `PveApi.nodeRrd` / `guestRrd` (`cf=AVERAGE`) |
| Mock | 70 samples per timeframe; gaps for stopped guests; 595 for the offline node |
| Data | `MetricsRepository` + `MetricsSource` (guest or node) |
| Design system | `ChartCard`: Canvas line chart with gradient fills, gap handling, axis labels, tap/drag scrubbing, TalkBack summary |
| UI | `ui/metrics/PerformanceSection` on the guest and node screens, with a timeframe picker; polls only while visible |
| Tests | +8 JVM tests (65 in total); a tour step checks the Disk I/O chart has values after switching to Week |

**App lock (third M1 feature),** [ADR 0013](adr/0013-app-lock.md): Android's biometric prompt with
the phone's screen lock as fallback (no separate AnProx PIN). `AppLock` (`:core:data`, 6 tests)
decides when to lock; `app/.../security/` holds the prompt, the lock screen and
`DangerousActionGuard`, which snapshot rollback now uses. `MainActivity` became a
`FragmentActivity`, with an explicit current `androidx.fragment`, because biometric 1.1.0 would
otherwise pull in fragment 1.2.5 from 2020. Security catch while building it: dialogs are separate
windows, so a plain overlay would have left an open "Roll back?" dialog usable above the lock.
The lock screen is therefore a full-screen dialog itself. The emulator tour checks the "needs a
screen lock" path; the full flow is on the manual checklist.

**Backups (fourth M1 feature):** `NodeStorage`/`StorageContentItem`/`BackupMode` (guest type from
`subtype`, or guessed from the volume name on older servers); `PveApi` storage listing, content,
`vzdump`, `nextid` and restore through `POST /nodes/{node}/{qemu|lxc}`; the mock keeps backups on
`local`, `nas-nfs` and `pbs` and enforces Proxmox's restore rules (replace needs `force` and a
stopped guest, and drops snapshots; new guests come from the backup's config). `BackupRepository`
lists storages in parallel and reports a failing one instead of failing the whole list. One
screen serves both a guest's backups and a storage's backups; replace is type-to-confirm plus
the app lock check. +9 JVM tests (80 in total) and a tour step that backs up `pihole` and restores
it as CT 105. Side fix: lint's ContextCastToActivity wanted `LocalActivity` in the lock screen.

**Guest agent info and options (night work for beta.2, PVE first because the owner has no
Backup Server).** Running guests get a Network card: OS and IP addresses from the QEMU guest agent,
or from `GET …/lxc/{vmid}/interfaces` for containers (no agent needed); loopback and link-local
addresses are hidden. The status screen never waits on a slow agent: the network flow emits a
loading `null` first. Guest → Tools → Options edits the everyday options through
`GET/PUT …/config`. `GuestOptions.changes` sends only changed fields, empties through `delete`, and
the config `digest`, which the mock checks like Proxmox does. The guest Tools rows now go through
one `onTool(GuestTool, …)` callback. The agent commit's CI failed in 30 seconds because two
`<string name="guest_network">` entries existed (a new section header reused an existing key).
JVM-only builds don't merge Android resources, so this only showed in CI; the local strings check
now also reports duplicate names.

**Clone, templates and migration.** `CloneRequest` and `MigrationPlan` (`:core:model`) hold
Proxmox's rules: linked clones only from templates, a target storage only for full clones, live
migration with `with-local-disks` when the VM has local disks, `restart` for running containers.
`GET …/qemu/{vmid}/migrate` is Proxmox's pre-flight check; containers have none. The mock enforces
the same rules (local storage can't be cloned to another node without a target storage, a running
VM needs `online`, …), +7 tests (101 in total). After a migration the phone navigation replaces
the old guest screen; the tablet list re-selects the guest on its new node, which also covers HA
moving it. The task-type table said `qmmigrate`; Proxmox's VM migration task is `qmigrate`.
The emulator tour broke on the long guest screen: the Network card sits above the charts, and
checking it after the charts needed more than 16 swipes (down to the end, then back up). The step
now checks it first, and `scroll_to` allows 24 swipes.

**Consoles and node power.** Rather than re-implementing noVNC and the terminal protocol, the
console screen loads Proxmox's own console page (`/?console=kvm|lxc|shell&novnc|xtermjs=1…`) in a
WebView after putting the session ticket into the `PVEAuthCookie` cookie for the server's origin;
Proxmox then embeds the CSRF token itself. Certificate errors are accepted only for that host and
only if the server allows an insecure certificate, the same rule as the API client. The extra key
row sends synthetic `keydown` events to xterm.js's input (`keyCode` has to be overridden, since the
`KeyboardEvent` constructor leaves it 0). The mock serves a stand-in page that reports whether the
cookie was accepted and which key codes arrived, both in its title; the app shows the page title
under the screen title, which gives the tour something native to check. Node reboot/shutdown is
`POST /nodes/{node}/status`, type-to-confirm. +6 tests (107 in total).

**Favourites and bulk actions.** Favourites are a DataStore string set keyed by server, type and
VMID (not node, so they survive migrations); the dashboard matches them against the cluster
snapshot it already polls. Bulk actions keep the selection in `ResourcesViewModel`, filter it per
action (Start only takes stopped guests, Shut down only running ones), run four power calls at a
time and wait for every task before summarising. `GlassCard` got an optional long-click.

**0.1.0-beta.2 released (2026-10-15).** The owner started the Release workflow (run 3) from
`4881937`; tests, the signed build (same certificate as beta.1) and the emulator tour passed, and it
was published as a pre-release. This session could not start it itself: workflow dispatch answers
403 for the integration, and pushing a release tag is blocked by the session's permissions. The
owner is now running `docs/beta2-test-guide.md` on Proxmox VE 8.

Gotchas: one new data test ended in `assertNotNull(...)`, which returns a value, so JUnit silently
skipped it (the CLAUDE.md pitfall again, caught because the test count was one short). The tour's
`scroll_to` now turns around at the end of a list, since the guest screen got longer. Chart touch
handlers read the selection through `rememberUpdatedState`, because `pointerInput` blocks outlive
recompositions.

---

## 2026-10-14 — Session 2: preparing the first beta (0.1.0-beta.1)

The owner asked: "can you make a release version? or let's prepare for a beta package".

**What a beta needed, and what was done:**
| Need | Done |
|---|---|
| A signing key that stays the same forever | Can't be created by an AI session: the key is the app's identity and must stay with the owner. Signing now reads repository secrets or a git-ignored `keystore.properties` ([releasing.md](releasing.md)). |
| Ordered version numbers | `0.1.0-beta.1` → versionCode 10021; the final 0.1.0 → 10099 (`app/build.gradle.kts`). |
| Proof the minified release APK actually runs | New `e2e` CI job: release APK on Android 8.0 + 14 emulators, driven by `scripts/e2e/tour.py` against the mock server. |
| A way to publish | `release.yml`: dry run → verify → e2e → notes from CHANGELOG → GitHub pre-release. |
| Feedback without telemetry | Local crash reports with a share dialog; About → Report a bug; issue templates. |
| Store presence | Fastlane metadata, icon and feature graphic (rendered with headless Chromium from `docs/brand/`). |
| Tester guidance | [beta-testing.md](beta-testing.md). |

**Gotchas:**
- `java.util.Properties()` in `app/build.gradle.kts` → "Unresolved reference: util", because `java`
  is the Java extension in Gradle Kotlin scripts. Fixed with an import, and noted in CLAUDE.md.
- New-headless Chromium screenshots have a shorter viewport than the window. The headless shell
  binary renders exact sizes.
- e2e tour, first runs: the release APK started fine on both emulators (no R8 problems at startup).
  The tour itself needed two fixes: the soft keyboard swallowed scroll gestures (now disabled for
  the tour), and on Android 14 `dumpsys input_method` reports `mInputShown=true` whenever a field
  *requests* a keyboard, so "press BACK if the keyboard is shown" closed the server editor instead.
  The tour never presses BACK to hide the keyboard any more. With that, the full tour passed on
  Android 8.0.
- On Android 14 (emulator in three-button navigation mode) the tour still ended up back on the
  welcome screen. The app draws edge to edge, so a list item can be *visible* under the system
  navigation bar. The tour tapped "Allow insecure certificate" while it sat there and pressed the
  bar's Back button instead. `scroll_to()` now moves the target towards mid-screen and waits for
  it to stop moving before any list tap. Failures print the focused window, the app process and
  any crash, and every tap logs its coordinates. After that, the full tour passed on both
  Android 8.0 and 14 (CI run 33). This isn't an app bug: edge-to-edge apps always show content
  under translucent system bars, and the bars take the touches there.

**Owner sign-off:** the Release workflow's dry run passed (Release run 1), and the owner tested
the app on their own Proxmox hardware: "everything works great". Remaining before the
public beta: the release key, the four repository secrets, and the publishing run.

---

## 2026-10-14 — Session 1: kick-off and milestone M0

### 1. Questionnaire
Asked the owner 40 questions in 10 rounds (foundation, scope, security, connectivity, guests,
datacenter, monitoring, design, release, process). All answers are recorded verbatim in
[questionnaire.md](questionnaire.md). Notable choices:
- Audience is **pro sysadmins/MSPs** (Q8), but the design is **bespoke "Midnight Glass"** (Q29, Q37)
  rather than a dense console look. The answer is dense information inside a calm, premium frame.
- **API tokens not wanted** (Q9). **Simple insecure-TLS switch** chosen over pinning (Q10). The risk
  is documented in [security.md](security.md).
- Owner is **learning Android** (Q39), so the docs explain concepts ([architecture.md](architecture.md), [glossary.md](glossary.md)).

### 2. Environment constraint: no Google Maven in the dev container
The cloud container's network policy blocks `dl.google.com`, which hosts the Android SDK *and*
Google's Maven repository (`maven.google.com` redirects there). Maven Central, the Gradle Plugin
Portal and `raw.githubusercontent.com` were reachable.

Workarounds:
- The settings file got a **JVM-only mode** (`-Panprox.jvmOnly=true`) that swaps the root build file
  so AGP is never resolved. All pure-Kotlin modules (model, network, data, mock server) build and
  test locally.
- Android modules were verified in **GitHub Actions**, using a small first push to validate the
  toolchain before writing all the UI.
- Maven Central occasionally answered **HTTP 429** (rate limiting). Local builds retried with a
  backoff and `--max-workers=2`.
- To make this permanent for future sessions, allow `dl.google.com` in the environment's network
  settings.

### 3. Build log
| Step | Result |
|---|---|
| Core model + tests | 20 tests ✅ |
| Network client + Ktor mock server + integration tests | 16 tests ✅ on first run |
| Data layer (hub, snapshots, guests) + tests | 8 tests. One was silently skipped by JUnit because the test function returned a value instead of `Unit` (see testing.md). Fixed. ✅ |
| CI run 1 (app shell) | ❌ `okhttp-android:5.5.0` requires compileSdk 37, and AGP 8.12 maxes out at 36 |
| Fix | Pinned OkHttp **5.4.0** (checked its AAR metadata: `minCompileSdk=36`). Also replaced `URLDecoder.decode(String, Charset)` (Android 13+ only) with the String-charset overload. |
| CI run 2 (app shell) | ✅ tests, lint, debug + release |
| CI run 3 (full M0 UI, ~40 files) | ✅ on the first attempt |
| Self-review fix | Dashboard/resources briefly showed "empty" before the first fetch, so a per-server loading state is now emitted immediately (+1 test, 45 total). Task list keys de-duplicated. |

### 4. Side effect: Dependabot PRs
Because this branch was the repository's first push, it became the default branch, and
Dependabot immediately opened PRs bumping GitHub Actions (`checkout` v7, `setup-java` v6,
`upload-artifact` v7). The workflow was updated to those versions directly, so those PRs can be
closed. Gradle dependency PRs will follow weekly. Each should be checked against the AGP/compileSdk
constraint in [ADR 0002](adr/0002-toolchain-baseline.md).

### 5. Not yet verified
- Nothing has run on a real device or emulator yet (no emulator in the container). The owner's
  [manual checklist](testing.md#manual-test-checklist-real-hardware) is the next step.
- Real PVE 9 / PBS 4 behaviour versus the mock: the TFA partial-ticket format, the `new-format`
  parameter, and realm `default` flags are implemented from the documented wire format and need
  confirmation on hardware.

### Next session (M1 candidates)
App lock and biometric re-auth, OIDC, WebAuthn, snapshots, backup now/restore, RRD charts,
guest agent info, bulk actions, task notifications. See the [roadmap](roadmap.md#m1--daily-driver).
