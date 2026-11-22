// Movie / episode detail.
//
// Renders in the first frame from whatever the card already knew (seeded
// cache), then fills in the full record, streams, ratings and similar titles.
// Title, actions and the user's state all sit in the first screen, over the
// backdrop.
import { html, useState, cx } from "../lib/html.js";
import { useResource, prefetch, seed } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { Link } from "../lib/router.js";
import {
  paths, formatRuntime, formatClock, formatBytes, endsAt, resolutionLabel, episodeCode, genreList,
} from "../lib/paths.js";
import {
  useUserState, itemProgress, isItemSeen, isItemWatchlisted, isItemExcluded, itemRating,
  setItemSeen, setItemWatchlisted, setItemRating, setItemPreference,
} from "../lib/userstate.js";
import { playItem, playTrailer, searchTrailer } from "../lib/play.js";
import { preferredAudioIndex, preferredSubtitleIndex, isTextSubtitleStream, trackLabel as playerTrackLabel } from "../lib/playback.js";
import { Cast } from "../ui/cast.js";
import { Artwork } from "../ui/artwork.js";
import { ItemCard } from "../ui/cards.js";
import { Shelf } from "../ui/shelf.js";
import { Icon } from "../ui/icons.js";
import { RatingStars, ToggleButton, SelectField, Empty, ErrorState } from "../ui/controls.js";

function Overview({ text, tagline }) {
  const [open, setOpen] = useState(false);
  if (!text && !tagline) return null;
  const long = (text || "").length > 280;
  return html`<div class=${cx("overview", open && "is-open")}>
    ${tagline && html`<p class="tagline">${tagline}</p>`}
    ${text && html`<p class="overview-text">${text}</p>`}
    ${long && html`<button type="button" class="btn btn-text" onClick=${() => setOpen(!open)}>${open ? "Less" : "More"}</button>`}
  </div>`;
}

function ExternalRatings({ ratings, fallback }) {
  const rows = [];
  if (ratings?.imdbRating) rows.push(["IMDb", ratings.imdbRating.toFixed(1)]);
  if (ratings?.tmdbRating) rows.push(["TMDb", ratings.tmdbRating.toFixed(1)]);
  if (ratings?.rottenTomatoesRating) rows.push(["RT", `${ratings.rottenTomatoesRating}%`]);
  if (ratings?.metacriticRating) rows.push(["MC", String(ratings.metacriticRating)]);
  if (!rows.length && fallback > 0) rows.push(["Rating", fallback.toFixed(1)]);
  if (!rows.length) return null;
  return html`<div class="ext-ratings">${rows.map(([k, v]) => html`<span key=${k}><b>${v}</b> ${k}</span>`)}</div>`;
}

function trackLabel(stream) {
  const lang = (stream.language || stream.tags?.language || "").toUpperCase();
  const title = stream.title || stream.tags?.title || "";
  const codec = (stream.codec || "").toUpperCase();
  const names = { 1: "Mono", 2: "Stereo", 6: "5.1", 8: "7.1" };
  const channels = stream.channels ? names[stream.channels] || `${stream.channels}ch` : "";
  return [lang, title, codec, channels, stream.default && "default", stream.forced && "forced"].filter(Boolean).join(" · ");
}

function TechDetails({ item, streams }) {
  const audio = (streams || []).filter((s) => s.type === "audio");
  const subs = (streams || []).filter((s) => s.type === "subtitle");
  const size = formatBytes(item.sizeBytes);
  const rows = [
    ["Video", [resolutionLabel(item), (item.videoCodec || "").toUpperCase(), item.container?.toUpperCase()].filter(Boolean).join(" · ")],
    ["Audio", audio.length ? audio.map(trackLabel) : null],
    ["Subtitles", subs.length ? subs.map(trackLabel) : null],
    ["File", size],
    ["Director", item.directors],
    ["Writers", item.writers],
    ["Studio", item.studios],
    ["Country", item.countries],
  ].filter(([, v]) => v && (!Array.isArray(v) || v.length));
  if (!rows.length) return null;
  return html`<section class="section tech">
    <h2>Details</h2>
    <dl>${rows.map(([k, v]) => html`<div key=${k}><dt>${k}</dt><dd>${Array.isArray(v) ? v.map((line) => html`<span>${line}</span>`) : v}</dd></div>`)}</dl>
  </section>`;
}

// Audio and subtitle choice before pressing Play, preset from preferences.
function TrackPicker({ streams, tracks, setTracks }) {
  const audio = (streams || []).filter((t) => t.type === "audio");
  const subs = (streams || []).filter((t) => t.type === "subtitle" && isTextSubtitleStream(t));
  if (audio.length < 2 && !subs.length) return null;
  const audioValue = tracks.audioIndex !== undefined ? tracks.audioIndex : preferredAudioIndex(audio);
  const subValue = tracks.subtitleIndex !== undefined ? tracks.subtitleIndex : preferredSubtitleIndex(subs);
  return html`<div class="track-picker">
    ${audio.length > 1 && html`<${SelectField} label="Audio" value=${audioValue ?? ""}
      options=${audio.map((t) => [t.index, playerTrackLabel(t)])}
      onChange=${(v) => setTracks((t) => ({ ...t, audioIndex: Number(v) }))} />`}
    ${subs.length > 0 && html`<${SelectField} label="Subtitles" value=${subValue ?? ""}
      options=${[["", "Off"], ...subs.map((t) => [t.index, playerTrackLabel(t)])]}
      onChange=${(v) => setTracks((t) => ({ ...t, subtitleIndex: v === "" ? null : Number(v) }))} />`}
  </div>`;
}

export function DetailView({ itemId, query }) {
  useUserState();
  const { data: item, error } = useResource(R.item(itemId));
  const { data: streams } = useResource(R.itemStreams(itemId));
  const { data: ratings } = useResource(R.itemRatings(itemId));
  const { data: similar } = useResource(item?.kind === "movie" ? R.itemSimilar(itemId) : null);
  const { data: sidecars } = useResource(R.itemSidecars(itemId));
  const [tracks, setTracks] = useState({});
  if (error) return html`<main class="page"><${ErrorState} error=${error} /></main>`;
  if (!item) return html`<main class="page detail is-loading"><div class="detail-backdrop"></div></main>`;

  const isEpisode = item.kind === "episode";
  const progress = itemProgress(item.id);
  const resumeMs = progress && !progress.completed && progress.positionMs > 30_000 ? progress.positionMs : 0;
  const remaining = Math.max(0, (item.durationMs || 0) - resumeMs);
  const seen = isItemSeen(item.id);
  const watchlisted = isItemWatchlisted(item.id);
  const excluded = isItemExcluded(item.id);
  const title = isEpisode ? item.episodeTitle || item.title : item.title;
  const meta = [
    isEpisode ? episodeCode(item) : item.year,
    formatRuntime(item.durationMs),
    remaining > 0 && `Ends ${endsAt(remaining)}`,
  ].filter(Boolean);
  const chips = [item.officialRating, resolutionLabel(item), item.videoCodec?.toUpperCase()].filter(Boolean);
  const showHref = isEpisode ? paths.show(item.libraryId, item.showTitle, item.seasonNumber) : null;

  return html`<main class=${cx("page", "detail", isEpisode && "is-episode")}>
    <div class="detail-backdrop"><${Artwork} item=${item} kind="backdrop" eager /></div>
    <div class="detail-main">
      <div class="detail-poster"><${Artwork} item=${item} kind=${isEpisode ? "backdrop" : "poster"} width=${isEpisode ? 960 : 800} eager /></div>
      <div class="detail-info">
        ${isEpisode && html`<${Link} href=${showHref} class="eyebrow-link">${item.showTitle} · Season ${item.seasonNumber}<//>`}
        <h1 class="display">${title}</h1>
        ${item.originalTitle && item.originalTitle !== item.title && html`<p class="original-title">${item.originalTitle}</p>`}
        <p class="meta">${meta.map((m, i) => html`${i ? html`<i>·</i>` : ""}<span>${m}</span>`)}
          ${chips.map((c) => html`<span class="chip">${c}</span>`)}</p>
        <${ExternalRatings} ratings=${ratings} fallback=${item.rating} />
        ${!isEpisode && genreList(item.genres).length > 0 && html`<p class="genres">${genreList(item.genres).map((g) => html`<span>${g}</span>`)}</p>`}
        <div class="actions">
          <button type="button" class="btn btn-primary btn-lg" onClick=${() => playItem(item, { tracks })}>
            <${Icon} name="play" size=${20} /> ${resumeMs ? `Resume ${formatClock(resumeMs)}` : "Play"}
          </button>
          ${resumeMs > 0 && html`<button type="button" class="btn btn-glass" onClick=${() => playItem(item, { fromStart: true, tracks })}><${Icon} name="restart" size=${18} /> From start</button>`}
          <button type="button" class="btn btn-ghost" onClick=${() => (sidecars?.trailer ? playTrailer(item) : searchTrailer(item))}><${Icon} name="film" size=${18} /> Trailer</button>
          <${ToggleButton} on=${watchlisted} onLabel="On watchlist" offLabel="Watchlist" icon="bookmark" onIcon="bookmarkFilled" onClick=${() => setItemWatchlisted(item, !watchlisted)} />
          <${ToggleButton} on=${seen} onLabel="Seen" offLabel="Mark seen" icon="checkCircle" onClick=${() => setItemPreference(item, seen ? "unwatched" : "seen")} />
          ${!isEpisode && html`<${ToggleButton} on=${excluded} onLabel="Not interested" offLabel="Not interested" icon="eyeOff" onClick=${() => setItemPreference(item, excluded ? "unwatched" : "not_interested")} />`}
          <a class="btn btn-icon" href=${`/api/items/${item.id}/download`} download title=${`Download${item.sizeBytes ? ` · ${formatBytes(item.sizeBytes)}` : ""}`} aria-label="Download"><${Icon} name="download" size=${18} /></a>
        </div>
        <${TrackPicker} streams=${streams} tracks=${tracks} setTracks=${setTracks} />
        ${resumeMs > 0 && html`<div class="resume-bar" title=${`${Math.round((resumeMs / item.durationMs) * 100)}% watched`}><span style=${`width:${(resumeMs / item.durationMs) * 100}%`}></span></div>`}
        ${progress && html`<button type="button" class="btn btn-text reset-progress" onClick=${() => setItemSeen(item, false).catch(() => {})}>Reset progress</button>`}
        <${RatingStars} value=${itemRating(item.id)} onChange=${(n) => setItemRating(item, n)} />
        <${Overview} text=${item.overview} tagline=${item.tagline} />
      </div>
    </div>
    <div class="detail-sections">
      <${Cast} actors=${item.actors} />
      ${similar?.length > 0 && html`<${Shelf} title="More like this">${similar.slice(0, 24).map((s) => html`<${ItemCard} key=${s.id} item=${s} />`)}<//>`}
      <${TechDetails} item=${item} streams=${streams} />
    </div>
  </main>`;
}

export function prefetchDetail(itemId) {
  prefetch(R.item(itemId));
  prefetch(R.itemRatings(itemId));
  prefetch(R.itemStreams(itemId));
}

export { Empty };
