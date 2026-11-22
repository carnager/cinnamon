// Home: a rotating hero over the server's recommendations, then the shelves
// the user's home layout asks for.
import { html, useState, useEffect, cx } from "../lib/html.js";
import { useResource, seed } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { Link } from "../lib/router.js";
import { paths, formatRuntime, episodeCode, firstGenre } from "../lib/paths.js";
import { useUserState, itemProgress } from "../lib/userstate.js";
import { Artwork, hasArtwork } from "../ui/artwork.js";
import { ItemCard, ShowCard, SkeletonCard } from "../ui/cards.js";
import { Shelf } from "../ui/shelf.js";
import { Icon } from "../ui/icons.js";
import { ErrorState } from "../ui/controls.js";
import { playItem } from "../lib/play.js";

const HERO_ROTATE_MS = 9000;

function heroEntries(home) {
  const section = (home?.sections || []).find((s) => s.layout === "hero");
  return (section?.entries || [])
    .map((e) => ({ ...e, subject: e.item || e.show, isShow: !e.item && Boolean(e.show) }))
    .filter((e) => e.subject && hasArtwork(e.subject, "backdrop"))
    .slice(0, 6);
}

function Hero({ entries }) {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  useEffect(() => {
    if (paused || entries.length < 2) return undefined;
    const t = setTimeout(() => setIndex((i) => (i + 1) % entries.length), HERO_ROTATE_MS);
    return () => clearTimeout(t);
  }, [index, paused, entries.length]);
  if (!entries.length) return null;
  const entry = entries[Math.min(index, entries.length - 1)];
  const s = entry.subject;
  const progress = !entry.isShow && itemProgress(s.id);
  const resumable = progress && !progress.completed && progress.positionMs > 30_000;
  const meta = [s.rating > 0 && `★ ${s.rating.toFixed(1)}`, s.year, entry.isShow
    ? s.seasonCount && `${s.seasonCount} season${s.seasonCount === 1 ? "" : "s"}`
    : formatRuntime(s.durationMs), firstGenre(s.genres)].filter(Boolean);
  const detailHref = entry.isShow ? paths.show(s.libraryId, s.title) : paths.item(s);
  return html`
    <section class="hero" onPointerEnter=${() => setPaused(true)} onPointerLeave=${() => setPaused(false)}>
      <div class="hero-art">
        ${entries.map((e, i) => html`<div key=${e.key} class=${cx("hero-slide", i === index && "is-active")}>
          <${Artwork} item=${e.subject} kind="backdrop" eager=${i === 0} />
        </div>`)}
      </div>
      <div class="hero-content" key=${entry.key}>
        ${entry.reason && html`<span class="kicker">${entry.reason}</span>`}
        <h1 class="display">${s.title}</h1>
        <p class="hero-meta">${meta.map((m, i) => html`${i ? html`<i>·</i>` : ""}<span>${m}</span>`)}</p>
        ${s.overview && html`<p class="hero-overview">${s.overview}</p>`}
        <div class="hero-actions">
          ${entry.isShow
            ? html`<${Link} href=${detailHref} class="btn btn-primary"><${Icon} name="play" size=${18} /> View show<//>`
            : html`<button type="button" class="btn btn-primary" onClick=${() => playItem(s)}>
                <${Icon} name="play" size=${18} /> ${resumable ? "Resume" : "Play"}</button>`}
          <${Link} href=${detailHref} class="btn btn-glass" onPointerEnter=${() => seed(entry.isShow ? `show:${s.libraryId}:${s.title}` : `item:${s.id}`, s)}>More info<//>
        </div>
      </div>
      ${entries.length > 1 && html`<div class="hero-dots" role="tablist" aria-label="Featured">
        ${entries.map((e, i) => html`<button type="button" key=${e.key} role="tab" aria-selected=${i === index}
          aria-label=${e.subject.title} class=${cx(i === index && "is-active")} onClick=${() => setIndex(i)}></button>`)}
      </div>`}
    </section>`;
}

// Wide card for in-progress items: backdrop, progress, time left.
function ContinueCard({ item }) {
  useUserState();
  const p = itemProgress(item.id);
  const fraction = p && p.durationMs ? Math.min(1, p.positionMs / p.durationMs) : 0;
  const left = p && p.durationMs ? Math.max(0, p.durationMs - p.positionMs) : 0;
  const title = item.kind === "episode" ? item.showTitle : item.title;
  const sub = item.kind === "episode"
    ? [episodeCode(item), item.episodeTitle].filter(Boolean).join(" · ")
    : [item.year, firstGenre(item.genres)].filter(Boolean).join(" · ");
  return html`
    <div class="continue-card">
      <button type="button" class="continue-play" aria-label=${`Resume ${title}`} onClick=${() => playItem(item)}>
        <${Artwork} item=${item} kind="backdrop" width=${640} />
        <span class="continue-overlay"><${Icon} name="play" size=${26} /></span>
        <div class="card-progress"><span style=${`width:${fraction * 100}%`}></span></div>
      </button>
      <${Link} href=${paths.item(item)} class="continue-text" onPointerEnter=${() => seed(`item:${item.id}`, item)}>
        <strong>${title}</strong>
        <span>${sub}</span>
        ${left > 0 && html`<em>${formatRuntime(left)} left</em>`}
      <//>
    </div>`;
}

function HomeSection({ section }) {
  const items = section.items || [];
  const shows = section.shows || [];
  if (!items.length && !shows.length) return null;
  const count = items.length + shows.length;
  if (section.layout === "progress") {
    return html`<${Shelf} title=${section.title} count=${count} class="shelf-wide">
      ${items.slice(0, 24).map((item) => html`<${ContinueCard} key=${item.id} item=${item} />`)}
    <//>`;
  }
  return html`<${Shelf} title=${section.title} count=${count}>
    ${shows.slice(0, 30).map((show) => html`<${ShowCard} key=${`${show.libraryId}:${show.title}`} show=${show} />`)}
    ${items.slice(0, 30).map((item) => html`<${ItemCard} key=${item.id} item=${item} />`)}
  <//>`;
}

export function HomeView() {
  const { data: home, error, loading } = useResource(R.home());
  if (error) return html`<main class="page"><${ErrorState} error=${error} /></main>`;
  if (loading) {
    return html`<main class="page home is-loading">
      <section class="hero is-skeleton"></section>
      <div class="shelves">${[0, 1].map((i) => html`<section class="shelf" key=${i}><header class="shelf-head"><h2> </h2></header>
        <div class="shelf-row">${Array.from({ length: 8 }, (_, j) => html`<${SkeletonCard} key=${j} />`)}</div></section>`)}</div>
    </main>`;
  }
  const hero = heroEntries(home);
  const sections = (home.sections || []).filter((s) => s.layout === "poster" || s.layout === "progress");
  return html`<main class=${cx("page", "home", hero.length && "has-hero")}>
    <${Hero} entries=${hero} />
    <div class="shelves">${sections.map((s) => html`<${HomeSection} key=${s.id} section=${s} />`)}</div>
  </main>`;
}

export function prefetchHome() {
  // Home data is always loaded at startup; nothing extra to warm.
}
