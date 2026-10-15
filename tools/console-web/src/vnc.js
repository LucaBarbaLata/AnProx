// VM screen page: noVNC's RFB client. Instead of opening a WebSocket itself it gets a channel
// whose bytes the app carries over its own connection to Proxmox (see bridge.js).
import RFB from '@novnc/novnc';
import keysyms from '../node_modules/@novnc/novnc/core/input/keysymdef.js';
import { native, fromBase64, toBase64 } from './bridge.js';

/** The minimum of a WebSocket that noVNC's Websock.attach() needs. */
class BridgeChannel {
    constructor() {
        this.binaryType = 'arraybuffer';
        this.protocol = 'binary';
        this.readyState = 'connecting';
        this.onopen = null;
        this.onmessage = null;
        this.onclose = null;
        this.onerror = null;
    }

    send(data) {
        const bytes = data instanceof ArrayBuffer
            ? new Uint8Array(data)
            : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        native.send(toBase64(bytes));
    }

    close() {
        if (this.readyState === 'closed') return;
        this.readyState = 'closing';
        native.close();
    }
}

const screen = document.getElementById('screen');
let channel = null;
let rfb = null;

function key(keysym) {
    if (rfb && keysym) rfb.sendKey(keysym, null);
}

// The phone keyboard types into a hidden text field; each change becomes VNC key presses.
// Two placeholder characters let Backspace produce an input event even when "empty".
const PAD = '  ';
const input = document.getElementById('keyboard');
input.value = PAD;
input.addEventListener('input', () => {
    const value = input.value;
    if (value.length < PAD.length) {
        for (let i = value.length; i < PAD.length; i++) key(0xff08); // BackSpace
    } else {
        for (const ch of value.slice(PAD.length)) {
            key(ch === '\n' ? 0xff0d : keysyms.lookup(ch.codePointAt(0)));
        }
    }
    input.value = PAD;
});
input.addEventListener('keydown', (e) => {
    // Keys that don't produce text arrive only as keydown.
    const special = { Enter: 0xff0d, Tab: 0xff09, Escape: 0xff1b, ArrowUp: 0xff52, ArrowDown: 0xff54, ArrowLeft: 0xff51, ArrowRight: 0xff53 };
    if (special[e.key]) {
        key(special[e.key]);
        e.preventDefault();
    }
});

window.anprox = {
    /** The app opened the connection: create the client, then the bytes start flowing. */
    start(password) {
        channel = new BridgeChannel();
        rfb = new RFB(screen, channel, { credentials: { password }, shared: true });
        rfb.scaleViewport = true;
        rfb.resizeSession = false;
        rfb.showDotCursor = true;
        rfb.background = '#0b0f1a';
        rfb.addEventListener('connect', () => native.status('connected', ''));
        rfb.addEventListener('desktopname', (e) => native.status('desktop', e.detail.name || ''));
        rfb.addEventListener('disconnect', (e) => native.status('disconnected', e.detail.clean ? '' : 'error'));
        rfb.addEventListener('securityfailure', (e) => native.status('failed', e.detail.reason || 'Authentication failed'));
        rfb.addEventListener('credentialsrequired', () => rfb.sendCredentials({ password }));
    },
    opened() {
        channel.readyState = 'open';
        if (channel.onopen) channel.onopen(new Event('open'));
    },
    write(b64) {
        if (channel && channel.onmessage) channel.onmessage({ data: fromBase64(b64).buffer });
    },
    closed() {
        if (!channel || channel.readyState === 'closed') return;
        channel.readyState = 'closed';
        if (channel.onclose) channel.onclose({ code: 1000, reason: '', wasClean: true });
    },
    key,
    ctrlAltDel() {
        if (rfb) rfb.sendCtrlAltDel();
    },
    /** Shows the phone keyboard (the app calls showSoftInput right after). */
    focus() {
        input.focus();
    },
};

native.ready();
