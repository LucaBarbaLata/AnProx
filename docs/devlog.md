# Dev log

Chronological notes from development sessions: what was done, what went wrong, and how it was
solved. Newest first.

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
