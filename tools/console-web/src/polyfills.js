// Small polyfills for Android System WebView versions between 80 (the minimum the app accepts)
// and the versions xterm.js and noVNC are tested on. Imported first by both pages.

function define(proto, name, value) {
    if (proto && !proto[name]) {
        Object.defineProperty(proto, name, { value, configurable: true, writable: true });
    }
}

// Array/TypedArray .at() (Chrome 92): noVNC's Hextile and JPEG decoders use it.
function at(index) {
    let n = Math.trunc(index) || 0;
    if (n < 0) n += this.length;
    return n < 0 || n >= this.length ? undefined : this[n];
}
define(Array.prototype, 'at', at);
define(String.prototype, 'at', at);
define(Object.getPrototypeOf(Int8Array.prototype), 'at', at);

// String.prototype.replaceAll (Chrome 85).
define(String.prototype, 'replaceAll', function replaceAll(search, replacement) {
    if (search instanceof RegExp) return this.replace(search, replacement);
    return this.split(String(search)).join(String(replacement));
});

// WeakRef (Chrome 84): xterm.js keeps windows in one; a strong reference is fine for a page
// that lives as long as the console screen.
if (typeof window.WeakRef === 'undefined') {
    window.WeakRef = class WeakRef {
        constructor(target) { this._target = target; }
        deref() { return this._target; }
    };
}
