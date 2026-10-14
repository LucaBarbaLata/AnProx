# Getting started

A step-by-step guide from zero to running AnProx, written for someone new to Android
development (Q39). If something here doesn't work, open an issue. Fixing the docs counts as a
real contribution.

## 1. Install the tools

| Tool | Version | Notes |
|---|---|---|
| **Android Studio** | latest stable | [developer.android.com/studio](https://developer.android.com/studio). It bundles a JDK (Java 21) and the Android SDK manager. |
| **Git** | any recent | To clone the repository. |
| A phone with Android 8.0+ *or* an emulator | — | Android Studio → Device Manager → "Create device" (pick a Pixel and a recent system image). |

The first time Android Studio opens, let it download the Android SDK (it asks). The project
compiles against **Android 16 (API 36)**, and Studio installs that automatically when you sync.

## 2. Get the code

```bash
git clone https://github.com/LucaBarbaLata/AnProx.git
cd AnProx
```

Open the folder in Android Studio (**File → Open**). Studio runs a **Gradle sync**, which
downloads every library listed in `gradle/libs.versions.toml`. The first sync takes a few minutes.

> **What is Gradle?** It's the build tool. `settings.gradle.kts` lists the modules, each
> `build.gradle.kts` says how to build one module, and `./gradlew` is a wrapper script that
> downloads the exact Gradle version the project expects. See [architecture.md](architecture.md#modules).

## 3. Run the app

1. Pick a device in the toolbar (your emulator, or your phone connected by USB with
   [USB debugging](https://developer.android.com/studio/debug/dev-options) on).
2. Choose the `app` run configuration and press **Run ▶**.

The debug build installs as **AnProx** with package `io.github.lucabarbalata.anprox.debug`, so
it can sit next to a future release build.

From the command line instead:

```bash
./gradlew :app:installDebug      # build and install on the connected device
```

## 4. Give it something to talk to: the mock server

You don't need a real Proxmox server to explore. In a terminal:

```bash
./gradlew :tools:mock-server:run
```

Then, in the app, tap **Add your first server**:

| Field | Value |
|---|---|
| Server type | Proxmox VE |
| Host or URL | `10.0.2.2` (emulator) or your computer's LAN IP (real phone) |
| User | `root@pam` |
| Allow insecure certificate | **on** (the mock uses a self-signed certificate, like a fresh Proxmox install) |

Sign in with the password `anprox`. Try `admin@pve` for the two-factor flow (code `123456`).
Add a second server at port `8007` with type **Proxmox Backup Server** to see the combined
dashboard. All accounts and the fake data are listed in [testing.md](testing.md#the-mock-server-toolsmock-server).

## 5. Run the tests

```bash
./gradlew test
```

In Android Studio you can also right-click a test file (for example
`core/network/src/test/.../ProxmoxConnectionTest.kt`) and choose **Run**. These tests start the
mock server on a random port and talk to it over real HTTPS.

## 6. Make a change

A good first exercise is to add a field to the guest detail screen:

1. Find the data. `GuestStatus` in `core/model/.../pve/GuestStatus.kt` mirrors the API response.
2. Show it. In `app/.../ui/guest/GuestScreen.kt`, the `Details` composable lists `InfoRow`s, so add one.
3. Add the label to `app/src/main/res/values/strings.xml`. Never hard-code user-visible text.
4. Run the app. With **Live Edit** / Apply Changes, many Compose edits appear without a restart.

For a full feature, follow the recipe in [architecture.md](architecture.md#recipe-adding-a-new-feature).

## 7. Installing builds from CI (no Android Studio needed)

Every push runs GitHub Actions. On the repository's **Actions** tab, open the latest green
**CI** run and download **anprox-debug-apk**. Unzip it and open the `.apk` on your phone (you
need to allow installing unknown apps for your browser or file manager).

## Troubleshooting

| Problem | Fix |
|---|---|
| Sync fails with "SDK location not found" | Open **SDK Manager** once so Studio writes `local.properties`. |
| `Unsupported class file major version` | Use the JDK bundled with Android Studio (Settings → Build Tools → Gradle → Gradle JDK). |
| App can't reach the mock server from the emulator | Use `10.0.2.2`, not `localhost`. `localhost` inside the emulator is the emulator itself. |
| Phone can't reach the mock server | Same Wi-Fi? Firewall allowing ports 8006/8007? Use your computer's LAN IP. |
| "The server's certificate isn't trusted" | Expected with self-signed certificates: tap **Allow** or enable the switch in the server editor. |
| Building in a sandbox without the Android SDK | `./gradlew -Panprox.jvmOnly=true test` builds and tests only the pure-Kotlin modules. |
