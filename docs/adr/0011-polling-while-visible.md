# 0011. Poll only while a screen is visible

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q25, Q26

## Context
Proxmox has no push channel for live state. The web UI polls `/cluster/resources` every few
seconds. Mobile devices need to protect battery and data.

## Decision
- Live data comes from cold `Flow`s in `SnapshotRepository` / `GuestRepository` that poll only
  while collected. Screens collect with `collectAsStateWithLifecycle`, so polling stops when the
  app goes to the background, and ViewModels share flows with `WhileSubscribed(5 s)` so rotation
  doesn't restart them.
- The interval is user-configurable (2/5/10/30 s, default 5 s). Guest and node detail screens poll
  every 3 s, task logs every 2 s until the task ends.
- A failed refresh keeps the last good snapshot and marks it stale.
- Background monitoring (M3) is a separate, opt-in WorkManager job (about every 15 minutes) plus
  optional real-time push. It won't reuse foreground polling.

## Consequences
- Zero network use in the background in M0.
- Only the visible screen polls, so at any moment there is at most one `/cluster/resources` call per server per interval.
