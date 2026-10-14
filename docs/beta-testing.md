# Beta testing AnProx

Thanks for helping test AnProx! This page explains how to install the beta, what to try, and how
to report problems.

## Before you start

- **This is a beta.** It talks to your real servers. Power actions (stop, reset…) are real.
  Consider testing first with a **test cluster** or an account with limited permissions (for
  example a PVE user with the `PVEAuditor` role, which can look but not touch).
- You need **Android 8.0 or newer**, and **Proxmox VE 9** (8 should work) and/or **Proxmox Backup
  Server 4** (3 should work).
- No server handy? Run the [mock server](testing.md#the-mock-server-toolsmock-server) on a computer.

## Install

1. Open the latest [release](https://github.com/LucaBarbaLata/AnProx/releases) marked
   *Pre-release* and download `AnProx-<version>.apk`.
2. Open it on your phone. Android asks you to allow installing apps from your browser or file
   manager. Allow it for that app only.
3. **Automatic updates (recommended):** install [Obtainium](https://github.com/ImranR98/Obtainium),
   add `https://github.com/LucaBarbaLata/AnProx` and enable **Include prereleases**.

Optional: verify the download. The release notes list the APK's SHA-256 and the signing
certificate fingerprint, which is the same for every release and can be checked with
[AppVerifier](https://github.com/soupslurpr/AppVerifier).

## What to try

The most valuable things to test, roughly in order:

1. **Adding your servers**: by IP, by host name, behind a reverse proxy, with a self-signed certificate.
2. **Signing in** with your real setup: PAM/PVE/LDAP/AD realms and two-factor (TOTP, recovery
   key, YubiKey OTP).
3. **Several addresses for one server** (LAN plus VPN or public): leave home Wi-Fi and see whether
   the app switches over by itself, then switches back when you return.
4. **The overview**: do the numbers match the web UI? Does "Needs attention" show the right things?
5. **Resources**: search by name, ID, tag. Use the filters. On a tablet, try the side-by-side view.
6. **Power actions** on a guest you don't mind restarting. Check the confirmation, the result
   message and the task log.
7. **Leaving the app for hours** and coming back: are you still signed in? (Try with and without
   "Remember password".)
8. **Themes**: light, dark, pure black, and your phone's font size settings.

The full list is in the [manual test checklist](testing.md#manual-test-checklist-real-hardware).

## Known limitations in this beta

These are planned, not bugs ([roadmap](roadmap.md)):

- No console, snapshots, backups, charts, hardware editing, migration or VM creation yet (M1–M2).
- Single sign-on (OpenID Connect) and security keys (WebAuthn) can't be used to sign in yet (M1).
- No app lock or fingerprint check before actions yet (M1).
- No notifications or widgets yet (M3).
- Backup Server task logs can't be opened yet (M1).
- English only for now. Translations come later.

## Reporting problems

- **Something wrong?** Go to **More → About → Report a bug**. It opens a short form on GitHub.
  Please include the app version (shown on the About screen), your device, and the Proxmox version.
- **The app crashed?** On the next start AnProx shows the crash report it saved **on your phone**
  and offers **Share report**. Nothing is sent unless you choose to share it. Read it first: it can
  contain server host names. Paste it into the bug report.
- **Ideas?** Use the *Feature request* form.
- **Security problem?** Please report it privately via the repository's Security tab, not as a
  public issue.

**Never post passwords, tickets or tokens** in an issue.

## Privacy

AnProx has no analytics, no ads and no tracking. It only talks to the servers you add. Your
passwords (if you choose "Remember password") and sessions are encrypted with a key that never
leaves your phone. Uninstalling the app deletes everything.
