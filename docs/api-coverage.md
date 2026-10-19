# API coverage

This tracker maps the Proxmox VE and Proxmox Backup Server REST APIs to AnProx's
implementation. "Every function" (Q7) means every row here eventually reads ✅.

Legend: ✅ implemented · 🔸 partially · 🗓 Mx = planned for milestone *x* ([roadmap](roadmap.md)) · ➖ not planned (reason given)

Paths are relative to `/api2/json`. The authoritative lists are the official API viewers:
[PVE](https://pve.proxmox.com/pve-docs/api-viewer/) and [PBS](https://pbs.proxmox.com/docs/api-viewer/).
Code: `core/network/src/main/kotlin/.../network/api/` (`AccessApi`, `PveApi`, `PbsApi`).

---

## Proxmox VE

### `/version`, `/access`

| Endpoint | Purpose | Status |
|---|---|---|
| `GET /version` | Server version (feature detection) | ✅ |
| `GET /access/domains` | Realms for the login screen (unauthenticated) | ✅ |
| `POST /access/ticket` | Login, TFA challenge answer, ticket renewal | ✅ (password, TOTP, recovery, Yubico; WebAuthn 🗓 M1) |
| `/access/openid/{auth-url,login}` | OpenID Connect SSO | 🗓 M1 |
| `GET /access/permissions` | A user's effective privileges (Users → a user); hiding what you can't do 🗓 M9 | ✅ M7 |
| `PUT /access/password` | Change password (with the confirmation password PVE 8.1+ asks for) | ✅ M7 |
| `/access/users` (+ `/token`, `/unlock-tfa`) | Users (create, edit, delete, groups, expiry), API tokens (secret shown once), unlock TFA | ✅ M7 |
| `/access/groups`, `/access/roles`, `/access/acl` | Groups, roles (custom ones with a privilege picker), permissions | ✅ M7 |
| `/access/domains` (+ `/{realm}`, `/sync`) | LDAP, AD and OpenID realms; directory sync with a dry run | ✅ M7 |
| `/access/tfa` | Second factors of any user: TOTP (secret + `otpauth://` link), recovery keys, enable/disable, remove; WebAuthn 🗓 | ✅ M7 |

### `/cluster`

| Endpoint | Purpose | Status |
|---|---|---|
| `GET /cluster/resources` | Every node, guest, storage, pool, SDN zone | ✅ |
| `GET /cluster/status` | Quorum, nodes online | ✅ |
| `GET /cluster/tasks` | Recent tasks of all nodes | ✅ |
| `GET /cluster/log` | Cluster log (Datacenter → Cluster log, with a filter) | ✅ |
| `GET /cluster/nextid` | Free VMID for create/clone/restore | ✅ (create, restore, clone) |
| `/cluster/options` | Datacenter options | 🗓 M4 |
| `/cluster/backup` (list, create, edit, delete) | Backup jobs | ✅ (run now uses `POST /nodes/{node}/vzdump` per node, like the web UI) |
| `GET /cluster/backup-info/not-backed-up` | Guests no enabled job covers | ✅ |
| `/cluster/replication` (list, create, edit, delete with `keep`) | Storage replication jobs | ✅ |
| `GET /nodes/{node}/replication`, `POST …/{id}/schedule_now`, `GET …/{id}/log` | Last and next sync, failures, run now, last run's log | ✅ |
| `/cluster/ha/resources` (list, add, edit, remove), `POST …/{sid}/migrate` | Guests managed by HA, requested state, limits, moving | ✅ |
| `GET /cluster/ha/groups`, `GET /cluster/ha/status/current` | Groups (Proxmox VE 8) to pick from, manager and node status | ✅ |
| `POST/PUT/DELETE /cluster/ha/groups` | HA groups (Proxmox VE 8): nodes with priorities, restricted, no failback | ✅ |
| `/cluster/ha/rules` (list, add, edit, delete) | HA rules (Proxmox VE 9): node affinity and resource affinity | ✅ (written from the 9.0 docs, to confirm on hardware) |
| Node maintenance mode | `ha-manager crm-command node-maintenance` | 🗓 (no API in Proxmox VE 8; use the node's shell) |
| `/cluster/firewall/rules`, `/cluster/firewall/options` | Datacenter firewall rules (add, edit, enable, move, delete) and options | ✅ |
| `GET /cluster/firewall/macros`, `GET /cluster/firewall/groups` | Macros and security groups for the rule editor | ✅ |
| `POST/DELETE /cluster/firewall/groups`, `/cluster/firewall/groups/{group}` (rules, as for other firewalls) | Security groups and their rules | ✅ |
| `…/firewall/aliases` (list, add, edit with `rename`, delete) for the datacenter and guests | Aliases | ✅ |
| `…/firewall/ipset` (sets: list, create, delete with `force`; entries: list, add with `nomatch`, delete) for the datacenter and guests | IP sets | ✅ |
| `GET /nodes/{node}/firewall/log`, `GET /nodes/{node}/{type}/{vmid}/firewall/log` | Firewall log (newest 500 lines, via the response's `total`) | ✅ |
| `GET /cluster/firewall/refs` | Alias and IP set suggestions in the rule editor | 🗓 later |
| `/cluster/sdn` (+ `zones`, `vnets`, `vnets/{vnet}/subnets`, `controllers`, `ipams`, `dns`, `rollback`) | Zones (simple, VLAN, QinQ, VXLAN, EVPN), VNets, subnets with gateway, SNAT and a DHCP range, EVPN/BGP controllers, NetBox/phpIPAM, PowerDNS, the IPAM's addresses; pending changes, apply and discard | ✅ M6 (fabrics, route maps, prefix lists, the lock and IS-IS controllers 🗓 M9) |
| `GET /cluster/ceph/status` | Ceph health for background alerts | ✅ (the Ceph screen reads `/nodes/{node}/ceph/status`) |
| `/cluster/ceph/flags` (+ `/{flag}`) | OSD map flags (noout for maintenance, …) | ✅ M6 (`metadata`, `health-mute`, `restart-bulk` 🗓 M9) |
| `/cluster/mapping/{pci,usb,dir}` | Resource mappings | 🗓 M9 |
| `/cluster/notifications/*` | Notification targets & matchers (also used for push, M3) | 🗓 M3 (webhook setup) / M9 (full) |
| `/cluster/metrics/*` | External metric servers | 🗓 M9 |
| `/cluster/acme/*` | ACME accounts & plugins | 🗓 M9 |
| `/cluster/config/*` | Create/join cluster, corosync | 🗓 M9 |
| `GET /cluster/jobs/schedule-analyze` | Next runs of a calendar event (backup job preview) | ✅ |
| `/cluster/jobs/realm-sync` | Scheduled realm sync jobs | 🗓 M9 |

### `/nodes/{node}`

| Endpoint | Purpose | Status |
|---|---|---|
| `GET /nodes/{node}/status` | Node usage, CPU, kernel, versions | ✅ |
| `POST /nodes/{node}/status` | Reboot / shut down node | ✅ |
| `GET /nodes/{node}/tasks/{upid}/status` | Task status | ✅ |
| `GET /nodes/{node}/tasks/{upid}/log` | Task log | ✅ |
| `DELETE /nodes/{node}/tasks/{upid}` | Stop a running task (the task log's ⏹ button; Backup Server too) | ✅ |
| `GET /nodes/{node}/tasks` | Node task history with filters | 🗓 M1 |
| `GET /nodes/{node}/rrddata` | Performance history (charts) | ✅ |
| `POST /nodes/{node}/vzdump` | Backup now | ✅ (one guest; storage, mode, notes) |
| `/nodes/{node}/{startall,stopall,migrateall}` | Node → Start all (optionally only start-at-boot guests), Shut down all, Migrate all (target, local disks) | ✅ |
| `/nodes/{node}/{termproxy,vncshell,vncwebsocket}` | Node shell | ✅ `termproxy` + `vncwebsocket` through the app's connection, bundled xterm.js ([ADR 0014](adr/0014-consoles-through-the-app.md)) |
| `GET/POST /nodes/{node}/apt/update`, `GET …/apt/changelog`, `GET …/apt/versions` | Upgradable packages, refreshing the index (a task), changelogs, installed versions | ✅ |
| `GET/POST/PUT /nodes/{node}/apt/repositories` | Repositories with Proxmox's warnings; switch on/off; add a standard repository | ✅ |
| `POST /nodes/{node}/termproxy` with `cmd=upgrade` | The upgrade in a terminal, like the web UI | ✅ |
| `GET /nodes/{node}/disks/list`, `GET …/disks/smart` | Disks with health, wear and use; SMART attributes or text | ✅ |
| `PUT …/disks/wipedisk`, `POST …/disks/initgpt`, `POST …/disks/{directory,lvm,lvmthin,zfs}` | Wipe, initialize GPT, create storage (with `add_storage`) | ✅ |
| `GET …/disks/{zfs,lvm,lvmthin,directory}` | Listing pools, volume groups and mounts | 🗓 later |
| `GET /nodes/{node}/network` (with the `changes` diff), `POST`, `PUT …/{iface}`, `DELETE …/{iface}` | Interfaces: bridges, bonds, VLANs, ports; addresses, gateways, MTU, comments | ✅ |
| `PUT /nodes/{node}/network` (apply, a task), `DELETE /nodes/{node}/network` (revert) | Pending network changes | ✅ |
| OVS bridges, bonds and ports | Open vSwitch | 🗓 later (shown, edited in the web UI) |
| `GET /nodes/{node}/services`, `POST …/services/{service}/{start,stop,restart,reload}` | Services (tasks) | ✅ |
| `GET /nodes/{node}/journal?lastentries=` | System log (newest 500 lines) | ✅ |
| `GET /nodes/{node}/syslog` | The older syslog view | 🗓 (the journal covers it) |
| `GET/PUT /nodes/{node}/dns`, `GET/POST /nodes/{node}/hosts`, `GET/PUT /nodes/{node}/time` | DNS servers and search domain, /etc/hosts (with its digest), time zone | ✅ |
| `GET /nodes/{node}/subscription` | Subscription status (on the Updates screen) | ✅ |
| `PUT/DELETE /nodes/{node}/subscription` | Entering or removing a key | ✅ |
| `/nodes/{node}/certificates/*` | Certificates, ACME orders | 🗓 M9 |
| `/nodes/{node}/firewall/{rules,options}` | Node firewall | ✅ |
| `/nodes/{node}/firewall/log` | Node firewall log | 🗓 M4 |
| `/nodes/{node}/ceph/*` | Status; OSDs (create on an unused disk, in/out, scrub, start/stop/restart, destroy); pools (create, edit, delete); monitors, managers and metadata servers (add, start/stop/restart, remove); CephFS (list, create); `cfg/raw`, `cfg/db`, `crush`, `log`, `rules` | ✅ M6 (`init`, installing Ceph and removing a CephFS stay in the web UI) |
| `/nodes/{node}/sdn/*` | Per-node SDN status (zone and VNet health on each node) | 🗓 M9 |
| `/nodes/{node}/replication/*` | Replication status, run now, log | ✅ M4 |
| `/nodes/{node}/aplinfo` | Container template catalogue | 🗓 M4 |
| `GET /nodes/{node}/scan/{nfs,cifs,pbs,lvm,lvmthin,zfs}` | Storage discovery for the storage editor | ✅ |
| `GET /nodes/{node}/scan/iscsi` | iSCSI discovery | 🗓 (with iSCSI storages) |
| `/nodes/{node}/hardware/{pci,usb}` | Passthrough device lists | 🗓 M9 |
| `/nodes/{node}/capabilities/*` | CPU models, machine types | 🗓 M2 |

### `/nodes/{node}/qemu/{vmid}` (VMs) and `/nodes/{node}/lxc/{vmid}` (containers)

| Endpoint | Purpose | Status |
|---|---|---|
| `GET …/status/current` | Live status | ✅ |
| `POST …/status/{start,shutdown,reboot,stop}` | Power | ✅ |
| `POST …/status/{suspend,resume}` | Pause / resume (VM) | ✅ (container suspend ➖: experimental in Proxmox) |
| `POST /qemu/{vmid}/status/reset` | Hard reset (VM) | ✅ |
| `GET …/rrddata` | Charts | ✅ |
| `GET/POST …/snapshot` | List (tree) and take snapshots, with RAM for VMs | ✅ |
| `POST …/snapshot/{snap}/rollback` | Roll back | ✅ |
| `DELETE …/snapshot/{snap}` | Delete a snapshot | ✅ |
| `GET/PUT …/snapshot/{snap}/config` | Edit a snapshot's description | ✅ |
| `/qemu/{vmid}/agent/*` | Guest agent: IPs, OS info, fs-freeze, trim, set password, exec | 🔸 `network-get-interfaces`, `get-osinfo`, `fsfreeze-status`/`-freeze`/`-thaw`, `fstrim`, `set-user-password` ✅; `exec` and file read/write 🗓 M9 |
| `GET /lxc/{vmid}/interfaces` | A running container's IP addresses (PVE 7.2+) | ✅ |
| `GET/POST/PUT …/config`, `GET …/pending` | Hardware & options | 🔸 options ✅ (name/hostname, tags, notes, start at boot, start/shutdown order and delays, protection, QEMU agent); common hardware ✅ (VM: sockets, cores, CPU type, memory and ballooning, disks, CD/DVD, network devices, boot order; container: cores, memory, swap, mount points, network). Every change is sent with the config digest; `POST` for allocating VM disks as a task; pending keys are shown as "After restart". Advanced (passthrough, NUMA, CPU flags, cloud-init) 🗓 M2/M9 |
| `PUT …/resize`, `POST …/move_disk` / `move_volume`, `POST …/unlink` | Disks | ✅ resize (grow only), move to another storage (keep or delete the source), detach and delete unused volumes through `config`; `unlink` not needed |
| `POST …/clone`, `POST …/template` | Clone (full/linked, any node, target storage) / convert to template | ✅ |
| `/qemu/{vmid}/cloudinit` (+ `/dump`) | Cloud-init | ✅ settings through `config` (`ciuser`, `cipassword`, `sshkeys` URL-encoded as Proxmox expects, `ipconfigN` per network device, `nameserver`, `searchdomain`, `ciupgrade`), add a cloud-init drive (`storage:cloudinit`), `PUT …/cloudinit` to regenerate; `/dump` not used |
| `POST …/migrate` (+ `GET` precondition check) | Migration: live for VMs (with local disks), restart mode for containers, offline when stopped | ✅ |
| `POST /nodes/{node}/{qemu,lxc}` | Create guest (wizard) | ✅ VM (OS type, ISO, disk, CPU, memory, bridge; Advanced: sockets, CPU type, bus, discard, ballooning, NIC model, VLAN, firewall, UEFI, agent, start at boot) and container (template, root password and/or SSH keys, disk, cores, memory, network with DHCP/static; Advanced: swap, IPv6, VLAN, firewall, unprivileged, nesting, start at boot) |
| `DELETE …/{vmid}` | Delete a stopped guest (type its ID), optionally purging it from jobs, replication and HA and deleting unreferenced disks | ✅ |
| `…/vncproxy`, `…/vncwebsocket`, `…/termproxy` | noVNC / terminal console | ✅ through the app's connection (`generate-password=1` for VMs), bundled noVNC and xterm.js ([ADR 0014](adr/0014-consoles-through-the-app.md)) |
| `POST /qemu/{vmid}/spiceproxy` | SPICE `.vv` handoff | ✅ `proxy` = the address the app uses; the `.vv` file goes to a viewer app (aSPICE…) through a FileProvider |
| `…/firewall/{rules,options}` | Guest firewall rules, policies, DHCP/NDP/RA and MAC/IP filters | ✅ |
| `…/firewall/{aliases,ipset,log,refs}` | Guest aliases, IP sets, log | 🗓 M4 |
| `POST /nodes/{node}/qemu` (`archive=`) / `lxc` (`restore=1`) | Restore from backup | ✅ (as new with `unique=1`, or replace with `force=1`; target storage choice 🗓) |

### `/storage`, `/pools`, `/nodes/{node}/storage`

| Endpoint | Purpose | Status |
|---|---|---|
| storage rows in `/cluster/resources` | Usage on dashboard/resources | ✅ |
| `GET /storage` | Storage configuration (backup targets for jobs) | ✅ |
| `GET /storage/{storage}`, `POST /storage`, `PUT /storage/{storage}`, `DELETE /storage/{storage}` | Add, edit and remove directory, NFS, SMB/CIFS, Backup Server, LVM, LVM-thin and ZFS storages; remove any type | ✅ |
| The same for iSCSI, Ceph RBD/CephFS, ESXi import and others | Editing those types | 🗓 later |
| `GET /nodes/{node}/storage` | Storages a node can use (backup targets) | ✅ |
| `GET /nodes/{node}/storage/{storage}/content` | Browse ISOs, templates, backups, disks | ✅ |
| `DELETE /nodes/{node}/storage/{storage}/content/{volume}` | Delete ISO images, templates, snippets (backups from their screen) | ✅ |
| `POST …/upload`, `POST …/download-url` | Upload / download ISO & templates | ✅ (upload streams from the phone with progress) |
| `GET /nodes/{node}/query-url-metadata` | Name and size behind a download URL | ✅ |
| `GET …/rrddata` | Storage usage history (Storage → a node's storage) | ✅ |
| `…/file-restore/{list,download}` | File-level restore from PBS-backed backups | 🗓 M8 |
| `GET /pools` | Resource pools (list, backup job selection) | ✅ |
| `GET /pools?poolid=`, `POST /pools`, `PUT /pools` (`vms`, `storage`, `delete`, `comment`), `DELETE /pools?poolid=` | One pool's members, create, add and remove members, comment, delete (Proxmox VE 8.1+) | ✅ |
| `GET/PUT/DELETE /pools/{poolid}` | The same before Proxmox VE 8.1 (used when the newer form is refused) | ✅ |

---

## Proxmox Backup Server

| Endpoint | Purpose | Status |
|---|---|---|
| `GET /version` | Version | ✅ |
| `GET /access/domains`, `POST /access/ticket` | Login, TFA | ✅ |
| `GET /status/datastore-usage` | Datastore usage + "full in" estimate | ✅ |
| `GET /admin/datastore` | Datastore list | ✅ |
| `GET /nodes/localhost/tasks` | Task history | ✅ |
| `GET /nodes/localhost/tasks/{upid}/{status,log}` | Task detail & log (Tasks tab, after every change) | ✅ M8 |
| `/admin/datastore/{store}/{status,groups,snapshots,namespace}` | Usage and counts; namespaces (create, delete); backup groups and snapshots; forget a snapshot or a whole group | ✅ M8 |
| `/admin/datastore/{store}/{gc,prune,verify}` | Garbage collection (status, start), prune a group (with a dry run), verify a snapshot, group or namespace | ✅ M8 (`prune-datastore` 🗓 M9) |
| `/admin/datastore/{store}/{catalog,pxar-file-download}` | File restore from container and host backups: browse the catalog, save a file or a folder (zip) | ✅ M8 (VM images: through Proxmox VE's file restore 🗓 M9) |
| `/admin/datastore/{store}/{notes,protected}` | Snapshot notes & protection | ✅ M8 |
| `/config/{datastore,remote,prune}` | Maintenance mode (read-only, offline) of a datastore; remotes (add, delete); switch a prune job on or off | ✅ M8 (creating datastores and jobs, traffic control 🗓 M9) |
| `/admin/{sync,verify,prune}` (+ `/{id}/run`) | Jobs with their last and next run; run one now | ✅ M8 |
| `/tape/*` | Tape backup | 🗓 M8 (basic) / M9 |
| `/nodes/localhost/{status,apt,subscription,services,journal}` | The server's status; updates, repositories, subscription, services and the journal through the M5 screens | ✅ M8 (`disks`, `network` 🗓 M9) |
| `/access/{users,acl,roles,domains,tfa}` | Access control on a Backup Server | 🗓 M9 (Proxmox VE's is done in M7) |
| `/config/{acme,notifications,metrics}` | Certificates, notifications, metrics | 🗓 M9 |

---

## Not planned (➖)

| Area | Reason |
|---|---|
| API-token login | Owner's decision (Q9). Auth headers are added in one place (`AuthInterceptor`), so it stays cheap to add if Q9 is amended. |
| Proxmox Mail Gateway, Datacenter Manager | Out of scope (Q4). |
| Container suspend/hibernate | Experimental in Proxmox (CRIU) and hidden in the web UI. |
