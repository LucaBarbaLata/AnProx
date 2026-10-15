# Dev log

Chronological notes from development sessions: what was done, what went wrong, and how it was
solved. Newest first.

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

**M4: the rest of the firewall.** A security group's rules sit at the group's own path
(`/cluster/firewall/groups/{group}/{pos}`), not under `…/rules`, so `FirewallScope` gained a
`rulesPath` and a `Group` case, and the rules screen serves groups unchanged (minus options, and
minus inserting another group). IP set entries are addressed by CIDR in the path, so
`10.0.0.0/24` travels as `10.0.0.0%2F24` (Retrofit encodes `@Path` values). The log API pages
from the start of the file; the first call asks for one line to learn `total`, then fetches
the last 500 (`ApiEnvelope` gained `total`, and `ProxmoxConnection.pveEnvelope` returns the
whole envelope). That completes M4.

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
download step waits for the file name the app derives from the URL before tapping Download. The
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
