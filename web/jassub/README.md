# JASSUB (vendored)

libass compiled to WebAssembly — the web player renders ASS/SSA tracks with it
instead of the server's WebVTT conversion, which drops positioning, styling and
typesetting.

- Upstream: https://github.com/ThaUnknown/jassub
- Version: 1.8.8 (npm `jassub@1.8.8`)
- Licence: LGPL-2.1-or-later and others — see `LICENSE` and `COPYRIGHT`.

The 1.x line is vendored on purpose: it ships a self-contained UMD bundle, so it
drops into a `<script>` tag. 2.x is ESM with npm dependencies and would need a
bundler, which the web app deliberately does not have.

Files, taken verbatim from the package's `dist/`:

| here                 | upstream                  |
| -------------------- | ------------------------- |
| `jassub.js`          | `dist/jassub.umd.js`      |
| `jassub-worker.js`   | `dist/jassub-worker.js`   |
| `jassub-worker.wasm` | `dist/jassub-worker.wasm` |
| `default.woff2`      | `dist/default.woff2`      |

`dist/jassub-worker.wasm.js` (the 3.7 MB asm.js fallback for browsers without
WebAssembly) and `dist/jassub-worker-modern.wasm` are not shipped.

To update: `npm pack jassub@1`, unpack, copy the four files above.
