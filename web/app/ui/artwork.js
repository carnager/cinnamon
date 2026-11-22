// Artwork: the decoded thumbhash paints the box immediately, the real image
// fades in over it once decoded. Nothing ever pops in from an empty box.
import { html, useState, useRef, useLayoutEffect, cx } from "../lib/html.js";
import { thumbhashDataURL } from "../lib/thumbhash.js";

export function imageURL(item, kind, width) {
  const id = kind === "backdrop" ? item?.backdropItemId || item?.id : item?.posterItemId || item?.id;
  const mtime = kind === "backdrop" ? item?.backdropMtimeUnix : item?.posterMtimeUnix;
  const version = encodeURIComponent(`${id}-${mtime || item?.mtimeUnix || 0}`);
  return `/api/items/${id}/image/${kind}?v=${version}${width ? `&w=${width}` : ""}`;
}

export function hasArtwork(item, kind) {
  if (!item) return false;
  if (kind === "backdrop") return Boolean(item.backdropPath || item.backdropItemId || item.backdropMtimeUnix);
  return Boolean(item.posterPath || item.posterItemId || item.posterMtimeUnix);
}

// kind: "poster" | "backdrop". width: server-side thumbnail width (omit for
// full size). eager: above-the-fold images skip lazy loading.
export function Artwork({ item, kind = "poster", width, eager = false, class: className, morph = false, alt = "" }) {
  const hash = kind === "backdrop" ? item?.backdropThumbhash : item?.posterThumbhash;
  const placeholder = thumbhashDataURL(hash);
  const src = hasArtwork(item, kind) ? imageURL(item, kind, width) : "";
  const [loaded, setLoaded] = useState(false);
  const imgRef = useRef(null);

  // An image already in the memory cache can finish before onLoad is wired up.
  useLayoutEffect(() => {
    setLoaded(Boolean(imgRef.current?.complete && imgRef.current.naturalWidth));
  }, [src]);

  return html`
    <div class=${cx("art", `art-${kind}`, loaded && "is-loaded", !src && !placeholder && "is-empty", className)}
         style=${placeholder ? `background-image:url(${placeholder})` : ""}
         data-morph=${morph ? "" : undefined}>
      ${src && html`<img ref=${imgRef} src=${src} alt=${alt} decoding="async"
          loading=${eager ? "eager" : "lazy"} fetchpriority=${eager ? "high" : "auto"}
          onLoad=${() => setLoaded(true)} onError=${() => setLoaded(false)} />`}
    </div>`;
}
