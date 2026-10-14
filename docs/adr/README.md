# Architecture Decision Records

An ADR captures one significant decision: the context, the options, what was chosen and the
consequences. ADRs are never rewritten. If a decision changes, a new ADR supersedes the old one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-native-kotlin-compose.md) | Native Kotlin + Jetpack Compose | Accepted |
| [0002](0002-toolchain-baseline.md) | Toolchain baseline: AGP 8.12, Kotlin 2.4, compileSdk 36 | Accepted |
| [0003](0003-koin.md) | Koin for dependency injection | Accepted |
| [0004](0004-module-structure.md) | Pure-Kotlin core modules, thin Android app | Accepted |
| [0005](0005-networking.md) | OkHttp + Retrofit + kotlinx.serialization | Accepted |
| [0006](0006-endpoint-failover.md) | Ordered endpoint failover in an OkHttp interceptor | Accepted |
| [0007](0007-insecure-tls-switch.md) | Per-server "allow insecure certificate" switch | Accepted |
| [0008](0008-credential-storage.md) | Ticket auth; secrets encrypted with an Android Keystore key | Accepted |
| [0009](0009-foss-no-telemetry.md) | GPL-3.0, no telemetry, no Google services | Accepted |
| [0010](0010-bespoke-design.md) | Bespoke "Midnight Glass" design instead of Material You | Accepted |
| [0011](0011-polling-while-visible.md) | Poll only while a screen is visible | Accepted |

Template for new ADRs:

```markdown
# NNNN. Title
- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD
- Questionnaire: Qx

## Context
## Options
## Decision
## Consequences
```
