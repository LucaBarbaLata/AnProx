# CLAUDE.md — notes for AI coding sessions

AnProx is a native Android (Kotlin + Jetpack Compose) client for Proxmox VE and Proxmox Backup
Server. Scope and every product decision come from **docs/questionnaire.md (Q1–Q40)**. Treat it
as the source of truth, and record changes in its "Amendments" section.

## Orientation
- Architecture and the feature recipe: `docs/architecture.md`
- What's done and what's next: `docs/roadmap.md`, `docs/api-coverage.md`, `CHANGELOG.md`
- Design rules (Midnight Glass): `docs/design-system.md`
- Session history and environment gotchas: `docs/devlog.md`

## Modules
`:core:model`, `:core:network`, `:core:data`, `:tools:mock-server` are pure Kotlin/JVM.
`:core:designsystem` and `:app` are Android. Package root: `io.github.lucabarbalata.anprox`.

## Commands
```bash
./gradlew test                          # all JVM tests (mock server over HTTPS)
./gradlew -Panprox.jvmOnly=true test    # when the Android SDK / Google Maven is unreachable
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :tools:mock-server:run        # fake PVE :8006 + PBS :8007 (root@pam / anprox)
```
If the container can't reach `dl.google.com`, Android code is verified only by GitHub Actions
(`.github/workflows/ci.yml`). Push small and check the run.

## Conventions that bite
- All user-visible text goes in `app/src/main/res/values/strings.xml`. Check that every
  `R.string.x` used is defined.
- Pure-JVM modules must only use Java APIs available on Android 8.0 (API 26).
- Version pins have reasons (`docs/adr/0002-toolchain-baseline.md`). For example, OkHttp ≥ 5.5 needs compileSdk 37 / AGP 9.
- JUnit skips `@Test` functions that don't return `Unit`. End `= runBlocking { … }` tests with `Unit`.
- ViewModel functions referenced as callbacks should return `Unit` (block body), not `Job`.
- Non-composable lambdas (e.g. `SegmentedPicker(label = …)`) can't call `stringResource`; precompute labels.
- In `*.gradle.kts`, `java.util.X` fails ("Unresolved reference: util") because `java` is the Java
  extension there. Import the class instead.
- Update docs (api-coverage, roadmap, CHANGELOG, devlog) in the same change as the code.
