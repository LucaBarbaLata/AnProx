// Terminal page: xterm.js. Keys go to the app (which wraps them in Proxmox's termproxy
// protocol); output comes back from the app as base64.
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { native, fromBase64 } from './bridge.js';

const container = document.getElementById('terminal');
const term = new Terminal({
    cursorBlink: true,
    fontFamily: 'monospace',
    fontSize: 14,
    scrollback: 5000,
    theme: { background: '#0b0f1a', foreground: '#e5e7eb', cursor: '#818cf8' },
});
const fit = new FitAddon();
term.loadAddon(fit);
term.open(container);

function refit() {
    try {
        fit.fit();
    } catch (e) {
        // not laid out yet
    }
}

window.addEventListener('resize', refit);
// Tapping the terminal shows the keyboard (the app also has a keyboard button).
container.addEventListener('click', () => term.focus());

term.onData((data) => native.input(data));
term.onResize(({ cols, rows }) => native.resize(cols, rows));

window.anprox = {
    /** The server accepted the ticket: report the size and take the focus. */
    connected() {
        refit();
        native.resize(term.cols, term.rows);
        term.focus();
    },
    write(b64) {
        term.write(fromBase64(b64));
    },
    focus() {
        term.focus();
    },
    closed(message) {
        term.write(`\r\n\x1b[90m${message}\x1b[0m\r\n`);
    },
};

refit();
native.ready();
