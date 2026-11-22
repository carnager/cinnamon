// Show page: header like a detail page, season tabs (in the URL, replaced not
// pushed), and episodes as a list with stills, descriptions and state.
import { html, cx } from "../lib/html.js";
import { useResource, useResources, prefetch, seed } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { Link, replaceQuery } from "../lib/router.js";
import { paths, formatRuntime, genreList } from "../lib/paths.js";
import {
  useUserState, itemProgress, isItemSeen, resumeFraction,
  isShowSeen, isShowWatchlisted, showProgress, showRating,
  setItemSeen, setSeasonSeen, setShowSeen, setShowWatchlisted, setShowRating,
} from "../lib/userstate.js";
import { playItem } from "../lib/play.js";
import { Artwork } from "../ui/artwork.js";
import { Icon } from "../ui/icons.js";
import { RatingStars, ToggleButton, ErrorState, Empty } from "../ui/controls.js";

function EpisodeRow({ episode }) {
  useUserState();
  const seen = isItemSeen(episode.id);
  const resume = resumeFraction(episode.id);
  const title = episode.episodeTitle || episode.title;
  return html`<li class=${cx("episode", seen && "is-seen")}>
    <button type="button" class="episode-still" aria-label=${`Play ${title}`} onClick=${() => playItem(episode)}>
      <${Artwork} item=${episode} kind="backdrop" width=${480} />
      <span class="episode-play"><${Icon} name="play" size=${22} /></span>
      ${resume > 0 && html`<div class="card-progress"><span style=${`width:${resume * 100}%`}></span></div>`}
    </button>
    <${Link} href=${paths.item(episode)} class="episode-text" onPointerEnter=${() => seed(`item:${episode.id}`, episode)}>
      <span class="episode-num">${episode.episodeNumber}</span>
      <strong>${title}</strong>
      <span class="episode-meta">${[formatRuntime(episode.durationMs), episode.premiered || episode.aired].filter(Boolean).join(" · ")}</span>
      ${episode.overview && html`<p>${episode.overview}</p>`}
    <//>
    <button type="button" class=${cx("episode-seen", seen && "is-on")} aria-pressed=${seen}
      title=${seen ? "Mark as unseen" : "Mark as seen"} aria-label=${seen ? "Mark as unseen" : "Mark as seen"}
      onClick=${() => setItemSeen(episode, !seen).catch(() => {})}>
      <${Icon} name=${seen ? "checkCircle" : "checkCircle"} size=${22} />
    </button>
  </li>`;
}

function Season({ show, season }) {
  const { data: episodes, error } = useResource(R.episodes(show.libraryId, show.title, season));
  useUserState();
  if (error) return html`<${ErrorState} error=${error} />`;
  if (!episodes) return html`<ul class="episodes is-loading">${[0, 1, 2].map((i) => html`<li class="episode is-skeleton" key=${i}><div class="episode-still"><div class="art"></div></div><div class="episode-text"></div></li>`)}</ul>`;
  if (!episodes.length) return html`<${Empty} title="No episodes in this season" />`;
  const allSeen = episodes.every((e) => isItemSeen(e.id));
  return html`<div class="season">
    <div class="season-tools">
      <span>${episodes.length} episode${episodes.length === 1 ? "" : "s"}</span>
      <button type="button" class="btn btn-text" onClick=${() => setSeasonSeen(show, season, !allSeen, episodes).catch(() => {})}>
        <${Icon} name="checkCircle" size=${18} /> ${allSeen ? "Mark season unseen" : "Mark season seen"}
      </button>
    </div>
    <ul class="episodes">${episodes.map((e) => html`<${EpisodeRow} key=${e.id} episode=${e} />`)}</ul>
  </div>`;
}

// The episode to offer under "Play": the first unfinished one across all
// seasons in order, specials (season 0) last.
function nextEpisode(seasonLists) {
  const ordered = seasonLists
    .filter((list) => list?.length)
    .sort((a, b) => ((a[0].seasonNumber || 99) - (b[0].seasonNumber || 99)));
  for (const list of ordered) {
    const hit = list.find((e) => !isItemSeen(e.id));
    if (hit) return hit;
  }
  return null;
}

export function ShowView({ libraryId, title, query }) {
  useUserState();
  const { data: show, error } = useResource(R.show(libraryId, title));
  const { data: seasons } = useResource(R.seasons(libraryId, title));
  const seasonNumbers = (seasons || []).map((s) => s.seasonNumber).sort((a, b) => a - b);
  const requested = query.get("season");
  const progress = showProgress(libraryId, title);
  // Episodes of every season (a show has few; each list is cached) so "Play"
  // can offer the next unwatched episode wherever it is.
  const allSeasons = useResources(seasonNumbers.map((n) => R.episodes(libraryId, title, n)));
  const next = nextEpisode(allSeasons.map((r) => r.data));
  // Open the season the user is in the middle of, unless one was asked for.
  const season = requested != null ? Number(requested)
    : next?.seasonNumber ?? seasonNumbers.find((n) => n > 0) ?? seasonNumbers[0] ?? 1;

  if (error) return html`<main class="page"><${ErrorState} error=${error} /></main>`;
  if (show === null) return html`<main class="page"><${Empty} title="Show not found" /></main>`;
  if (!show) return html`<main class="page detail is-loading"><div class="detail-backdrop"></div></main>`;

  const seen = isShowSeen(libraryId, title);
  const watchlisted = isShowWatchlisted(libraryId, title);
  const meta = [
    show.year,
    show.seasonCount && `${show.seasonCount} season${show.seasonCount === 1 ? "" : "s"}`,
    show.episodeCount && `${show.episodeCount} episodes`,
    progress?.episodeCount && progress.completedCount ? `${progress.completedCount}/${progress.episodeCount} watched` : "",
  ].filter(Boolean);

  return html`<main class="page detail show">
    <div class="detail-backdrop"><${Artwork} item=${show} kind="backdrop" eager /></div>
    <div class="detail-main">
      <div class="detail-poster"><${Artwork} item=${show} kind="poster" width=${800} eager /></div>
      <div class="detail-info">
        <h1 class="display">${show.title}</h1>
        <p class="meta">${meta.map((m, i) => html`${i ? html`<i>·</i>` : ""}<span>${m}</span>`)}
          ${show.rating > 0 && html`<span class="chip">★ ${show.rating.toFixed(1)}</span>`}</p>
        ${genreList(show.genres).length > 0 && html`<p class="genres">${genreList(show.genres).map((g) => html`<span>${g}</span>`)}</p>`}
        <div class="actions">
          ${next && html`<button type="button" class="btn btn-primary btn-lg" onClick=${() => playItem(next)}>
            <${Icon} name="play" size=${20} /> ${itemProgress(next.id)?.positionMs > 30_000 ? "Resume" : "Play"} S${next.seasonNumber}·E${next.episodeNumber}
          </button>`}
          <${ToggleButton} on=${watchlisted} onLabel="On watchlist" offLabel="Watchlist" icon="bookmark" onIcon="bookmarkFilled" onClick=${() => setShowWatchlisted(show, !watchlisted)} />
          <${ToggleButton} on=${seen} onLabel="Seen" offLabel="Mark show seen" icon="checkCircle" onClick=${() => setShowSeen(show, !seen)} />
        </div>
        <${RatingStars} value=${showRating(libraryId, title)} onChange=${(n) => setShowRating(show, n)} />
        ${show.overview && html`<div class="overview"><p class="overview-text">${show.overview}</p></div>`}
      </div>
    </div>
    <div class="detail-sections">
      ${seasonNumbers.length > 1 && html`<nav class="tabs" aria-label="Seasons">
        ${seasonNumbers.map((n) => html`<button type="button" key=${n} class=${cx("tab", n === season && "is-active")} aria-current=${n === season ? "true" : undefined}
          onClick=${() => replaceQuery({ season: n })}
          onPointerEnter=${() => prefetch(R.episodes(libraryId, title, n))}>${n === 0 ? "Specials" : `Season ${n}`}</button>`)}
      </nav>`}
      <${Season} key=${season} show=${show} season=${season} />
    </div>
  </main>`;
}

export function prefetchShow(libraryId, title) {
  prefetch(R.show(libraryId, title));
  prefetch(R.seasons(libraryId, title));
}
