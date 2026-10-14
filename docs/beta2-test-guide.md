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
1. Test VM → **Console**: the VM's screen appears (noVNC). Type, click, rotate to landscape.
2. Test container → **Console**: a login prompt. Log in with the soft keyboard. Try the key row:
   **Esc**, **Tab** (completion), **Ctrl+C** (stop `ping 1.1.1.1`), **Ctrl+D** (log out), arrows
   (shell history).
3. Node → **Shell**: a root shell (needs root@pam or Sys.Console).
4. Leave a console open for a while, lock the phone, come back, tap **Reconnect** (top right).
5. If your server uses a self-signed certificate, this works only because "Allow insecure
   certificate" is on for it. Turning it off (More → Servers → edit) should make the console show
   a certificate message instead.

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
