// Search results and the watchlist: plain grids of shows and movies.
import { html } from "../lib/html.js";
import { useResource } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { api, q } from "../lib/api.js";
import { useUserState } from "../lib/userstate.js";
import { ItemCard, ShowCard, SkeletonCard } from "../ui/cards.js";
import { Empty, ErrorState } from "../ui/controls.js";

function Grid({ title, shows = [], items = [] }) {
  if (!shows.length && !items.length) return null;
  return html`<section class="section">
    <h2>${title} <span class="count">${shows.length + items.length}</span></h2>
    <div class="grid">
      ${shows.map((s) => html`<${ShowCard} key=${`${s.libraryId}:${s.title}`} show=${s} />`)}
      ${items.map((i) => html`<${ItemCard} key=${i.id} item=${i} />`)}
    </div>
  </section>`;
}

export function SearchView({ query }) {
  const term = (query.get("q") || "").trim();
  const { data, error } = useResource(term.length >= 2 ? R.search(term) : null);
  return html`<main class="page list-page">
    <header class="page-head"><h1 class="display">${term ? html`“${term}”` : "Search"}</h1></header>
    ${term.length < 2 ? html`<${Empty} title="Type at least two letters">Press <kbd>/</kbd> anywhere to jump to search.<//>`
      : error ? html`<${ErrorState} error=${error} />`
      : !data ? html`<div class="grid">${Array.from({ length: 12 }, (_, i) => html`<${SkeletonCard} key=${i} />`)}</div>`
      : !data.shows.length && !data.movies.length ? html`<${Empty} title="No matches" />`
      : html`<${Grid} title="Shows" shows=${data.shows} /><${Grid} title="Movies" items=${data.movies} />`}
  </main>`;
}

const watchlistResource = () => ({ key: "watchlist", fetch: () => api(q("/api/watchlist", { limit: 1000 })), maxAge: 15_000 });

export function WatchlistView() {
  const state = useUserState();
  const { data, error } = useResource(watchlistResource());
  // Drop entries removed since the list was fetched, without a refetch.
  const items = (data?.items || []).filter((i) => state.watchlistItems.has(Number(i.id)));
  const shows = (data?.shows || []).filter((s) => state.watchlistShows.has(`${s.libraryId}\u0000${s.title}`));
  return html`<main class="page list-page">
    <header class="page-head"><h1 class="display">Watchlist</h1></header>
    ${error ? html`<${ErrorState} error=${error} />`
      : !data ? html`<div class="grid">${Array.from({ length: 12 }, (_, i) => html`<${SkeletonCard} key=${i} />`)}</div>`
      : !items.length && !shows.length ? html`<${Empty} title="Your watchlist is empty">Save something with the bookmark on any poster.<//>`
      : html`<${Grid} title="Shows" shows=${shows} /><${Grid} title="Movies" items=${items} />`}
  </main>`;
}
