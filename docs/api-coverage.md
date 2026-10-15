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
| `GET /access/permissions` | Current user's privileges (hide what you can't do) | 🗓 M1 |
| `PUT /access/password` | Change password | 🗓 M7 |
| `/access/users` (+ `/token`, `/tfa`, `/unlock-tfa`) | Users, API tokens, TFA admin | 🗓 M7 |
| `/access/groups`, `/access/roles`, `/access/acl` | Groups, roles, permissions | 🗓 M7 |
| `/access/domains/{realm}` (+ `/sync`) | Realm config, LDAP/AD sync | 🗓 M7 |
| `/access/tfa` | Own TFA entries (add TOTP/WebAuthn/recovery) | 🗓 M7 |

### `/cluster`

| Endpoint | Purpose | Status |
|---|---|---|
| `GET /cluster/resources` | Every node, guest, storage, pool, SDN zone | ✅ |
| `GET /cluster/status` | Quorum, nodes online | ✅ |
| `GET /cluster/tasks` | Recent tasks of all nodes | ✅ |
| `GET /cluster/log` | Cluster log | 🗓 M4 |
| `GET /cluster/nextid` | Free VMID for create/clone/restore | ✅ (create, restore, clone) |
| `/cluster/options` | Datacenter options | 🗓 M4 |
| `/cluster/backup` (list, create, edit, delete) | Backup jobs | ✅ (run now uses `POST /nodes/{node}/vzdump` per node, like the web UI) |
| `GET /cluster/backup-info/not-backed-up` | Guests no enabled job covers | ✅ |
| `/cluster/replication` (list, create, edit, delete with `keep`) | Storage replication jobs | ✅ |
| `GET /nodes/{node}/replication`, `POST …/{id}/schedule_now`, `GET …/{id}/log` | Last and next sync, failures, run now, last run's log | ✅ |
| `/cluster/ha/resources` (list, add, edit, remove), `POST …/{sid}/migrate` | Guests managed by HA, requested state, limits, moving | ✅ |
| `GET /cluster/ha/groups`, `GET /cluster/ha/status/current` | Groups (Proxmox VE 8) to pick from, manager and node status | ✅ |
| `/cluster/ha/groups` (editing), `/cluster/ha/rules` (Proxmox VE 9) | Node affinity | 🗓 M4 |
| `/cluster/firewall/rules`, `/cluster/firewall/options` | Datacenter firewall rules (add, edit, enable, move, delete) and options | ✅ |
| `GET /cluster/firewall/macros`, `GET /cluster/firewall/groups` | Macros and security groups for the rule editor | ✅ |
| `POST/DELETE /cluster/firewall/groups`, `/cluster/firewall/groups/{group}` (rules, as for other firewalls) | Security groups and their rules | ✅ |
| `…/firewall/aliases` (list, add, edit with `rename`, delete) for the datacenter and guests | Aliases | ✅ |
| `…/firewall/ipset` (sets: list, create, delete with `force`; entries: list, add with `nomatch`, delete) for the datacenter and guests | IP sets | ✅ |
| `GET /nodes/{node}/firewall/log`, `GET /nodes/{node}/{type}/{vmid}/firewall/log` | Firewall log (newest 500 lines, via the response's `total`) | ✅ |
| `GET /cluster/firewall/refs` | Alias and IP set suggestions in the rule editor | 🗓 later |
| `/cluster/sdn/*` | Zones, VNets, subnets, IPAM, DHCP, controllers, fabrics | 🗓 M6 |
| `GET /cluster/ceph/status` | Ceph health for background alerts | ✅ (health only; full status 🗓 M6) |
| `/cluster/ceph/*` (flags, metadata) | Cluster-wide Ceph flags | 🗓 M6 |
| `/cluster/mapping/{pci,usb,dir}` | Resource mappings | 🗓 M9 |
| `/cluster/notifications/*` | Notification targets & matchers (also used for push, M3) | 🗓 M3 (webhook setup) / M9 (full) |
| `/cluster/metrics/*` | External metric servers | 🗓 M9 |
| `/cluster/acme/*` | ACME accounts & plugins | 🗓 M9 |
| `/cluster/config/*` | Create/join cluster, corosync | 🗓 M9 |
| `GET /cluster/jobs/schedule-analyze` | Next runs of a calendar event (backup job preview) | ✅ |
| `/cluster/jobs/realm-sync` | Realm sync jobs | 🗓 M7 |

### `/nodes/{node}`

| Endpoint | Purpose | Status |
|---|---|---|
| `GET /nodes/{node}/status` | Node usage, CPU, kernel, versions | ✅ |
| `POST /nodes/{node}/status` | Reboot / shut down node | ✅ |
| `GET /nodes/{node}/tasks/{upid}/status` | Task status | ✅ |
| `GET /nodes/{node}/tasks/{upid}/log` | Task log | ✅ |
| `DELETE /nodes/{node}/tasks/{upid}` | Stop a running task | 🗓 M1 |
| `GET /nodes/{node}/tasks` | Node task history with filters | 🗓 M1 |
| `GET /nodes/{node}/rrddata` | Performance history (charts) | ✅ |
| `POST /nodes/{node}/vzdump` | Backup now | ✅ (one guest; storage, mode, notes) |
| `/nodes/{node}/{startall,stopall,migrateall}` | Bulk actions | 🗓 M4 |
| `/nodes/{node}/{termproxy,vncshell,vncwebsocket}` | Node shell | ✅ `termproxy` + `vncwebsocket` through the app's connection, bundled xterm.js ([ADR 0014](adr/0014-consoles-through-the-app.md)) |
| `GET/POST /nodes/{node}/apt/update`, `GET …/apt/changelog`, `GET …/apt/versions` | Upgradable packages, refreshing the index (a task), changelogs, installed versions | ✅ |
| `GET/POST/PUT /nodes/{node}/apt/repositories` | Repositories with Proxmox's warnings; switch on/off; add a standard repository | ✅ |
| `POST /nodes/{node}/termproxy` with `cmd=upgrade` | The upgrade in a terminal, like the web UI | ✅ |
| `/nodes/{node}/disks/*` | SMART, wipe, GPT, ZFS, LVM, LVM-thin, directory | 🗓 M5 |
| `/nodes/{node}/network` | Interfaces, bridges, bonds, VLANs, apply/revert | 🔸 `GET ?type=any_bridge` ✅ (bridges for network devices); editing 🗓 M5 |
| `GET /nodes/{node}/services`, `POST …/services/{service}/{start,stop,restart,reload}` | Services (tasks) | ✅ |
| `GET /nodes/{node}/journal?lastentries=` | System log (newest 500 lines) | ✅ |
| `GET /nodes/{node}/syslog` | The older syslog view | 🗓 (the journal covers it) |
| `GET/PUT /nodes/{node}/dns`, `GET/POST /nodes/{node}/hosts`, `GET/PUT /nodes/{node}/time` | DNS servers and search domain, /etc/hosts (with its digest), time zone | ✅ |
| `GET /nodes/{node}/subscription` | Subscription status (on the Updates screen) | ✅ |
| `PUT/POST /nodes/{node}/subscription` | Entering a key | 🗓 M5 |
| `/nodes/{node}/certificates/*` | Certificates, ACME orders | 🗓 M9 |
| `/nodes/{node}/firewall/{rules,options}` | Node firewall | ✅ |
| `/nodes/{node}/firewall/log` | Node firewall log | 🗓 M4 |
| `/nodes/{node}/ceph/*` | OSDs, pools, monitors, managers, MDS, FS, config, crush, log | 🗓 M6 |
| `/nodes/{node}/sdn/*` | Node SDN status | 🗓 M6 |
| `/nodes/{node}/replication/*` | Replication status / run now | 🗓 M4 |
| `/nodes/{node}/aplinfo` | Container template catalogue | 🗓 M4 |
| `GET /nodes/{node}/scan/{nfs,cifs,pbs,lvm,lvmthin,zfs}` | Storage discovery for the storage editor | ✅ |
| `GET /nodes/{node}/scan/iscsi` | iSCSI discovery | 🗓 (with iSCSI storages) |
| `/nodes/{node}/hardware/{pci,usb}` | Passthrough device lists | 🗓 M9 |
| `/nodes/{node}/capabilities/*` | CPU models, machine types | 🗓 M2 |
| `/nodes/{node}/query-url-metadata` | Download-from-URL helper | 🗓 M4 |

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
| `GET/PUT …/snapshot/{snap}/config` | Edit a snapshot's description | 🗓 M1 |
| `/qemu/{vmid}/agent/*` | Guest agent: IPs, OS info, fs-freeze, set password, exec | 🔸 `network-get-interfaces`, `get-osinfo` ✅; the rest later |
| `GET /lxc/{vmid}/interfaces` | A running container's IP addresses (PVE 7.2+) | ✅ |
| `GET/POST/PUT …/config`, `GET …/pending` | Hardware & options | 🔸 options ✅ (name/hostname, tags, notes, start at boot, protection, QEMU agent); common hardware ✅ (VM: sockets, cores, CPU type, memory and ballooning, disks, CD/DVD, network devices, boot order; container: cores, memory, swap, mount points, network). Every change is sent with the config digest; `POST` for allocating VM disks as a task; pending keys are shown as "After restart". Advanced (passthrough, NUMA, CPU flags, cloud-init) 🗓 M2/M9 |
| `PUT …/resize`, `POST …/move_disk` / `move_volume`, `POST …/unlink` | Disks | ✅ resize (grow only), move to another storage (keep or delete the source), detach and delete unused volumes through `config`; `unlink` not needed |
| `POST …/clone`, `POST …/template` | Clone (full/linked, any node, target storage) / convert to template | ✅ |
| `/qemu/{vmid}/cloudinit` (+ `/dump`) | Cloud-init | ✅ settings through `config` (`ciuser`, `cipassword`, `sshkeys` URL-encoded as Proxmox expects, `ipconfigN` per network device, `nameserver`, `searchdomain`, `ciupgrade`), add a cloud-init drive (`storage:cloudinit`), `PUT …/cloudinit` to regenerate; `/dump` not used |
| `POST …/migrate` (+ `GET` precondition check) | Migration: live for VMs (with local disks), restart mode for containers, offline when stopped | ✅ |
| `POST /nodes/{node}/{qemu,lxc}` | Create guest (wizard) | ✅ VM (OS type, ISO, disk, CPU, memory, bridge; Advanced: sockets, CPU type, bus, discard, ballooning, NIC model, VLAN, firewall, UEFI, agent, start at boot) and container (template, root password and/or SSH keys, disk, cores, memory, network with DHCP/static; Advanced: swap, IPv6, VLAN, firewall, unprivileged, nesting, start at boot) |
| `DELETE …/{vmid}` | Destroy guest (type-to-confirm) | 🗓 M2 |
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
| `GET …/rrddata` | Storage charts | 🗓 M1 |
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
| `GET /nodes/localhost/tasks/{upid}/{status,log}` | Task detail & log | 🗓 M1 |
| `/admin/datastore/{store}/{groups,snapshots,namespace}` | Browse backups | 🗓 M8 |
| `/admin/datastore/{store}/{gc,prune,prune-datastore,verify}` | Maintenance | 🗓 M8 |
| `/admin/datastore/{store}/{catalog,pxar-file-download,download-decoded}` | File-level restore | 🗓 M8 |
| `/admin/datastore/{store}/{notes,protected}` | Snapshot notes & protection | 🗓 M8 |
| `/config/{datastore,remote,sync,verify,prune,traffic-control}` | Configuration & jobs | 🗓 M8 |
| `/admin/{sync,verify,prune}` | Run jobs | 🗓 M8 |
| `/tape/*` | Tape backup | 🗓 M8 (basic) / M9 |
| `/nodes/localhost/{status,disks,network,apt,services,syslog,journal}` | Node admin | 🗓 M8 (reuses M5 UI) |
| `/access/{users,acl,roles,domains,tfa}` | Access control | 🗓 M7 |
| `/config/{acme,notifications,metrics}` | Certificates, notifications, metrics | 🗓 M9 |

---

## Not planned (➖)

| Area | Reason |
|---|---|
| API-token login | Owner's decision (Q9). Auth headers are added in one place (`AuthInterceptor`), so it stays cheap to add if Q9 is amended. |
| Proxmox Mail Gateway, Datacenter Manager | Out of scope (Q4). |
| Container suspend/hibernate | Experimental in Proxmox (CRIU) and hidden in the web UI. |
