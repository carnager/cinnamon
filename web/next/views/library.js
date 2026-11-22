// A library as a poster grid with filters.
//
// Filters live in the URL (replaceQuery, no history entry per change).
// Loaded pages are cache entries keyed by filters + offset, and how many pages
// were open is remembered per filter set, so coming Back from a detail page
// re-renders the same grid from memory at the same scroll position.
import { html, useState, useEffect, useRef, cx } from "../lib/html.js";
import { useResource, useResources, prefetch } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { replaceQuery } from "../lib/router.js";
import { ItemCard, ShowCard, SkeletonCard } from "../ui/cards.js";
import { Icon } from "../ui/icons.js";
import { Empty, ErrorState } from "../ui/controls.js";

const PAGE = 60;
const openPages = new Map(); // filterKey -> number of pages shown

const SORTS = [
  ["", "Title A–Z"], ["title_desc", "Title Z–A"],
  ["mtime", "Recently added"], ["mtime_asc", "Oldest added"],
  ["year_desc", "Newest"], ["year", "Oldest"],
  ["rating", "Highest rated"], ["rating_asc", "Lowest rated"],
];
const SEEN = [["", "All"], ["unseen", "Unseen"], ["started", "In progress"], ["seen", "Seen"]];
const RATINGS = [["", "Any rating"], ["6", "6+"], ["7", "7+"], ["8", "8+"]];

function readFilters(query) {
  return {
    sort: query.get("sort") || "",
    seen: query.get("seen") || "",
    genre: query.get("genre") || "",
    decades: query.get("decades") || "",
    minRating: query.get("minRating") || "",
  };
}

function useOutsideClose(open, setOpen) {
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => { if (!ref.current?.contains(e.target)) setOpen(false); };
    const esc = (e) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("pointerdown", close);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);
  return ref;
}

// A pill that opens a list. multi: comma-joined multi-select.
function FilterMenu({ label, value, options, multi = false, onChange, align = "left" }) {
  const [open, setOpen] = useState(false);
  const ref = useOutsideClose(open, setOpen);
  const selected = multi ? value.split(",").filter(Boolean) : [value];
  const summary = multi
    ? (selected.length === 0 ? "Any" : selected.length === 1 ? (options.find((o) => o[0] === selected[0])?.[1] || selected[0]) : `${selected.length} selected`)
    : options.find((o) => o[0] === value)?.[1] || options[0][1];
  const active = multi ? selected.length > 0 : Boolean(value);
  const toggle = (opt) => {
    if (!multi) {
      onChange(opt);
      setOpen(false);
      return;
    }
    const set = new Set(selected);
    set.has(opt) ? set.delete(opt) : set.add(opt);
    onChange([...set].join(","));
  };
  return html`<div class=${cx("filter", active && "is-active")} ref=${ref}>
    <button type="button" class="filter-pill" aria-haspopup="listbox" aria-expanded=${open} onClick=${() => setOpen(!open)}>
      <span class="filter-label">${label}</span><span class="filter-value">${summary}</span><${Icon} name="chevronDown" size=${16} />
    </button>
    ${open && html`<div class=${cx("popover", `align-${align}`)} role="listbox" aria-multiselectable=${multi}>
      ${multi && selected.length > 0 && html`<button type="button" class="popover-clear" onClick=${() => onChange("")}>Clear</button>`}
      ${options.map(([v, text]) => {
        const on = multi ? selected.includes(v) : v === value;
        return html`<button type="button" key=${v} role="option" aria-selected=${on} class=${cx("option", on && "is-selected")} onClick=${() => toggle(v)}>
          <span>${text}</span>${on && html`<${Icon} name="check" size=${16} />`}
        </button>`;
      })}
    </div>`}
  </div>`;
}

function Toolbar({ library, filters, total }) {
  const isTV = library.type === "tv";
  const { data: genres } = useResource(R.genres(library.id));
  const { data: decades } = useResource(R.decades(library.id, isTV ? "tv" : "movie"));
  const set = (patch) => replaceQuery(patch);
  const anyFilter = filters.seen || filters.genre || filters.decades || filters.minRating;
  return html`<div class="toolbar">
    <div class="toolbar-filters">
      <${FilterMenu} label="Sort" value=${filters.sort} options=${SORTS} onChange=${(v) => set({ sort: v })} />
      <${FilterMenu} label="Show" value=${filters.seen} options=${SEEN} onChange=${(v) => set({ seen: v })} />
      <${FilterMenu} label="Genre" value=${filters.genre} multi options=${(genres || []).map((g) => [g, g])} onChange=${(v) => set({ genre: v })} />
      <${FilterMenu} label="Decade" value=${filters.decades} multi options=${(decades || []).slice().sort((a, b) => b - a).map((d) => [String(d), `${d}s`])} onChange=${(v) => set({ decades: v })} />
      <${FilterMenu} label="Rating" value=${filters.minRating} options=${RATINGS} onChange=${(v) => set({ minRating: v })} />
      ${anyFilter && html`<button type="button" class="btn btn-text" onClick=${() => set({ seen: "", genre: "", decades: "", minRating: "" })}>Reset</button>`}
    </div>
    <span class="toolbar-count">${total}</span>
  </div>`;
}

export function LibraryView({ libraryId, query }) {
  const { data: libraries } = useResource(R.libraries());
  const library = (libraries || []).find((l) => l.id === libraryId);
  if (!libraries) return html`<main class="page library"><div class="grid">${Array.from({ length: 18 }, (_, i) => html`<${SkeletonCard} key=${i} />`)}</div></main>`;
  if (!library) return html`<main class="page"><${Empty} title="Library not found" /></main>`;
  return html`<${LibraryGrid} key=${library.id} library=${library} filters=${readFilters(query)} />`;
}

function LibraryGrid({ library, filters }) {
  const isTV = library.type === "tv";
  const filterKey = `${library.id}:${JSON.stringify(filters)}`;
  // The page count belongs to one filter set; a new filter starts from what was
  // last open for it (usually 1), never from the previous filter's count.
  const [opened, setOpened] = useState({ key: filterKey, n: openPages.get(filterKey) || 1 });
  const pages = opened.key === filterKey ? opened.n : openPages.get(filterKey) || 1;
  const setPages = (fn) => setOpened({ key: filterKey, n: fn(pages) });
  const [, force] = useState(0);
  const sentinel = useRef(null);
  useEffect(() => { openPages.set(filterKey, pages); }, [filterKey, pages]);

  // Subscribe to every open page; render what has arrived, in order.
  const results = useResources(Array.from({ length: pages }, (_, i) => R.libraryPage(library, filters, i * PAGE, PAGE)));
  const rows = [];
  let complete = false;
  let pending = false;
  let error = null;
  for (const r of results) {
    if (r.error) { error = r.error; break; }
    if (!r.data) { pending = true; break; }
    rows.push(...r.data);
    if (r.data.length < PAGE) { complete = true; break; }
  }

  // Load the next page before the user reaches the end.
  useEffect(() => {
    const el = sentinel.current;
    if (!el || complete || pending) return undefined;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) setPages((n) => n + 1);
    }, { rootMargin: "1400px 0px" });
    io.observe(el);
    return () => io.disconnect();
  }, [complete, pending, pages, filterKey]);

  // The unfiltered total comes from the A–Z index (its counts add up to the
  // library); with filters, say how many are shown and whether more exist.
  const unfiltered = !filters.seen && !filters.genre && !filters.decades && !filters.minRating;
  const { data: alphabet } = useResource(unfiltered ? R.alphabet(library.id, isTV ? "tv" : "movie") : null);
  const noun = isTV ? "shows" : "movies";
  const total = unfiltered && alphabet
    ? `${alphabet.reduce((n, l) => n + (l.count || 0), 0)} ${noun}`
    : complete ? `${rows.length} ${noun}` : rows.length ? `${rows.length}+ ${noun}` : "";

  return html`<main class="page library">
    <header class="page-head">
      <h1 class="display">${library.name}</h1>
    </header>
    <${Toolbar} library=${library} filters=${filters} total=${total} />
    ${error && !rows.length ? html`<${ErrorState} error=${error} retry=${() => force((n) => n + 1)} />`
      : complete && !rows.length ? html`<${Empty} title="Nothing matches these filters">Try removing a filter.<//>`
      : html`<div class="grid">
          ${rows.map((row, i) => isTV
            ? html`<${ShowCard} key=${`${row.libraryId}:${row.title}`} show=${row} eager=${i < 12} />`
            : html`<${ItemCard} key=${row.id} item=${row} eager=${i < 12} />`)}
          ${!complete && Array.from({ length: rows.length ? 6 : 18 }, (_, i) => html`<${SkeletonCard} key=${`s${i}`} />`)}
        </div>`}
    <div ref=${sentinel} class="sentinel" aria-hidden="true"></div>
  </main>`;
}

export function prefetchLibrary(library, query) {
  if (!library) return;
  prefetch(R.libraryPage(library, readFilters(query || new URLSearchParams()), 0, PAGE));
}
