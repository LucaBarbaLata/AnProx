# 0001. Native Kotlin + Jetpack Compose

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q1, Q2, Q3

## Context
The app must cover all of Proxmox in a beautiful UI and integrate deeply with Android: widgets
and Quick Settings tiles (Q28), biometrics (Q11), background work (Q26) and adaptive tablet
layouts (Q3). iOS was not requested.

## Options
1. **Kotlin + Jetpack Compose**: native, first-class Android APIs, Google's recommended UI toolkit.
2. Kotlin Multiplatform + Compose Multiplatform: shares code with iOS/desktop, adds build complexity.
3. Flutter: one codebase, but its own rendering engine, larger APKs, and plugins for native features.
4. React Native: JavaScript bridge, more native glue needed.

## Decision
Kotlin + Jetpack Compose, minimum API 26 (Android 8.0), phones plus tablets and foldables.

## Consequences
- Every Android API (Glance widgets, TileService, BiometricPrompt, WorkManager) is directly available.
- The pure-Kotlin core modules (ADR 0004) could later be moved to Kotlin Multiplatform if iOS is
  ever wanted, because they don't depend on Android.
- API 26 gives `java.time`, notification channels and variable fonts without compatibility shims.
