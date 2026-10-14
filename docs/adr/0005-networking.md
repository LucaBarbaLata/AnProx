# 0005. OkHttp + Retrofit + kotlinx.serialization

- Status: Accepted
- Date: 2026-10-14

## Context
Proxmox exposes a large REST API with loosely typed JSON, uses cookies plus a CSRF header, and
will later need WebSockets (noVNC, xterm.js, Q15).

## Options
1. **OkHttp + Retrofit**: the de-facto Android standard, with interceptors and WebSockets. Interfaces document the API.
2. Ktor client: Kotlin-first and multiplatform. It would also work well, but has fewer Android learning resources.

## Decision
OkHttp 5 for transport, interceptors and (later) WebSockets. Retrofit 3 for declarative
endpoint interfaces (`AccessApi`, `PveApi`, `PbsApi`). kotlinx.serialization with a lenient
shared configuration (`ProxmoxJson`) and `PveBooleanSerializer` for `0`/`1` booleans.

Retrofit is built against a placeholder base URL. `FailoverInterceptor` rewrites each request to
the real endpoint (ADR 0006).

## Consequences
- Each endpoint is one annotated function, so coverage is easy to audit (docs/api-coverage.md).
- Dynamic config objects (VM `config` with `net0`, `scsi1`…) will use `JsonObject` plus typed
  parsers in M2.
- R8 keep rules for the model package are in `app/proguard-rules.pro`. CI builds a minified
  release on every push to catch problems.
