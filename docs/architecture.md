# Architecture

This document explains how AnProx is put together and *why*. It's written for someone learning
Android (Q39), so it explains concepts as it goes. Words in **bold** are explained in the
[glossary](glossary.md).

## The big picture

```
┌──────────────────────────────────── :app (Android) ────────────────────────────────────┐
│                                                                                          │
│  Screen (Compose)  ──user events──▶  ViewModel  ──calls──▶  Repository / ServerHub      │
│        ▲                               │                        (from :core:data)        │
│        └──────── StateFlow<UiState> ───┘                                                 │
│                                                                                          │
│  Android-only pieces: DataStoreProfileStore, KeystoreSecretStore, SettingsRepository,   │
│  Koin wiring (di/AppModule.kt), MainActivity, navigation                                │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │ uses
┌──────────────── :core:data (pure Kotlin) ─▼──────────────────────────────────────────────┐
│ ServerHub: one ProxmoxConnection per saved server, auth state, silent re-login          │
│ SnapshotRepository: polls cluster/backup-server state while a screen is visible          │
│ GuestRepository: power actions, task polling                                             │
│ ProfileStore / SecretStore: interfaces (implemented in :app)                             │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │ uses
┌──────────────── :core:network (pure Kotlin) ▼────────────────────────────────────────────┐
│ ProxmoxConnection: login + two-factor, ticket renewal, typed API calls, error mapping    │
│ OkHttp interceptors: FailoverInterceptor (endpoint failover + headers), AuthInterceptor  │
│ Retrofit interfaces: AccessApi, PveApi, PbsApi                                           │
└───────────────────────────────────────────┬──────────────────────────────────────────────┘
                                            │ uses
┌──────────────── :core:model (pure Kotlin) ─▼─────────────────────────────────────────────┐
│ @Serializable data classes mirroring the Proxmox JSON, lenient parsing, formatters,      │
│ UPID parser, TFA challenge parser, endpoint URL normalisation                            │
└──────────────────────────────────────────────────────────────────────────────────────────┘

:core:designsystem (Android) — Midnight Glass theme + components, used by :app
:tools:mock-server (pure Kotlin) — fake PVE/PBS API, used by tests and for manual testing
```

### Why this shape?

- **Most logic is pure Kotlin.** `:core:model`, `:core:network` and `:core:data` have no
  Android dependencies, so their tests run on a normal JVM in seconds, against a real HTTP
  server (the mock) rather than mocks of classes. That's where the risky logic lives:
  authentication, failover, error handling. ([ADR 0004](adr/0004-module-structure.md))
- **The UI layer is thin.** Screens only render state and forward clicks. ViewModels turn
  repository data into UI state. Nothing in the UI talks HTTP directly.
- **One direction of data.** Data flows *down* into the UI as `StateFlow`, and events flow *up*
  as function calls. This is called **unidirectional data flow (UDF)** and is what Google
  recommends for Compose apps.

## Modules

| Module | Type | Depends on | Purpose |
|---|---|---|---|
| `:core:model` | Kotlin/JVM | kotlinx.serialization | API data classes, parsing helpers, formatters |
| `:core:network` | Kotlin/JVM | model, OkHttp, Retrofit | HTTP, auth, failover, TLS, typed API |
| `:core:data` | Kotlin/JVM | network | Multi-server hub, repositories, storage interfaces |
| `:core:designsystem` | Android library | Compose, Material 3 | Theme, colours, type, components |
| `:app` | Android application | data, designsystem, AndroidX, Koin | Screens, ViewModels, DI, storage implementations |
| `:tools:mock-server` | Kotlin/JVM app | model, Ktor | Fake Proxmox for tests and demos |

Feature screens currently live as packages inside `:app` (`ui/dashboard`, `ui/guest`, …). As
the app grows (M2+), each feature can move into its own `:feature:*` module without changing
its code, because features only talk to `:core:*` APIs.

### JVM-only builds

The settings file supports `-Panprox.jvmOnly=true` (or `ANPROX_JVM_ONLY=true`), which builds
only the pure-Kotlin modules. It exists for environments that can't download the Android SDK
(see the [dev log](devlog.md)). Android Studio users never need it.

## Layer by layer

### 1. Model (`:core:model`)

Proxmox answers every request with JSON wrapped in `{"data": …}`. Each response type is a
Kotlin `data class` annotated `@Serializable`, and the **kotlinx.serialization** compiler plugin
generates the parsing code at build time (no reflection).

Proxmox's JSON is loose: booleans come as `0`/`1`, numbers sometimes arrive as strings, and new
fields appear between versions. `ProxmoxJson` (one shared configuration) and
`PveBooleanSerializer` absorb all of that, so the rest of the code sees clean types. The tests in
`JsonParsingTest` pin this behaviour down.

Pure helpers also live here:
- `Formatters`: bytes (KiB/MiB/GiB like the web UI), percentages, durations.
- `Upid.parse`: decodes task IDs such as `UPID:pve1:…:qmstart:100:root@pam:`.
- `TfaChallenge.fromTicket`: decodes the second-factor challenge embedded in a partial ticket.
- `EndpointUrl.normalize`: turns "192.168.1.10" into `https://192.168.1.10:8006`.

### 2. Network (`:core:network`)

`ProxmoxConnection` is everything needed to talk to **one** saved server:

```
ProxmoxConnection
 ├─ OkHttpClient (per server: its TLS setting + interceptors, shared connection pool)
 │    ├─ User-Agent interceptor
 │    ├─ FailoverInterceptor   rewrites the placeholder URL to a real endpoint, adds that
 │    │                        endpoint's custom headers, tries the next endpoint on failure
 │    └─ AuthInterceptor       adds the ticket cookie, and the CSRF header on writes
 ├─ Retrofit (AccessApi, PveApi, PbsApi)  — typed endpoint definitions
 ├─ session: AuthSession?     — ticket + CSRF token, renewed after 1 h (tickets live 2 h)
 └─ execute { … }             — renew if needed → call → map every failure to ApiException
```

Key ideas:

- **Retrofit** turns an interface like
  `@GET("nodes/{node}/status") suspend fun nodeStatus(@Path("node") node: String): ApiEnvelope<NodeStatus>`
  into a working HTTP call. The interfaces double as documentation of which endpoints are used.
- **Interceptors** are OkHttp's middleware. Each one sees every request and can change it or
  retry it. Failover lives in an interceptor so that *every* call gets it for free.
- **Failover is careful.** GET requests move to the next endpoint on any network error. A POST
  (for example "start VM") only fails over if the error happened while *connecting*, which
  guarantees the server never received it. An action is never executed twice.
  ([ADR 0006](adr/0006-endpoint-failover.md))
- **Typed errors.** Everything that can go wrong becomes an `ApiException`: `Unreachable` (with
  every endpoint tried), `UntrustedCertificate`, `Unauthorized`, `Forbidden`, `Validation` (with
  per-field messages), `Server` (with Proxmox's reason) or `Protocol`. The UI maps each one to a
  precise message (`ui/common/UiText.kt`).
- **Two-factor login.** `login(password)` returns either `Success` or `NeedsTfa(partialTicket, challenge)`.
  `completeTfa(partialTicket, kind, code)` finishes the login. ([Security](security.md#authentication))

### 3. Data (`:core:data`)

- **`ServerHub`**: the heart of multi-server support (Q6). It watches the saved profiles,
  keeps one `ProxmoxConnection` per server, restores saved sessions on start-up, persists
  renewed ones, and exposes `servers: StateFlow<List<ServerState>>` with each server's
  `AuthState` (`LOGGED_IN`, `LOGGED_OUT`, `SESSION_EXPIRED`). Every API call from the app goes
  through `hub.use(serverId) { connection -> … }`. If the server says the session expired and the
  password is remembered, `use` signs in again silently and retries once.
- **`SnapshotRepository`**: produces `Flow`s that **poll** the servers. Polling runs *only while a
  screen collects the flow*, so the app does no network work in the background. A failed refresh
  keeps the last good data and marks it stale, so the UI can show "data from 2 min ago".
- **`GuestRepository`**: power actions and task polling for one guest.
- **`ProfileStore` / `SecretStore`**: storage *interfaces*. `:app` implements them with
  Android APIs, and tests use in-memory versions. This is **dependency inversion**: the data
  layer defines what it needs and the platform layer provides it.

### 4. App (`:app`)

- **Screens** are `@Composable` functions (`DashboardScreen`, `GuestDetail`, …). They read state
  with `collectAsStateWithLifecycle()`, which also stops collecting when the app goes to the
  background, and that stops polling.
- **ViewModels** survive screen rotation, hold UI state in a `StateFlow`, and launch work in
  `viewModelScope`, which is cancelled automatically when the screen is closed.
- **Navigation** uses Navigation Compose with **type-safe routes**: each destination is a
  `@Serializable` class (`ui/navigation/Routes.kt`) and its properties are the arguments.
- **Dependency injection** uses **Koin** (`di/AppModule.kt`). `single { … }` creates one instance for
  the app. `viewModel { (serverId: String) -> … }` creates a ViewModel per screen, with
  parameters passed via `koinViewModel { parametersOf(id) }`. ([ADR 0003](adr/0003-koin.md))
- **Storage**: `DataStoreProfileStore` (profiles as JSON in Jetpack DataStore),
  `KeystoreSecretStore` (passwords and session tickets encrypted with an AES-256-GCM key that
  lives in the Android Keystore), and `SettingsRepository` (theme and refresh rate).
- **Network changes**: `AnProxApplication` listens for Wi-Fi/mobile/VPN switches and calls
  `hub.onNetworkChanged()`, so each server tries its primary (LAN) address first again.

### 5. Design system (`:core:designsystem`)

`AnProxTheme` provides the Midnight Glass colour tokens (`AnProxTheme.colors.accent` …), Inter
typography and shapes, and maps them onto Material 3 so stock components (dialogs, text fields)
match. Reusable components: `GlassBackground`, `GlassCard`, `UsageRing`, `UsageBar`,
`StatusPill`, `MetricTile`, `GlassTextField`, `SwitchRow`, `SegmentedPicker`, `AnProxButton`,
`AdaptiveNavScaffold` (bottom bar on phones, side rail on tablets). See [design-system.md](design-system.md).

## Walkthrough: what happens when you tap "Start" on a VM

1. `GuestDetail` shows the Start button from `state.availableActions`, and tapping it calls
   `viewModel.request(PowerAction.START)`.
2. `GuestViewModel.request` sees that `START` is `DangerLevel.SAFE` (no confirmation needed) and calls `run`.
   (For STOP it would set `confirm = STOP`, and the screen shows `ConfirmActionDialog`.)
3. `run` sets `running = START` (the button shows a spinner) and launches a coroutine:
   `guests.power(ref, START)` → `hub.use(serverId) { it.pve { guestPower(node, "qemu", 100, "start", …) } }`.
4. `ProxmoxConnection.execute` renews the ticket if it's older than an hour, then Retrofit builds
   `POST https://anprox.invalid/nodes/pve1/qemu/100/status/start`.
5. `FailoverInterceptor` rewrites it to `https://192.168.1.10:8006/api2/json/nodes/…` (or the next
   address if that one can't be reached) and adds the address's custom headers. `AuthInterceptor`
   adds `Cookie: PVEAuthCookie=…` and `CSRFPreventionToken: …`.
6. Proxmox answers `{"data": "UPID:pve1:…:qmstart:100:root@pam:"}`, which is the task ID.
7. `guests.awaitTask` polls `GET /nodes/pve1/tasks/{upid}/status` every second until it stops.
8. The ViewModel sets `result = "VM start finished"`, and the screen shows a snackbar with
   **View log**. Meanwhile `observeStatus` (polling every 3 s) picks up `status: running`, and
   the gauges come alive.

## Threading and coroutines

- Retrofit `suspend` functions run network I/O on OkHttp's own threads, so callers don't need
  `Dispatchers.IO`.
- DataStore and Keystore work runs on `Dispatchers.IO` (`KeystoreSecretStore`).
- Long-lived app work (`ServerHub`) runs in an app-wide `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- Screens never block: everything they show comes from `StateFlow`.

## Error handling, end to end

```
IOException / HttpException / SerializationException      (OkHttp, Retrofit, kotlinx)
        │  ProxmoxConnection.translateErrors
        ▼
ApiException.{Unreachable|UntrustedCertificate|Unauthorized|Forbidden|NotFound|Validation|Server|Protocol}
        │  ServerHub.use → silent re-login on Unauthorized (if password remembered)
        ▼
ViewModel → Throwable.toUiText()  → string resource with details
        ▼
Screen → ErrorBanner / snackbar   (certificate errors offer a one-tap "Allow" fix)
```

## Recipe: adding a new feature

Worked example: snapshots (M1). Every step below exists in the code, so you can follow it.

1. **Model**: `@Serializable data class GuestSnapshot(...)` plus helpers in `:core:model`
   (`pve/Snapshot.kt`), with a parsing test using real JSON from the Proxmox API viewer
   (`SnapshotTest`).
2. **API**: `@GET("nodes/{node}/{type}/{vmid}/snapshot") suspend fun snapshots(...)` and friends
   in `PveApi`.
3. **Mock**: `MockState` keeps per-guest snapshot trees; `MockProxmoxServer` routes the endpoints.
4. **Test**: an integration test against the mock (`GuestSnapshotTest` in `:core:data`). Run
   `./gradlew -Panprox.jvmOnly=true test`.
5. **Repository**: `snapshots(ref)`, `createSnapshot(...)`, … in `GuestRepository` (a new
   repository would be wired in `di/AppModule.kt`).
6. **ViewModel and screen**: state class, ViewModel and composable in `ui/<feature>/`
   (`ui/snapshots/`). Use design system components and put **every** user-visible string in
   `res/values/strings.xml`.
7. **Navigation**: a `@Serializable` route in `Routes.kt` (`SnapshotsRoute`) and a
   `composable<…>` in `AnProxApp.kt`. Add a step to `scripts/e2e/tour.py` for the main flow.
8. **Docs**: tick the endpoint in [api-coverage.md](api-coverage.md), update the
   [roadmap](roadmap.md) and [CHANGELOG](../CHANGELOG.md).

## Further reading

- [Guide to app architecture (developer.android.com)](https://developer.android.com/topic/architecture)
- [Thinking in Compose](https://developer.android.com/develop/ui/compose/mental-model)
- [Kotlin coroutines and Flow](https://kotlinlang.org/docs/flow.html)
- [Proxmox VE API viewer](https://pve.proxmox.com/pve-docs/api-viewer/) and
  [Proxmox Backup Server API viewer](https://pbs.proxmox.com/docs/api-viewer/)
