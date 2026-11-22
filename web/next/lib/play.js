// Playback entry point: every Play button comes through here. The player is a
// route (/watch/<id>), so Back closes it and returns to the page it came from.
import { navigate } from "./router.js";
import { seed } from "./cache.js";

// How the next player mount should start. Kept out of the URL so reloading a
// player page resumes from the saved position instead of repeating "from start".
let pending = null;
let openedInApp = false;

// tracks: optional { audioIndex, subtitleIndex } chosen on the detail page.
export function playItem(item, { fromStart = false, replace = false, instant = false, tracks = null } = {}) {
  if (!item?.id) return;
  seed(`item:${item.id}`, item);
  pending = { itemId: String(item.id), startMs: fromStart ? 0 : null, ...(tracks || {}) };
  openedInApp = true;
  navigate(`/watch/${encodeURIComponent(item.id)}`, { replace, instant });
}

export function playTrailer(item) {
  if (!item?.id) return;
  seed(`item:${item.id}`, item);
  pending = null;
  openedInApp = true;
  navigate(`/watch/${encodeURIComponent(item.id)}/trailer`);
}

// A title without a local trailer: search for one, as the classic client did.
export function searchTrailer(item) {
  const query = [item.title, item.year, "trailer"].filter(Boolean).join(" ");
  window.open(`https://www.youtube.com/results?search_query=${encodeURIComponent(query)}`, "_blank", "noopener");
}

// The start options requested for this item, once. startMs null means resume.
export function takeStart(itemId) {
  const request = pending;
  pending = null;
  if (!request || request.itemId !== String(itemId)) return { startMs: null };
  const { itemId: _, ...options } = request;
  return options;
}

// True when Back has an in-app page to return to (not a direct link or reload).
export function hasPlayerOrigin() {
  return openedInApp;
}
