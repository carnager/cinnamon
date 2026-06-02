function posterBlock(item, fallbackTitle, badges = {}) {
  const poster = el("div", "poster");
  if (item?.posterPath) {
    poster.style.backgroundImage = `url(${imageURL(item, "poster")})`;
  } else {
    poster.textContent = (fallbackTitle || "?").slice(0, 1).toUpperCase();
  }
  const badgeStack = posterBadges(badges);
  if (badgeStack) poster.append(badgeStack);
  if (item?.rating) poster.append(ratingBadge(item.rating, "poster-rating"));
  return poster;
}

function posterBadges({ seen = false, watchlisted = false } = {}) {
  if (!seen && !watchlisted) return null;
  const stack = el("div", "poster-badges");
  if (seen) stack.append(el("span", "poster-badge seen", "Seen"));
  if (watchlisted) stack.append(el("span", "poster-badge watchlist", "Watchlist"));
  return stack;
}

function imageURL(item, kind) {
  return `/api/items/${item.id}/image/${kind}?v=${encodeURIComponent(`${item.id}-${item.mtimeUnix || 0}`)}`;
}

function ratingBadge(rating, className) {
  return el("div", className, `\u2605 ${Number(rating).toFixed(1)}`);
}

function sourceRatingBadge(label, value) {
  const badge = el("span", "source-rating");
  badge.append(el("span", "source-rating-label", label), el("span", "source-rating-value", value));
  return badge;
}

function itemCard(item) {
  const card = el("button", "item");
  card.type = "button";
  card.addEventListener("click", () => openDetail(item).catch(console.error));

  const poster = posterBlock(item, item.title, { seen: itemSeen(item), watchlisted: itemWatchlisted(item) });

  // Hover overlay
  const overlay = el("div", "poster-overlay");
  const oTitle = el("div", "overlay-title", item.kind === "episode" ? (item.episodeTitle || item.title) : item.title);
  const metaText = item.kind === "episode"
    ? [`S${String(item.seasonNumber || 0).padStart(2, "0")}E${String(item.episodeNumber || 0).padStart(2, "0")}`, fmtDuration(item.durationMs)].filter(Boolean).join(" \u00b7 ")
    : [item.year || "", fmtDuration(item.durationMs)].filter(Boolean).join(" \u00b7 ");
  const oMeta = el("div", "overlay-meta", metaText);
  overlay.append(oTitle, oMeta);

  if (item.rating) {
    overlay.append(ratingBadge(item.rating, "overlay-rating"));
  }
  poster.append(overlay);

  const title = el("div", "title", item.kind === "episode" ? (item.episodeTitle || item.title) : item.title);
  const meta = el("div", "meta", metaText);

  card.append(poster, title, meta);
  return card;
}

function showCard(show) {
  const card = el("button", "item show-card");
  card.type = "button";
  card.addEventListener("click", () => openShow(show));

  const poster = posterBlock({ ...show.posterItem, rating: showRating(show) }, show.title, {
    seen: showSeen(show),
    watchlisted: showWatchlisted(show),
  });
  const seasons = new Set(show.episodes.map((e) => e.seasonNumber).filter(Boolean)).size;
  const metaText = `${seasons || 1} seasons \u00b7 ${show.episodes.length} episodes`;

  const overlay = el("div", "poster-overlay");
  overlay.append(el("div", "overlay-title", show.title), el("div", "overlay-meta", metaText));
  poster.append(overlay);

  card.append(poster, el("div", "title", show.title), el("div", "meta", metaText));
  return card;
}

function showRating(show) {
  const rated = show.episodes.find((episode) => episode.rating);
  return rated?.rating || 0;
}

function renderGrid(items) {
  const grid = el("div", "grid");
  for (const item of items) grid.append(itemCard(item));
  return grid;
}

async function renderUsers() {
  stopPlayer();
  const users = await api("/api/users");
  const frag = document.createDocumentFragment();
  frag.append(makeBreadcrumb([
    { label: activeLibrary()?.name || "Library", action: () => render() },
    { label: "Users" },
  ]));

  const wrap = el("div", "users-view");
  const header = el("div", "view-header users-header");
  header.append(el("h1", null, "Users"), el("span", null, `${users.length} accounts`));

  const form = el("form", "user-form");
  form.append(
    inputField("Username", "text", "username", true),
    inputField("Display Name", "text", "displayName", false),
    inputField("Password", "password", "password", true),
  );
  const adminLabel = el("label", "toggle user-admin");
  const adminInput = document.createElement("input");
  adminInput.type = "checkbox";
  adminInput.name = "isAdmin";
  adminLabel.append(adminInput, document.createTextNode("Admin"));
  const submit = el("button", "primary", "Add User");
  submit.type = "submit";
  form.append(adminLabel, submit);

  const error = el("div", "form-error");
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    error.textContent = "";
    submit.disabled = true;
    const data = new FormData(form);
    try {
      await api("/api/users", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username: data.get("username"),
          displayName: data.get("displayName"),
          password: data.get("password"),
          isAdmin: data.get("isAdmin") === "on",
        }),
      });
      await renderUsers();
    } catch (err) {
      error.textContent = cleanError(err);
    } finally {
      submit.disabled = false;
    }
  });
  form.append(error);

  const list = el("div", "user-list");
  for (const user of users) {
    const row = el("div", "user-row");
    row.append(
      el("div", "user-row-name", user.displayName || user.username),
      el("div", "user-row-meta", `${user.username}${user.isAdmin ? " · admin" : ""}`),
    );
    list.append(row);
  }
  wrap.append(header, form, list);
  frag.append(wrap);
  setView(frag);
}

function inputField(label, type, name, required) {
  const field = el("label", "field");
  field.append(el("span", null, label));
  const input = document.createElement("input");
  input.type = type;
  input.name = name;
  input.required = required;
  field.append(input);
  return field;
}

/* ── Episode Row ── */
function episodeRow(episode) {
  const row = el("button", "episode-row");
  row.type = "button";
  row.addEventListener("click", () => openDetail(episode).catch(console.error));

  // Thumbnail with episode number badge
  const thumb = el("div", "ep-thumb");
  if (episode.backdropPath) {
    thumb.style.backgroundImage = `url(${imageURL(episode, "backdrop")})`;
  } else if (episode.posterPath) {
    thumb.style.backgroundImage = `url(${imageURL(episode, "poster")})`;
  }
  const epNum = String(episode.episodeNumber || 0).padStart(2, "0");
  thumb.append(el("span", "ep-thumb-badge", epNum));
  const badges = posterBadges({ seen: itemSeen(episode), watchlisted: itemWatchlisted(episode) });
  if (badges) thumb.append(badges);

  // Info
  const info = el("div", "ep-info");
  info.append(el("div", "ep-title", episode.episodeTitle || episode.title));
  if (episode.overview) {
    info.append(el("div", "ep-overview", episode.overview));
  }

  // Meta (duration + rating)
  const meta = el("div", "ep-meta");
  meta.append(el("span", "ep-duration", fmtDuration(episode.durationMs)));
  if (episode.rating) {
    meta.append(el("span", "ep-rating", `\u2605 ${Number(episode.rating).toFixed(1)}`));
  }

  row.append(thumb, info, meta);
  return row;
}

/* ── Detail View ── */
async function openDetail(item, skipHistory) {
  currentItem = item;
  selectedAudio = "";
  selectedSubtitle = "";
  const [streams, externalRatings] = await Promise.all([
    api(`/api/items/${item.id}/streams`),
    api(`/api/items/${item.id}/ratings`).catch(() => null),
  ]);
  const audioStreams = streams.filter((s) => s.type === "audio");
  const subtitleStreams = streams.filter((s) => s.type === "subtitle");
  const defaultAudio = audioStreams.find((s) => s.default) || audioStreams[0];
  if (defaultAudio) selectedAudio = String(defaultAudio.index);

  stopPlayer();
  if (!skipHistory) pushState({ view: "detail", libraryId: activeLibraryId, itemId: item.id });

  const frag = document.createDocumentFragment();
  const library = activeLibrary();

  // Breadcrumb
  const crumbs = [{ label: library?.name || "Library", action: () => render() }];
  if (item.kind === "episode" && currentShow) {
    crumbs.push({ label: currentShow.title, action: () => openShow(currentShow) });
  }
  crumbs.push({ label: item.kind === "episode" ? (item.episodeTitle || item.title) : item.title });
  frag.append(makeBreadcrumb(crumbs));

  // Hero backdrop
  if (item.backdropPath) {
    const hero = el("div", "detail-hero");
    const bd = el("div", "detail-backdrop");
    bd.style.backgroundImage = `url(${imageURL(item, "backdrop")})`;
    hero.append(bd, el("div", "detail-backdrop-overlay"));
    frag.append(hero);
  }

  // Detail layout
  const detail = el("article", "detail");
  const poster = posterBlock(item, item.title, { seen: itemSeen(item), watchlisted: itemWatchlisted(item) });
  poster.classList.add("detail-poster");

  const body = el("div", "detail-body");
  body.append(el("h1", null, item.title));

  // Meta line
  const meta = el("div", "detail-meta");
  const parts = detailMetaParts(item);
  parts.forEach((part, i) => {
    if (i > 0) meta.append(el("span", "meta-dot"));
    meta.append(el("span", null, part));
  });
  const hasSourceRatings = Boolean(externalRatings?.imdbRating || externalRatings?.tmdbRating || externalRatings?.rottenTomatoesRating);
  if (item.rating && !hasSourceRatings) {
    meta.append(el("span", "detail-rating", `\u2605 ${Number(item.rating).toFixed(1)}`));
  }
  if (externalRatings?.imdbRating) {
    meta.append(sourceRatingBadge("IMDb", Number(externalRatings.imdbRating).toFixed(1)));
  }
  if (externalRatings?.tmdbRating) {
    meta.append(sourceRatingBadge("TMDb", Number(externalRatings.tmdbRating).toFixed(1)));
  }
  if (externalRatings?.rottenTomatoesRating) {
    meta.append(sourceRatingBadge("RT", `${externalRatings.rottenTomatoesRating}%`));
  }
  body.append(meta);

  // Genres
  if (item.genres) {
    const genreList = item.genres.split(/[,/]/).map((g) => g.trim()).filter(Boolean);
    if (genreList.length) {
      const genres = el("div", "detail-genres");
      for (const g of genreList) genres.append(el("span", "genre-pill", g));
      body.append(genres);
    }
  }

  // Overview
  if (item.overview || item.tagline) {
    body.append(el("p", "overview", item.overview || item.tagline));
  }

  // Stream selectors
  const selectors = el("div", "stream-selectors");
  selectors.append(
    selectField("Audio", audioStreams, selectedAudio, (v) => { selectedAudio = v; }),
    selectField("Subtitles", subtitleStreams, selectedSubtitle, (v) => { selectedSubtitle = v; }, true),
  );
  body.append(selectors);

  // Play button
  const actions = el("div", "detail-actions");
  const playBtn = el("button", "primary detail-play", "Play");
  playBtn.type = "button";
  playBtn.addEventListener("click", () => play(item));
  actions.append(playBtn);
  body.append(actions);

  detail.append(poster, body);
  frag.append(detail);
  setView(frag);
}

/* ── Show View ── */
function openShow(show, skipHistory) {
  currentShow = show;
  stopPlayer();
  if (!skipHistory) pushState({ view: "show", libraryId: activeLibraryId, showTitle: show.title });

  const frag = document.createDocumentFragment();
  const library = activeLibrary();

  frag.append(makeBreadcrumb([
    { label: library?.name || "Library", action: () => render() },
    { label: show.title },
  ]));

  // Hero backdrop
  const backdropEp = show.episodes.find((ep) => ep.backdropPath);
  if (backdropEp) {
    const hero = el("div", "detail-hero");
    const bd = el("div", "detail-backdrop");
    bd.style.backgroundImage = `url(${imageURL(backdropEp, "backdrop")})`;
    hero.append(bd, el("div", "detail-backdrop-overlay"));
    frag.append(hero);
  }

  // Show header
  const header = el("div", "show-detail");
  const poster = posterBlock(show.posterItem, show.title, { seen: showSeen(show), watchlisted: showWatchlisted(show) });
  poster.classList.add("detail-poster");

  const body = el("div", "detail-body");
  body.append(el("h1", null, show.title));

  const seasonCount = new Set(show.episodes.map((e) => e.seasonNumber).filter(Boolean)).size || 1;
  body.append(el("div", "detail-meta", `${seasonCount} seasons \u00b7 ${show.episodes.length} episodes`));

  const overview = show.episodes.find((e) => e.overview)?.overview;
  if (overview) body.append(el("p", "overview", overview));

  // Genre pills
  const genreEp = show.episodes.find((e) => e.genres);
  if (genreEp?.genres) {
    const genreList = genreEp.genres.split(/[,/]/).map((g) => g.trim()).filter(Boolean);
    if (genreList.length) {
      const genres = el("div", "detail-genres");
      for (const g of genreList) genres.append(el("span", "genre-pill", g));
      body.append(genres);
    }
  }

  header.append(poster, body);
  frag.append(header, seasonBrowser(show));
  setView(frag);
}

/* ── Season Browser ── */
function seasonBrowser(show) {
  const wrap = el("div", "season-browser");
  const seasons = groupSeasons(show.episodes);
  const nav = el("div", "season-nav");
  const content = el("div", "season-content");

  const renderSeason = (season) => {
    content.innerHTML = "";
    for (const btn of nav.querySelectorAll("button")) {
      btn.classList.toggle("active", btn.dataset.season === String(season.number));
    }
    const list = el("div", "episode-list");
    for (const episode of season.episodes) list.append(episodeRow(episode));
    content.append(list);
  };

  for (const season of seasons) {
    const tab = el("button", "season-tab");
    tab.type = "button";
    tab.dataset.season = String(season.number);
    const label = season.number ? `Season ${season.number}` : "Specials";
    tab.append(document.createTextNode(label));
    const count = el("span", "season-ep-count", `(${season.episodes.length})`);
    tab.append(count);
    tab.addEventListener("click", () => renderSeason(season));
    nav.append(tab);
  }

  wrap.append(nav, content);
  if (seasons[0]) setTimeout(() => renderSeason(seasons[0]), 0);
  return wrap;
}

function groupSeasons(episodes) {
  const seasons = new Map();
  for (const episode of episodes) {
    const key = episode.seasonNumber || 0;
    if (!seasons.has(key)) seasons.set(key, []);
    seasons.get(key).push(episode);
  }
  return [...seasons.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([number, eps]) => ({ number, episodes: eps }));
}

/* ── Helpers ── */
function detailMetaParts(item) {
  const parts = [];
  if (item.kind === "episode") {
    if (item.showTitle) parts.push(item.showTitle);
    if (item.seasonNumber || item.episodeNumber) {
      parts.push(`S${String(item.seasonNumber || 0).padStart(2, "0")}E${String(item.episodeNumber || 0).padStart(2, "0")}`);
    }
  } else if (item.year) {
    parts.push(String(item.year));
  }
  if (item.durationMs) parts.push(fmtDuration(item.durationMs));
  if (item.videoCodec) parts.push(item.videoCodec.toUpperCase());
  if (item.width && item.height) parts.push(`${item.width}\u00d7${item.height}`);
  return parts;
}

function selectField(label, streams, value, onChange, allowNone = false) {
  const field = el("label", "field stream-field");
  field.append(el("span", null, label));
  const select = document.createElement("select");
  if (allowNone) {
    const none = document.createElement("option");
    none.value = "";
    none.textContent = "None";
    select.append(none);
  }
  for (const stream of streams) {
    const option = document.createElement("option");
    option.value = String(stream.index);
    option.textContent = streamLabel(stream);
    select.append(option);
  }
  select.value = value;
  select.addEventListener("change", () => onChange(select.value));
  field.append(select);
  return field;
}

function streamLabel(stream) {
  return [
    stream.language || "und",
    stream.title || "",
    stream.codec || "",
    stream.default ? "default" : "",
    stream.forced ? "forced" : "",
  ].filter(Boolean).join(" \u00b7 ");
}

