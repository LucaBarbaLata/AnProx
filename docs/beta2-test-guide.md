# Testing 0.1.0-beta.2 and beta.3

This is the checklist for the owner's test runs before a beta is published. It started with
everything new since beta.1 (sections A–M); the **D** sections marked "new after beta.2" are
what 0.1.0-beta.3 adds (D2–D15 for milestones M2–M5, D16–D22 for M6–M8 and the smaller
additions), and section **N** covers the Backup Server. Everything except N works with
**Proxmox VE** alone. Each step says what to do and what you should see. Note anything that
differs, with a screenshot if you can.

Automated tests already run every read and write the app makes against a real Proxmox VE 9
server and a Proxmox Backup Server 4 (see [testing.md](testing.md)). What they can't see is the app itself: screens, buttons,
texts and how it feels on a phone. That's what this guide is for.

The general [manual checklist](testing.md#manual-test-checklist-real-hardware) and the
[beta testing guide](beta-testing.md) still apply. This page is the short, ordered version.

## 1. Install the test build

1. On GitHub, open **Actions → CI**, pick the newest **green** run on the branch
   `ccr-ca586193-f4zcbm`, and download the **`anprox-debug-apk`** artifact (a zip).
2. Unzip it and install `app-debug.apk` on your phone.
3. It installs as **AnProx Debug**, a separate app next to your installed AnProx beta. The beta
   and its data stay untouched; add your server again in AnProx Debug (Overview → **+**).

The debug build has the same features as the release. The R8-minified release build is tested by
CI on Android 8.0 and 14 emulators against the mock server.

## 2. Prepare

- A **test VM** you can break, ideally Debian/Ubuntu with `qemu-guest-agent` installed
  (`apt install qemu-guest-agent`, then in the web UI: VM → Options → QEMU Guest Agent → enabled,
  then stop and start the VM).
- A **test container** (any template).
- If you have **two or more nodes**, a storage both can use (NFS, Ceph…) makes clone/migrate tests
  easier. With one node, migration correctly says there is nowhere to move to.
- A storage that accepts **backups** (e.g. `local`).
- For section **N**, a **Proxmox Backup Server**. A small VM on the test server installed from
  the PBS ISO is enough. Without one, skip N.
- For **D18** (Ceph), a cluster with Ceph. Without Ceph, only step 1 of D18 applies.

## 3. What to test

Tick each line, or note what happened instead.

### A0. The calmer design, new after beta.2
1. **Overview**: one card per server with three numbers (guests running, CPU, memory) and its
   nodes; warnings (an offline node, a full storage) sit inside that card. Favourites are one
   card below. Tapping the guests number opens Resources.
2. **Resources**: one list; each row is a dot, the name, "VM 100 · *node*" and the CPU (or
   **Stopped**) on the right. The filters are words; **Running only** still works; a long press
   still starts a selection.
3. **A guest**: one card with its state, uptime and address, CPU, memory and disk, then
   **Console** and **Start** or **Shut down**; the ⋯ beside them has the rest (Stop and Reset in
   red). The tools are a grid; **More** shows the rest and **Fewer** folds them again.
   **Network**, **Performance** and **Details** open when you tap them.
4. **A node**: the same kind of card, a grid of tools, its guests with a ⋯ for all of them, and
   reboot/shutdown behind the power icon at the top. **Performance** and **System** fold.
5. **Datacenter** and **More**: titles only, in small groups.
6. Settings → Theme: light, dark and AMOLED are all readable; nothing glows.

### A1. Single sign-on (only if you have an OpenID Connect realm), new after beta.2
1. Servers → your server → ⋮ → **Sign out**, then **Sign in**. Pick the OpenID realm: the user
   and password fields go away and the button says **Sign in with *realm***.
2. Tap it: the provider's page opens with its host at the top. Sign in there → the login screen
   closes; Servers shows the account the provider named (`you@realm`).
3. **×** on the provider's page goes back to the login screen without signing in.

### A2. Certificates, new after beta.2
1. Node → **Certificates** (behind **More**): the cards match the web UI's Node → System →
   Certificates (names, issuer, valid until).
2. If you use ACME: the account and domains match; **Renew** in the last 30 days renews (the log
   ends with "Restarting pveproxy"), earlier it asks first. Datacenter → **ACME accounts and
   plugins** lists the same accounts and plugins as the web UI.
3. Only on a test node: **Upload a certificate** with a self-signed one (`openssl req -x509 …`),
   then **Remove certificate in use**. The app keeps working (it may need "Allow insecure
   certificate").

### A. Network card (guest agent)
1. Start the test VM, open it. Tap **Network**: you see the OS name and its IP addresses
   (no `127.0.0.1` or `fe80::` noise).
2. Tap an IP: "Copied …" appears; paste it somewhere to check.
3. Open the test container (running): its IPs show without any agent.
4. A VM without the agent shows how to enable it.

### B. Performance charts
1. On the test VM, tap **Performance**: CPU, memory, network and disk I/O.
2. Switch Hour / Day / Week / Month / Year. Touch and drag across a chart: time and values follow
   your finger.
3. Open a node (Resources → a node): CPU with IO wait, load, memory, network.

### C. Options (Tools → Options)
1. On the test VM, the name, tags, notes, Start at boot and Protection match the web UI.
2. Rename it (e.g. `test-vm-2`), add tags `test demo`, write a note, turn on **Start at boot** →
   **Save** → "Options saved". Check the web UI shows the same.
3. Type `my vm` as the name: an error appears and Save is disabled.
4. Change something in the web UI while the Options screen is open, then change and save in the
   app: Proxmox refuses it (configuration changed). **Reload** shows the web UI's values.
5. Change a field and press Back: "Discard changes?".
6. Container: the field is called **Hostname**.

### D. Consoles (the guest's **Console** button, Node → Shell)
Rebuilt after beta.2 (the VM screen looped and terminals stayed empty). Test with a CI build
newer than beta.2 (section 1). The subtitle under the title says **Connecting…**, then
**Connected** (terminals) or **Connected · *VM name*** (VM screen).
1. Test VM → **Console**: the VM's screen appears and stays (no reconnect loop). Tap and drag on
   it, rotate to landscape. Tap **Keyboard** and type; try **Esc**, **Tab**, the arrows,
   **Enter** and **Ctrl+Alt+Del** on the key row.
2. Test container → **Console**: a login prompt. Tap **Keyboard**, log in. Try the key row:
   **Esc**, **Tab** (completion), **Ctrl+C** (stop `ping 1.1.1.1`), **Ctrl+D** (log out), arrows
   (shell history). Rotate the phone: the same session continues, with the text redrawn for the
   new width. (The VM screen reconnects when you rotate; that's expected.)
3. Node → **Shell**: a root shell (needs root@pam or Sys.Console). Same checks as the container.
4. Leave a console open, lock the phone for a minute, come back. If it says **Disconnected**, tap
   **Reconnect**.
5. Self-signed certificates no longer need anything special: the console uses the same
   connection as the rest of the app.
6. If a console shows "Android System WebView is too old", update **Android System WebView** in
   the Play Store (or your store) and try again.

### D2. Hardware (Tools → Hardware), new after beta.2
Use the test VM and the test container. Changes are sent with the config's checksum, so if you
edit the same guest in the web interface at the same time, the app refuses instead of
overwriting (try it once: open Hardware, change something in the web UI, then save in the app).
1. VM, **stopped**: tap **Processor** → change cores and the CPU type → **Save**. Check the
   web UI (VM → Hardware) shows the same.
2. VM, **running**: change **Memory** or turn off **Ballooning** → **Save** → the row says
   **After restart** and a yellow note appears. Shut the VM down and start it: the note goes away.
3. **Add disk** → SCSI, a storage, 8 GiB → after the task an "SCSI 1 · 8.00 GiB" row appears
   (in the VM: `lsblk`). Tap it → **Grow** by 2 GiB → 10 GiB. **Move** it to another storage
   (if you have one). **Detach** → it becomes "Unused disk 0" → tap it → **Delete** (type
   `unused0`).
4. **CD/DVD**: tap the IDE 2 drive → pick an ISO → **Save**; then **Empty** to eject.
5. **Network**: tap net0 → change the VLAN tag or tick **Disconnected** → **Save**; the MAC
   address must stay the same. **Add network device** on another bridge, then **Remove** it.
6. **Boot order**: put the CD/DVD first → **Save** (running VMs: after restart).
7. Container: **Resources** → change memory and swap (applies at once, even while running).
   **Add mount point** (8 GiB at `/srv/data`) → inside the container `df -h /srv/data`. Tap the
   root disk → **Grow** by 1 GiB. **Move** works only while the container is stopped; the app
   says so. Edit net0: switch IPv4 between DHCP and static (`192.168.x.y/24` + gateway).

### D3. Create VMs and containers (Node → Create VM / Create container), new after beta.2
1. Node → **Create VM**: the next free ID is filled in. Give it a name, pick an ISO you have,
   keep the rest → **Create VM** → after the task, **Open it** → **Start** → **Console**: the
   installer boots. Compare with the web UI (VM → Hardware).
2. Try **Windows** as the OS: the hint explains SATA, E1000E, UEFI and TPM. Create it and check
   the web UI shows an EFI disk and a TPM.
3. Open **Advanced** and change a few things (CPU type `host`, VLAN tag, no ballooning).
4. Node → **Create container**: pick a template (download one in the web UI first if the list is
   empty), set a root password (twice), 4 GiB, DHCP → **Create container** → **Open it** →
   **Start** → **Console** → log in as root.
5. Try a static address (`192.168.x.y/24` and the gateway) and an SSH key instead of a password.
6. Wrong input is explained before you can press Create: an ID that exists, a too-short
   password, a disk bigger than the free space.

### D4. Cloud-init (VM → Tools → Cloud-init), new after beta.2
Best with a VM made from a cloud image (for example the Debian or Ubuntu cloud images).
1. A VM without a cloud-init drive: the screen offers **Add cloud-init drive** → pick a storage →
   the form appears (web UI: VM → Hardware shows a CloudInit drive).
2. Set a user, a password and your SSH key; net0 → **Static** with an address and gateway; DNS
   server → **Save**. Check VM → Cloud-Init in the web UI: same values (the SSH key must look
   right there, not URL-encoded).
3. Start the VM, log in with the key, check the address. Change something while it runs: the
   note says it applies at the next start.
4. Leave the password empty and save another change: the old password must still work.

### D5. SPICE viewer, new after beta.2
Needs a SPICE app on the phone (for example aSPICE) and a VM whose Display is SPICE (VM →
Hardware → Display → SPICE in the web UI).
1. Start the VM → Tools → **SPICE viewer** → aSPICE opens and shows the VM.
2. Without a SPICE app the app says what to install; with a non-SPICE display Proxmox's error
   is shown.
3. If you connect through a reverse proxy or a VPN address, port 3128 on that address must be
   reachable (Proxmox's SPICE proxy); say whether it worked.

### D6. Background alerts (More → Alerts), new after beta.2
1. Switch on **Background alerts**. On Android 13+ the phone asks to allow notifications: allow.
2. **Check now** → after a few seconds "Servers checked: N". If something is already wrong
   (a node offline, storage above 85 %) a notification arrives right away. A second **Check now**
   adds nothing new.
3. Stop a test VM *from inside the VM* (`poweroff`), then **Check now** → "test-vm (ID) stopped".
   Stop it from the app or the web UI instead → no notification.
4. Start a task that fails (for example start a VM with a missing ISO) → **Check now** → "Failed: VM start …".
5. Set the storage limit just under a storage's current use → **Check now** → "… is N% full".
6. Tap an alert notification: it opens the node, the VM or the failed task's log.
7. **Live tasks**: start a backup or a migration that takes a while, then leave the app (Home).
   A "Running…" notification stays until it ends, then says "… finished" (or "failed"). With
   the app open, the notification disappears when the task ends. Tasks that take under 3 s
   show nothing.
8. **Shortcuts**: long-press the AnProx icon → Resources / Tasks / Alerts open those screens.
9. **Tile**: pull down Quick Settings → edit → add "Proxmox". It shows the last check ("OK at …"
   or "N new alerts"); tap it → the dashboard.
10. **Widget**: long-press the home screen → Widgets → AnProx "Proxmox status". Each server shows
    nodes online and guests running; it updates after each check (**Check now** to see it change).
    **Favourites widget**: star two guests (one running, one stopped; the star is on the guest's
    screen), then add AnProx "Favourite guests". Both appear with their state within a few
    seconds. Tap **Start** on the stopped one: it shows "…", then "Running" once the task ends.
    Tap **Shut down** once: it turns into **Tap again**; wait 10 s and it goes back without doing
    anything; tap twice quickly and the guest shuts down. Unstar a guest in the app and leave the
    app: it disappears from the widget. Switch the app lock on: the buttons now open the guest
    in the app (after unlocking) instead of acting.
11. Leave alerts on overnight with the app closed: say whether checks kept running (the time of
   the last check) and whether anything was notified twice. Some phones stop background work
   for apps with battery optimisation; say which phone you have.

### D7. Backup jobs (Dashboard → your cluster → Datacenter → Backup jobs), new after beta.2
Compare everything with Datacenter → Backup in the web UI.
1. The list matches the web UI: schedule, storage, which guests, mode, next run. Jobs you set
   up in the web UI with options the app doesn't edit (fleecing, bandwidth limits) must keep
   them after you edit the job in the app: check that.
2. Switch a job off and on → the web UI's Enabled column follows.
3. **Add backup job** → pick a schedule preset, then type your own (`sat 02:00`) → "Next runs"
   shows when it would run. Type nonsense → "Proxmox can't read this schedule."
4. Try each guest choice (All / Selected / All but / Pool) and retention (Storage / Custom /
   Keep all) → **Create job** → open it in the web UI and check every field.
5. **Run now** on a small job → a backup task per node → "Backup job finished"; the backups
   appear under the guests' Backups.
6. "Not backed up" lists the guests no enabled job covers (compare with Datacenter → Backup →
   "Show guests without backup job" in the web UI).
7. Delete the test job.

### D8. Storage (Dashboard → your cluster → Datacenter → Storage), new after beta.2
1. Every storage is listed with its type, content types and usage per node; a shared one shows
   one row. Compare with the web UI's Datacenter → Storage and each node's storage summary.
2. Tap a node's row → the chips show what the storage can hold. ISO images and templates match
   the web UI's lists; VM disks and CT volumes show which guest owns them.
3. **From URL** → paste the link of a small ISO (for example a netinst image) → **Check the URL**
   shows its name and size → **Download** → the download task runs on the node; the file appears
   when it finishes. Try a wrong checksum: the task fails and says so.
4. **Upload** → pick an ISO on the phone → **Upload** → the progress bar moves; keep the screen
   open; the file appears after the task. Say how fast it was over Wi-Fi.
5. Delete the test ISO (trash icon) → gone in the web UI too.

### D8b. Adding and editing storages (Datacenter → Storage), new after beta.2
Use a test share; removing a storage never deletes its data, but guests on it stop working.
1. **Add storage** → NFS (or SMB/CIFS) → an ID → your NAS's address → **Find exports** (or
   user, password and **Find shares**) → pick one → choose the content → **Create storage**.
   It appears in the list and in the web UI with the same settings, mounted on each node.
2. Tap its pencil: the server and export are read-only; untick a node under "Nodes and
   options", set retention to Custom (keep daily 3) → **Save** → compare with the web UI.
3. With a Backup Server: **Add storage** → Backup Server → address, `root@pam` and password,
   the fingerprint from the PBS dashboard → **Find datastores** → pick → create.
4. On a node with spare disks: LVM / LVM-thin / ZFS → **Find …** lists what `pvesm scan` shows.
5. **Remove storage** → type its name → gone from the configuration; the data is still there.

### D9. Firewall (Datacenter → Firewall, Node → Firewall, Tools → Firewall), new after beta.2
Careful: a wrong firewall rule can lock you out of the web UI or SSH. Test on a guest first.
1. Open a guest's firewall: the rules match the web UI's VM → Firewall, in the same order.
2. **Add rule**: In, ACCEPT, protocol tcp, port 8080, a comment → it appears first; check the
   web UI.
3. Switch a rule off and on; move it down and up with the arrows; edit it into a macro rule (the
   protocol and port disappear); delete it.
4. **Options**: switch the guest firewall on, set the incoming policy, switch the MAC filter
   off → **Save** → compare with the web UI's Firewall → Options.
5. Change a rule in the web UI while the app shows the list, then edit the same rule in the
   app: it must refuse ("modified configuration") rather than overwrite; pull to refresh.
6. Datacenter → Firewall → **Security groups** → add `anprox-test` → open it → add a rule →
   compare with the web UI's Firewall → Security Group. Delete the rule, then the group.
7. **Aliases**: add `nas` = your NAS's address; edit it (rename it); delete it. **IP sets**: add
   a set, add an address and a network, mark one "Except", remove one, delete the set.
8. Node → Firewall → **Firewall log** (and a guest's): matches `/var/log/pve-firewall.log`
   (only rules or policies with a log level other than nolog write there).

### D10. High availability (Datacenter → High availability), new after beta.2
Needs a cluster. With a single node the screen shows the status and an empty list.
1. The status card matches the web UI's Datacenter → HA: quorum, the manager's node, each
   node's state.
2. **Add a guest to HA**: pick a stopped test guest, requested state Started → **Save**. Within
   a minute the HA manager starts it; the web UI lists it under Resources.
3. Tap it: set the requested state to Stopped, restarts 2, a comment → **Save** → it stops; the
   web UI shows the same settings.
4. **Move to another node** → pick a node → the guest moves (follow it in Tasks or the web UI).
5. **Remove from HA** → confirm → gone from the list; the guest itself is unchanged.
6. On PVE 8: **Add group** with two nodes and priorities → compare with the web UI's HA →
   Groups; edit, then delete it. On PVE 9 the Groups section is replaced by **Rules**: add a
   "Prefer nodes" rule and a "Keep apart" rule, compare with HA → Affinity Rules (written
   from the documentation, so please check carefully).

### D11. Replication (Datacenter → Replication), new after beta.2
Needs two nodes and a guest whose disks are on local ZFS storage that exists on both.
1. Existing jobs match the web UI's Datacenter → Replication: target, schedule, state, last
   sync and duration, the error of a failing job.
2. **Add replication job**: the ZFS guest, the other node, Every 15 minutes → **Add**. Tap
   **Run now** (the play button): within a minute the state becomes Syncing, then In sync.
3. Tap the job: change the schedule to a calendar event of your own (the next runs are
   previewed), set a rate limit, **Save**; open **Last run's log**.
4. Switch it off and on with the switch on the card.
5. **Delete job** without keeping the copy → in the web UI the job is marked for removal and
   the copy on the target disappears after its run.

### D12. Resource pools (Datacenter → Resource pools), new after beta.2
1. The pools and their guests and storages match the web UI's Datacenter → Permissions → Pools
   (and each pool's Members).
2. **Create pool** `anprox-test` with a comment → open it → **Add guests**: tick a test guest
   → it is listed; in the web UI it is a member. Tap it: its screen opens.
3. **Add storage** → a storage → listed. Edit the comment (pencil at the top).
4. Remove the guest and the storage (minus icons) → **Delete pool** becomes available → delete
   it → gone in the web UI too.

### D13. Move to another phone (More → Settings), new after beta.2
1. **Export** → a passphrase twice (try a mismatch first) → leave **Include saved passwords**
   off → **Choose where to save** → Downloads. Open the file in a text editor: no server name or
   address is readable.
2. **Import** that file → a wrong passphrase says so → the right one lists your servers →
   **Import** → "0 added, N replaced"; nothing changes in the server list.
3. Export again with saved passwords: the fingerprint/screen lock is asked first.
4. On a second phone or emulator (or after removing a server): import → the servers appear;
   with passwords included, they sign in without asking.

### D14. Node administration (the node's tools: Updates, Services, Network, …), new after beta.2
1. **Updates**: the list matches the web UI's Node → Updates after **Refresh**; tap a package →
   its changelog. **Installed versions** matches the web UI's Package versions.
2. **Upgrade** opens a terminal running the upgrade; answer its questions there (try it on a
   test node first).
3. **Repositories**: the warnings match the web UI's Repositories; add No-Subscription and
   switch the enterprise one off → check /etc/apt/sources.list* on the node.
4. **Services**: restart `pvestatd` (harmless) → "done"; stopping `pveproxy` asks first.
5. **System log**: the newest lines match `journalctl -n 500`; the filter narrows them.
6. **Network** (careful, test on a lab node): **Bridge** → keep vmbr2, no address → Save → the
   pending banner and **Show changes** (compare with the web UI's Network → Pending changes) →
   **Revert**. Then the same and **Apply** (type the node's name) → vmbr2 exists; remove it and
   apply again.
7. **Disks**: health and wear match the web UI's Disks; tap one → SMART. Only on a spare disk:
   **Create storage** → ZFS single (or directory ext4) with **Add as storage** → it appears under
   Datacenter → Storage. **Wipe** asks for the device typed out.
8. **DNS, hosts and time**: values match the web UI's System → DNS / Hosts / Time; change the
   time zone and back; add a comment line to /etc/hosts and remove it again.

### D15. SSH client (Node → SSH, Guest → SSH, Settings → SSH keys), new after beta.2
1. **Settings → SSH keys → New key**, name it after the phone, Ed25519 → it is listed with its
   fingerprint. **Copy** the public key and paste it somewhere: it starts with `ssh-ed25519` and
   ends with `anprox-<name>`.
2. **Node → SSH**: the host is the node's address from the cluster (check it), port 22, user
   root. Choose **Password**, type root's password → **Connect** → **Trust this server?** shows a
   fingerprint: compare it with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` in the node's
   Shell (the type shown says which file) → **Trust** → a root prompt. Try `htop`, the arrows and
   Ctrl+C from the key row, and rotate the phone (the session stays).
3. In that session tap **Add key** → your key → "… was added". Check `~/.ssh/authorized_keys` on
   the node: one new line, ending with `anprox-<name>`. Do it again: no second line.
4. **Change connection** (pencil) → the form now uses the key → **Connect**: no host key question,
   no password.
5. Settings → SSH keys → **Trusted servers** lists the node; **Forget** it → the next connection
   asks again. With a wrong password: "The server refused the sign-in".
6. **Import key**: paste (or choose the file of) a key you use on your computer, with its
   passphrase if it has one → a wrong passphrase is refused → right one → it signs in.
7. **Guest → SSH** on a running VM with the guest agent (or a container): its address is filled
   in. With PVE 9, say whether anything differs.

### D16. Permissions (Datacenter → Permissions), new after beta.2
Use root@pam (other admins are also asked for **Your password** in the password and second-factor
dialogs, which is worth one try too).
1. **Users**, **Groups**, **Roles**, **Permissions** and **Realms** match the web UI's
   Datacenter → Permissions (Users, Groups, Roles, Permissions, Realms). Built-in roles
   open read-only.
2. **Groups → Add group** `anprox-test` → **Create**. **Roles → Add role** `AnproxTest` with
   VM.Audit and Datastore.Audit → **Create**; it appears under "Your roles".
3. **Permissions → Add permission**: path `/vms`, **Given to** Group → `anprox-test`, role
   `AnproxTest`, **Propagate** on → **Add**. The web UI lists the same entry.
4. **Users → Add user**: user name `anprox-test`, realm **pve**, a password twice, group
   `anprox-test` ticked → **Create user**. Open it: **What anprox-test may do** shows `/vms` with
   the role's privileges.
5. **Change password** → new password twice → **Save** → "Password changed". Sign in as
   `anprox-test@pve` in the web UI with it: only the VMs are visible, read-only.
6. **Add API token** `ci` → **Create** → "API token created" shows the token ID and the secret
   once (copy them) → **I've saved it**. The web UI's API Tokens lists it. Delete it: "Delete
   token …?" → **Delete**.
7. **Second factors → Authenticator app**: **Open in authenticator app** (or copy the secret),
   type the 6-digit code → **Add** → "Authenticator app added". Signing in as the user in the
   web UI now asks for the code. **Recovery keys** → **Create** lists 10 keys. Switch a factor off
   and on (each asks first), then remove it.
8. Remove the permission (⊖ → **Remove**), then **Delete user**: type `anprox-test@pve`. Delete
   the group and the role (tap them → delete). All gone in the web UI.
9. **Realms**: pam and pve are marked "Built in". With an LDAP or AD realm: **Sync** → **Preview
   only** on → **Preview** → **View log** shows what a sync would change, without changing it.

### D17. SDN (Datacenter → SDN), new after beta.2
Applying reloads the network configuration of every node: do it on the test server.
1. Zones, VNets, subnets, controllers and IPAM match the web UI's Datacenter → SDN. With nothing
   set up: "No SDN yet".
2. **Add zone**: type Simple, ID `apxtest` → **Create** → "Saved; apply to use it". The card
   "1 change not applied yet" appears and the zone is marked **New**; the web UI shows it as
   pending too.
3. **Add VNet** `apxvnet` in `apxtest` → open it → **Add subnet** `10.250.0.0/24`, gateway
   `10.250.0.1`, SNAT on → saved.
4. **Apply** → "Apply the SDN changes?" → **Apply** → "SDN changes applied" with **View log**. On
   the node (Node → Shell) `ip link show apxvnet` lists it, and the web UI offers `apxvnet` as a
   bridge for a guest's network device.
5. Clean up: **Delete VNet** is disabled while it has a subnet ("Delete its subnets first."), so
   delete the subnet, then the VNet, then the zone → **Apply** again → `ip link show apxvnet`
   says it doesn't exist.
6. On Proxmox VE 8.4 or later: add a zone again and press **Discard** instead of Apply → it is
   gone and nothing is pending.

### D18. Ceph (Datacenter → Ceph), new after beta.2
1. Without Ceph: "Ceph isn't set up", with how to install it in the web UI. That's all for a
   server without Ceph.
2. With Ceph: **Health**, **Capacity** and the OSD, monitor, manager and placement group counts
   match the web UI's Ceph → Status. Tap a health check to read it.
3. **OSD flags**: switch **noout** on → "noout set"; the web UI shows the flag; switch it off.
   **pause** asks first (cancel it).
4. **OSDs**: the list matches the web UI's OSD view; tap one → **Scrub** → the Ceph log shows the
   scrub. (**Destroy** only on an OSD you can spare: it must be stopped and marked out first, then
   you type its name, e.g. `osd.3`.)
5. **Pools → Create pool** `anprox-test` (3 copies, 2 needed to write, no **Add as storage**) →
   it is listed in the web UI; edit it; **Delete pool** needs its name typed.
6. **Monitors and managers**, **Configuration**, **CRUSH map** and **Cluster log** match the web
   UI; the log's **Filter** narrows it.

### D19. Guest agent tools (VM → Tools → Guest agent), new after beta.2
Needs the test VM running with the agent installed and enabled (see Prepare).
1. **Guest agent** is listed under Tools only while the VM runs with the agent enabled. The
   **Filesystems** state says Writable.
2. **Freeze filesystems** → "Freeze the filesystems of …?" → confirm → "Filesystems frozen: N";
   the state says Frozen. In the VM's console, `touch /root/x` now hangs. **Thaw filesystems** →
   "Filesystems thawed: N", and the `touch` finishes.
3. **Trim filesystems** lists each filesystem with the space freed (or "Done").
4. **Users → Set user password**: user `root` (or a test user), a new password twice → **Save** →
   "Password of root changed". Sign in on the VM's console with the new password.

### D20. Guests: start order, snapshot notes, restore storage, delete, new after beta.2
1. **Tools → Options → Start and shutdown order**: Order `1`, Wait after start `10`, Shutdown
   timeout `60` → **Save** → "Options saved". The web UI's Options → Start/Shutdown order shows
   `order=1,up=10,down=60`.
2. **Tools → Snapshots**: a snapshot's ⋮ → **Edit description** → change it → **Save** →
   "Description of … saved"; the web UI shows the new text. An empty description removes it.
3. **Tools → Backups → Restore** on a backup: **As a new VM**, **Disks go to** another storage (if
   you have two that hold disks) → **Start restore** → the new VM's disks are on that storage
   (web UI → Hardware). Delete that VM afterwards (next step).
4. **Tools → Delete** (behind **More**) on a running VM only says to shut it down first. On a stopped one: the
   dialog asks you to type its **ID** (not the name), with both options ticked → **Delete** (and
   the fingerprint if App lock checks again) → the screen closes; the VM is gone in the web UI;
   its backups are still there.

### D21. Nodes and tasks: all guests at once, history, catalogue, stop a task, new after beta.2
1. Node → the **⋯** beside **Guests on this node** → **Shut down all** → type the node's name (and the
   fingerprint) → every guest on it shuts down.
2. **Start all** with "Also guests that aren't set to start at boot" ticked → they start one
   after another; the guest with Order 1 (D20) starts first. **Migrate all** with one node says
   there is no other node to move to (with two nodes: pick one; the guests move).
3. Node → **Task history** (behind **More**) matches the web UI's Node → Task History. **Failed**
   shows only failed tasks; **Load older tasks** adds older pages; a task opens its log.
4. Datacenter → **Storage** → tap the `local` row of your node → **CT templates** → **Template
   catalogue** → search `alpine` → **Download to this storage** → "… downloaded"; the entry now
   says **On this storage**. Delete the template from the storage's list again.
5. **Stop a task**: **Back up now** on the test VM → **View log** → the red stop icon at the top
   → **Stop task** → the task ends with an error and the half-finished backup is not listed.
6. **Locked guest**: in Node → **Shell** run `flock /var/lock/qemu-server/lock-<ID>.conf sleep 60`
   with the test VM's ID, then start or stop that VM in the app. After about 10 seconds it fails,
   and the message says another operation still holds the guest's lock and to try again
   shortly; the task log shows the same hint. After the minute it works. (Proxmox's own cleanup
   holds the lock like this for up to half a minute after a rollback with RAM.)

### D22. Datacenter: options, cluster log, storage history, new after beta.2
1. **Datacenter options** match the web UI's Datacenter → Options. Change **Sender address** →
   **Save** → "Datacenter options saved"; the web UI shows it. Change it back.
2. **Cluster log** matches the web UI's cluster log (bottom panel); **Filter** narrows it; errors
   are red, warnings orange.
3. Datacenter → **Storage** → a storage's node row → **Usage history** at the bottom: Hour, Day,
   Week, Month and Year roughly match the storage's Summary graph in the web UI.

### E. Snapshots (Tools → Snapshots)
1. **Take snapshot** on the running test VM with **Include RAM** → "VM snapshot finished"; it
   appears in the tree with a RAM badge.
2. Make a visible change in the VM (e.g. create a file), then **Roll back** to the snapshot: you
   must type its name → the change is gone.
3. **Delete** the snapshot (confirm) → it disappears.

### F. Backups (Tools → Backups)
1. The list matches the web UI's Backup tab of the guest.
2. **Back up now** to `local` (or any backup storage), mode Snapshot → "Backup finished"; the new
   backup is listed first, with the guest name as notes.
3. **Restore** it **as a new** VM (next free ID suggested) → "VM restore finished"; the copy appears
   stopped in Resources.
4. Restore **over the original** while it runs → refused with a clear message. Stop it, try again,
   type its name → it is replaced.
5. Resources → tap the backup storage itself → every guest's backups on it.

### G. Clone and templates (Tools → Clone)
1. On the stopped test VM copy: **Full clone**, next free ID suggested, name `…-clone` → **Create
   clone** → **Open it** shows the new VM.
2. With several nodes: clone to another node. If the disks are on local storage, Proxmox refuses
   unless you pick a target storage on that node.
3. **Convert to template** on a VM you don't need (stopped; type its name). Afterwards Clone
   offers **Linked clone**, which is quick.

### H. Migrate (Tools → Migrate), needs two nodes
1. Stopped VM → "Offline migration" → **Start migration** → confirm → the guest screen reopens on
   the new node (the subtitle shows it).
2. Running VM → "Live migration" (local disks are copied too, which takes longer) → it keeps
   running.
3. Running container → "Restart migration" → it is briefly down, then runs on the other node.
4. Single node: "No other node".

### I. Bulk actions (Resources)
1. Long-press a stopped test guest: "1 selected". Tap a second stopped guest: "2 selected".
2. Top bar ▶ (Start) → the dialog lists both → **Start** → "Started 2 of 2 guests".
3. Select them again → ⏻ (Shut down) → "Shut down 2 of 2 guests".
4. Back (or the arrow) ends the selection. Templates and nodes can't be selected.

### J. Favourites
1. On a guest, tap the ☆ star (top bar): it fills in.
2. Overview: a **Favourites** card shows it with its state and CPU; tap opens it.
3. Unstar it: it disappears from the Overview.

### K. Nodes
1. Node screen: **Shell** (see D), and the power icon in the top bar → **Reboot / Shut down**.
2. Only on a node you can afford to restart: **Reboot** asks you to type the node name → "Reboot
   of … requested" → the node goes offline and comes back in the app.

### L. App lock (More → Settings → Security)
1. Turn on **App lock** (asks for your fingerprint/screen lock first).
2. Close AnProx Debug, reopen: locked until you authenticate. With "Lock after 1 minute", a quick
   switch to another app and back does not lock.
3. Snapshot **Roll back** and backup **Restore over the original** ask for the fingerprint again
   after typing the name.

### M. Still working from beta.1
Sign-in (and two-factor if you use it), the Overview numbers, Resources search and filters,
guest power actions, the Tasks tab with live logs, themes. Quick look only.

### N. Proxmox Backup Server, new after beta.2
Needs a Proxmox Backup Server (see Prepare). Back up the test container and the test VM to it
first (in the Proxmox VE web UI: Datacenter → Storage → Add → Proxmox Backup Server, then
Backup now on each guest).
1. Overview → **+** → **Proxmox Backup Server**, its address (port 8007), `root@pam`, **Allow
   insecure certificate** if it has its own certificate → **Save and sign in**. The Overview card
   shows each datastore's usage → **Manage Backup Server**.
2. **This server** (CPU, memory, system disk, uptime, kernel) and the datastores match the PBS
   web UI's Dashboard.
3. Open the datastore: groups and snapshots match the PBS web UI's Content. **Garbage collect** →
   "Garbage collection finished". **Verify** → **Verify** → the task runs.
4. **Namespaces** → **New namespace** `anprox-test` → **Create** → it is listed in the PBS web
   UI → **Delete namespace** (type `anprox-test`).
5. Open the container's backup group → a snapshot → **Edit notes** → **Save** → "Notes saved"
   (the PBS web UI shows them). Switch **Protected** on: **Forget** is disabled. Switch it off.
6. **Browse files** on the container's snapshot → open `etc` → tap `hostname` → save it → "…
   saved". The download icon on a folder saves a zip. On the VM's snapshot, Browse files is not
   offered (disk images, not files) and says why.
7. **Prune** on a group: Last `1` → **Show what it would remove** lists which snapshots are kept
   and removed. Press **Remove** only if you mean it.
8. **Jobs**: create a verify job in the PBS web UI first → it is listed with its last and next
   run → **Run now** → **Last run's log**.
9. **Remotes → Add remote**: a second PBS (or this one's own address), `root@pam`, its password
   and fingerprint (PBS web UI → Dashboard → Show Fingerprint) → **Create** → listed; delete it.
10. Datastore → **Maintenance mode** → Read-only → **Save** → the PBS web UI shows it; set it back
    to Off.
11. **Updates**, **Services**, **System log** and **Task history** work as for a Proxmox VE node.

## 4. Reporting back

For each section: OK, or what went wrong. Include your Proxmox VE version (8.x / 9.x), your phone
and Android version, and screenshots where useful. In the app, **More → About → Report a bug**
opens a pre-filled GitHub form; a short message in chat is fine too.

When everything is OK, the release is: rename the `[Unreleased]` section in `CHANGELOG.md` to
`[0.1.0-beta.3] — <date>`, then run **Actions → Release** with version `0.1.0-beta.3` (dry run
first, then publish), as described in [releasing.md](releasing.md).
