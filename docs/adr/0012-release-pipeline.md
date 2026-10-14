# 0012. Release pipeline: owner-held key, GitHub pre-releases, emulator gate

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q34, Q35, Q36, Q38

## Context
The owner asked to prepare a beta package. Releases go to GitHub (with Obtainium) first and
IzzyOnDroid/F-Droid later (Q34). CI had built R8-minified release APKs, but nobody had ever
*run* one. Shrinking problems (missing serializers, routes or DI bindings) only show up at runtime.

## Decision
- **Signing key**: generated and kept by the owner. CI receives it only through four repository
  secrets (`ANPROX_KEYSTORE_BASE64`, `ANPROX_KEYSTORE_PASSWORD`, `ANPROX_KEY_ALIAS`,
  `ANPROX_KEY_PASSWORD`). Local builds read a git-ignored `keystore.properties`. Nothing secret is
  ever committed or created by an AI session.
- **Version scheme**: `MAJOR.MINOR.PATCH[-alpha|beta|rc.N]` with a derived versionCode in which
  pre-releases sort below the final release (docs/releasing.md).
- **Release workflow** (`.github/workflows/release.yml`), started by a `v*` tag or manually, with a
  **dry-run** mode signed by the debug key. It tests, builds, verifies the signature and version,
  runs the **end-to-end tour on an emulator against the exact APK**, writes notes from CHANGELOG,
  and publishes a GitHub release (a pre-release when the version has a suffix).
- **End-to-end tour in CI** (`scripts/e2e/tour.py`, `ci.yml` → `e2e`): installs the minified
  release APK (signed with the debug key) on Android 8.0 (minSdk) and 14 emulators and drives the
  main flows with adb + uiautomator against the mock server. This needs no test framework inside
  the APK, so it tests exactly what users install.
- **Beta feedback without telemetry** (Q36): crash reports are stored locally and offered for
  sharing through the Android share sheet on the next start. "Report a bug" opens GitHub issue forms.
- **Store metadata** in Fastlane layout (`fastlane/metadata/android/en-US`) so IzzyOnDroid and
  F-Droid can use it directly.

## Consequences
- Publishing needs a one-time setup by the owner (key plus secrets). Until then only dry runs work.
- Every push now also runs two emulator jobs (about 10–15 minutes in parallel). The `e2e` job can be
  limited to pull requests and the default branch if Actions minutes become a concern.
- The adb/uiautomator tour relies on visible text. Changing UI strings may require updating
  `scripts/e2e/tour.py`, and the tour's failure artifacts (screenshot, UI dump, logcat) show what it saw.
- F-Droid's main repository will sign with its own key unless the build becomes reproducible.
  IzzyOnDroid ships our signature.
