# Dev log

Chronological notes from development sessions: what was done, what went wrong, and how it was
solved. Newest first.

---

## 2026-10-14 — Session 3: M1 starts with snapshots

**Beta 0.1.0-beta.1:** the owner tested the app on **Proxmox VE 8** ("everything works great").
They have no Backup Server, so PBS support is still only tested against the mock. A Proxmox VE 9
test will follow later. The beta is pinned to commit `e38c64b`: it is published by pushing the
tag `v0.1.0-beta.1` on that commit, so the M1 work below doesn't end up in it.
`anprox.versionName` is now `0.1.0-beta.2`.

**Snapshots (first M1 feature),** built along the architecture recipe:
| Layer | What |
|---|---|
| Model | `GuestSnapshot`, `SnapshotTree.flatten` (depth-first, `current` last, orphans become roots, cycle-safe), `SnapshotName` (Proxmox `pve-configid` rules, 40 characters, `current` reserved, a locale-proof suggested name) |
| API | `PveApi.snapshots` / `createSnapshot` / `rollbackSnapshot` / `deleteSnapshot` |
| Mock | Seeded trees for `web`, `win11` and `pihole`. Rollback restores the running state only for RAM snapshots, and deleting re-parents children, as in Proxmox. |
| Data | `GuestRepository` snapshot functions. `vmstate` is only sent for VMs. |
| UI | `ui/snapshots/`: tree list, take dialog (suggested name, live validation, Include RAM for running VMs), roll back (type the name) and delete (confirm), busy card, task snackbar with View log |
| Tests | +12 JVM tests (57 in total) and a snapshot step in the emulator tour |

Small refactors on the way: `MenuRow` moved to `ui/common`, and the task-result snackbar was
extracted to `ui/common/TaskResult.kt` (used by the guest and snapshot screens).

---

## 2026-10-14 — Session 2: preparing the first beta (0.1.0-beta.1)

The owner asked: "can you make a release version? or let's prepare for a beta package".

**What a beta needed, and what was done:**
| Need | Done |
|---|---|
| A signing key that stays the same forever | Can't be created by an AI session: the key is the app's identity and must stay with the owner. Signing now reads repository secrets or a git-ignored `keystore.properties` ([releasing.md](releasing.md)). |
| Ordered version numbers | `0.1.0-beta.1` → versionCode 10021; the final 0.1.0 → 10099 (`app/build.gradle.kts`). |
| Proof the minified release APK actually runs | New `e2e` CI job: release APK on Android 8.0 + 14 emulators, driven by `scripts/e2e/tour.py` against the mock server. |
| A way to publish | `release.yml`: dry run → verify → e2e → notes from CHANGELOG → GitHub pre-release. |
| Feedback without telemetry | Local crash reports with a share dialog; About → Report a bug; issue templates. |
| Store presence | Fastlane metadata, icon and feature graphic (rendered with headless Chromium from `docs/brand/`). |
| Tester guidance | [beta-testing.md](beta-testing.md). |

**Gotchas:**
- `java.util.Properties()` in `app/build.gradle.kts` → "Unresolved reference: util", because `java`
  is the Java extension in Gradle Kotlin scripts. Fixed with an import, and noted in CLAUDE.md.
- New-headless Chromium screenshots have a shorter viewport than the window. The headless shell
  binary renders exact sizes.
- e2e tour, first runs: the release APK started fine on both emulators (no R8 problems at startup).
  The tour itself needed two fixes: the soft keyboard swallowed scroll gestures (now disabled for
  the tour), and on Android 14 `dumpsys input_method` reports `mInputShown=true` whenever a field
  *requests* a keyboard, so "press BACK if the keyboard is shown" closed the server editor instead.
  The tour never presses BACK to hide the keyboard any more. With that, the full tour passed on
  Android 8.0.
- On Android 14 (emulator in three-button navigation mode) the tour still ended up back on the
  welcome screen. The app draws edge to edge, so a list item can be *visible* under the system
  navigation bar. The tour tapped "Allow insecure certificate" while it sat there and pressed the
  bar's Back button instead. `scroll_to()` now moves the target towards mid-screen and waits for
  it to stop moving before any list tap. Failures print the focused window, the app process and
  any crash, and every tap logs its coordinates. After that, the full tour passed on both
  Android 8.0 and 14 (CI run 33). This isn't an app bug: edge-to-edge apps always show content
  under translucent system bars, and the bars take the touches there.

**Owner sign-off:** the Release workflow's dry run passed (Release run 1), and the owner tested
the app on their own Proxmox hardware: "everything works great". Remaining before the
public beta: the release key, the four repository secrets, and the publishing run.

---

## 2026-10-14 — Session 1: kick-off and milestone M0

### 1. Questionnaire
Asked the owner 40 questions in 10 rounds (foundation, scope, security, connectivity, guests,
datacenter, monitoring, design, release, process). All answers are recorded verbatim in
[questionnaire.md](questionnaire.md). Notable choices:
- Audience is **pro sysadmins/MSPs** (Q8), but the design is **bespoke "Midnight Glass"** (Q29, Q37)
  rather than a dense console look. The answer is dense information inside a calm, premium frame.
- **API tokens not wanted** (Q9). **Simple insecure-TLS switch** chosen over pinning (Q10). The risk
  is documented in [security.md](security.md).
- Owner is **learning Android** (Q39), so the docs explain concepts ([architecture.md](architecture.md), [glossary.md](glossary.md)).

### 2. Environment constraint: no Google Maven in the dev container
The cloud container's network policy blocks `dl.google.com`, which hosts the Android SDK *and*
Google's Maven repository (`maven.google.com` redirects there). Maven Central, the Gradle Plugin
Portal and `raw.githubusercontent.com` were reachable.

Workarounds:
- The settings file got a **JVM-only mode** (`-Panprox.jvmOnly=true`) that swaps the root build file
  so AGP is never resolved. All pure-Kotlin modules (model, network, data, mock server) build and
  test locally.
- Android modules were verified in **GitHub Actions**, using a small first push to validate the
  toolchain before writing all the UI.
- Maven Central occasionally answered **HTTP 429** (rate limiting). Local builds retried with a
  backoff and `--max-workers=2`.
- To make this permanent for future sessions, allow `dl.google.com` in the environment's network
  settings.

### 3. Build log
| Step | Result |
|---|---|
| Core model + tests | 20 tests ✅ |
| Network client + Ktor mock server + integration tests | 16 tests ✅ on first run |
| Data layer (hub, snapshots, guests) + tests | 8 tests. One was silently skipped by JUnit because the test function returned a value instead of `Unit` (see testing.md). Fixed. ✅ |
| CI run 1 (app shell) | ❌ `okhttp-android:5.5.0` requires compileSdk 37, and AGP 8.12 maxes out at 36 |
| Fix | Pinned OkHttp **5.4.0** (checked its AAR metadata: `minCompileSdk=36`). Also replaced `URLDecoder.decode(String, Charset)` (Android 13+ only) with the String-charset overload. |
| CI run 2 (app shell) | ✅ tests, lint, debug + release |
| CI run 3 (full M0 UI, ~40 files) | ✅ on the first attempt |
| Self-review fix | Dashboard/resources briefly showed "empty" before the first fetch, so a per-server loading state is now emitted immediately (+1 test, 45 total). Task list keys de-duplicated. |

### 4. Side effect: Dependabot PRs
Because this branch was the repository's first push, it became the default branch, and
Dependabot immediately opened PRs bumping GitHub Actions (`checkout` v7, `setup-java` v6,
`upload-artifact` v7). The workflow was updated to those versions directly, so those PRs can be
closed. Gradle dependency PRs will follow weekly. Each should be checked against the AGP/compileSdk
constraint in [ADR 0002](adr/0002-toolchain-baseline.md).

### 5. Not yet verified
- Nothing has run on a real device or emulator yet (no emulator in the container). The owner's
  [manual checklist](testing.md#manual-test-checklist-real-hardware) is the next step.
- Real PVE 9 / PBS 4 behaviour versus the mock: the TFA partial-ticket format, the `new-format`
  parameter, and realm `default` flags are implemented from the documented wire format and need
  confirmation on hardware.

### Next session (M1 candidates)
App lock and biometric re-auth, OIDC, WebAuthn, snapshots, backup now/restore, RRD charts,
guest agent info, bulk actions, task notifications. See the [roadmap](roadmap.md#m1--daily-driver).
