// List pages: search, watchlist, watch history, and a person's filmography.
import { html, useState, cx } from "../lib/html.js";
import { useResource, seed } from "../lib/cache.js";
import { Link, replaceQuery } from "../lib/router.js";
import { paths } from "../lib/paths.js";
import { Artwork } from "../ui/artwork.js";
import { initials } from "../ui/cast.js";
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

const SCOPES = [["", "Movies & TV"], ["movies", "Movies"], ["tv", "TV shows"]];
const FIELDS = [["title", "Title"], ["original", "Original title"], ["people", "People"], ["description", "Description"]];

function Chips({ label, options, isOn, onPick }) {
  return html`<div class="chips" role="group" aria-label=${label}>
    <span class="chips-label">${label}</span>
    ${options.map(([value, text]) => html`<button type="button" key=${value} class=${cx("chip-btn", isOn(value) && "is-on")} aria-pressed=${isOn(value)} onClick=${() => onPick(value)}>${text}</button>`)}
  </div>`;
}

export function SearchView({ query }) {
  const term = (query.get("q") || "").trim();
  const scope = query.get("scope") || "";
  const fields = (query.get("in") || "title").split(",").filter(Boolean);
  const { data, error } = useResource(term.length >= 2 ? R.search(term, scope, fields.join(",")) : null);
  const toggleField = (value) => {
    const on = fields.includes(value);
    // At least one field stays selected.
    if (on && fields.length === 1) return;
    const next = on ? fields.filter((f) => f !== value) : [...fields, value];
    replaceQuery({ in: next.join(",") === "title" ? "" : next.join(",") });
  };
  const count = data ? data.shows.length + data.movies.length : 0;
  return html`<main class="page list-page">
    <header class="page-head"><h1 class="display">${term ? html`“${term}”` : "Search"}</h1>${data && term.length >= 2 && html`<span class="count">${count} result${count === 1 ? "" : "s"}</span>`}</header>
    <div class="search-options">
      <${Chips} label="Scope" options=${SCOPES} isOn=${(v) => v === scope} onPick=${(v) => replaceQuery({ scope: v })} />
      <${Chips} label="Search in" options=${FIELDS} isOn=${(v) => fields.includes(v)} onPick=${toggleField} />
    </div>
    ${term.length < 2 ? html`<${Empty} title="Type at least two letters">Press <kbd>/</kbd> anywhere to jump to search.<//>`
      : error ? html`<${ErrorState} error=${error} />`
      : !data ? html`<div class="grid">${Array.from({ length: 12 }, (_, i) => html`<${SkeletonCard} key=${i} />`)}</div>`
      : !count ? html`<${Empty} title=${`Nothing found for “${term}”`}>Try another phrase or include another search field.<//>`
      : html`<${Grid} title="Shows" shows=${data.shows} /><${Grid} title="Movies" items=${data.movies} />`}
  </main>`;
}

function historyTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value || "").replace("T", " ").slice(0, 16);
  return date.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

function HistoryRow({ entry }) {
  const body = html`
    <div class="history-art">${entry.item ? html`<${Artwork} item=${entry.item} kind="poster" width=${160} />` : html`<span>${entry.kind === "episode" ? "TV" : "FILM"}</span>`}</div>
    <div class="history-copy"><strong>${entry.title}</strong><span>${[entry.subtitle, entry.year].filter(Boolean).join(" · ")}</span></div>
    ${!entry.item && html`<span class="chip">Trakt</span>`}
    <time datetime=${entry.watchedAt}>${historyTime(entry.watchedAt)}</time>`;
  return html`<li>${entry.item
    ? html`<${Link} href=${paths.item(entry.item)} class="history-row" onPointerEnter=${() => seed(`item:${entry.item.id}`, entry.item)}>${body}<//>`
    : html`<div class="history-row">${body}</div>`}</li>`;
}

export function HistoryView() {
  const { data, error } = useResource(R.history());
  return html`<main class="page list-page history">
    <header class="page-head"><h1 class="display">Watch history</h1>${data && html`<span class="count">${data.traktLinked ? "Local + Trakt" : "This server"}</span>`}</header>
    ${error ? html`<${ErrorState} error=${error} />`
      : !data ? html`<ul class="history-list is-loading">${Array.from({ length: 8 }, (_, i) => html`<li key=${i}><div class="history-row is-skeleton"><div class="history-art"><div class="art"></div></div></div></li>`)}</ul>`
      : !data.items?.length ? html`<${Empty} title="Nothing watched yet" />`
      : html`<ul class="history-list">${data.items.map((entry, i) => html`<${HistoryRow} key=${entry.id || i} entry=${entry} />`)}</ul>`}
  </main>`;
}

export function ActorView({ name }) {
  const { data, error } = useResource(R.actor(name));
  const [open, setOpen] = useState(false);
  if (error) return html`<main class="page"><${ErrorState} error=${error} /></main>`;
  const info = data?.info || {};
  const photo = data?.profileUrl || data?.actor?.thumb || `/api/actors/image?name=${encodeURIComponent(name)}`;
  const meta = [info.knownForDepartment, info.birthday, info.placeOfBirth].filter(Boolean);
  const movies = data?.movies || [];
  const shows = data?.shows || [];
  return html`<main class="page actor">
    <header class="actor-head">
      <span class="actor-photo" style=${`background-image:url("${photo.replace(/"/g, "%22")}")`}>${initials(name)}</span>
      <div class="actor-copy">
        <h1 class="display">${data?.actor?.name || name}</h1>
        ${meta.length > 0 && html`<p class="meta">${meta.map((m, i) => html`${i ? html`<i>·</i>` : ""}<span>${m}</span>`)}</p>`}
        ${info.biography && html`<div class=${cx("overview", open && "is-open")}><p class="overview-text">${info.biography}</p>
          ${info.biography.length > 280 && html`<button type="button" class="btn btn-text" onClick=${() => setOpen(!open)}>${open ? "Less" : "More"}</button>`}</div>`}
      </div>
    </header>
    ${!data ? html`<div class="grid">${Array.from({ length: 12 }, (_, i) => html`<${SkeletonCard} key=${i} />`)}</div>`
      : !movies.length && !shows.length ? html`<${Empty} title="Nothing in your library" />`
      : html`<${Grid} title="Movies" items=${movies} /><${Grid} title="TV shows" shows=${shows} />`}
  </main>`;
}

const watchlistResource = () => ({ key: "watchlist", fetch: () => api(q("/api/watchlist", { limit: 1000 })), maxAge: 15_000 });

export function WatchlistView() {
  const state = useUserState();
  const { data, error } = useResource(watchlistResource());
  // Drop entries removed since the list was fetched, without a refetch.
  const items = (data?.items || []).filter((i) => !state.ready || state.watchlistItems.has(Number(i.id)));
  const shows = (data?.shows || []).filter((s) => !state.ready || state.watchlistShows.has(`${s.libraryId}\u0000${s.title}`));
  return html`<main class="page list-page">
    <header class="page-head"><h1 class="display">Watchlist</h1></header>
    ${error ? html`<${ErrorState} error=${error} />`
      : !data ? html`<div class="grid">${Array.from({ length: 12 }, (_, i) => html`<${SkeletonCard} key=${i} />`)}</div>`
      : !items.length && !shows.length ? html`<${Empty} title="Your watchlist is empty">Save something with the bookmark on any poster.<//>`
      : html`<${Grid} title="Shows" shows=${shows} /><${Grid} title="Movies" items=${items} />`}
  </main>`;
}
