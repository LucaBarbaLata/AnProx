# 0009. GPL-3.0, no telemetry, no Google services

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q33, Q34, Q35, Q36

## Decision
- License: **GPL-3.0** (Q33).
- Distribution: **GitHub Releases** (signed APK, Obtainium-friendly) and **F-Droid/IzzyOnDroid** (Q34).
- Business model: free, with optional donations (Q35).
- **No telemetry** of any kind (Q36). No Firebase, no Google Play Services, no proprietary SDKs.
  Fonts are bundled rather than fetched from Google Fonts. Push notifications (M3) will use
  UnifiedPush/ntfy/Gotify, not FCM.
- Debug builds use the `.debug` application ID suffix so they can coexist with release builds.
- `dependenciesInfo` is excluded from APKs/bundles (an F-Droid requirement).

## Consequences
- No crash dashboards. Bugs come from user reports, so a local, user-exportable crash log is
  planned (M1).
- Every dependency must be FOSS-compatible. Check licenses before adding a library.
