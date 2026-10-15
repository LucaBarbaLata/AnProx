# Testing 0.1.0-beta.2 (Proxmox VE)

This is the checklist for the owner's test run before 0.1.0-beta.2 is published. It covers
everything new since beta.1, written for **Proxmox VE** (no Backup Server needed). Each step says
what to do and what you should see. Note anything that differs, with a screenshot if you can.

The general [manual checklist](testing.md#manual-test-checklist-real-hardware) and the
[beta testing guide](beta-testing.md) still apply. This page is the short, ordered version.

## 1. Install the test build

1. On GitHub, open **Actions → CI**, pick the newest **green** run on the branch
   `ccr-ca586193-f4zcbm`, and download the **`anprox-debug-apk`** artifact (a zip).
2. Unzip it and install `app-debug.apk` on your phone.
3. It installs as **AnProx Debug**, a separate app next to your AnProx beta.1. Your beta.1 and its
   data stay untouched; add your server again in AnProx Debug (Overview → **+**).

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

## 3. What to test

Tick each line, or note what happened instead.

### A. Network card (guest agent)
1. Start the test VM, open it. Under **Network** you see the OS name and its IP addresses
   (no `127.0.0.1` or `fe80::` noise).
2. Tap an IP: "Copied …" appears; paste it somewhere to check.
3. Open the test container (running): its IPs show without any agent.
4. A VM without the agent shows how to enable it.

### B. Performance charts
1. On the test VM: **Performance** shows CPU, memory, network and disk I/O.
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

### D. Consoles (Tools → Console, Node → Shell)
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
1. Node screen: **Tools → Shell** (see D) and **Power → Reboot / Shut down**.
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

## 4. Reporting back

For each section: OK, or what went wrong. Include your Proxmox VE version (8.x / 9.x), your phone
and Android version, and screenshots where useful. In the app, **More → About → Report a bug**
opens a pre-filled GitHub form; a short message in chat is fine too.

When everything is OK, the release is: set the date of the `[0.1.0-beta.2]` section in
`CHANGELOG.md`, then run **Actions → Release** with version `0.1.0-beta.2` (dry run first, then
publish), as described in [releasing.md](releasing.md).
