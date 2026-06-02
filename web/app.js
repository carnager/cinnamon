const authScreen = document.querySelector("#authScreen");
const appShell = document.querySelector("#appShell");
const loginForm = document.querySelector("#loginForm");
const loginUser = document.querySelector("#loginUser");
const loginPass = document.querySelector("#loginPass");
const loginBtn = document.querySelector("#loginBtn");
const loginError = document.querySelector("#loginError");
const userPanel = document.querySelector("#userPanel");
const view = document.querySelector("#view");
const libraryNav = document.querySelector("#libraryNav");
const search = document.querySelector("#search");
const scan = document.querySelector("#scan");
const statusEl = document.querySelector("#status");
const player = document.querySelector("#player");
const theater = document.querySelector("#theater");
const nowPlaying = document.querySelector("#nowPlaying");
const bandwidth = document.querySelector("#bandwidth");
const transcode = document.querySelector("#transcode");
const timeline = document.querySelector("#timeline");
const currentTimeEl = document.querySelector("#currentTime");
const durationEl = document.querySelector("#duration");
const playPause = document.querySelector("#playPause");
const muteBtn = document.querySelector("#muteBtn");
const volume = document.querySelector("#volume");
const fullscreenBtn = document.querySelector("#fullscreenBtn");
const closePlayerBtn = document.querySelector("#closePlayer");

let libraries = [];
let activeLibraryId = "";
let libraryItems = [];
let currentPage = 1;
const perPage = 60;
let searchTimer = 0;
let currentItem = null;
let streamStart = 0;
let draggingTimeline = false;
let selectedAudio = "";
let selectedSubtitle = "";
let hls = null;
let currentShow = null;
let currentHLSSession = null;
let authToken = localStorage.getItem("popcornToken") || "";
let savedVolume = Number(localStorage.getItem("popcornVolume") || "1");
let currentUser = null;
let statusTimer = 0;
let timelineTimer = 0;
let watchedItemIds = new Set();
let watchedShowKeys = new Set();
let watchlistItemIds = new Set();
let watchlistShowKeys = new Set();

async function api(path, options) {
  const init = { ...(options || {}) };
  const headers = new Headers(init.headers || {});
  if (authToken) headers.set("Authorization", `Bearer ${authToken}`);
  init.headers = headers;
  const res = await fetch(path, init);
  if (!res.ok) throw new Error(await res.text());
  if (res.status === 202 || res.status === 204) return null;
  return res.json();
}

function setAuthenticated(user, token) {
  currentUser = user;
  authToken = token || authToken;
  if (authToken) localStorage.setItem("popcornToken", authToken);
  authScreen.classList.add("hidden");
  appShell.classList.remove("hidden");
  renderUserPanel();
}

function setUnauthenticated(message = "") {
  currentUser = null;
  authToken = "";
  localStorage.removeItem("popcornToken");
  appShell.classList.add("hidden");
  authScreen.classList.remove("hidden");
  loginError.textContent = message;
  loginPass.value = "";
  loginUser.focus();
  clearInterval(statusTimer);
  clearInterval(timelineTimer);
  stopPlayer();
}

function renderUserPanel() {
  userPanel.innerHTML = "";
  if (!currentUser) return;
  const name = el("div", "user-name", currentUser.displayName || currentUser.username);
  const actions = el("div", "user-actions");
  if (currentUser.isAdmin) {
    const users = el("button", "side-btn", "Users");
    users.type = "button";
    users.addEventListener("click", () => renderUsers().catch(console.error));
    actions.append(users);
  }
  const logout = el("button", "side-btn", "Logout");
  logout.type = "button";
  logout.addEventListener("click", logoutUser);
  actions.append(logout);
  userPanel.append(name, actions);
}

async function logoutUser() {
  try {
    await api("/api/auth/logout", { method: "POST" });
  } catch (_) {
    // Clear the local session even if the server session is already gone.
  }
  setUnauthenticated("");
}

function fmtDuration(ms) {
  if (!ms) return "";
  const total = Math.round(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  return h ? `${h}h ${m}m` : `${m}m`;
}

function fmtClock(seconds) {
  seconds = Math.max(0, Math.floor(seconds || 0));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

function movieSeconds(item) {
  return Math.max(0, Math.round((item?.durationMs || 0) / 1000));
}

function activeLibrary() {
  return libraries.find((library) => library.id === activeLibraryId) || libraries[0];
}

function showKey(libraryId, title) {
  return `${String(libraryId || "").toLowerCase()}\u0000${String(title || "").trim().toLowerCase()}`;
}

function itemSeen(item) {
  return watchedItemIds.has(Number(item?.id || 0));
}

function itemWatchlisted(item) {
  return watchlistItemIds.has(Number(item?.id || 0));
}

function showSeen(show) {
  return watchedShowKeys.has(showKey(show?.libraryId, show?.title));
}

function showWatchlisted(show) {
  return watchlistShowKeys.has(showKey(show?.libraryId, show?.title));
}

async function refreshMediaState() {
  if (!authToken) return;
  const [progress, showProgress, watchlist] = await Promise.all([
    loadAllProgress().catch(() => []),
    api("/api/progress/tv").catch(() => []),
    api("/api/watchlist?limit=1000").catch(() => ({ items: [], shows: [] })),
  ]);

  watchedItemIds = new Set((progress || [])
    .filter((row) => row?.completed)
    .map((row) => Number(row.itemId))
    .filter(Boolean));
  watchedShowKeys = new Set((showProgress || [])
    .filter((row) => row?.completed)
    .map((row) => showKey(row.libraryId, row.showTitle)));
  watchlistItemIds = new Set((watchlist?.items || [])
    .map((item) => Number(item.id))
    .filter(Boolean));
  watchlistShowKeys = new Set((watchlist?.shows || [])
    .map((show) => showKey(show.libraryId, show.title)));
}

async function loadAllProgress() {
  const out = [];
  const limit = 500;
  for (let offset = 0; ; offset += limit) {
    const page = await api(`/api/progress?limit=${limit}&offset=${offset}`);
    out.push(...(page || []));
    if (!page || page.length < limit) break;
  }
  return out;
}

function effectiveTime() {
  if (!currentItem) return 0;
  if (transcode.checked) return Math.min(movieSeconds(currentItem), streamStart + (player.currentTime || 0));
  return Math.min(movieSeconds(currentItem) || player.duration || 0, player.currentTime || 0);
}

function updateTimeline(displayValue) {
  if (!currentItem) return;
  const duration = movieSeconds(currentItem);
  const value = displayValue ?? effectiveTime();
  timeline.max = String(duration);
  if (!draggingTimeline) timeline.value = String(Math.round(value));
  currentTimeEl.textContent = fmtClock(value);
  durationEl.textContent = fmtClock(duration);
  playPause.textContent = player.paused ? "Play" : "Pause";
  playPause.setAttribute("aria-label", player.paused ? "Play" : "Pause");
}

function updateVolumeUI() {
  volume.value = String(player.muted ? 0 : player.volume);
  const level = player.muted || player.volume === 0 ? "Muted" : player.volume < 0.5 ? "Low" : "Vol";
  muteBtn.textContent = level;
  muteBtn.setAttribute("aria-label", player.muted || player.volume === 0 ? "Unmute" : "Mute");
}

function el(tag, className, children) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (typeof children === "string") e.textContent = children;
  else if (Array.isArray(children)) children.forEach((c) => { if (c) e.append(c); });
  else if (children instanceof Node) e.append(children);
  return e;
}

function setView(content) {
  view.innerHTML = "";
  if (typeof content === "string") view.innerHTML = content;
  else if (content) view.append(content);
  view.classList.remove("view-fade");
  void view.offsetWidth;
  view.classList.add("view-fade");
  window.scrollTo({ top: 0, behavior: "instant" });
}

function stopPlayer() {
  player.pause();
  player.removeAttribute("src");
  player.load();
  closeTheater();
  destroyHLS();
  stopServerSession();
}

function stopServerSession() {
  if (currentHLSSession) {
    const session = currentHLSSession;
    currentHLSSession = null;
    fetch(`/api/hls/${encodeURIComponent(session)}`, { method: "DELETE" }).catch(() => {});
  }
}

function openTheater() {
  theater.classList.remove("hidden");
  theater.classList.remove("idle");
  document.body.style.overflow = "hidden";
  resetIdleTimer();
}

function closeTheater() {
  theater.classList.add("hidden");
  theater.classList.remove("idle");
  document.body.style.overflow = "";
  clearTimeout(idleTimer);
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}

let idleTimer = 0;
function resetIdleTimer() {
  theater.classList.remove("idle");
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    if (!theater.classList.contains("hidden") && !player.paused) {
      theater.classList.add("idle");
    }
  }, 3000);
}

theater.addEventListener("mousemove", resetIdleTimer);
theater.addEventListener("mousedown", resetIdleTimer);

function renderNav() {
  libraryNav.innerHTML = "";
  for (const library of libraries) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = library.id === activeLibraryId ? "nav-item active" : "nav-item";
    button.textContent = library.name;
    button.addEventListener("click", () => {
      activeLibraryId = library.id;
      currentPage = 1;
      currentShow = null;
      renderNav();
      loadActiveLibrary().catch(console.error);
    });
    libraryNav.append(button);
  }
}

function makeBreadcrumb(crumbs) {
  const nav = el("nav", "breadcrumb");
  crumbs.forEach((crumb, i) => {
    if (i > 0) nav.append(el("span", "breadcrumb-sep", "/"));
    if (crumb.action) {
      const btn = el("button", "breadcrumb-item", crumb.label);
      btn.type = "button";
      btn.addEventListener("click", crumb.action);
      nav.append(btn);
    } else {
      nav.append(el("span", "breadcrumb-current", crumb.label));
    }
  });
  return nav;
}

/* ── Library View ── */
function render(skipHistory) {
  if (search.value.trim()) {
    renderSearch(skipHistory);
    return;
  }
  const frag = document.createDocumentFragment();
  const library = activeLibrary();
  currentShow = null;
  if (!skipHistory) pushState({ view: "library", libraryId: library?.id, page: currentPage, q: search.value.trim() });

  if (!library) {
    frag.append(el("div", "empty", "No libraries configured"));
    setView(frag);
    return;
  }
  stopPlayer();

  const header = el("div", "view-header");
  header.append(
    el("h1", null, library.name),
    el("span", null, library.type === "tv" ? tvCountText(libraryItems) : `${libraryItems.length} movies`),
  );
  frag.append(header);

  if (!libraryItems.length) {
    frag.append(el("div", "empty", "No media found"));
    setView(frag);
    return;
  }

  if (library.type === "tv") {
    renderTV(frag, libraryItems);
  } else {
    renderMovies(frag, libraryItems);
  }
  setView(frag);
}

function renderSearch(skipHistory) {
  const query = search.value.trim();
  const frag = document.createDocumentFragment();
  currentShow = null;
  if (!skipHistory) pushState({ view: "search", q: query, page: currentPage });
  stopPlayer();

  const header = el("div", "view-header");
  header.append(el("h1", null, "Search"), el("span", null, `${libraryItems.length} results for "${query}"`));
  frag.append(header);

  if (!libraryItems.length) {
    frag.append(el("div", "empty", "No matches found"));
    setView(frag);
    return;
  }

  const movies = libraryItems.filter((item) => item.kind !== "episode");
  const episodes = libraryItems.filter((item) => item.kind === "episode");
  if (movies.length) {
    frag.append(sectionTitle("Movies", `${movies.length} matches`));
    renderMovies(frag, movies);
  }
  if (episodes.length) {
    frag.append(sectionTitle("TV Shows", tvCountText(episodes)));
    renderTV(frag, episodes);
  }
  setView(frag);
}

function sectionTitle(title, meta) {
  const header = el("div", "section-header");
  header.append(el("h2", null, title), el("span", null, meta));
  return header;
}

function renderMovies(root, movies) {
  const totalPages = Math.ceil(movies.length / perPage);
  if (currentPage > totalPages) currentPage = totalPages || 1;
  const start = (currentPage - 1) * perPage;
  root.append(renderGrid(movies.slice(start, start + perPage)));
  if (totalPages > 1) root.append(pagination(currentPage, totalPages, (p) => { currentPage = p; render(); }));
}

function renderTV(root, episodes) {
  const shows = groupShows(episodes);
  const totalPages = Math.ceil(shows.length / perPage);
  if (currentPage > totalPages) currentPage = totalPages || 1;
  const start = (currentPage - 1) * perPage;
  const page = shows.slice(start, start + perPage);
  const grid = el("div", "grid show-grid");
  for (const show of page) grid.append(showCard(show));
  root.append(grid);
  if (totalPages > 1) root.append(pagination(currentPage, totalPages, (p) => { currentPage = p; render(); }));
}

function pagination(current, total, onChange) {
  const nav = el("nav", "pagination");

  const prev = el("button", "page-btn", "\u2039 Prev");
  prev.type = "button";
  prev.disabled = current <= 1;
  prev.addEventListener("click", () => onChange(current - 1));

  const next = el("button", "page-btn", "Next \u203a");
  next.type = "button";
  next.disabled = current >= total;
  next.addEventListener("click", () => onChange(current + 1));

  nav.append(prev);

  const pages = pageNumbers(current, total);
  for (const p of pages) {
    if (p === "...") {
      nav.append(el("span", "page-ellipsis", "\u2026"));
    } else {
      const btn = el("button", p === current ? "page-btn active" : "page-btn", String(p));
      btn.type = "button";
      btn.addEventListener("click", () => onChange(p));
      nav.append(btn);
    }
  }

  nav.append(next);
  return nav;
}

function pageNumbers(current, total) {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1);
  const pages = [];
  pages.push(1);
  if (current > 3) pages.push("...");
  const rangeStart = Math.max(2, current - 1);
  const rangeEnd = Math.min(total - 1, current + 1);
  for (let i = rangeStart; i <= rangeEnd; i++) pages.push(i);
  if (current < total - 2) pages.push("...");
  pages.push(total);
  return pages;
}

function groupShows(episodes) {
  const grouped = new Map();
  for (const episode of episodes) {
    const title = episode.showTitle || "Unknown Show";
    const key = showKey(episode.libraryId, title);
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(episode);
  }
  return [...grouped.entries()]
    .map(([, eps]) => {
      eps.sort((a, b) => (a.seasonNumber - b.seasonNumber) || (a.episodeNumber - b.episodeNumber) || a.title.localeCompare(b.title));
      const first = eps[0] || {};
      return {
        libraryId: first.libraryId || "",
        title: first.showTitle || "Unknown Show",
        episodes: eps,
        posterItem: eps.find((e) => e.posterPath) || first,
      };
    })
    .sort((a, b) => a.title.localeCompare(b.title));
}

function tvCountText(episodes) {
  return `${new Set(episodes.map((e) => e.showTitle || e.title)).size} shows \u00b7 ${episodes.length} episodes`;
}

/* ── Card Components ── */
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

async function loadLibrary(library) {
  const q = encodeURIComponent(search.value.trim());
  const all = [];
  const limit = 2000;
  if (q) {
    for (let offset = 0; ; offset += limit) {
      const result = await api(`/api/search?limit=${limit}&offset=${offset}&q=${q}`);
      const page = result.items || [];
      all.push(...page);
      if (page.length < limit) break;
    }
    return all;
  }
  for (let offset = 0; ; offset += limit) {
    const page = await api(`/api/items?libraryId=${encodeURIComponent(library.id)}&limit=${limit}&offset=${offset}&q=${q}`);
    all.push(...page);
    if (page.length < limit) break;
  }
  return all;
}

async function loadActiveLibrary(skipHistory) {
  const library = activeLibrary();
  if (!library && !search.value.trim()) return;
  view.innerHTML = "";
  view.append(el("div", "empty", "Loading\u2026"));
  libraryItems = await loadLibrary(library);
  render(skipHistory);
}

async function load() {
  libraries = await api("/api/libraries");
  const route = routeFromLocation();
  if (route.q) search.value = route.q;
  if (route.page) currentPage = route.page;
  if (route.libraryId && libraries.some((library) => library.id === route.libraryId)) {
    activeLibraryId = route.libraryId;
  } else if (!activeLibraryId && libraries.length) {
    activeLibraryId = libraries[0].id;
  }
  renderNav();
  await refreshMediaState();
  await loadActiveLibrary(true);
  await navigate(route, true);
}

async function bootstrapAuth() {
  if (!authToken) {
    setUnauthenticated("");
    return;
  }
  try {
    const user = await api("/api/auth/me");
    setAuthenticated(user, authToken);
    await startApp();
  } catch (_) {
    setUnauthenticated("Session expired");
  }
}

async function startApp() {
  await load();
  await refreshStatus();
  clearInterval(statusTimer);
  clearInterval(timelineTimer);
  statusTimer = setInterval(refreshStatus, 3000);
  timelineTimer = setInterval(() => updateTimeline(), 1000);
}

function cleanError(err) {
  return String(err?.message || err || "Request failed").trim();
}

/* ── Playback ── */
function play(item, start = 0) {
  currentItem = item;
  streamStart = Math.max(0, Math.min(start, movieSeconds(item)));
  const bw = bandwidth.value;
  const mode = transcode.checked ? "transcode" : "stream";
  const audio = selectedAudio ? `&audio=${encodeURIComponent(selectedAudio)}` : "";
  destroyHLS();
  stopServerSession();
  openTheater();
  player.onerror = () => {
    const err = player.error;
    nowPlaying.textContent = `${item.title} \u00b7 playback failed (${err?.code || "no-code"} ${err?.message || ""})`;
  };
  let playWhenReady = true;
  if (mode === "transcode") {
    currentHLSSession = `s${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
    const src = `/api/items/${item.id}/hls/${currentHLSSession}/index.m3u8?bandwidth=${bw}&start=${streamStart.toFixed(3)}${audio}`;
    if (window.Hls?.isSupported()) {
      nowPlaying.textContent = `${item.title} \u00b7 loading HLS\u2026`;
      hls = new Hls({ lowLatencyMode: false });
      hls.on(Hls.Events.ERROR, (_, data) => {
        console.warn("hls error", data);
        if (data.fatal) {
          nowPlaying.textContent = `${item.title} \u00b7 transcode failed (${data.type || "error"}: ${data.details || "unknown"})`;
          destroyHLS();
        }
      });
      hls.on(Hls.Events.MANIFEST_LOADED, () => {
        nowPlaying.textContent = `${item.title} \u00b7 buffering\u2026`;
      });
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        nowPlaying.textContent = `${item.title} \u00b7 starting\u2026`;
        player.play().catch(() => {
          nowPlaying.textContent = `${item.title} \u00b7 click Play to start`;
        });
      });
      hls.on(Hls.Events.FRAG_LOADED, () => {
        nowPlaying.textContent = `${item.title} \u00b7 playing`;
      });
      hls.loadSource(src);
      hls.attachMedia(player);
      playWhenReady = false;
    } else if (player.canPlayType("application/vnd.apple.mpegurl")) {
      nowPlaying.textContent = `${item.title} \u00b7 loading native HLS\u2026`;
      player.src = src;
    } else {
      nowPlaying.textContent = `${item.title} \u00b7 HLS is not supported by this browser`;
      closeTheater();
      return;
    }
  } else {
    player.src = `/api/items/${item.id}/stream`;
  }
  nowPlaying.textContent = `${item.title}${transcode.checked ? ` \u00b7 ${Number(bw) / 1000} Mbps` : " \u00b7 direct"} \u00b7 ${fmtClock(movieSeconds(item))}`;
  updateTimeline(streamStart);
  if (!transcode.checked && streamStart > 0) {
    player.addEventListener("loadedmetadata", () => {
      player.currentTime = streamStart;
    }, { once: true });
  }
  if (playWhenReady) {
    player.play().catch(() => {
      nowPlaying.textContent = `${item.title} \u00b7 click Play to start`;
    });
  }
}

function destroyHLS() {
  if (hls) { hls.destroy(); hls = null; }
}

function seekTo(seconds) {
  if (!currentItem) return;
  const target = Math.max(0, Math.min(seconds, movieSeconds(currentItem)));
  if (transcode.checked) {
    play(currentItem, target);
  } else {
    player.currentTime = target;
  }
  updateTimeline(target);
}

async function refreshStatus() {
  try {
    const rows = await api("/api/scan");
    statusEl.textContent = rows.length ? rows.map((r) => {
      const counts = `${r.itemsImported || 0}/${r.mediaFound || 0} imported`;
      const seen = `${r.filesSeen || 0} files`;
      const errors = r.errors ? `, ${r.errors} errors` : "";
      return `${r.libraryId}: ${r.status} (${counts}, ${seen}${errors})`;
    }).join("\n") : "Idle";
  } catch (err) {
    statusEl.textContent = "Status unavailable";
  }
}

/* ── History / back button ── */
function pushState(state, replace = false) {
  const url = urlForState(state);
  if (replace) history.replaceState(state, "", url);
  else history.pushState(state, "", url);
}

async function navigate(state, replaceURL = false) {
  state = state || routeFromLocation();
  if (state.q !== undefined) search.value = state.q || "";
  currentPage = state.page || 1;
  if (state.libraryId && state.libraryId !== activeLibraryId && libraries.some((library) => library.id === state.libraryId)) {
    activeLibraryId = state.libraryId;
    renderNav();
    await loadActiveLibrary(true);
  }
  if (!state.libraryId) state.libraryId = activeLibraryId;
  if (replaceURL) pushState(state.view ? state : { view: "library", libraryId: activeLibraryId, page: currentPage, q: search.value.trim() }, true);
  if (state.view === "search" || search.value.trim()) {
    currentShow = null;
    renderSearch(true);
  } else if (!state || state.view === "library") {
    currentShow = null;
    render(true);
  } else if (state.view === "show" && state.showTitle) {
    const shows = groupShows(libraryItems);
    const show = shows.find((s) => s.title === state.showTitle);
    if (show) openShow(show, true);
    else render(true);
  } else if (state.view === "detail" && state.itemId) {
    const item = libraryItems.find((i) => i.id === state.itemId);
    if (item) openDetail(item, true).catch(console.error);
    else render(true);
  } else {
    render(true);
  }
}

function routeFromLocation() {
  const parts = location.pathname.split("/").filter(Boolean).map(decodeURIComponent);
  const params = new URLSearchParams(location.search);
  const state = {
    view: parts[0] === "search" ? "search" : "library",
    libraryId: parts[0] === "library" ? parts[1] : "",
    page: Math.max(1, Number(params.get("page") || "1")),
    q: params.get("q") || "",
  };
  if (parts[2] === "show" && parts[3]) {
    state.view = "show";
    state.showTitle = parts.slice(3).join("/");
  } else if (parts[2] === "item" && parts[3]) {
    state.view = "detail";
    state.itemId = Number(parts[3]);
  }
  return state;
}

function urlForState(state) {
  const libraryId = encodeURIComponent(state.libraryId || activeLibraryId || "");
  let path = state.view === "search" ? "/search" : libraryId ? `/library/${libraryId}` : "/";
  if (state.view === "show" && state.showTitle) {
    path += `/show/${encodeURIComponent(state.showTitle)}`;
  } else if (state.view === "detail" && state.itemId) {
    path += `/item/${encodeURIComponent(String(state.itemId))}`;
  }
  const params = new URLSearchParams();
  if (state.view === "library" && state.page && state.page > 1) params.set("page", String(state.page));
  if (state.q) params.set("q", state.q);
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

window.addEventListener("popstate", (e) => {
  // Close theater if open
  if (!theater.classList.contains("hidden")) {
    stopPlayer();
    return;
  }
  navigate(e.state).catch(console.error);
});

/* ── Event listeners ── */
loginForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  loginError.textContent = "";
  loginBtn.disabled = true;
  try {
    const result = await api("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: loginUser.value, password: loginPass.value }),
    });
    setAuthenticated(result.user, result.token);
    await startApp();
  } catch (err) {
    setUnauthenticated(cleanError(err));
  } finally {
    loginBtn.disabled = false;
  }
});

search.addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    currentPage = 1;
    loadActiveLibrary().catch(console.error);
  }, 180);
});

scan.addEventListener("click", async () => {
  scan.disabled = true;
  try {
    await fetch("/api/scan", { method: "POST" });
    await refreshStatus();
    setTimeout(() => loadActiveLibrary().catch(console.error), 1500);
  } finally {
    scan.disabled = false;
  }
});

timeline.addEventListener("input", () => {
  draggingTimeline = true;
  updateTimeline(Number(timeline.value));
});

timeline.addEventListener("change", () => {
  draggingTimeline = false;
  seekTo(Number(timeline.value));
});

playPause.addEventListener("click", () => {
  if (!currentItem) return;
  if (player.paused) player.play().catch(() => {});
  else player.pause();
  updateTimeline();
});

player.addEventListener("click", () => playPause.click());
player.addEventListener("timeupdate", () => updateTimeline());
player.addEventListener("loadedmetadata", () => updateTimeline());
player.addEventListener("play", () => { updateTimeline(); resetIdleTimer(); });
player.addEventListener("pause", () => { updateTimeline(); resetIdleTimer(); });
player.addEventListener("ended", () => updateTimeline());

closePlayerBtn.addEventListener("click", () => stopPlayer());

fullscreenBtn.addEventListener("click", () => {
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {});
  } else {
    theater.requestFullscreen().catch(() => {});
  }
});

document.addEventListener("fullscreenchange", () => {
  fullscreenBtn.textContent = document.fullscreenElement ? "Exit FS" : "Fullscreen";
});

document.addEventListener("keydown", (e) => {
  // Esc closes theater (if not in native fullscreen — browser handles that)
  if (e.key === "Escape" && !theater.classList.contains("hidden") && !document.fullscreenElement) {
    e.preventDefault();
    stopPlayer();
  }
  // Space toggles play/pause when theater is open
  if (e.key === " " && !theater.classList.contains("hidden")) {
    e.preventDefault();
    playPause.click();
  }
  // F for fullscreen when theater is open
  if (e.key === "f" && !theater.classList.contains("hidden") && document.activeElement?.tagName !== "INPUT") {
    e.preventDefault();
    fullscreenBtn.click();
  }
});

function updateBandwidthVisibility() {
  bandwidth.style.display = transcode.checked ? "" : "none";
}
updateBandwidthVisibility();
player.volume = Number.isFinite(savedVolume) ? Math.max(0, Math.min(1, savedVolume)) : 1;
updateVolumeUI();

volume.addEventListener("input", () => {
  player.volume = Number(volume.value);
  player.muted = player.volume === 0;
  if (player.volume > 0) {
    savedVolume = player.volume;
    localStorage.setItem("popcornVolume", String(savedVolume));
  }
  updateVolumeUI();
});

muteBtn.addEventListener("click", () => {
  if (player.muted || player.volume === 0) {
    player.muted = false;
    player.volume = savedVolume > 0 ? savedVolume : 1;
  } else {
    savedVolume = player.volume;
    localStorage.setItem("popcornVolume", String(savedVolume));
    player.muted = true;
  }
  updateVolumeUI();
});

player.addEventListener("volumechange", updateVolumeUI);

bandwidth.addEventListener("change", () => {
  if (currentItem && transcode.checked) play(currentItem, effectiveTime());
});

transcode.addEventListener("change", () => {
  updateBandwidthVisibility();
  if (currentItem) play(currentItem, effectiveTime());
});

bootstrapAuth().catch(console.error);
