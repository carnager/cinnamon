// Playback entry point: every Play button comes through here. The player is a
// route (/watch/<id>), so Back closes it and returns to the page it came from.
import { navigate } from "./router.js";
import { seed } from "./cache.js";

// How the next player mount should start. Kept out of the URL so reloading a
// player page resumes from the saved position instead of repeating "from start".
let pendingStart = null;
let openedInApp = false;

export function playItem(item, { fromStart = false, replace = false, instant = false } = {}) {
  if (!item?.id) return;
  seed(`item:${item.id}`, item);
  pendingStart = fromStart ? { itemId: String(item.id), startMs: 0 } : null;
  openedInApp = true;
  navigate(`/watch/${encodeURIComponent(item.id)}`, { replace, instant });
}

// The start position requested for this item, once; null means "resume".
export function takeStart(itemId) {
  const pending = pendingStart;
  pendingStart = null;
  return pending && pending.itemId === String(itemId) ? pending.startMs : null;
}

// True when Back has an in-app page to return to (not a direct link or reload).
export function hasPlayerOrigin() {
  return openedInApp;
}
