# 0008. Ticket auth; secrets encrypted with an Android Keystore key

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q9, Q11

## Context
Q9 selects password (realms), two-factor and OIDC, and explicitly not API tokens. Sessions should
survive app restarts, and expired sessions should renew without friction when the user allows it.

## Decision
- Use Proxmox's ticket flow (`POST /access/ticket`), the same as the web UI: cookie plus CSRF header,
  the `new-format` TFA flow (with an automatic retry without that parameter if a future PVE drops it),
  and renewal after 1 hour.
- Persist the **session** (ticket, CSRF token, issue time) so restarts don't require a login.
- Persist the **password** only if the user ticks "Remember password". It is used for silent
  re-login after expiry, and never when a second factor is required.
- Encrypt both with **AES-256-GCM** using a key generated in the **Android Keystore**
  (`anprox_secrets_v1`), stored in a dedicated DataStore file. `allowBackup=false`.
- Jetpack Security's `EncryptedSharedPreferences` is deprecated, so a small, auditable
  `KeystoreCipher` (about 60 lines) is used instead.

## Consequences
- Stolen app files can't be decrypted off-device.
- If the Keystore key is lost (for example after a factory reset or certain OS-level resets),
  decryption fails, the values read as absent, and the user simply signs in again.
- App lock (M1) will add user-presence checks before showing the app and before destructive actions.
