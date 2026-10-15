// Builds the console pages' scripts into app/src/main/assets/console/ (committed, so the Android
// build doesn't need Node). Run after changing src/ or the pinned versions in package.json:
//   cd tools/console-web && npm ci && npm run build
// Target chrome80: the app refuses to open consoles on an older Android System WebView.
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';

const out = '../../app/src/main/assets/console';
mkdirSync(`${out}/licenses`, { recursive: true });

// noVNC 1.7 probes for H.264 support with a top-level await, which a classic script can't
// contain. Proxmox's VNC server doesn't send H.264, so the probe may finish in the background.
const noTopLevelAwait = {
    name: 'novnc-no-top-level-await',
    setup(b) {
        b.onLoad({ filter: /@novnc[\\/]novnc[\\/]core[\\/]util[\\/]browser\.js$/ }, (args) => {
            const source = readFileSync(args.path, 'utf8');
            const probe = 'supportsWebCodecsH264Decode = await _checkWebCodecsH264DecodeSupport();';
            if (!source.includes(probe)) throw new Error('noVNC changed: update the H.264 probe patch in build.mjs');
            return {
                contents: source.replace(probe,
                    '_checkWebCodecsH264DecodeSupport().then((s) => { supportsWebCodecsH264Decode = s; });'),
                loader: 'js',
            };
        });
    },
};

for (const page of ['terminal', 'vnc']) {
    await build({
        entryPoints: [`src/${page}.js`],
        outfile: `${out}/${page}.js`,
        bundle: true,
        format: 'iife',
        target: 'chrome80',
        minify: true,
        legalComments: 'eof',
        plugins: [noTopLevelAwait],
        logLevel: 'warning',
    });
}

copyFileSync('node_modules/@xterm/xterm/css/xterm.css', `${out}/xterm.css`);
copyFileSync('node_modules/@xterm/xterm/LICENSE', `${out}/licenses/xterm.js-MIT.txt`);
copyFileSync('node_modules/@novnc/novnc/LICENSE.txt', `${out}/licenses/noVNC-LICENSE.txt`);
for (const l of ['MPL-2.0', 'BSD-2-Clause', 'BSD-3-Clause']) {
    copyFileSync(`node_modules/@novnc/novnc/docs/LICENSE.${l}`, `${out}/licenses/noVNC-${l}.txt`);
}
copyFileSync('node_modules/@novnc/novnc/vendor/pako/LICENSE', `${out}/licenses/pako-MIT.txt`);
console.log(`Built terminal.js and vnc.js into ${out}`);
