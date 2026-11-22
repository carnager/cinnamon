// Poster cards for movies, episodes and shows.
//
// The card carries its state as corner marks (seen, watchlist, rating) and a
// resume bar, and offers quick actions on hover/focus, so marking something
// seen or saving it no longer means opening its page first.

import { html, cx } from "../lib/html.js";
import { Link } from "../lib/router.js";
import { seed } from "../lib/cache.js";
import { paths, formatRuntime, firstGenre } from "../lib/paths.js";
import {
  useUserState, isItemSeen, isItemWatchlisted, resumeFraction, itemRating,
  isShowSeen, isShowWatchlisted, showProgress, showRating,
  setItemSeen, setItemWatchlisted, setShowSeen, setShowWatchlisted,
} from "../lib/userstate.js";
import { Artwork } from "./artwork.js";
import { Icon } from "./icons.js";

function QuickAction({ label, active, icon, activeIcon, onClick }) {
  const run = (e) => {
    e.preventDefault();
    e.stopPropagation();
    onClick().catch(() => {});
  };
  return html`<button type="button" class=${cx("quick", active && "is-active")} title=${label} aria-label=${label} aria-pressed=${active} onClick=${run}>
    <${Icon} name=${active ? activeIcon : icon} size=${18} />
  </button>`;
}

function Marks({ seen, watchlisted, rating }) {
  return html`<div class="marks">
    ${seen && html`<span class="mark mark-seen" title="Seen"><${Icon} name="check" size=${14} /></span>`}
    ${watchlisted && !seen && html`<span class="mark mark-watchlist" title="On your watchlist"><${Icon} name="bookmarkFilled" size=${13} /></span>`}
    ${rating > 0 && html`<span class="mark mark-rating" title="Your rating">★ ${rating}</span>`}
  </div>`;
}

export function ItemCard({ item, eager = false, subtitle }) {
  useUserState();
  const seen = isItemSeen(item.id);
  const watchlisted = isItemWatchlisted(item.id);
  const resume = resumeFraction(item.id);
  const meta = subtitle ?? [item.year, formatRuntime(item.durationMs)].filter(Boolean).join(" · ");
  const prime = () => seed(`item:${item.id}`, item);
  return html`
    <${Link} href=${paths.item(item)} class=${cx("card", seen && "is-seen")} morph onClick=${prime} onPointerEnter=${prime}>
      <div class="card-art">
        <${Artwork} item=${item} kind="poster" width=${400} eager=${eager} morph />
        <${Marks} seen=${seen} watchlisted=${watchlisted} rating=${itemRating(item.id)} />
        ${item.rating > 0 && html`<span class="card-score">${item.rating.toFixed(1)}</span>`}
        <div class="card-actions">
          <${QuickAction} label=${watchlisted ? "Remove from watchlist" : "Add to watchlist"} active=${watchlisted}
            icon="bookmark" activeIcon="bookmarkFilled" onClick=${() => setItemWatchlisted(item, !watchlisted)} />
          <${QuickAction} label=${seen ? "Mark as unseen" : "Mark as seen"} active=${seen}
            icon="checkCircle" activeIcon="checkCircle" onClick=${() => setItemSeen(item, !seen)} />
        </div>
        ${resume > 0 && html`<div class="card-progress"><span style=${`width:${resume * 100}%`}></span></div>`}
      </div>
      <div class="card-text">
        <strong>${item.title}</strong>
        <span>${meta}${!subtitle && firstGenre(item.genres) ? html` · ${firstGenre(item.genres)}` : ""}</span>
      </div>
    <//>`;
}

export function ShowCard({ show, eager = false }) {
  useUserState();
  const seen = isShowSeen(show.libraryId, show.title);
  const watchlisted = isShowWatchlisted(show.libraryId, show.title);
  const progress = showProgress(show.libraryId, show.title);
  const fraction = progress && !progress.completed && progress.episodeCount
    ? progress.completedCount / progress.episodeCount : 0;
  const meta = [show.year, show.seasonCount ? `${show.seasonCount} season${show.seasonCount === 1 ? "" : "s"}` : ""].filter(Boolean).join(" · ");
  const prime = () => seed(`show:${show.libraryId}:${show.title}`, show);
  return html`
    <${Link} href=${paths.show(show.libraryId, show.title)} class=${cx("card", seen && "is-seen")} morph onClick=${prime} onPointerEnter=${prime}>
      <div class="card-art">
        <${Artwork} item=${show} kind="poster" width=${400} eager=${eager} morph />
        <${Marks} seen=${seen} watchlisted=${watchlisted} rating=${showRating(show.libraryId, show.title)} />
        ${show.rating > 0 && html`<span class="card-score">${show.rating.toFixed(1)}</span>`}
        <div class="card-actions">
          <${QuickAction} label=${watchlisted ? "Remove from watchlist" : "Add to watchlist"} active=${watchlisted}
            icon="bookmark" activeIcon="bookmarkFilled" onClick=${() => setShowWatchlisted(show, !watchlisted)} />
          <${QuickAction} label=${seen ? "Mark show as unseen" : "Mark whole show as seen"} active=${seen}
            icon="checkCircle" activeIcon="checkCircle" onClick=${() => setShowSeen(show, !seen)} />
        </div>
        ${fraction > 0 && html`<div class="card-progress"><span style=${`width:${fraction * 100}%`}></span></div>`}
      </div>
      <div class="card-text">
        <strong>${show.title}</strong>
        <span>${meta}${firstGenre(show.genres) ? html` · ${firstGenre(show.genres)}` : ""}</span>
      </div>
    <//>`;
}

export function SkeletonCard() {
  return html`<div class="card is-skeleton" aria-hidden="true"><div class="card-art"><div class="art"></div></div><div class="card-text"><strong> </strong><span> </span></div></div>`;
}
