// A small stale-while-revalidate cache. This is what makes navigation feel
// instant: a page that was seen (or prefetched, or seeded from the card that
// was clicked) renders from memory in the same frame, and refreshes in the
// background when its data is older than maxAge.
//
// Unlike the classic client, writes do not clear the whole cache. A mutation
// updates the entries it touched (setData) or marks a family stale
// (invalidate), so a progress report during playback no longer throws away
// every page.

import { useEffect, useReducer, useRef } from "./html.js";

const entries = new Map(); // key -> { data, error, at, promise, seeded, subs:Set }
const DEFAULT_MAX_AGE = 60_000;

function entryFor(key) {
  let entry = entries.get(key);
  if (!entry) {
    entry = { data: undefined, error: null, at: 0, promise: null, seeded: false, subs: new Set() };
    entries.set(key, entry);
  }
  return entry;
}

function notify(entry) {
  for (const fn of entry.subs) fn();
}

function revalidate(key, fetcher) {
  const entry = entryFor(key);
  if (entry.promise) return entry.promise;
  entry.promise = Promise.resolve()
    .then(fetcher)
    .then((data) => {
      entry.data = data;
      entry.error = null;
      entry.at = Date.now();
      entry.seeded = false;
    })
    .catch((error) => {
      // Keep showing what we had; only surface the error when there is nothing.
      entry.error = error;
      entry.at = Date.now();
    })
    .finally(() => {
      entry.promise = null;
      notify(entry);
    });
  return entry.promise;
}

function isStale(entry, maxAge) {
  return entry.seeded || entry.at === 0 || Date.now() - entry.at > maxAge;
}

// load returns cached data (possibly stale) and starts a refresh if needed.
export function load(resource) {
  const entry = entryFor(resource.key);
  if (!entry.promise && isStale(entry, resource.maxAge ?? DEFAULT_MAX_AGE)) {
    revalidate(resource.key, resource.fetch);
  }
  return entry;
}

// fetchNow resolves with fresh-enough data, for code paths outside rendering.
export async function fetchNow(resource) {
  const entry = load(resource);
  if (entry.promise && entry.data === undefined) await entry.promise;
  if (entry.error && entry.data === undefined) throw entry.error;
  return entry.data;
}

export function prefetch(resource) {
  if (resource) load(resource);
}

// useResource subscribes a component to one resource. data is undefined only
// on a true cold load; a stale value is returned immediately while refreshing.
export function useResource(resource) {
  const [, rerender] = useReducer((n) => n + 1, 0);
  const key = resource?.key;
  const seen = useRef(undefined);
  useEffect(() => {
    if (!key) return undefined;
    const entry = entryFor(key);
    entry.subs.add(rerender);
    // Subscribing happens after paint; a fetch that finished in between
    // notified nobody, so catch up now.
    if (entry.data !== seen.current || (entry.error && entry.data === undefined)) rerender();
    return () => entry.subs.delete(rerender);
  }, [key]);
  if (!resource) return { data: undefined, error: null, loading: false };
  const entry = load(resource);
  seen.current = entry.data;
  return {
    data: entry.data,
    error: entry.data === undefined ? entry.error : null,
    loading: entry.data === undefined && !entry.error,
    refreshing: Boolean(entry.promise),
  };
}

// useResources subscribes to a list whose length may change between renders
// (pages of a grid) with a fixed number of hooks.
export function useResources(resources) {
  const [, rerender] = useReducer((n) => n + 1, 0);
  const keys = resources.map((r) => r.key).join("\n");
  const seen = useRef([]);
  useEffect(() => {
    const subscribed = resources.map((r) => entryFor(r.key));
    subscribed.forEach((entry) => entry.subs.add(rerender));
    if (subscribed.some((entry, i) => entry.data !== seen.current[i])) rerender();
    return () => subscribed.forEach((entry) => entry.subs.delete(rerender));
  }, [keys]);
  seen.current = [];
  return resources.map((resource) => {
    const entry = load(resource);
    seen.current.push(entry.data);
    return {
      data: entry.data,
      error: entry.data === undefined ? entry.error : null,
      loading: entry.data === undefined && !entry.error,
    };
  });
}

export function peek(key) {
  return entries.get(key)?.data;
}

// seed fills an entry with partial data we already hold (e.g. the item from a
// list), marked stale so the full record is still fetched.
export function seed(key, data) {
  const entry = entryFor(key);
  if (entry.data !== undefined) return;
  entry.data = data;
  entry.seeded = true;
  notify(entry);
}

export function setData(key, updater) {
  const entry = entries.get(key);
  if (!entry || entry.data === undefined) return;
  entry.data = typeof updater === "function" ? updater(entry.data) : updater;
  notify(entry);
}

// invalidate marks matching entries stale and refreshes the ones on screen.
export function invalidate(match) {
  for (const [key, entry] of entries) {
    const hit = typeof match === "function" ? match(key) : key === match || key.startsWith(match);
    if (!hit) continue;
    entry.at = 0;
    if (entry.subs.size) notify(entry);
  }
}

export function clearAll() {
  entries.clear();
}
