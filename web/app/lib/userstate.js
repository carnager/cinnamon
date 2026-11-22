// The signed-in user's relationship to the library: progress, watched,
// watchlist, ratings. Cards everywhere read it for their badges, so it is one
// store loaded once and kept current by optimistic updates: the UI changes on
// click, the request follows, and a failure rolls the change back.

import { api, q } from "./api.js";
import { useEffect, useReducer, useRef } from "./html.js";
import { invalidate } from "./cache.js";
import { toast } from "./toast.js";

const state = {
  ready: false,
  progress: new Map(),      // itemId -> { positionMs, durationMs, completed }
  shows: new Map(),         // showKey -> { episodeCount, completedCount, completed, hasAnyCompletion }
  watchlistItems: new Set(),
  watchlistShows: new Set(),
  itemRatings: new Map(),
  showRatings: new Map(),
  excluded: new Set(),      // "item:12" / "show:<library>:<title>", lower-cased
};
const subs = new Set();
let loading = null;

export const showKey = (libraryId, title) => `${libraryId}\u0000${title}`;

let version = 0;
function emit() {
  version++;
  for (const fn of subs) fn();
}

async function fetchAllProgress() {
  const rows = [];
  for (let offset = 0; offset < 50_000; offset += 500) {
    const page = await api(q("/api/progress", { limit: 500, offset }));
    rows.push(...(page || []));
    if (!page || page.length < 500) break;
  }
  return rows;
}

const showExclusionKey = (libraryId, title) =>
  `show:${String(libraryId || "").trim().toLowerCase()}:${String(title || "").trim().toLowerCase()}`;

function exclusionKey(entry) {
  if (entry.item) return `item:${Number(entry.item.id)}`;
  if (entry.show) return showExclusionKey(entry.show.libraryId, entry.show.title);
  return entry.key || "";
}

export function loadUserState({ force = false } = {}) {
  if (loading && !force) return loading;
  loading = (async () => {
    const [progress, shows, watchlist, ratings, exclusions] = await Promise.all([
      fetchAllProgress(),
      api("/api/progress/tv"),
      api(q("/api/watchlist", { limit: 1000 })),
      api("/api/ratings/user"),
      api("/api/recommendations/exclusions").catch(() => []),
    ]);
    state.excluded = new Set((exclusions || []).map(exclusionKey).filter(Boolean));
    state.progress = new Map((progress || []).map((p) => [Number(p.itemId), p]));
    state.shows = new Map((shows || []).map((s) => [showKey(s.libraryId, s.showTitle), s]));
    state.watchlistItems = new Set((watchlist?.items || []).map((i) => Number(i.id)));
    state.watchlistShows = new Set((watchlist?.shows || []).map((s) => showKey(s.libraryId, s.title)));
    state.itemRatings = new Map();
    state.showRatings = new Map();
    for (const r of ratings || []) {
      if (r.kind === "show") state.showRatings.set(showKey(r.libraryId, r.showTitle), r.rating);
      else if (r.itemId) state.itemRatings.set(Number(r.itemId), r.rating);
    }
    state.ready = true;
    emit();
  })().catch((err) => {
    loading = null;
    throw err;
  });
  return loading;
}

export function useUserState() {
  const [, rerender] = useReducer((n) => n + 1, 0);
  const seen = useRef(version);
  seen.current = version;
  useEffect(() => {
    subs.add(rerender);
    // A load that finished between render and this effect notified nobody.
    if (seen.current !== version) rerender();
    return () => subs.delete(rerender);
  }, []);
  return state;
}

// ---- reads -----------------------------------------------------------------

export function itemProgress(id) {
  return state.progress.get(Number(id));
}

export function isItemSeen(id) {
  return Boolean(state.progress.get(Number(id))?.completed);
}

// Fraction watched for a resumable item, or 0. Mirrors the server's rule:
// at least 30s in and more than 90s from the end.
export function resumeFraction(id) {
  const p = state.progress.get(Number(id));
  if (!p || p.completed || !p.durationMs || p.positionMs < 30_000) return 0;
  if (p.positionMs >= p.durationMs - 90_000) return 0;
  return Math.min(0.98, Math.max(0.02, p.positionMs / p.durationMs));
}

export function showProgress(libraryId, title) {
  return state.shows.get(showKey(libraryId, title));
}

export function isShowSeen(libraryId, title) {
  return Boolean(showProgress(libraryId, title)?.completed);
}

export const isItemWatchlisted = (id) => state.watchlistItems.has(Number(id));
export const isShowWatchlisted = (libraryId, title) => state.watchlistShows.has(showKey(libraryId, title));
export const itemRating = (id) => state.itemRatings.get(Number(id)) || 0;
export const showRating = (libraryId, title) => state.showRatings.get(showKey(libraryId, title)) || 0;

export const isItemExcluded = (id) => state.excluded.has(`item:${Number(id)}`);
export const isShowExcluded = (libraryId, title) => state.excluded.has(showExclusionKey(libraryId, title));

// Records what the server stored for a playback report, so cards and resume
// buttons are current when the player closes. No request: the report was it.
export function noteProgress(saved) {
  const id = Number(saved.itemId);
  if (saved.completed || saved.positionMs > 0) state.progress.set(id, saved);
  // Finishing a title takes it off the watchlist on the server.
  if (saved.completed) state.watchlistItems.delete(id);
  emit();
}

// ---- writes ----------------------------------------------------------------

async function optimistic(apply, request, failureMessage) {
  const undo = apply();
  emit();
  try {
    await request();
  } catch (err) {
    undo();
    emit();
    toast(`${failureMessage}: ${err.message}`, { tone: "error" });
    throw err;
  }
}

const enc = encodeURIComponent;

export function setItemWatchlisted(item, on) {
  const id = Number(item.id);
  return optimistic(
    () => {
      const had = state.watchlistItems.has(id);
      on ? state.watchlistItems.add(id) : state.watchlistItems.delete(id);
      return () => (had ? state.watchlistItems.add(id) : state.watchlistItems.delete(id));
    },
    async () => {
      await api(`/api/items/${enc(id)}/watchlist`, { method: on ? "PUT" : "DELETE" });
      invalidate("home");
      invalidate("watchlist");
    },
    "Watchlist not updated",
  );
}

export function setShowWatchlisted(show, on) {
  const key = showKey(show.libraryId, show.title);
  return optimistic(
    () => {
      const had = state.watchlistShows.has(key);
      on ? state.watchlistShows.add(key) : state.watchlistShows.delete(key);
      return () => (had ? state.watchlistShows.add(key) : state.watchlistShows.delete(key));
    },
    async () => {
      await api(q("/api/watchlist/tv", { libraryId: show.libraryId, showTitle: show.title }), { method: on ? "PUT" : "DELETE" });
      invalidate("home");
      invalidate("watchlist");
    },
    "Watchlist not updated",
  );
}

export function setItemSeen(item, seen) {
  const id = Number(item.id);
  const duration = item.durationMs || 1;
  return optimistic(
    () => {
      const before = state.progress.get(id);
      const hadWatchlist = state.watchlistItems.has(id);
      if (seen) {
        state.progress.set(id, { itemId: id, positionMs: duration, durationMs: duration, completed: true });
        // The server takes a finished title off the watchlist; mirror it.
        state.watchlistItems.delete(id);
      } else {
        state.progress.delete(id);
      }
      return () => {
        before ? state.progress.set(id, before) : state.progress.delete(id);
        if (hadWatchlist) state.watchlistItems.add(id);
      };
    },
    async () => {
      if (seen) {
        await api(`/api/items/${enc(id)}/progress`, {
          method: "PUT",
          body: { positionMs: duration, durationMs: duration, completed: true, state: "manual" },
        });
      } else {
        await api(`/api/items/${enc(id)}/progress`, { method: "DELETE" });
      }
      invalidate("home");
      if (item.kind === "episode") loadUserState({ force: true }).catch(() => {});
    },
    seen ? "Not marked as seen" : "Not marked as unseen",
  );
}

export async function setSeasonSeen(show, season, seen, episodes) {
  const ids = (episodes || []).map((e) => Number(e.id));
  return optimistic(
    () => {
      const before = new Map(ids.map((id) => [id, state.progress.get(id)]));
      for (const e of episodes || []) {
        const d = e.durationMs || 1;
        if (seen) state.progress.set(Number(e.id), { itemId: e.id, positionMs: d, durationMs: d, completed: true });
        else state.progress.delete(Number(e.id));
      }
      return () => {
        for (const [id, p] of before) (p ? state.progress.set(id, p) : state.progress.delete(id));
      };
    },
    async () => {
      await api(q("/api/progress/tv/season", { libraryId: show.libraryId, showTitle: show.title, season }), { method: seen ? "PUT" : "DELETE" });
      invalidate("home");
      await loadUserState({ force: true });
    },
    "Season not updated",
  );
}

export function setShowSeen(show, seen) {
  const key = showKey(show.libraryId, show.title);
  return optimistic(
    () => {
      const before = state.shows.get(key);
      state.shows.set(key, { ...(before || {}), completed: seen, hasAnyCompletion: seen });
      return () => (before ? state.shows.set(key, before) : state.shows.delete(key));
    },
    async () => {
      await api(q("/api/progress/tv", { libraryId: show.libraryId, showTitle: show.title }), { method: seen ? "PUT" : "DELETE" });
      invalidate("home");
      await loadUserState({ force: true });
    },
    "Show not updated",
  );
}

export function setItemRating(item, rating) {
  const id = Number(item.id);
  return optimistic(
    () => {
      const before = state.itemRatings.get(id);
      rating > 0 ? state.itemRatings.set(id, rating) : state.itemRatings.delete(id);
      return () => (before ? state.itemRatings.set(id, before) : state.itemRatings.delete(id));
    },
    () => api(`/api/items/${enc(id)}/rating`, rating > 0 ? { method: "PUT", body: { rating } } : { method: "DELETE" }),
    "Rating not saved",
  );
}

export function setShowRating(show, rating) {
  const key = showKey(show.libraryId, show.title);
  const path = q("/api/ratings/tv", { libraryId: show.libraryId, showTitle: show.title });
  return optimistic(
    () => {
      const before = state.showRatings.get(key);
      rating > 0 ? state.showRatings.set(key, rating) : state.showRatings.delete(key);
      return () => (before ? state.showRatings.set(key, before) : state.showRatings.delete(key));
    },
    () => api(path, rating > 0 ? { method: "PUT", body: { rating } } : { method: "DELETE" }),
    "Rating not saved",
  );
}

function setExcluded(key, path, on) {
  return optimistic(
    () => {
      const had = state.excluded.has(key);
      on ? state.excluded.add(key) : state.excluded.delete(key);
      return () => (had ? state.excluded.add(key) : state.excluded.delete(key));
    },
    async () => {
      await api(path, { method: on ? "PUT" : "DELETE" });
      invalidate("home");
      invalidate("exclusions");
    },
    "Not interested not updated",
  );
}

export const setItemExcluded = (item, on) =>
  setExcluded(`item:${Number(item.id)}`, `/api/items/${enc(item.id)}/recommendation-exclusion`, on);

export const setShowExcluded = (show, on) =>
  setExcluded(
    showExclusionKey(show.libraryId, show.title),
    q("/api/recommendations/exclusions/tv", { libraryId: show.libraryId, showTitle: show.title }),
    on,
  );

// Seen and Not interested exclude each other, so one choice sets both.
export async function setItemPreference(item, preference) {
  if (preference === "not_interested") {
    if (isItemSeen(item.id) || itemProgress(item.id)) await setItemSeen(item, false);
    await setItemExcluded(item, true);
    return;
  }
  if (isItemExcluded(item.id)) await setItemExcluded(item, false);
  await setItemSeen(item, preference === "seen");
}

export async function setShowPreference(show, preference) {
  if (preference === "not_interested") {
    if (showProgress(show.libraryId, show.title)?.hasAnyCompletion) await setShowSeen(show, false);
    await setShowExcluded(show, true);
    return;
  }
  if (isShowExcluded(show.libraryId, show.title)) await setShowExcluded(show, false);
  await setShowSeen(show, preference === "seen");
}
