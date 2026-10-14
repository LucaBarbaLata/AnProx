# 0013. App lock uses the phone's own biometrics and screen lock

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q11, Q12

## Context
Q11 asks for an optional app lock ("biometric/PIN on launch") and a biometric check before
actions that can't be undone. AnProx holds sessions to production clusters, so someone picking up
an unlocked phone shouldn't be able to roll back a VM.

## Options
1. **A separate AnProx PIN** plus biometrics. One more secret to remember. It has to be stored
   (hashed) and rate-limited by us, and a forgotten PIN means wiping app data.
2. **The phone's own credentials through BiometricPrompt**: any enrolled biometric
   (`BIOMETRIC_WEAK`) or the screen lock PIN/pattern/password (`DEVICE_CREDENTIAL`). Android does
   the verification, rate limiting and lockout.
3. Bind the Keystore key to user authentication, so secrets can't even be decrypted while locked.

## Decision
Option 2, the same as banking and password-manager apps.
- The lock is **off by default**. Turning it on, turning it off, and turning off the extra check
  before dangerous actions all require authenticating first.
- While enabled, the app is locked after a fresh start and after it has been in the background
  for the chosen time (immediately, 1 minute or 5 minutes; default 1 minute). Time spent in the
  system prompt itself doesn't count.
- The lock screen is a **full-screen dialog**, so it also covers dialogs and menus that were open
  when the app locked. The app underneath stays composed, so navigation survives.
- With the lock on, actions confirmed by typing a name (`DangerLevel.TYPE_TO_CONFIRM`: snapshot
  rollback today, destroy/purge later) also ask for the fingerprint or screen lock
  (`DangerousActionGuard`). This can be switched off.
- If the phone's screen lock is removed later, the app lock turns itself off. It can't work
  without one, and removing it already required the old PIN.
- Pure logic (`AppLock` in `:core:data`) is unit-tested; Android wiring lives in
  `app/.../security/`.

## Consequences
- Nothing new to remember or store; works the same on every Android version from 8.0 on.
- `MainActivity` is a `FragmentActivity` (BiometricPrompt requires it), with an explicit current
  `androidx.fragment` dependency.
- Option 3 (auth-bound Keystore keys) would also protect secrets against a rooted, unlocked phone,
  but it breaks silent re-login and background monitoring (M3). It stays a possible later
  hardening.
- The recent-apps thumbnail can still show the last screen. Hiding it (`FLAG_SECURE`) would also
  block screenshots, so it's left for a separate, opt-in setting.
