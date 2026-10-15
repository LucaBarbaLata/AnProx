# Console pages (xterm.js and noVNC)

The VM screen and the terminal in the app are two small local web pages built from here into
`app/src/main/assets/console/` (see [ADR 0014](../../docs/adr/0014-consoles-through-the-app.md)).
The built files are committed, so building the Android app doesn't need Node.

| File | What |
|---|---|
| `src/terminal.js` | xterm.js + fit addon; keys go to the app, output comes back from it |
| `src/vnc.js` | noVNC's RFB client on a bridge channel instead of a WebSocket; hidden input for the phone keyboard |
| `src/bridge.js` | base64 helpers and the `AnProxNative` interface (a logging stand-in in a desktop browser) |
| `build.mjs` | esbuild: one classic script per page, target Chrome 80, licenses copied next to them |

Rebuild after changing `src/` or the versions in `package.json`:

```bash
cd tools/console-web
npm ci
npm run build
```

The app talks to Proxmox; the pages never do. `ConsoleScreen.kt` calls `window.anprox.*`
(`connected`, `start`, `opened`, `write`, `closed`, `key`, `ctrlAltDel`, `focus`) and the pages
call `AnProxNative` (`ready`, `input`, `resize`, `send`, `close`, `status`).
