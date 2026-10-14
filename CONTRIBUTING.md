# Contributing to AnProx

Thanks for helping! Bug reports, docs fixes and code are all welcome.

## Before you start

- Read [docs/architecture.md](docs/architecture.md) (layers and the "adding a feature" recipe)
  and [docs/design-system.md](docs/design-system.md).
- Check the [roadmap](docs/roadmap.md) and [API coverage](docs/api-coverage.md) to see where your
  change fits. For anything big, open an issue first.

## Ground rules

1. **No telemetry, no proprietary SDKs, no Google Play Services.** Every dependency must be
   GPL-3.0-compatible and acceptable to F-Droid ([ADR 0009](docs/adr/0009-foss-no-telemetry.md)).
2. **Every user-visible string goes in `app/src/main/res/values/strings.xml`.** Use typographic
   apostrophes (’), or escape plain ones (`\'`).
3. **Use the design system.** Use `AnProxTheme.colors.*` roles and the components in
   `:core:designsystem`, never raw colours. Status must never rely on colour alone.
4. **Destructive actions need tiered confirmation** (`DangerLevel`, `ConfirmActionDialog`).
5. **Pure-Kotlin modules must run on Android 8.0.** Don't use Java APIs newer than API 26 in
   `:core:*` (lint can't check those modules for you).
6. **Test against the mock.** New endpoints get mock-server support and an integration test.
7. **Docs ship with code.** Update `docs/api-coverage.md`, `docs/roadmap.md` and `CHANGELOG.md`,
   and add an ADR for significant decisions.

## Workflow

```bash
./gradlew test                 # JVM tests (must pass)
./gradlew :app:lintDebug       # Android lint (no errors)
./gradlew :app:assembleDebug   # builds the APK
```

- Code style: Kotlin official (`kotlin.code.style=official`), trailing commas, 4-space indent.
- Commits: imperative mood ("Add snapshot list"), with a body explaining *why* when it isn't obvious.
- Pull requests: describe the change, link the roadmap item, and add screenshots for UI changes
  (light and dark).

## Releases

Maintainers: see [docs/releasing.md](docs/releasing.md).

## Translations

Translation-ready from day one (Q16). To add a language, copy
`app/src/main/res/values/strings.xml` to `values-<code>/strings.xml` and translate the values,
keeping every `%1$s`/`%1$d` placeholder. A translation platform is planned for M9.

## Reporting security issues

Please use GitHub's private vulnerability reporting (Security tab), not public issues.
See [docs/security.md](docs/security.md).
