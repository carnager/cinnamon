// A library as a poster grid with filters.
//
// Filters live in the URL (replaceQuery, no history entry per change).
// Loaded pages are cache entries keyed by filters + offset, and how many pages
// were open is remembered per filter set, so coming Back from a detail page
// re-renders the same grid from memory at the same scroll position.
import { html, useState, useEffect, useLayoutEffect, useRef, cx } from "../lib/html.js";
import { useResource, useResources, prefetch, fetchNow } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { replaceQuery } from "../lib/router.js";
import { ItemCard, ShowCard, SkeletonCard } from "../ui/cards.js";
import { Icon } from "../ui/icons.js";
import { Empty, ErrorState } from "../ui/controls.js";

const PAGE = 60;
const FIRST_CHUNK = [{ offset: 0, limit: PAGE }];
const openWindows = new Map(); // filterKey -> loaded chunks, restored on Back

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

// Jump-to-letter rail. Only valid for a title-sorted list without seen/rating
// filters, because the server's letter offsets assume exactly that order.
function AlphaRail({ entries, active, onJump }) {
  return html`<nav class="alpha-rail" aria-label="Jump to letter">
    ${entries.map((e) => {
      const letter = String(e.letter || "#").toUpperCase();
      return html`<button type="button" key=${letter} class=${cx(letter === active && "is-active")}
        title=${`${letter} · ${e.count}`} aria-current=${letter === active ? "true" : undefined} onClick=${() => onJump(e)}>${letter}</button>`;
    })}
  </nav>`;
}

// The first card in the viewport, just below the sticky toolbar.
function cardUnderToolbar(side = "left") {
  const grid = document.querySelector(".library .grid");
  const bar = document.querySelector(".toolbar");
  if (!grid || !bar) return null;
  const box = grid.getBoundingClientRect();
  const y = bar.getBoundingClientRect().bottom + 40;
  const at = (x) => document.elementFromPoint(x, y)?.closest("[data-offset]") || null;
  return (side === "right" && at(box.right - 12)) || at(box.left + 12);
}

function LibraryGrid({ library, filters }) {
  const isTV = library.type === "tv";
  const kind = isTV ? "tv" : undefined;
  const filterKey = `${library.id}:${JSON.stringify(filters)}`;
  // The loaded window is a list of contiguous chunks. It usually starts at 0;
  // after a letter jump it starts mid-library and grows in both directions.
  const [opened, setOpened] = useState({ key: filterKey, chunks: openWindows.get(filterKey) || FIRST_CHUNK });
  const chunks = opened.key === filterKey ? opened.chunks : openWindows.get(filterKey) || FIRST_CHUNK;
  const setChunks = (next) => setOpened({ key: filterKey, chunks: next });
  const [, force] = useState(0);
  const sentinel = useRef(null);
  const topSentinel = useRef(null);
  const prevBusy = useRef(false);
  const anchor = useRef(null);
  const pendingJump = useRef(null);
  const [activeLetter, setActiveLetter] = useState("");
  useEffect(() => { openWindows.set(filterKey, chunks); }, [filterKey, chunks]);

  const pageOf = (c) => R.libraryPage(library, filters, c.offset, c.limit);
  const results = useResources(chunks.map(pageOf));
  const base = chunks[0].offset;
  const rows = [];
  let complete = false;
  let pending = false;
  let error = null;
  for (let i = 0; i < results.length; i++) {
    const r = results[i];
    if (r.error) { error = r.error; break; }
    if (!r.data) { pending = true; break; }
    rows.push(...r.data);
    if (r.data.length < chunks[i].limit) { complete = true; break; }
  }

  // Load the next chunk before the user reaches the end.
  useEffect(() => {
    const el = sentinel.current;
    if (!el || complete || pending) return undefined;
    const io = new IntersectionObserver((entries) => {
      if (!entries.some((e) => e.isIntersecting)) return;
      const last = chunks[chunks.length - 1];
      setChunks([...chunks, { offset: last.offset + last.limit, limit: PAGE }]);
    }, { rootMargin: "1400px 0px" });
    io.observe(el);
    return () => io.disconnect();
  }, [complete, pending, chunks, filterKey]);

  // After a jump, scrolling up loads what comes before the jump point. The
  // chunk is fetched first so the grid never blanks while it arrives.
  useEffect(() => {
    const el = topSentinel.current;
    if (!el || base <= 0 || pending) return undefined;
    const io = new IntersectionObserver(async (entries) => {
      if (!entries.some((e) => e.isIntersecting) || prevBusy.current) return;
      prevBusy.current = true;
      const start = Math.max(0, base - PAGE);
      const chunk = { offset: start, limit: base - start };
      try {
        await fetchNow(pageOf(chunk));
        const card = cardUnderToolbar();
        anchor.current = { base: start, offset: card ? Number(card.dataset.offset) : base, top: card?.getBoundingClientRect().top ?? 0 };
        setChunks([chunk, ...chunks]);
      } catch {
        prevBusy.current = false;
      }
    }, { rootMargin: "300px 0px" });
    io.observe(el);
    return () => io.disconnect();
  }, [base, pending, chunks, filterKey]);

  const scrollToCard = (offset) => {
    const card = document.querySelector(`.library [data-offset="${offset}"]`);
    if (!card) return false;
    // Where the sticky toolbar ends once stuck, not where it is right now: near
    // the top of the page it still sits lower and moves up as the page scrolls.
    const topbar = document.querySelector(".topbar")?.getBoundingClientRect().bottom || 64;
    const bar = topbar + (document.querySelector(".toolbar")?.getBoundingClientRect().height || 60);
    window.scrollTo({ top: Math.max(0, window.scrollY + card.getBoundingClientRect().top - bar - 14), behavior: "instant" });
    return true;
  };

  // Puts a card back at a viewport position. Measuring the card rather than
  // the page height matters: off-screen cards only have an estimated height
  // (content-visibility), so the page height changes again once they lay out.
  const holdCard = (offset, top) => {
    const card = document.querySelector(`.library [data-offset="${offset}"]`);
    if (!card) return;
    const delta = card.getBoundingClientRect().top - top;
    if (Math.abs(delta) > 0.5) window.scrollBy({ top: delta, behavior: "instant" });
  };

  useLayoutEffect(() => {
    // Keep the viewport on the same cards after prepending.
    if (anchor.current && anchor.current.base === base) {
      const { offset, top } = anchor.current;
      anchor.current = null;
      holdCard(offset, top);
      requestAnimationFrame(() => {
        holdCard(offset, top);
        requestAnimationFrame(() => holdCard(offset, top));
      });
      prevBusy.current = false;
    }
    if (pendingJump.current != null && scrollToCard(pendingJump.current)) {
      const target = pendingJump.current;
      pendingJump.current = null;
      // Off-screen cards start at an estimated height; correct once laid out.
      requestAnimationFrame(() => scrollToCard(target));
    }
  });

  const railAllowed = !filters.sort && !filters.seen && !filters.minRating;
  const { data: alphabet } = useResource(railAllowed ? R.alphabet(library.id, kind, { genre: filters.genre, decades: filters.decades }) : null);
  const letters = railAllowed && alphabet?.length > 1 ? alphabet : null;

  const jump = async (entry) => {
    setActiveLetter(String(entry.letter || "#").toUpperCase());
    if (entry.offset >= base && entry.offset < base + rows.length) {
      scrollToCard(entry.offset);
      return;
    }
    const chunk = { offset: entry.offset, limit: PAGE };
    try {
      await fetchNow(pageOf(chunk));
      pendingJump.current = entry.offset;
      prevBusy.current = false;
      setChunks([chunk]);
    } catch { /* keep the current window */ }
  };

  // The active letter follows the first card under the toolbar.
  useEffect(() => {
    if (!letters) return undefined;
    let frame = 0;
    const update = () => {
      frame = 0;
      // The row's last card: a row that starts in S and reaches T is where
      // "T" begins, and it is where a jump to T lands.
      const card = cardUnderToolbar("right");
      if (!card) return;
      const offset = Number(card.dataset.offset);
      let current = letters[0];
      for (const e of letters) { if (e.offset <= offset) current = e; else break; }
      setActiveLetter(String(current.letter || "#").toUpperCase());
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update); };
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, [letters, base, rows.length]);

  // The total comes from the letter index whenever it applies (its counts add
  // up to the list); otherwise say how many are shown and whether more exist.
  const noun = isTV ? "shows" : "movies";
  const total = railAllowed && alphabet
    ? `${alphabet.reduce((n, l) => n + (l.count || 0), 0)} ${noun}`
    : complete && base === 0 ? `${rows.length} ${noun}` : rows.length ? `${rows.length}+ ${noun}` : "";

  return html`<main class=${cx("page", "library", letters && "has-rail")}>
    <header class="page-head">
      <h1 class="display">${library.name}</h1>
    </header>
    <${Toolbar} library=${library} filters=${filters} total=${total} />
    ${letters && html`<${AlphaRail} entries=${letters} active=${activeLetter} onJump=${jump} />`}
    <div ref=${topSentinel} class="sentinel" aria-hidden="true"></div>
    ${error && !rows.length ? html`<${ErrorState} error=${error} retry=${() => force((n) => n + 1)} />`
      : complete && !rows.length ? html`<${Empty} title="Nothing matches these filters">Try removing a filter.<//>`
      : html`<div class="grid">
          ${rows.map((row, i) => isTV
            ? html`<${ShowCard} key=${`${row.libraryId}:${row.title}`} show=${row} eager=${i < 12} offset=${base + i} />`
            : html`<${ItemCard} key=${row.id} item=${row} eager=${i < 12} offset=${base + i} />`)}
          ${!complete && Array.from({ length: rows.length ? 6 : 18 }, (_, i) => html`<${SkeletonCard} key=${`s${i}`} />`)}
        </div>`}
    <div ref=${sentinel} class="sentinel" aria-hidden="true"></div>
  </main>`;
}

export function prefetchLibrary(library, query) {
  if (!library) return;
  prefetch(R.libraryPage(library, readFilters(query || new URLSearchParams()), 0, PAGE));
}
