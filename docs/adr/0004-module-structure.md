# 0004. Pure-Kotlin core modules, thin Android app

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q38

## Context
The riskiest code (authentication, two-factor, failover, TLS handling, error mapping, session
renewal) must be heavily tested against something realistic. Android instrumentation tests are
slow and need emulators.

## Decision
- `:core:model`, `:core:network` and `:core:data` are **plain Kotlin/JVM** modules with no Android
  dependency. Storage is behind interfaces (`ProfileStore`, `SecretStore`) that `:app` implements.
- `:tools:mock-server` is a Ktor fake of PVE/PBS. JVM tests start it in-process and talk to it
  over real HTTPS.
- `:core:designsystem` and `:app` are the only Android modules. Features are packages in
  `:app` for now and can become `:feature:*` modules later.
- A `-Panprox.jvmOnly=true` switch builds only the JVM modules (it swaps the root build file
  so the Android Gradle Plugin is never resolved).

## Consequences
- 45 integration and unit tests run in seconds on any JVM, including environments without the Android SDK.
- The Android layer stays thin and easy to read.
- Java APIs used in core modules must exist on Android 8.0 (see ADR 0002).
