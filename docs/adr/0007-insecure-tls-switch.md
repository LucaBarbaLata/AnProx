# 0007. Per-server "allow insecure certificate" switch

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q10

## Context
Proxmox installs with a self-signed certificate. The options offered were trust-on-first-use
pinning (recommended), pinning plus custom CA import, a simple "allow insecure" switch, or
requiring publicly trusted certificates. **The owner chose the simple switch.**

## Decision
- A per-server `allowInsecureTls` flag, **off by default**.
- When on, that server's OkHttp client uses a trust-all `X509TrustManager` and a permissive
  hostname verifier. Other servers keep full validation, because each server has its own client.
- The UI warns when the switch is turned on. A certificate failure at login explains the cause and
  offers a one-tap **Allow**.

## Consequences
- Simple and familiar (it's what many tools do), and it works with every setup immediately.
- Users who enable it lose protection against man-in-the-middle attacks for that server. This is
  documented in docs/security.md, together with the recommendation to use ACME/Let's Encrypt or a VPN.
- Fingerprint pinning remains a possible future hardening. It needs an amendment to Q10.
