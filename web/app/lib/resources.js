// Every piece of server data the client reads, as { key, fetch, maxAge }.
// Keys are stable strings so a page and a prefetch share one cache entry.

import { api, q } from "./api.js";

const enc = encodeURIComponent;

export const R = {
  me: () => ({ key: "me", fetch: () => api("/api/auth/me"), maxAge: 300_000 }),
  libraries: () => ({ key: "libraries", fetch: () => api("/api/libraries"), maxAge: 300_000 }),
  home: () => ({ key: "home", fetch: () => api("/api/home"), maxAge: 30_000 }),

  item: (id) => ({ key: `item:${id}`, fetch: () => api(`/api/items/${enc(id)}`), maxAge: 120_000 }),
  itemStreams: (id) => ({ key: `item:${id}:streams`, fetch: () => api(`/api/items/${enc(id)}/streams`), maxAge: 600_000 }),
  itemRatings: (id) => ({ key: `item:${id}:ratings`, fetch: () => api(`/api/items/${enc(id)}/ratings`), maxAge: 600_000 }),
  itemSimilar: (id) => ({ key: `item:${id}:similar`, fetch: () => api(`/api/items/${enc(id)}/similar`), maxAge: 600_000 }),
  itemSidecars: (id) => ({ key: `item:${id}:sidecars`, fetch: () => api(`/api/items/${enc(id)}/sidecars`), maxAge: 600_000 }),

  // One page of a library listing. filters: sort, genre, seen, minRating, decades.
  libraryPage: (library, filters, offset, limit = 60) => {
    const isTV = library.type === "tv";
    const params = { libraryId: library.id, limit, offset, ...filters };
    const path = isTV ? "/api/tv/shows" : "/api/items";
    return { key: `lib:${library.id}:${JSON.stringify(filters)}:${offset}:${limit}`, fetch: () => api(q(path, params)), maxAge: 60_000 };
  },
  genres: (libraryId) => ({ key: `genres:${libraryId}`, fetch: () => api(q("/api/genres", { libraryId })), maxAge: 600_000 }),
  decades: (libraryId, kind) => ({ key: `decades:${libraryId}`, fetch: () => api(q("/api/decades", { libraryId, kind })), maxAge: 600_000 }),
  // Letter index of a title-sorted list; genre/decade narrow it, so they are part of the key.
  alphabet: (libraryId, kind, { genre = "", decades = "" } = {}) => ({
    key: `alphabet:${libraryId}:${genre}:${decades}`,
    fetch: () => api(q("/api/alphabet", { libraryId, kind, genre, decades })),
    maxAge: 120_000,
  }),

  // A show is addressed by library + title; the shows endpoint filters by q.
  show: (libraryId, title) => ({
    key: `show:${libraryId}:${title}`,
    fetch: async () => {
      const rows = await api(q("/api/tv/shows", { libraryId, q: title, limit: 20 }));
      return (rows || []).find((s) => s.title === title) || null;
    },
    maxAge: 120_000,
  }),
  seasons: (libraryId, title) => ({ key: `seasons:${libraryId}:${title}`, fetch: () => api(q("/api/tv/seasons", { libraryId, showTitle: title })), maxAge: 120_000 }),
  episodes: (libraryId, title, season) => ({
    key: `episodes:${libraryId}:${title}:${season}`,
    fetch: () => api(q("/api/tv/episodes", { libraryId, showTitle: title, season })),
    maxAge: 120_000,
  }),

  showActors: (libraryId, title) => ({ key: `show:${libraryId}:${title}:actors`, fetch: () => api(q("/api/tv/shows/actors", { libraryId, showTitle: title })), maxAge: 600_000 }),
  actor: (name) => ({ key: `actor:${name}`, fetch: () => api(q("/api/actors", { name })), maxAge: 300_000 }),
  history: () => ({ key: "history", fetch: () => api(q("/api/history", { limit: 200 })), maxAge: 15_000 }),
  exclusions: () => ({ key: "exclusions", fetch: () => api("/api/recommendations/exclusions"), maxAge: 15_000 }),

  traktStatus: () => ({ key: "settings:trakt", fetch: () => api("/api/trakt/status"), maxAge: 15_000 }),
  users: () => ({ key: "settings:users", fetch: () => api("/api/users"), maxAge: 15_000 }),
  userTrakt: (id) => ({ key: `settings:users:${id}:trakt`, fetch: () => api(`/api/users/${enc(id)}/trakt`), maxAge: 15_000 }),
  appUpdates: () => ({ key: "settings:apps", fetch: () => api("/api/app/updates"), maxAge: 15_000 }),
  scanStatus: () => ({ key: "settings:scan", fetch: () => api("/api/scan"), maxAge: 2_000 }),
  homeCatalog: () => ({ key: "settings:home:catalog", fetch: () => api("/api/home/catalog"), maxAge: 300_000 }),
  homeLayout: () => ({ key: "settings:home:layout", fetch: () => api("/api/home/layout"), maxAge: 5_000 }),

  // scope: "" (both) | "movies" | "tv". fields: comma list, "" means titles.
  search: (term, scope = "", fields = "title") => ({
    key: `search:${term}:${scope}:${fields}`,
    fetch: async () => {
      const [movies, shows] = await Promise.all([
        scope === "tv" ? null : api(q("/api/search", { q: term, kind: "movie", limit: 500, fields })),
        scope === "movies" ? [] : api(q("/api/tv/shows", { q: term, limit: 500, fields })),
      ]);
      return { movies: movies?.items || [], shows: shows || [] };
    },
    maxAge: 60_000,
  }),
};
