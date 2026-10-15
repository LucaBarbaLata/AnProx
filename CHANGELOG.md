# Changelog

All notable changes to AnProx are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Hardware** for VMs and containers (Guest → Tools → Hardware).
  - VMs: sockets, cores and CPU type; memory and ballooning; disks (add on any storage with
    SCSI/VirtIO/SATA/IDE, IO thread, discard, SSD emulation; grow; move to another storage; detach;
    attach or delete unused disks); CD/DVD drives (pick an ISO image from the node's storages,
    eject, add or remove a drive); network devices (model, bridge, VLAN, firewall, disconnected,
    rate limit); boot order.
  - Containers: cores, memory and swap; grow or move the root disk and mount points; add and
    detach mount points; network devices with DHCP, static or no IPv4 and DHCPv6, SLAAC or static
    IPv6.
  - Every change sends the config digest, so a concurrent edit in the web interface is refused
    instead of overwritten. Changes a running VM only picks up at its next start are marked
    **After restart**. Deleting an unused disk asks you to type its name.

- **Create VMs and containers** (Node → Create VM / Create container): a short form with the
  choices most people need and an **Advanced** switch for the rest. VMs: name, operating system
  (Windows gets SATA, an Intel network card, UEFI and a TPM), installation ISO, disk, cores,
  memory and bridge. Containers: template, root password and/or SSH keys, disk, cores, memory,
  and DHCP or a static address. The next free ID is filled in; problems are explained before
  Proxmox would refuse them. **Open it** goes to the new guest.

- **Cloud-init** for VMs (Guest → Tools → Cloud-init): user, password (kept unless you type a
  new one), SSH keys, DHCP/static/none IPv4 and IPv6 per network device, DNS servers, search
  domain and package upgrades. Adds the cloud-init drive if the VM has none, and can regenerate
  the image.

- **SPICE viewer** for running VMs with a SPICE display (Guest → Tools → SPICE viewer): opens
  the VM in aSPICE or another app that opens `.vv` files, through the address the app already
  uses.

- **Background alerts** (More → Alerts), checked every 15 minutes to 3 hours while the app is
  closed: a node going offline, a cluster losing quorum, a guest that stopped without anyone
  stopping it, failed tasks and backups, CPU, memory or storage above your limits, and Ceph
  warnings or errors. Each problem is notified once, on its own Android notification channel.
  **Check now** runs a check right away; the screen shows what the last one found and which
  servers couldn't be reached.
  Tapping a notification opens the node, guest or task log it is about.

- **Live task notifications**: a task you start (power, snapshot, backup, clone, migrate, a
  hardware change…) that runs for more than a few seconds shows a silent notification until it
  ends; if the app is in the background by then, a notification says how it ended. Switch it
  off under More → Alerts.

- **App shortcuts**: long-press the launcher icon for Resources, Tasks or Alerts.

- **Quick Settings tile** "Proxmox": what the last background check found ("OK at 14:32",
  "2 new alerts", "1 unreachable"); tap it to open the dashboard.

- **Home-screen widget** "Proxmox status": each server with nodes online, guests running and
  current problems, as of the last background check; tap it to open the dashboard.

- **Backup jobs** (Dashboard → Datacenter → Backup jobs): every scheduled backup with its next
  run; switch a job on or off, run it now, or create and edit one: schedule (presets, or any
  Proxmox calendar event with a preview of the next runs), storage, node, which guests (all,
  selected, all but some, a pool), mode, compression, retention and notes. The screen also lists
  guests that no enabled job backs up.

- **Storage** (Dashboard → Datacenter → Storage): every storage with its usage on each node, and
  what it holds by type. The node can download an ISO image or container template straight from
  a URL (optionally checked against a checksum), you can upload one from the phone, and delete
  ISO images and templates you no longer need.

- **Firewall** for the datacenter (Datacenter → Firewall), each node (Node → Firewall) and each
  guest (Tools → Firewall): rules in the order they apply, with a switch per rule, arrows to
  reorder, and an editor (in/out or a security group; accept, drop or reject; macro, protocol,
  source, destination, ports, interface, log, comment). Options: switch the firewall on or off,
  the policies for unmatched traffic, log levels, and a guest's DHCP, NDP, router advertisement
  and MAC/IP filters. Edits made elsewhere in the meantime are refused instead of overwritten.

- **High availability** (Datacenter → High availability): the HA manager's status, quorum and
  each node's state; the guests HA manages with their requested and current state. Add a guest,
  choose whether HA keeps it started, stopped, disabled or ignores it, pick a group, set how
  often it is restarted or moved, move it to another node through the HA manager, or remove it
  from HA.

- **Replication** (Datacenter → Replication): which guests are copied to which node, whether
  the last copy worked (with Proxmox's error when it didn't), when it ran and runs next. Add a
  job (guest, node, every 15 or 30 minutes, every 2 hours, nightly or any schedule, a speed
  limit), edit it, switch it off, run it now, read the last run's log, or delete it.

- **Resource pools** (Datacenter → Resource pools): what each pool holds. Create a pool, add
  guests and storages to it or take them out, change its comment, delete it once empty. Tap a
  guest to open it.

### Changed
- The bulk-action summary on the Resources list stays on screen longer.
- After creating or cloning a guest, the form scrolls to the result and its **Open it** button.

### Fixed
- Text fields no longer lose characters when typing fast (seen on Android 14).
- **Consoles work on real servers.** In 0.1.0-beta.2 the VM screen reconnected endlessly and node
  and container terminals stayed empty, without a keyboard. The app now connects to the console
  itself, through the same connection as everything else, so self-signed certificates, custom
  headers and several addresses work. noVNC and xterm.js are now bundled in the app. A
  **keyboard** button shows the phone keyboard, and the key row works on the VM screen too (Esc,
  Tab, arrows, Enter, Ctrl+Alt+Del). An outdated Android System WebView is explained instead of
  failing silently.

## [0.1.0-beta.2] — 2026-10-15 (milestone M1 "Daily driver", plus consoles and guest management from M2)

Second beta, focused on everyday Proxmox VE work: consoles, snapshots, backups, charts, guest
options, clone and migrate, bulk actions and an optional app lock.

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

- **Consoles** (Guest → Tools → Console, Node → Shell): the VM's screen (noVNC) or a terminal for
  containers and nodes (xterm.js), using Proxmox's own console pages signed in with the app's
  session. Terminals get a row of keys phone keyboards lack: Esc, Tab, Ctrl+C, Ctrl+D and the
  arrows. Self-signed certificates are accepted only for servers with "Allow insecure certificate".

- **Bulk actions** (Resources): long-press a guest to start selecting, tap more, then Start, Shut
  down or Stop them all from the top bar. Only the guests the action fits are included (Start skips
  running ones), the confirmation lists them, and a summary says how many succeeded.

- **Favourites:** the star on a guest's screen pins it to the dashboard, with its live state. A
  favourite follows the guest when it moves to another node.

- **Node reboot and shut down** (Node → Power): type the node's name to confirm, plus the app lock
  check when it is on.

- **Clone** (Guest → Tools → Clone): a full or linked clone (linked needs a template) with the
  next free ID, a name, the target node and the target storage. When it's done, **Open it** jumps
  to the new guest. **Convert to template** (stopped guests; type the name to confirm).

- **Migrate** (Guest → Tools → Migrate): move a guest to another node. Running VMs move live
  (local disks are copied too), running containers are restarted on the new node, stopped guests
  move offline. Uses Proxmox's own pre-flight check: nodes missing a storage, and host devices that
  pin the VM to its node, are shown before you start. Afterwards the guest screen follows the
  guest to its new node.

### Changed
- The guest screen's tools section lists what works now; everything else is under "Coming later".
- Backup storages in the Resources list open their backups.
- On tablets, a selected guest that moves to another node (migration or HA) stays selected.

### Fixed
- VM migration tasks are labelled "VM migrate" (the task type is `qmigrate`), and HA migrations "HA migrate".
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
