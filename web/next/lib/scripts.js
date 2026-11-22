// Loads a classic (non-module) script once. hls.js and JASSUB are big and only
// needed in the player, so they are fetched when it first opens.
const loaded = new Map();

export function loadScript(src) {
  if (!loaded.has(src)) {
    loaded.set(src, new Promise((resolve, reject) => {
      const el = document.createElement("script");
      el.src = src;
      el.async = true;
      el.onload = () => resolve();
      el.onerror = () => {
        loaded.delete(src);
        reject(new Error(`Could not load ${src}`));
      };
      document.head.append(el);
    }));
  }
  return loaded.get(src);
}
