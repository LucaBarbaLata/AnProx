# Security

AnProx holds the keys to production infrastructure, so it's treated as security-sensitive
software. This page covers what is protected, how, and the known trade-offs.

## Threat model

| Threat | Mitigation | Status |
|---|---|---|
| Stolen or lost phone | Secrets encrypted with a hardware-backed Keystore key; optional app lock (Q11) | ✅ |
| Another app reading AnProx's files | Android app sandbox; secrets encrypted at rest; no backups | ✅ |
| Malware with root / a forensic image | Keystore key can't be extracted from secure hardware; tickets expire after 2 h | ✅ (partial: a rooted live device is out of scope) |
| Network eavesdropping | HTTPS only; plain `http://` addresses are rejected | ✅ |
| Man-in-the-middle with a fake certificate | Certificate validation on by default; the per-server insecure switch is opt-in with a warning | ✅ (see trade-off below) |
| Accidental or unauthorised destructive actions | Tiered confirmation (Q12); fingerprint or screen lock check before type-to-confirm actions while the app lock is on (Q11) | ✅ |
| Leaking data to third parties | No analytics, crash reporting, ads or Google libraries (Q36) | ✅ |
| Logs leaking credentials | HTTP logging is off in builds and, when enabled, only logs method, URL and status (never headers) | ✅ |
| Executing an action twice during failover | Failover retries a write only if the connection was never established | ✅ |

## Authentication

AnProx uses the same mechanism as the Proxmox web UI ([ADR 0008](adr/0008-credential-storage.md)):

1. `POST /api2/json/access/ticket` with `username=user@realm` and the password.
2. If the account has two-factor authentication, Proxmox answers with `NeedTFA: 1` and a
   **partial ticket** that embeds the challenge. AnProx shows the second-factor step (TOTP,
   recovery key or Yubico OTP) and sends
   `tfa-challenge=<partial ticket>` with `password=<kind>:<answer>`.
3. The response contains the **ticket** (sent as the `PVEAuthCookie`/`PBSAuthCookie` cookie) and a
   **CSRF prevention token** (sent as a header on every non-GET request).
4. Tickets are valid for 2 hours. After 1 hour AnProx swaps the ticket for a fresh one
   (`password=<current ticket>`) before the next request.
5. If a ticket has expired anyway (the phone was off, for example):
   - with **Remember password** on and no second factor, AnProx signs in again silently;
   - otherwise the server is marked **Session expired** and the user signs in again.

API tokens are **not** supported by design (Q9).

### OpenID Connect (single sign-on)

For a realm of type `openid` the login screen offers **Sign in with *realm*** instead of a
password, and follows the web interface:

1. `POST /access/openid/auth-url` with the realm and a **redirect address**: the origin of the
   server address in use (`https://pve.example.com:8006`), which is what the web interface sends,
   so the identity provider already allows it.
2. The provider's page opens full screen in a WebView. The top bar names its host. The user signs
   in there; AnProx never sees that password.
3. The provider sends the browser back to the redirect address with `code` and `state`. The
   WebView catches that navigation (redirects included) and never loads the address.
4. `POST /access/openid/login` with `code`, `state` and the same redirect address returns a
   normal ticket. The profile takes the account the provider named (`alice@sso`).

Nothing is remembered for a silent sign-in: the ticket renews every hour like any other, and once
it has expired the user signs in at the provider again. The WebView keeps the provider's cookies,
so that is usually one tap.

Certificates: the server's **Allow insecure certificate** setting also lets the WebView accept a
self-signed certificate from the server's own host and from the provider host that the server
named in step 1, and from no other host. Some providers refuse embedded browsers (Google sign-in
does); a self-hosted one (Authentik, Keycloak, Zitadel, …) works.

### Why not WebAuthn (security keys, passkeys)

An Android app can only use WebAuthn for a website that vouches for it: the site must publish a
Digital Asset Links file (`/.well-known/assetlinks.json`) naming the app's signing key, and
Android's own WebAuthn support needs Google Play services. Every Proxmox server is someone's own
host, so neither is possible for an app that talks to any server. Accounts that only have security
keys are told to add a TOTP or recovery keys in the web interface (questionnaire amendment of
2026-10-21).

## What is stored, and where

| Data | Store | Protection |
|---|---|---|
| Server profiles (name, addresses, headers, user@realm, TLS switch) | DataStore `profiles` | App sandbox. **Custom headers are stored here unencrypted.** Don't put long-lived secrets in headers unless you accept that. |
| Passwords (only if "Remember password" is on) | DataStore `secrets` | AES-256-GCM, key in the Android Keystore |
| Session tickets + CSRF tokens | DataStore `secrets` | AES-256-GCM, key in the Android Keystore |
| Settings (theme, refresh interval) | DataStore `settings` | App sandbox (not sensitive) |
| SSH private keys and their passphrases | DataStore `ssh` | AES-256-GCM, key `anprox_ssh_v1` in the Android Keystore |
| SSH public keys, trusted host keys, last SSH connection per node/guest (no password) | DataStore `ssh` | App sandbox (not secret) |

- The Keystore key (`anprox_secrets_v1`) is generated on the device, can't be exported, and lives
  in the TEE/StrongBox where the hardware has one.
- `android:allowBackup="false"`: nothing goes to cloud or ADB backups. Backed-up ciphertext would be
  useless anyway, since the key never leaves the device. To move to a new phone, use the
  deliberate, encrypted export (below).
- Removing a server deletes its password and session too.

> Planned (M1): move custom headers into the encrypted secret store. Today they are plain
> text inside the app sandbox, which is fine for routing headers but not ideal for secrets.

## Exporting servers (More → Settings → Move to another phone)

- The file holds the server profiles (custom headers included) and, only if the user ticks
  **Include saved passwords**, the remembered passwords. Session tickets are never exported.
- Encryption: a key derived from the passphrase with **PBKDF2-HMAC-SHA256** (600,000 iterations,
  16-byte random salt) encrypts with **AES-256-GCM** (12-byte random nonce). The JSON header
  (format, version, KDF, cost, salt, nonce) is authenticated as additional data, so changing the
  cost or any other field makes the file fail to open. An import refuses a cost above 10
  million iterations instead of spinning on a crafted file.
- The passphrase must be at least 8 characters; nothing checks its strength beyond that, so the
  dialog suggests a few unrelated words. A wrong passphrase and a damaged file look the same to
  GCM; the app reports both as "wrong passphrase".
- Exporting saved passwords asks for the fingerprint/face/screen lock first when the phone has
  one, since they leave the Keystore's protection.
- The file is written where the user chooses with the system file picker (the app needs no
  storage permission). Delete it once imported.
- Import adds new servers and replaces a saved server with the same internal id (an export of
  this phone, or of the phone it came from); it never deletes servers.

## SSH (Q15)

- The built-in SSH client connects straight to the node's or guest's address, not through
  Proxmox ([ADR 0015](adr/0015-ssh-client.md)).
- **Host keys are trusted on first use.** The first connection to a `host:port` shows the key's
  SHA-256 fingerprint; a different key later is shown with the old one and a warning, and is
  refused unless the user accepts it. Compare with `ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub`
  on the server. Settings → SSH keys lists trusted servers and can forget them.
- SSH passwords are never stored. Keys made on the phone never leave it; only the public half
  can be copied or shared. An imported key's passphrase is stored, encrypted with the key, so
  it isn't asked for at every connection.
- "Add key" appends the public key to the signed-in user's `~/.ssh/authorized_keys` with a
  command built only from a validated key line (type, Base64, a comment made of letters, digits
  and dashes).
- SSH keys and trusted hosts aren't part of the server export.

## The "Allow insecure certificate" switch (Q10)

Fresh Proxmox installs use **self-signed certificates**. The owner chose a simple per-server
switch over certificate pinning ([ADR 0007](adr/0007-insecure-tls-switch.md)).

- **Off by default**, for every server.
- When on, *that server's* connection skips certificate-chain **and** hostname verification.
  Other servers are unaffected, because each server has its own HTTP client.
- Traffic is still encrypted, but an attacker who can intercept traffic between the phone and
  the server could impersonate the server and capture the password or ticket.
- The editor shows a warning when the switch is on. When a certificate is rejected, the login
  screen explains the error and offers a one-tap **Allow**.

**Recommendations for users:**
- Prefer a real certificate: Proxmox has built-in ACME/Let's Encrypt support (Datacenter → ACME).
- If you must use the switch, do so only over networks you trust: your LAN, or a VPN such as
  WireGuard or Tailscale.

**Possible future hardening** (not scheduled; would need an amendment to Q10): show and pin the
certificate's SHA-256 fingerprint on first use, and warn if it changes.

## Network

- HTTPS only: `usesCleartextTraffic=false`, and `http://` URLs are rejected by `EndpointUrl.normalize`.
- Connect timeout 5 s (for fast failover), read timeout 30 s.
- Failover order and safety rules: [ADR 0006](adr/0006-endpoint-failover.md).
- `User-Agent: AnProx/<version>` identifies the app in Proxmox's access logs.

## App lock (Q11)

Optional, under **More → Settings → Security** ([ADR 0013](adr/0013-app-lock.md)).

- Uses Android's own biometric prompt: any fingerprint or face the phone has enrolled, or the
  phone's screen lock PIN/pattern/password. There's no separate AnProx PIN, and Android handles
  rate limiting and lockouts.
- Locks after a fresh start and after the app has been in the background for the chosen time
  (immediately, 1 or 5 minutes). The lock screen covers everything, including dialogs that were
  open, and Back leaves the app.
- **Check again before dangerous actions** (on by default while the lock is on): rolling back a
  snapshot, and later destroying a guest, ask for the fingerprint or screen lock after you type
  the name.
- Turning the lock or that check off requires authenticating first. If the phone's screen lock
  is removed, the app lock turns itself off, since it can't work without one.
- Not covered: the recent-apps thumbnail can still show the last screen (an opt-in "hide content"
  setting may follow), and secrets stay decryptable while the app is locked, because silent
  re-login and background alerts need them.

## Crash reports

If the app crashes, the stack trace, app version, Android version and device model are written to
a private file on the device. **Nothing is sent.** On the next start the app shows the report and
offers **Share report** through the Android share sheet, or **Discard**. Exception messages can
include server host names, so the dialog asks the user to review it before sharing.
(`app/.../diagnostics/CrashReporter.kt`)

## Release signing

Release APKs are signed with a key that only the owner holds, provided to CI through repository
secrets ([releasing.md](releasing.md), [ADR 0012](adr/0012-release-pipeline.md)). The
certificate's SHA-256 fingerprint is printed in every release's notes and in the
[README](../README.md#install-the-beta), so users can verify updates.

## Privacy (Q36)

- No analytics, no crash reporting SDKs, no ads, no Firebase, no Google Play Services.
- The app contacts **only** the server addresses you enter. The only outside links are the
  source, license and donation pages, opened in your browser when you tap them.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Talk to your servers |
| `ACCESS_NETWORK_STATE` | Notice network changes so the LAN address is retried first |

Future milestones will add `POST_NOTIFICATIONS` (M1/M3) and `USE_BIOMETRIC` (M1). Every new
permission will be documented here.

## Reporting a vulnerability

Please open a GitHub security advisory ("Report a vulnerability" on the repository's Security tab)
rather than a public issue.
