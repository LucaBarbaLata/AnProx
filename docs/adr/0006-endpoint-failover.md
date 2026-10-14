# 0006. Ordered endpoint failover in an OkHttp interceptor

- Status: Accepted
- Date: 2026-10-14
- Questionnaire: Q13, Q14

## Context
Admins reach servers over the LAN, a VPN or a public reverse proxy (Q13), and want one server
entry with automatic failover (Q14).

## Decision
- A server has an ordered list of `Endpoint(url, label, headers)`.
- `EndpointSelector` tries the last working endpoint first, then the rest in configured order.
  The preference expires after 15 minutes and is reset on every network change (Wi-Fi ↔ mobile ↔
  VPN), so the primary (usually LAN) address is retried promptly.
- `FailoverInterceptor` rewrites the placeholder URL to `endpoint.url + /api2/json + path` (sub-paths
  supported), adds that endpoint's custom headers, and on an `IOException` moves on to the next endpoint.
- **Safety rule:** GET requests fail over on any I/O error. Non-GET requests fail over only on
  *connect-phase* errors (refused, unknown host, no route, connect timeout, TLS handshake), where
  the server cannot have received the request. A start/stop/delete is never sent twice.
- If every endpoint fails, the error lists each attempt (`ApiException.Unreachable`). If any
  attempt failed on certificate trust, that takes priority (`UntrustedCertificate`), because it
  is actionable.
- Connect timeout is 5 s, so a dead LAN address costs at most 5 s before the next is tried.

## Consequences
- Every API call, current and future, gets failover for free.
- When away from home, the first request after a network change may wait up to 5 s for the
  unreachable LAN address. Accepted. A future refinement could probe endpoints in parallel.
