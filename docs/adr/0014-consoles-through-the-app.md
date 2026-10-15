# 0014. Consoles connect through the app; noVNC and xterm.js are bundled

- Status: Accepted (replaces the WebView-loads-Proxmox approach of 0.1.0-beta.2)
- Date: 2026-10-15
- Questionnaire: Q15

## Context
0.1.0-beta.2 opened Proxmox's own console pages (`/?console=kvm&novnc=1…`) in a WebView with the
session cookie set. On the owner's phone (Proxmox VE 8, self-signed certificate) the page loaded,
but noVNC reconnected in an endless loop and the node shell stayed empty without a keyboard. The
pages loaded, so the live WebSocket is what failed. The most likely cause is that the WebView's
"proceed despite the certificate" decision doesn't apply to WebSockets. Whatever the exact cause,
the WebView approach had two more structural gaps: custom per-address headers (reverse-proxy
auth) and address failover can't reach a WebSocket the page opens itself.

## Options
1. Keep loading Proxmox's pages and work around the WebView (install the server's CA on the
   phone, or ask users for a trusted certificate). Pushes the problem onto every user.
2. Proxy the whole web UI through a local HTTP server in the app. Heavy and fragile.
3. **The app talks to Proxmox itself** (`termproxy` / `vncproxy` for a ticket, then the
   `vncwebsocket` over its own OkHttp client) and bundles the two front ends: **xterm.js** for
   terminals and **noVNC** for the VM screen. Bytes cross a JavaScript bridge as base64.

## Decision
Option 3.
- `ConsoleRepository` (`:core:data`) opens the session; `ProxmoxConnection.openWebSocket` reuses
  the API client, so certificates, custom headers, failover and the session cookie behave exactly
  as for every API call. The terminal protocol (`user:ticket\n`, `0:len:data`, `1:cols:rows:`,
  ping `2`) is implemented in Kotlin (`TermProxyProtocol`); for VMs noVNC gets a channel object
  instead of a WebSocket and speaks RFB itself, with the password from `generate-password=1`
  (falling back to the ticket on older servers).
- The pages live in `app/src/main/assets/console/`, built by `tools/console-web/` (pinned npm
  versions, esbuild, one classic script per page, target Chrome 80). The built files are
  committed, so the Android build needs no Node. The WebView loads only these local files, blocks
  navigation and has no network access of its own.
- Phone keyboards: a native keyboard button focuses the page's input and shows the IME; a key
  row adds Esc, Tab, Ctrl+C/D and arrows (terminals) or Esc, Tab, arrows, Enter and Ctrl+Alt+Del
  (VM screen).
- Android System WebView older than 80 gets an explanation instead of a broken page.
- Rotation creates the page again. A live terminal keeps its session and the ViewModel replays
  the recent output (up to 256 KB); noVNC can't take over a running RFB session, so the VM screen
  reconnects.

## Consequences
- Works with self-signed certificates, reverse proxies with header auth, and several addresses.
- The mock server implements termproxy/vncproxy, a terminal and a minimal VNC server (RFB 3.8
  with VNC authentication), so JVM tests and the emulator tour cover the whole chain.
- Updating noVNC or xterm.js means rebuilding the assets (`npm ci && npm run build` in
  `tools/console-web/`). noVNC 1.7's top-level `await` (an H.264 probe) is rewritten at build
  time; the build fails loudly if that code changes.
- Third-party licenses ship with the assets (`assets/console/licenses/`): xterm.js (MIT), noVNC
  (MPL-2.0, with BSD-licensed parts) and pako (MIT).
