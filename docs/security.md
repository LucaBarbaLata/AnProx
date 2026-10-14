# Security

AnProx holds the keys to production infrastructure, so it's treated as security-sensitive
software. This page covers what is protected, how, and the known trade-offs.

## Threat model

| Threat | Mitigation | Status |
|---|---|---|
| Stolen or lost phone | Secrets encrypted with a hardware-backed Keystore key; optional app lock (Q11) | ✅ encryption · 🗓 app lock (M1) |
| Another app reading AnProx's files | Android app sandbox; secrets encrypted at rest; no backups | ✅ |
| Malware with root / a forensic image | Keystore key can't be extracted from secure hardware; tickets expire after 2 h | ✅ (partial: a rooted live device is out of scope) |
| Network eavesdropping | HTTPS only; plain `http://` addresses are rejected | ✅ |
| Man-in-the-middle with a fake certificate | Certificate validation on by default; the per-server insecure switch is opt-in with a warning | ✅ (see trade-off below) |
| Accidental destructive actions | Tiered confirmation (Q12); biometric re-auth (Q11) | ✅ confirmation · 🗓 biometric (M1) |
| Leaking data to third parties | No analytics, crash reporting, ads or Google libraries (Q36) | ✅ |
| Logs leaking credentials | HTTP logging is off in builds and, when enabled, only logs method, URL and status (never headers) | ✅ |
| Executing an action twice during failover | Failover retries a write only if the connection was never established | ✅ |

## Authentication

AnProx uses the same mechanism as the Proxmox web UI ([ADR 0008](adr/0008-credential-storage.md)):

1. `POST /api2/json/access/ticket` with `username=user@realm` and the password.
2. If the account has two-factor authentication, Proxmox answers with `NeedTFA: 1` and a
   **partial ticket** that embeds the challenge. AnProx shows the second-factor step (TOTP,
   recovery key or Yubico OTP; WebAuthn arrives in M1) and sends
   `tfa-challenge=<partial ticket>` with `password=<kind>:<answer>`.
3. The response contains the **ticket** (sent as the `PVEAuthCookie`/`PBSAuthCookie` cookie) and a
   **CSRF prevention token** (sent as a header on every non-GET request).
4. Tickets are valid for 2 hours. After 1 hour AnProx swaps the ticket for a fresh one
   (`password=<current ticket>`) before the next request.
5. If a ticket has expired anyway (the phone was off, for example):
   - with **Remember password** on and no second factor, AnProx signs in again silently;
   - otherwise the server is marked **Session expired** and the user signs in again.

API tokens are **not** supported by design (Q9). OpenID Connect sign-in arrives in M1.

## What is stored, and where

| Data | Store | Protection |
|---|---|---|
| Server profiles (name, addresses, headers, user@realm, TLS switch) | DataStore `profiles` | App sandbox. **Custom headers are stored here unencrypted.** Don't put long-lived secrets in headers unless you accept that. |
| Passwords (only if "Remember password" is on) | DataStore `secrets` | AES-256-GCM, key in the Android Keystore |
| Session tickets + CSRF tokens | DataStore `secrets` | AES-256-GCM, key in the Android Keystore |
| Settings (theme, refresh interval) | DataStore `settings` | App sandbox (not sensitive) |

- The Keystore key (`anprox_secrets_v1`) is generated on the device, can't be exported, and lives
  in the TEE/StrongBox where the hardware has one.
- `android:allowBackup="false"`: nothing goes to cloud or ADB backups. Backed-up ciphertext would be
  useless anyway, since the key never leaves the device. A deliberate, encrypted
  **export/import of server profiles** is on the roadmap.
- Removing a server deletes its password and session too.

> Planned (M1): move custom headers into the encrypted secret store. Today they are plain
> text inside the app sandbox, which is fine for routing headers but not ideal for secrets.

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

## Crash reports

If the app crashes, the stack trace, app version, Android version and device model are written to
a private file on the device. **Nothing is sent.** On the next start the app shows the report and
offers **Share report** through the Android share sheet, or **Discard**. Exception messages can
include server host names, so the dialog asks the user to review it before sharing.
(`app/.../diagnostics/CrashReporter.kt`)

## Release signing

Release APKs are signed with a key that only the owner holds, provided to CI through repository
secrets ([releasing.md](releasing.md), [ADR 0012](adr/0012-release-pipeline.md)). The
certificate's SHA-256 fingerprint is printed in every release's notes so users can verify updates.

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
