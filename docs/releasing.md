# Releasing AnProx

How a version goes from `main` (currently the default branch) to a signed APK that people can
install and update. Distribution channels come from Q34: **GitHub Releases** (plus Obtainium),
then **IzzyOnDroid / F-Droid**.

```
CI green ─▶ manual checklist on real hardware ─▶ CHANGELOG ─▶ Release workflow (dry run) ─▶ Release workflow (publish)
                                                                  │ tests · signed build · verify · e2e on emulator · notes
                                                                  ▼
                                                     GitHub (pre)release: APK + .sha256 + screenshots
```

## One-time setup (owner only)

The release signing key is the app's identity. **Every future update must be signed with the same
key.** If it is lost, users have to uninstall (and lose their saved servers) to update. If it
leaks, someone else could publish "updates". That's why the key never goes into the repository
and is only ever handled by the owner.

### 1. Create the key

Use `keytool`, which comes with any JDK and with Android Studio:
- macOS: `/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool`
- Windows: `C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe`

```bash
keytool -genkeypair -v \
  -keystore anprox-release.jks -alias anprox \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=AnProx"
```

Choose a strong password when asked. Using the same password for the store and the key is fine.

### 2. Back it up

Keep `anprox-release.jks` and its password in **two** safe places, for example your password
manager and an offline copy. Never commit it (`*.jks` is in `.gitignore`).

### 3. Add four repository secrets

GitHub → repository **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `ANPROX_KEYSTORE_BASE64` | The keystore file, Base64-encoded (see below) |
| `ANPROX_KEYSTORE_PASSWORD` | The keystore password |
| `ANPROX_KEY_ALIAS` | `anprox` (or whatever `-alias` you chose) |
| `ANPROX_KEY_PASSWORD` | The key password (often the same as the keystore password) |

Base64-encode the keystore:

```bash
base64 -w0 anprox-release.jks > keystore.b64                 # Linux
base64 -i anprox-release.jks | tr -d '\n' > keystore.b64     # macOS
```
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("anprox-release.jks")) | Out-File keystore.b64   # Windows
```

Paste the content of `keystore.b64` into the secret, then delete `keystore.b64`.

### 4. (Optional) Sign locally

To build a signed release on your own computer, create a git-ignored `keystore.properties` in the
project root:

```properties
storeFile=/absolute/path/to/anprox-release.jks
storePassword=…
keyAlias=anprox
keyPassword=…
```

Then run `./gradlew :app:assembleRelease`. Without this file (and without the `ANPROX_*`
environment variables), release builds are **unsigned**, which is fine for compile checks but
they can't be installed.

## Version numbers

`gradle.properties` holds the *next* version (`anprox.versionName`). The release workflow
overrides it with the version you release.

| versionName | versionCode | Meaning |
|---|---|---|
| `0.1.0-alpha.N` | 10000 + N | Early internal builds |
| `0.1.0-beta.N` | 10020 + N | Public beta, published as a GitHub **pre-release** |
| `0.1.0-rc.N` | 10060 + N | Release candidate |
| `0.1.0` | 10099 | Stable |

Formula: `MAJOR·1 000 000 + MINOR·10 000 + PATCH·100 + stage`, implemented in
`app/build.gradle.kts`. Pre-releases always sort below their final version, so updates install
in order. A versionCode can never go down, so to "roll back", publish a fix with a higher version.

## Cutting a release

1. **CI is green** on the commit you want to ship: the `build` job and both `e2e` emulator jobs.
2. **Manual checklist**: run [testing.md → Manual test checklist](testing.md#manual-test-checklist-real-hardware)
   against real PVE/PBS hardware with a debug build from CI.
3. **Changelog**:
   - `CHANGELOG.md`: make sure the section is headed `## [<version>] — YYYY-MM-DD`. The release
     notes are generated from it.
   - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: a summary of at most 500
     characters for F-Droid/IzzyOnDroid.
   - Commit and push.
4. **Dry run**: GitHub → **Actions → Release → Run workflow**. Enter the version (e.g. `0.1.0-beta.1`)
   and keep **Dry run** ticked. It runs the whole pipeline with a throwaway debug key and publishes
   nothing. Check the job summary and the uploaded artifact (APK, screenshots).
5. **Publish**: run the workflow again with **Dry run** unticked, or push a tag:
   ```bash
   git tag v0.1.0-beta.1 && git push origin v0.1.0-beta.1
   ```
6. **After publishing**:
   - Note the *signing certificate SHA-256* from the release notes and add it to the README's
     install section. It never changes, and users can verify it.
   - Bump `anprox.versionName` in `gradle.properties` to the next version (e.g. `0.1.0-beta.2`).
   - Add a dated entry to [devlog.md](devlog.md).

### What the Release workflow does

| Step | Purpose |
|---|---|
| Resolve version | Validates the format and refuses to reuse an existing tag. Versions containing `-` become **pre-releases**. |
| Signing key | Decodes the keystore from secrets, with a clear error if any secret is missing. Dry runs use the debug key. |
| Tests | All JVM tests against the mock server. |
| Build | `assembleRelease` with R8 minification and resource shrinking. |
| Verify | `apksigner verify`, checks the APK's versionName, computes SHA-256, and prints the certificate fingerprint. |
| End-to-end | Installs the exact APK on an Android 14 emulator and runs `scripts/e2e/tour.py` against the mock server. |
| Notes | `scripts/release/notes.py`: the CHANGELOG section plus install and verification instructions. |
| Publish | `gh release create v<version>` with the APK, `.sha256` and a screenshots zip. |

## Distribution channels

### GitHub Releases + Obtainium (now)
Users download the APK from the release page. To get updates automatically, they add the
repository URL to [Obtainium](https://github.com/ImranR98/Obtainium) and enable *Include
prereleases* for betas.

### IzzyOnDroid (after the first public beta)
[IzzyOnDroid](https://apt.izzysoft.de/fdroid/) serves the APKs from GitHub Releases, signed with
*our* key, so users can move between GitHub and Izzy freely. Requirements, all already met: FOSS
license, APK attached to releases, no trackers or proprietary libraries, and Fastlane metadata in
the repo (`fastlane/metadata/android/en-US/`). To apply, open an inclusion request on IzzyOnDroid's
issue tracker with the repository URL.

### F-Droid main repository (after a stable release)
F-Droid builds the app **from source on its own servers** and, unless the build is reproducible,
signs it with **F-Droid's key**. Users then can't switch between the F-Droid build and the
GitHub/Izzy build without reinstalling. Steps:
1. Make sure a clean checkout builds with only `./gradlew :app:assembleRelease`. It does: no
   proprietary dependencies, and `dependenciesInfo` is disabled.
2. Open a merge request to [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding
   `metadata/io.github.lucabarbalata.anprox.yml`.
3. Optional, later: make the build reproducible so F-Droid can ship our signature.

### Google Play
Not planned (Q34). It would need a developer account, Play policy compliance, a privacy policy
page and Play App Signing.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Missing repository secrets: …" | Add the named secrets (step 3 above). |
| "Tag vX already exists" | That version was already released. Pick the next one. |
| "APK versionName is not X" | A stale build output. Re-run the workflow. |
| e2e step fails | Open the run's artifact (`e2e-output/`): failure screenshots, UI dumps and `logcat.txt` show what happened. |
| `apksigner` reports v1 signature missing | Expected. minSdk 26 uses APK Signature Scheme v2+ only. |
