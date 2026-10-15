// The native side of the console screen (ConsoleScreen.kt) and these pages talk through:
//  - window.AnProxNative: a JavascriptInterface (ready, input, resize, send, close, status);
//  - window.anprox: functions the app calls with evaluateJavascript.
// Binary data crosses the bridge as base64.

export const native = window.AnProxNative || {
    // Opened in a desktop browser for development: log instead of crashing.
    ready() { console.log('native.ready'); },
    input(text) { console.log('native.input', JSON.stringify(text)); },
    resize(cols, rows) { console.log('native.resize', cols, rows); },
    send(b64) { console.log('native.send', b64.length); },
    close() { console.log('native.close'); },
    status(state, detail) { console.log('native.status', state, detail); },
};

export function fromBase64(b64) {
    const binary = atob(b64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
}

export function toBase64(bytes) {
    let binary = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
}
