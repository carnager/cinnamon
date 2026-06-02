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
