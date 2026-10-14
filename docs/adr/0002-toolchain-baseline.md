# 0002. Toolchain baseline: AGP 8.12, Kotlin 2.4, compileSdk 36

- Status: Accepted
- Date: 2026-10-14

## Context
The development container for the first session could not reach Google's Maven repository or
the Android SDK host (`dl.google.com`, blocked by the network policy), but it could reach Maven
Central and the Gradle Plugin Portal. Android builds were therefore verified only in GitHub
Actions, so the chosen versions had to be ones known to exist and to work together.

## Decision
| Component | Version | Why |
|---|---|---|
| Gradle | 8.14.3 | Supported by AGP 8.12. Matches the container's local Gradle. |
| Android Gradle Plugin | 8.12.0 | Stable and well documented. Supports compileSdk 36. |
| Kotlin (+ Compose compiler plugin, serialization plugin) | 2.4.21 | Current stable (verified on Maven Central). 2.4.21 is a bug-fix release taken from Dependabot (#5). |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | Android 16 APIs; Q2 minimum. |
| Compose BOM | 2025.06.00 | Compose 1.8 / Material 3 1.3, known compatible with AGP 8.12. |
| OkHttp | **5.4.0** (pinned) | 5.5.0's Android artifact requires compileSdk 37, which AGP 8.12 doesn't support. |
| Koin | 4.1.1 | Matches the Compose 1.8 generation (4.2 pulls newer AndroidX). |
| JVM target | 17 | Android-supported bytecode level. Builds run on JDK 21. |

Dependabot (`.github/dependabot.yml`) proposes updates weekly.

## Consequences
- Moving to AGP 9 (built-in Kotlin, new DSL) is a deliberate future step: it unlocks compileSdk 37
  and therefore OkHttp ≥ 5.5 and newer AndroidX releases. It needs its own ADR.
- Pure-Kotlin modules must avoid Java APIs newer than Android 8.0. Example found in M0:
  `URLDecoder.decode(String, Charset)` exists only on Android 13+, so the `String` charset
  overload is used instead. Lint doesn't check pure-JVM modules, so code review must catch this.
