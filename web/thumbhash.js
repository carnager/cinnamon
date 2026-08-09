/* ThumbHash decoding — Evan Wallace's reference implementation
   (https://github.com/evanw/thumbhash, MIT), trimmed to the decode half and
   adapted from an ES module to a plain script so it loads alongside the rest
   of the UI without a build step.

   The server sends a ~25-byte base64 thumbhash with every item that has
   artwork. Decoding it yields a tiny blurry PNG that can be painted instantly
   as a placeholder, so posters fade in over roughly the right colours instead
   of popping out of empty grey boxes.

   Only the decoder lives here; encoding happens server-side in Go
   (internal/media/thumbhash.go), and that side is pinned byte-for-byte against
   this same reference implementation. */

function thumbHashToRGBA(hash) {
  const { PI, min, max, cos, round } = Math;

  const header24 = hash[0] | (hash[1] << 8) | (hash[2] << 16);
  const header16 = hash[3] | (hash[4] << 8);
  const l_dc = (header24 & 63) / 63;
  const p_dc = ((header24 >> 6) & 63) / 31.5 - 1;
  const q_dc = ((header24 >> 12) & 63) / 31.5 - 1;
  const l_scale = ((header24 >> 18) & 31) / 31;
  const hasAlpha = header24 >> 23;
  const p_scale = ((header16 >> 3) & 63) / 63;
  const q_scale = ((header16 >> 9) & 63) / 63;
  const isLandscape = header16 >> 15;
  const lx = max(3, isLandscape ? (hasAlpha ? 5 : 7) : header16 & 7);
  const ly = max(3, isLandscape ? header16 & 7 : hasAlpha ? 5 : 7);
  const a_dc = hasAlpha ? (hash[5] & 15) / 15 : 1;
  const a_scale = (hash[5] >> 4) / 15;

  /* Saturation is boosted 1.25x to compensate for quantization. */
  const ac_start = hasAlpha ? 6 : 5;
  let ac_index = 0;
  const decodeChannel = (nx, ny, scale) => {
    const ac = [];
    for (let cy = 0; cy < ny; cy++) {
      for (let cx = cy ? 0 : 1; cx * ny < nx * (ny - cy); cx++) {
        ac.push(((((hash[ac_start + (ac_index >> 1)] >> ((ac_index++ & 1) << 2)) & 15) / 7.5) - 1) * scale);
      }
    }
    return ac;
  };
  const l_ac = decodeChannel(lx, ly, l_scale);
  const p_ac = decodeChannel(3, 3, p_scale * 1.25);
  const q_ac = decodeChannel(3, 3, q_scale * 1.25);
  const a_ac = hasAlpha ? decodeChannel(5, 5, a_scale) : null;

  const ratio = thumbHashToApproximateAspectRatio(hash);
  const w = round(ratio > 1 ? 32 : 32 * ratio);
  const h = round(ratio > 1 ? 32 / ratio : 32);
  const rgba = new Uint8Array(w * h * 4);
  const fx = [];
  const fy = [];

  for (let y = 0, i = 0; y < h; y++) {
    for (let x = 0; x < w; x++, i += 4) {
      let l = l_dc;
      let p = p_dc;
      let q = q_dc;
      let a = a_dc;

      for (let cx = 0, n = max(lx, hasAlpha ? 5 : 3); cx < n; cx++) {
        fx[cx] = cos((PI / w) * (x + 0.5) * cx);
      }
      for (let cy = 0, n = max(ly, hasAlpha ? 5 : 3); cy < n; cy++) {
        fy[cy] = cos((PI / h) * (y + 0.5) * cy);
      }

      for (let cy = 0, j = 0; cy < ly; cy++) {
        for (let cx = cy ? 0 : 1, fy2 = fy[cy] * 2; cx * ly < lx * (ly - cy); cx++, j++) {
          l += l_ac[j] * fx[cx] * fy2;
        }
      }
      for (let cy = 0, j = 0; cy < 3; cy++) {
        for (let cx = cy ? 0 : 1, fy2 = fy[cy] * 2; cx < 3 - cy; cx++, j++) {
          const f = fx[cx] * fy2;
          p += p_ac[j] * f;
          q += q_ac[j] * f;
        }
      }
      if (hasAlpha) {
        for (let cy = 0, j = 0; cy < 5; cy++) {
          for (let cx = cy ? 0 : 1, fy2 = fy[cy] * 2; cx < 5 - cy; cx++, j++) {
            a += a_ac[j] * fx[cx] * fy2;
          }
        }
      }

      const b = l - (2 / 3) * p;
      const r = (3 * l - b + q) / 2;
      const g = r - q;
      rgba[i] = max(0, 255 * min(1, r));
      rgba[i + 1] = max(0, 255 * min(1, g));
      rgba[i + 2] = max(0, 255 * min(1, b));
      rgba[i + 3] = max(0, 255 * min(1, a));
    }
  }
  return { w, h, rgba };
}

function thumbHashToAverageRGBA(hash) {
  const { min, max } = Math;
  const header = hash[0] | (hash[1] << 8) | (hash[2] << 16);
  const l = (header & 63) / 63;
  const p = ((header >> 6) & 63) / 31.5 - 1;
  const q = ((header >> 12) & 63) / 31.5 - 1;
  const hasAlpha = header >> 23;
  const a = hasAlpha ? (hash[5] & 15) / 15 : 1;
  const b = l - (2 / 3) * p;
  const r = (3 * l - b + q) / 2;
  const g = r - q;
  return { r: max(0, min(1, r)), g: max(0, min(1, g)), b: max(0, min(1, b)), a };
}

function thumbHashToApproximateAspectRatio(hash) {
  const header = hash[3];
  const hasAlpha = hash[2] & 0x80;
  const isLandscape = hash[4] & 0x80;
  const lx = isLandscape ? (hasAlpha ? 5 : 7) : header & 7;
  const ly = isLandscape ? header & 7 : hasAlpha ? 5 : 7;
  return lx / ly;
}

/* Writes an uncompressed PNG by hand. Deliberately not size-optimised — the
   images are at most 32px on a side, and this avoids pulling in any encoder. */
function rgbaToDataURL(w, h, rgba) {
  const row = w * 4 + 1;
  const idat = 6 + h * (5 + row);
  const bytes = [
    137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0,
    w >> 8, w & 255, 0, 0, h >> 8, h & 255, 8, 6, 0, 0, 0, 0, 0, 0, 0,
    idat >>> 24, (idat >> 16) & 255, (idat >> 8) & 255, idat & 255,
    73, 68, 65, 84, 120, 1,
  ];
  const table = [
    0, 498536548, 997073096, 651767980, 1994146192, 1802195444, 1303535960,
    1342533948, -306674912, -267414716, -690576408, -882789492, -1687895376,
    -2032938284, -1609899400, -1111625188,
  ];
  let a = 1;
  let b = 0;
  for (let y = 0, i = 0, end = row - 1; y < h; y++, end += row - 1) {
    bytes.push(y + 1 < h ? 0 : 1, row & 255, row >> 8, ~row & 255, (row >> 8) ^ 255, 0);
    for (b = (b + a) % 65521; i < end; i++) {
      const u = rgba[i] & 255;
      bytes.push(u);
      a = (a + u) % 65521;
      b = (b + a) % 65521;
    }
  }
  bytes.push(b >> 8, b & 255, a >> 8, a & 255, 0, 0, 0, 0, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130);
  for (const [start, rangeEnd] of [[12, 29], [37, 41 + idat]]) {
    let c = ~0;
    for (let i = start; i < rangeEnd; i++) {
      c ^= bytes[i];
      c = (c >>> 4) ^ table[c & 15];
      c = (c >>> 4) ^ table[c & 15];
    }
    c = ~c;
    let end = rangeEnd;
    bytes[end++] = c >>> 24;
    bytes[end++] = (c >> 16) & 255;
    bytes[end++] = (c >> 8) & 255;
    bytes[end++] = c & 255;
  }
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return "data:image/png;base64," + btoa(binary);
}

/* ── Cinnamon glue ──────────────────────────────────────────────── */

/* Header bytes every hash carries: three for the packed 24-bit header, two
   for the 16-bit one. The alpha DC byte at index 5 is only read when the
   header's alpha bit is set. */
const THUMBHASH_MIN_BYTES = 5;

const thumbhashURLCache = new Map();
const thumbhashColorCache = new Map();

function thumbhashBytes(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/* thumbhashDataURL turns the server's base64 thumbhash into a PNG data URL.
   Results are cached because a show poster's hash is shared by every one of
   its episodes, and decoding runs on the main thread. Returns "" for a missing
   or malformed hash so callers can fall back to a flat background. */
function thumbhashDataURL(base64) {
  if (!base64) return "";
  const cached = thumbhashURLCache.get(base64);
  if (cached !== undefined) return cached;
  let url = "";
  try {
    const bytes = thumbhashBytes(base64);
    /* The decoder indexes the first five bytes unconditionally; anything
       shorter is a truncated or corrupt hash and would decode to noise rather
       than fail loudly. Better to show no placeholder than a wrong one. */
    if (bytes.length >= THUMBHASH_MIN_BYTES) {
      const image = thumbHashToRGBA(bytes);
      url = rgbaToDataURL(image.w, image.h, image.rgba);
    }
  } catch {
    url = "";
  }
  thumbhashURLCache.set(base64, url);
  return url;
}

/* thumbhashAverageColor returns the hash's average colour as an rgb() string,
   for tinting surfaces behind artwork. Returns "" when unavailable. */
function thumbhashAverageColor(base64) {
  if (!base64) return "";
  const cached = thumbhashColorCache.get(base64);
  if (cached !== undefined) return cached;
  let color = "";
  try {
    const bytes = thumbhashBytes(base64);
    if (bytes.length >= THUMBHASH_MIN_BYTES) {
      const { r, g, b } = thumbHashToAverageRGBA(bytes);
      color = `rgb(${Math.round(r * 255)} ${Math.round(g * 255)} ${Math.round(b * 255)})`;
    }
  } catch {
    color = "";
  }
  thumbhashColorCache.set(base64, color);
  return color;
}
