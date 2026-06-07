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
const topbarFilters = document.querySelector("#topbarFilters");
const search = document.querySelector("#search");
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
let activeView = "home";
let activeLibraryId = "";
let libraryItems = [];
let currentPage = 1;
const perPage = 50;
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
let timelineTimer = 0;
let watchedItemIds = new Set();
let mediaProgressRows = [];
let watchedShowKeys = new Set();
let watchlistItemIds = new Set();
let watchlistShowKeys = new Set();
let currentGenre = "";
let currentSort = "";
let currentSeenStatus = "";
let currentMinRating = 0;
let currentSeason = null;
let libraryGenres = [];
let pageHasNext = false;
let homeData = {
  movies: [],
  shows: [],
  continueMovies: [],
  continueEpisodes: [],
  recentMovies: [],
  recentShows: [],
  watchlistMovies: [],
  watchlistShows: [],
};

async function api(path, options) {
  const init = { ...(options || {}) };
  const headers = new Headers(init.headers || {});
  if (authToken) headers.set("Authorization", `Bearer ${authToken}`);
  init.headers = headers;
  const res = await fetch(path, init);
  if (!res.ok) throw new Error(await res.text());
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text.trim()) return null;
  return JSON.parse(text);
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
  clearInterval(timelineTimer);
  stopPlayer();
}

function renderUserPanel() {
  userPanel.innerHTML = "";
  if (!currentUser) return;
  const menu = document.createElement("details");
  menu.className = "user-menu";
  const summary = document.createElement("summary");
  summary.className = "user-menu-trigger";
  summary.append(
    el("span", "user-menu-avatar", initials(currentUser.displayName || currentUser.username || "U")),
    el("span", "user-menu-name", currentUser.displayName || currentUser.username),
    el("span", "user-menu-caret", "▾"),
  );
  const actions = el("div", "user-menu-popover");
  const scan = el("button", "side-btn", "Scan Libraries");
  scan.type = "button";
  scan.addEventListener("click", () => {
    scanLibraries(scan).catch(console.error);
  });
  actions.append(scan);
  const settings = el("button", "side-btn", "Settings");
  settings.type = "button";
  settings.addEventListener("click", () => {
    menu.open = false;
    renderSettings().catch(console.error);
  });
  actions.append(settings);
  const logout = el("button", "side-btn", "Logout");
  logout.type = "button";
  logout.addEventListener("click", () => {
    menu.open = false;
    logoutUser();
  });
  actions.append(logout);
  menu.append(summary, actions);
  userPanel.append(menu);
}

async function scanLibraries(button) {
  const oldLabel = button?.textContent || "";
  if (button) {
    button.disabled = true;
    button.textContent = "Scanning...";
  }
  try {
    await api("/api/scan", { method: "POST" });
    setTimeout(() => loadCurrentView().catch(console.error), 1500);
  } finally {
    if (button) {
      button.disabled = false;
      button.textContent = oldLabel;
    }
  }
}

function closeUserMenu() {
  userPanel.querySelectorAll("details[open]").forEach((menu) => {
    menu.open = false;
  });
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

function fmtEndsAround(ms) {
  if (!ms) return "";
  return `Ends around ${new Date(Date.now() + Number(ms)).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
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

  mediaProgressRows = progress || [];
  watchedItemIds = new Set(mediaProgressRows
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

async function setItemSeen(item, seen) {
  if (!item?.id) return;
  if (seen) {
    const duration = item.durationMs || 1;
    await api(`/api/items/${encodeURIComponent(String(item.id))}/progress`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ positionMs: duration, durationMs: duration, completed: true, state: "manual" }),
    });
    watchedItemIds.add(Number(item.id));
  } else {
    await api(`/api/items/${encodeURIComponent(String(item.id))}/progress`, { method: "DELETE" });
    watchedItemIds.delete(Number(item.id));
  }
}

async function setShowSeen(show, seen) {
  const libraryId = show?.libraryId || activeLibraryId;
  const title = show?.title || "";
  if (!libraryId || !title) return;
  const query = `libraryId=${encodeURIComponent(libraryId)}&showTitle=${encodeURIComponent(title)}`;
  await api(`/api/progress/tv?${query}`, { method: seen ? "PUT" : "DELETE" });
  if (seen) watchedShowKeys.add(showKey(libraryId, title));
  else watchedShowKeys.delete(showKey(libraryId, title));
  await refreshMediaState();
}

async function setSeasonSeen(show, season, seen, episodes = []) {
  const libraryId = show?.libraryId || activeLibraryId;
  const title = show?.title || "";
  if (!libraryId || !title) return;
  const query = `libraryId=${encodeURIComponent(libraryId)}&showTitle=${encodeURIComponent(title)}&season=${encodeURIComponent(String(season || 0))}`;
  await api(`/api/progress/tv/season?${query}`, { method: seen ? "PUT" : "DELETE" });
  for (const episode of episodes || []) {
    if (!episode?.id) continue;
    if (seen) watchedItemIds.add(Number(episode.id));
    else watchedItemIds.delete(Number(episode.id));
  }
  await refreshMediaState();
}

async function setItemWatchlisted(item, watchlisted) {
  if (!item?.id) return;
  await api(`/api/items/${encodeURIComponent(String(item.id))}/watchlist`, { method: watchlisted ? "PUT" : "DELETE" });
  if (watchlisted) watchlistItemIds.add(Number(item.id));
  else watchlistItemIds.delete(Number(item.id));
  await fetchWatchlist();
}

async function setShowWatchlisted(show, watchlisted) {
  const libraryId = show?.libraryId || activeLibraryId;
  const title = show?.title || "";
  if (!libraryId || !title) return;
  const query = `libraryId=${encodeURIComponent(libraryId)}&showTitle=${encodeURIComponent(title)}`;
  await api(`/api/watchlist/tv?${query}`, { method: watchlisted ? "PUT" : "DELETE" });
  if (watchlisted) watchlistShowKeys.add(showKey(libraryId, title));
  else watchlistShowKeys.delete(showKey(libraryId, title));
  await fetchWatchlist();
}

theater.addEventListener("mousemove", resetIdleTimer);
theater.addEventListener("mousedown", resetIdleTimer);

function renderNav() {
  appShell.classList.toggle("settings-mode", activeView === "settings");
  libraryNav.innerHTML = "";
  const home = document.createElement("button");
  home.type = "button";
  home.className = activeView === "home" ? "nav-item active" : "nav-item";
  home.append(navIcon("home"), el("span", "nav-label", "Home"));
  home.addEventListener("click", () => {
    search.value = "";
    currentPage = 1;
    activeView = "home";
    renderNav();
    loadHome().catch(console.error);
  });
  libraryNav.append(home);

  for (const library of libraries) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = activeView === "library" && library.id === activeLibraryId ? "nav-item active" : "nav-item";
    button.append(navIcon(library.type === "tv" ? "tv" : "movies"), el("span", "nav-label", library.name));
    button.addEventListener("click", () => {
      search.value = "";
      activeView = "library";
      activeLibraryId = library.id;
      currentPage = 1;
      currentShow = null;
      currentSeason = null;
      currentGenre = "";
      currentSort = "";
      currentSeenStatus = "";
      currentMinRating = 0;
      renderNav();
      loadLibraryPage().catch(console.error);
    });
    libraryNav.append(button);
  }

  const searchButton = document.createElement("button");
  searchButton.type = "button";
  searchButton.className = activeView === "search" ? "nav-item active" : "nav-item";
  searchButton.append(navIcon("search"), el("span", "nav-label", "Search"));
  searchButton.addEventListener("click", () => {
    activeView = "search";
    renderNav();
    search.focus();
    if (search.value.trim()) renderSearch().catch(console.error);
  });
  libraryNav.append(searchButton);

  const watchlist = document.createElement("button");
  watchlist.type = "button";
  watchlist.className = activeView === "watchlist" ? "nav-item active" : "nav-item";
  watchlist.append(navIcon("watchlist"), el("span", "nav-label", "Watchlist"));
  watchlist.addEventListener("click", () => {
    search.value = "";
    activeView = "watchlist";
    currentPage = 1;
    renderNav();
    renderWatchlist().catch(console.error);
  });
  libraryNav.append(watchlist);

  const settings = document.createElement("button");
  settings.type = "button";
  settings.className = activeView === "settings" ? "nav-item active" : "nav-item";
  settings.append(navIcon("settings"), el("span", "nav-label", "Settings"));
  settings.addEventListener("click", () => {
    search.value = "";
    activeView = "settings";
    currentPage = 1;
    renderNav();
    renderSettings().catch(console.error);
  });
  libraryNav.append(settings);
  renderTopbarControls();
}

function navIcon(kind) {
  const icons = {
    home: "⌂",
    movies: "▣",
    tv: "▤",
    search: "⌕",
    watchlist: "♡",
    settings: "⚙",
  };
  return el("span", "nav-icon", icons[kind] || "•");
}

function renderTopbarControls() {
  topbarFilters.innerHTML = "";
  if (activeView !== "library" || search.value.trim()) {
    topbarFilters.classList.add("empty");
    return;
  }
  const library = activeLibrary();
  if (!library) {
    topbarFilters.classList.add("empty");
    return;
  }
  topbarFilters.classList.remove("empty");
  topbarFilters.append(
    topbarSelect("Sort", currentSort, [
      ["", "Title"],
      ["mtime", "File date"],
      ["rating", "Rating"],
    ], (value) => {
      currentSort = value;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }),
    topbarSelect("Seen", currentSeenStatus, [
      ["", "All"],
      ["unseen", "Unseen"],
      ["seen", "Seen"],
    ], (value) => {
      currentSeenStatus = value;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }),
    topbarSelect("Rating", String(currentMinRating || ""), [
      ["", "All"],
      ["6", "6+"],
      ["7", "7+"],
      ["8", "8+"],
      ["9", "9+"],
    ], (value) => {
      currentMinRating = Number(value || 0);
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }),
    topbarSelect("Genre", currentGenre, [["", "All"], ...libraryGenres.map((genre) => [genre, genre])], (value) => {
      currentGenre = value;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }),
  );
}

function topbarSelect(label, value, options, onChange) {
  const field = el("div", "topbar-filter");
  const trigger = el("button", "topbar-filter-trigger");
  trigger.type = "button";
  const current = options.find(([optionValue]) => String(optionValue) === String(value));
  trigger.append(
    el("span", "topbar-filter-label", label),
    el("strong", "topbar-filter-value", current?.[1] || "All"),
    el("span", "topbar-filter-caret", "▾"),
  );
  const menu = el("div", "topbar-filter-menu");
  trigger.addEventListener("click", (event) => {
    event.stopPropagation();
    const willOpen = !field.classList.contains("open");
    closeTopbarMenus();
    field.classList.toggle("open", willOpen);
  });
  trigger.addEventListener("keydown", (event) => {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      closeTopbarMenus();
      field.classList.add("open");
      menu.querySelector("button")?.focus();
    }
    if (event.key === "Escape") {
      field.classList.remove("open");
      trigger.focus();
    }
  });
  menu.addEventListener("click", (event) => event.stopPropagation());
  menu.addEventListener("keydown", (event) => {
    const buttons = [...menu.querySelectorAll("button")];
    const index = buttons.indexOf(document.activeElement);
    if (event.key === "Escape") {
      event.preventDefault();
      field.classList.remove("open");
      trigger.focus();
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      buttons[Math.min(buttons.length - 1, index + 1)]?.focus();
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      buttons[Math.max(0, index - 1)]?.focus();
    }
  });
  for (const [optionValue, optionLabel] of options) {
    const active = String(optionValue) === String(value);
    const button = el("button", active ? "topbar-filter-option active" : "topbar-filter-option");
    button.type = "button";
    button.append(el("span", "topbar-filter-check", active ? "✓" : ""), el("span", null, optionLabel));
    button.addEventListener("click", () => {
      field.classList.remove("open");
      onChange(optionValue);
    });
    menu.append(button);
  }
  field.append(trigger, menu);
  return field;
}

function closeTopbarMenus() {
  topbarFilters.querySelectorAll(".topbar-filter.open").forEach((menu) => {
    menu.classList.remove("open");
  });
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

async function fetchItemsPage(libraryId, { limit = perPage, offset = 0, sort = "", genre = "", seen = "", minRating = 0 } = {}) {
  const params = new URLSearchParams({ libraryId, limit: String(limit), offset: String(offset) });
  if (sort) params.set("sort", sort);
  if (genre) params.set("genre", genre);
  if (seen) params.set("seen", seen);
  if (minRating) params.set("minRating", String(minRating));
  return api(`/api/items?${params}`);
}

async function fetchItem(itemId) {
  return api(`/api/items/${encodeURIComponent(String(itemId))}`);
}

async function fetchShowsPage(libraryId, { limit = perPage, offset = 0, sort = "", genre = "", seen = "", minRating = 0 } = {}) {
  const params = new URLSearchParams({ libraryId, limit: String(limit), offset: String(offset) });
  if (sort) params.set("sort", sort);
  if (genre) params.set("genre", genre);
  if (seen) params.set("seen", seen);
  if (minRating) params.set("minRating", String(minRating));
  return api(`/api/tv/shows?${params}`);
}

async function fetchLibraryGenres(libraryId) {
  return api(`/api/genres?libraryId=${encodeURIComponent(libraryId)}`).catch(() => []);
}

async function fetchWatchlist() {
  const list = await api("/api/watchlist?limit=1000").catch(() => ({ items: [], shows: [] }));
  homeData.watchlistMovies = (list.items || []).filter((item) => item.kind === "movie");
  homeData.watchlistShows = list.shows || [];
  watchlistItemIds = new Set((list.items || []).map((item) => Number(item.id)).filter(Boolean));
  watchlistShowKeys = new Set((list.shows || []).map((show) => showKey(show.libraryId, show.title)));
  return list;
}

function setLoading(label = "Loading...") {
  view.innerHTML = "";
  view.append(el("div", "empty", label));
}

async function loadCurrentView(skipHistory = false) {
  if (search.value.trim()) {
    await renderSearch(skipHistory);
  } else if (activeView === "home") {
    await loadHome(skipHistory);
  } else if (activeView === "watchlist") {
    await renderWatchlist(skipHistory);
  } else if (activeView === "settings") {
    await renderSettings(skipHistory);
  } else {
    await loadLibraryPage(skipHistory);
  }
}

/* ── Library View ── */
function render(skipHistory) {
  if (search.value.trim()) {
    renderSearch(skipHistory).catch(console.error);
    return;
  }
  if (activeView === "home") {
    renderHome(skipHistory);
    return;
  }
  if (activeView === "watchlist") {
    renderWatchlist(skipHistory).catch(console.error);
    return;
  }
  if (activeView === "settings") {
    renderSettings(skipHistory).catch(console.error);
    return;
  }
  const frag = document.createDocumentFragment();
  const library = activeLibrary();
  currentShow = null;
  currentSeason = null;
  renderTopbarControls();
  if (!skipHistory) pushState({ view: "library", libraryId: library?.id, page: currentPage, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating });

  if (!library) {
    frag.append(el("div", "empty", "No libraries configured"));
    setView(frag);
    return;
  }
  stopPlayer();

  const header = el("div", "view-header");
  header.append(
    el("h1", null, currentGenre ? `${library.name} / ${currentGenre}` : library.name),
    el("span", null, library.type === "tv" ? `${libraryItems.length} shows on this page` : `${libraryItems.length} movies on this page`),
  );
  frag.append(header);

  if (!libraryItems.length) {
    frag.append(el("div", "empty", "No media found"));
    setView(frag);
    return;
  }

  if (library.type === "tv") {
    renderTVShows(frag, libraryItems);
  } else {
    frag.append(renderGrid(libraryItems));
  }
  frag.append(pagination(currentPage, pageHasNext ? currentPage + 1 : currentPage, (p) => {
    currentPage = p;
    loadLibraryPage().catch(console.error);
  }));
  setView(frag);
}

function renderHome(skipHistory) {
  activeView = "home";
  renderNav();
  currentShow = null;
  currentSeason = null;
  if (!skipHistory) pushState({ view: "home" });
  stopPlayer();

  const frag = document.createDocumentFragment();
  frag.append(el("div", "view-header home-header", el("h1", null, "Popcorn"), el("span", null, "Ready to watch")));
  frag.append(curatedHome(homeData));
  setView(frag);
}

async function renderWatchlist(skipHistory) {
  activeView = "watchlist";
  renderNav();
  currentShow = null;
  currentSeason = null;
  if (!skipHistory) pushState({ view: "watchlist" });
  stopPlayer();
  await refreshMediaState();
  await fetchWatchlist();

  const frag = document.createDocumentFragment();
  frag.append(el("div", "view-header", el("h1", null, "Watchlist"), el("span", null, `${homeData.watchlistMovies.length} movies · ${homeData.watchlistShows.length} shows`)));
  if (!homeData.watchlistMovies.length && !homeData.watchlistShows.length) {
    frag.append(el("div", "empty", "No watchlist items"));
    setView(frag);
    return;
  }
  if (homeData.watchlistMovies.length) {
    frag.append(sectionTitle("Movies", `${homeData.watchlistMovies.length} saved`), renderGrid(homeData.watchlistMovies));
  }
  if (homeData.watchlistShows.length) {
    frag.append(sectionTitle("TV Shows", `${homeData.watchlistShows.length} saved`));
    renderTVShows(frag, homeData.watchlistShows);
  }
  setView(frag);
}

async function renderSearch(skipHistory) {
  const query = search.value.trim();
  const frag = document.createDocumentFragment();
  currentShow = null;
  currentSeason = null;
  activeView = "search";
  renderNav();
  if (!skipHistory) pushState({ view: "search", q: query });
  stopPlayer();

  if (query.length < 2) {
    frag.append(el("div", "view-header", el("h1", null, "Search"), el("span", null, "Type at least two characters")));
    frag.append(el("div", "empty", "Search movies and shows"));
    setView(frag);
    return;
  }

  const [movieResults, showResults] = await Promise.all([
    api(`/api/search?limit=120&kind=movie&q=${encodeURIComponent(query)}`).then((r) => r.items || []).catch(() => []),
    api(`/api/tv/shows?limit=120&q=${encodeURIComponent(query)}`).catch(() => []),
  ]);

  const header = el("div", "view-header");
  header.append(el("h1", null, "Search"), el("span", null, `${movieResults.length + showResults.length} results for "${query}"`));
  frag.append(header);

  if (!movieResults.length && !showResults.length) {
    frag.append(el("div", "empty", "No matches found"));
    setView(frag);
    return;
  }

  if (movieResults.length) {
    frag.append(sectionTitle("Movies", `${movieResults.length} matches`), renderGrid(movieResults));
  }
  if (showResults.length) {
    frag.append(sectionTitle("TV Shows", `${showResults.length} matches`));
    renderTVShows(frag, showResults);
  }
  setView(frag);
}

function sectionTitle(title, meta) {
  const header = el("div", "section-header");
  header.append(el("h2", null, title), el("span", null, meta));
  return header;
}

function renderTVShows(root, shows) {
  const grid = el("div", "grid show-grid");
  for (const show of shows) grid.append(showCard(show));
  root.append(grid);
}

function curatedHome(data) {
  const wrap = el("div", "home-shelves");
  const moviePicks = pickFeatured(data.movies);
  const showPicks = pickFeatured(data.shows);
  const movieGenres = genreShelves(data.movies, 2);
  const showGenres = genreShelves(data.shows, 1);

  appendShelf(wrap, "Continue Movies", data.continueMovies, (items) => renderShelfGrid(items));
  appendShelf(wrap, "Continue TV", data.continueEpisodes, (items) => renderShelfGrid(items));
  appendShelf(wrap, "Watchlist Movies", data.watchlistMovies, (items) => renderShelfGrid(items));
  appendShelf(wrap, "Watchlist TV", data.watchlistShows, (items) => renderShelfGrid(items.map((show) => showCard(show))));
  appendShelf(wrap, "Recently Added Movies", data.recentMovies, (items) => renderShelfGrid(items));
  appendShelf(wrap, "Recently Added TV", data.recentShows, (items) => renderShelfGrid(items.map((show) => showCard(show))));
  appendShelf(wrap, "Movie Picks", moviePicks, (items) => renderShelfGrid(items));
  appendShelf(wrap, "TV Picks", showPicks, (items) => renderShelfGrid(items.map((show) => showCard(show))));

  for (const row of movieGenres) {
    appendShelf(wrap, row.genre, row.items, (items) => renderShelfGrid(items));
  }
  for (const row of showGenres) {
    appendShelf(wrap, `${row.genre} TV`, row.items, (items) => renderShelfGrid(items.map((show) => showCard(show))));
  }
  return wrap;
}

function appendShelf(wrap, title, items, renderItems) {
  if (!items?.length) return;
  wrap.append(shelf(title, items, renderItems));
}

function shelf(title, items, renderItems) {
  const section = el("section", "shelf");
  const header = el("div", "shelf-header");
  header.append(el("h2", null, title), el("span", null, `${items.length}`));
  section.append(header);
  section.append(renderItems(items.slice(0, 18)));
  return section;
}

function renderShelfGrid(itemsOrNodes) {
  const row = el("div", "shelf-row");
  for (const entry of itemsOrNodes) row.append(entry instanceof Node ? entry : itemCard(entry));
  return row;
}

function splitGenres(value) {
  return String(value || "").split(/[,/]/).map((genre) => genre.trim()).filter(Boolean);
}

function pickFeatured(items) {
  const rated = [...items]
    .filter((item) => Number(item.rating || 0) >= 7)
    .sort((a, b) => Number(b.rating || 0) - Number(a.rating || 0))
    .slice(0, 18);
  return rated.length ? rated : items.slice(0, 18);
}

function genreShelves(items, maxRows) {
  const byGenre = new Map();
  for (const item of items) {
    for (const genre of splitGenres(item.genres).slice(0, 3)) {
      const key = genre.toLowerCase();
      if (!byGenre.has(key)) byGenre.set(key, { genre, items: [] });
      byGenre.get(key).items.push(item);
    }
  }
  return [...byGenre.values()]
    .filter((row) => row.items.length >= 4)
    .sort((a, b) => b.items.length - a.items.length || a.genre.localeCompare(b.genre))
    .slice(0, maxRows);
}

function libraryGenresBar(library) {
  const bar = el("div", "genre-filter-bar");
  for (const [sort, label] of [["", "Title"], ["mtime", "File date"], ["rating", "Rating"], ["recent", "Added"]]) {
    const btn = el("button", sort === currentSort ? "genre-filter active" : "genre-filter", label);
    btn.type = "button";
    btn.addEventListener("click", () => {
      currentSort = sort;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    });
    bar.append(btn);
  }
  if (libraryGenres.length) bar.append(el("span", "breadcrumb-sep", "/"));
  const all = el("button", currentGenre ? "genre-filter" : "genre-filter active", "All");
  all.type = "button";
  all.addEventListener("click", () => {
    currentGenre = "";
    currentPage = 1;
    loadLibraryPage().catch(console.error);
  });
  bar.append(all);
  for (const genre of libraryGenres) {
    const btn = el("button", genre === currentGenre ? "genre-filter active" : "genre-filter", genre);
    btn.type = "button";
    btn.addEventListener("click", () => {
      currentGenre = genre;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    });
    bar.append(btn);
  }
  return bar;
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

  nav.append(el("span", "page-current", `Page ${current}`));

  nav.append(next);
  return nav;
}

/* ── Data loading ── */
async function loadHome(skipHistory) {
  activeView = "home";
  currentShow = null;
  currentSeason = null;
  currentGenre = "";
  currentSort = "";
  currentSeenStatus = "";
  currentMinRating = 0;
  search.value = "";
  renderNav();
  setLoading();
  const movieLibrary = libraries.find((library) => library.type === "movies");
  const tvLibrary = libraries.find((library) => library.type === "tv");
  await refreshMediaState();
  const resumable = mediaProgressRows
    .filter((row) => {
      const position = Number(row?.positionMs || 0);
      const duration = Number(row?.durationMs || 0);
      return !row?.completed && duration > 0 && position >= 30000 && position < Math.max(duration - 90000, 30000);
    })
    .slice(0, 40);
  const continueItems = await Promise.all(
    resumable.map((row) => fetchItem(row.itemId).catch(() => null)),
  );
  const [recentMovies, movies, recentShows, shows] = await Promise.all([
    movieLibrary ? fetchItemsPage(movieLibrary.id, { limit: 24, sort: "mtime" }) : [],
    movieLibrary ? fetchItemsPage(movieLibrary.id, { limit: 220 }) : [],
    tvLibrary ? fetchShowsPage(tvLibrary.id, { limit: 24, sort: "mtime" }) : [],
    tvLibrary ? fetchShowsPage(tvLibrary.id, { limit: 220 }) : [],
    fetchWatchlist(),
  ]);
  homeData.continueMovies = continueItems.filter((item) => item?.kind === "movie").slice(0, 24);
  homeData.continueEpisodes = continueItems.filter((item) => item?.kind === "episode").slice(0, 24);
  homeData.recentMovies = recentMovies || [];
  homeData.movies = movies || [];
  homeData.recentShows = recentShows || [];
  homeData.shows = shows || [];
  renderHome(skipHistory);
}

async function loadLibraryPage(skipHistory) {
  const library = activeLibrary();
  if (!library) return;
  activeView = "library";
  currentShow = null;
  currentSeason = null;
  search.value = "";
  renderNav();
  setLoading();
  const offset = (currentPage - 1) * perPage;
  await refreshMediaState();
  const [genres, page] = await Promise.all([
    fetchLibraryGenres(library.id),
    library.type === "tv"
      ? fetchShowsPage(library.id, { limit: perPage, offset, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating })
      : fetchItemsPage(library.id, { limit: perPage, offset, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating }),
  ]);
  libraryGenres = genres || [];
  libraryItems = page || [];
  pageHasNext = libraryItems.length >= perPage;
  render(skipHistory);
}

async function load() {
  libraries = await api("/api/libraries");
  const route = routeFromLocation();
  if (route.q) search.value = route.q;
  if (route.page) currentPage = route.page;
  if (route.genre) currentGenre = route.genre;
  if (route.seen) currentSeenStatus = route.seen;
  if (route.minRating) currentMinRating = Number(route.minRating || 0);
  if (route.libraryId && libraries.some((library) => library.id === route.libraryId)) {
    activeLibraryId = route.libraryId;
  } else if (!activeLibraryId && libraries.length) {
    activeLibraryId = libraries[0].id;
  }
  renderNav();
  await refreshMediaState();
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
  clearInterval(timelineTimer);
  timelineTimer = setInterval(() => updateTimeline(), 1000);
}

function cleanError(err) {
  return String(err?.message || err || "Request failed").trim();
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
  currentGenre = state.genre || "";
  currentSort = state.sort || "";
  currentSeenStatus = state.seen || "";
  currentMinRating = Number(state.minRating || 0);
  if (state.libraryId && libraries.some((library) => library.id === state.libraryId)) {
    activeLibraryId = state.libraryId;
  }
  if (replaceURL) pushState(state.view ? state : { view: "home" }, true);

  if (state.view === "search" || search.value.trim()) {
    currentShow = null;
    await renderSearch(true);
  } else if (!state || state.view === "home") {
    await loadHome(true);
  } else if (state.view === "watchlist") {
    await renderWatchlist(true);
  } else if (state.view === "settings") {
    await renderSettings(true);
  } else if (state.view === "users") {
    await renderUsers(true);
  } else if (state.view === "library") {
    await loadLibraryPage(true);
  } else if (state.view === "show" && state.showTitle) {
    activeView = "library";
    renderNav();
    const show = await fetchShowSummary(state.libraryId || activeLibraryId, state.showTitle);
    if (show) await openShow(show, true);
    else await loadLibraryPage(true);
  } else if (state.view === "season" && state.showTitle) {
    activeView = "library";
    renderNav();
    const show = await fetchShowSummary(state.libraryId || activeLibraryId, state.showTitle);
    if (show) await openSeason(show, Number(state.season || 0), true);
    else await loadLibraryPage(true);
  } else if (state.view === "detail" && state.itemId) {
    activeView = "library";
    renderNav();
    const item = await api(`/api/items/${encodeURIComponent(String(state.itemId))}`).catch(() => null);
    if (!item) {
      await loadLibraryPage(true);
      return;
    }
    if (item.kind === "episode") {
      currentSeason = Number(state.season ?? item.seasonNumber ?? 0);
      const showTitle = state.showTitle || item.showTitle || "";
      currentShow = showTitle ? await fetchShowSummary(state.libraryId || item.libraryId || activeLibraryId, showTitle) : null;
    } else {
      currentShow = null;
      currentSeason = null;
    }
    await openDetail(item, true);
  } else {
    await loadHome(true);
  }
}

function routeFromLocation() {
  const parts = location.pathname.split("/").filter(Boolean).map(decodeURIComponent);
  const params = new URLSearchParams(location.search);
  const state = {
    view: "home",
    libraryId: parts[0] === "library" ? parts[1] : "",
    page: Math.max(1, Number(params.get("page") || "1")),
    genre: params.get("genre") || "",
    sort: params.get("sort") || "",
    seen: params.get("seen") || "",
    minRating: Number(params.get("minRating") || "0"),
    q: params.get("q") || "",
  };
  if (parts[0] === "search") {
    state.view = "search";
  } else if (parts[0] === "watchlist") {
    state.view = "watchlist";
  } else if (parts[0] === "settings") {
    state.view = "settings";
    if (parts[1] === "users") state.view = "users";
  } else if (parts[0] === "library") {
    state.view = "library";
  }
  if (parts[2] === "show" && parts[3]) {
    state.view = "show";
    state.showTitle = parts.slice(3).join("/");
  } else if (parts[2] === "season" && parts[3]) {
    state.view = "season";
    state.season = Number(parts[3]);
    state.showTitle = parts.slice(4).join("/");
  } else if (parts[2] === "item" && parts[3]) {
    state.view = "detail";
    state.itemId = Number(parts[3]);
  }
  if (state.view === "detail") {
    if (params.has("show")) state.showTitle = params.get("show") || "";
    if (params.has("season")) state.season = Number(params.get("season") || "0");
  }
  return state;
}

function urlForState(state) {
  const libraryId = encodeURIComponent(state.libraryId || activeLibraryId || "");
  let path = "/";
  if (state.view === "search") path = "/search";
  else if (state.view === "watchlist") path = "/watchlist";
  else if (state.view === "settings") path = "/settings";
  else if (state.view === "users") path = "/settings/users";
  else if (libraryId) path = `/library/${libraryId}`;
  if (state.view === "show" && state.showTitle) {
    path += `/show/${encodeURIComponent(state.showTitle)}`;
  } else if (state.view === "season" && state.showTitle) {
    path += `/season/${encodeURIComponent(String(state.season || 0))}/${encodeURIComponent(state.showTitle)}`;
  } else if (state.view === "detail" && state.itemId) {
    path += `/item/${encodeURIComponent(String(state.itemId))}`;
  }
  const params = new URLSearchParams();
  if (state.view === "library" && state.page && state.page > 1) params.set("page", String(state.page));
  if (state.view === "library" && state.genre) params.set("genre", state.genre);
  if (state.view === "library" && state.sort) params.set("sort", state.sort);
  if (state.view === "library" && state.seen) params.set("seen", state.seen);
  if (state.view === "library" && state.minRating) params.set("minRating", String(state.minRating));
  if (state.view === "detail" && state.showTitle) params.set("show", state.showTitle);
  if (state.view === "detail" && state.season !== undefined && state.season !== null) params.set("season", String(state.season));
  if (state.q) params.set("q", state.q);
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

async function fetchShowSummary(libraryId, title) {
  const shows = await fetchShowsPage(libraryId, { limit: 5, offset: 0 });
  let show = shows.find((entry) => entry.title === title);
  if (show) return show;
  const searched = await api(`/api/tv/shows?libraryId=${encodeURIComponent(libraryId)}&limit=20&q=${encodeURIComponent(title)}`).catch(() => []);
  show = searched.find((entry) => entry.title === title) || searched[0];
  return show || null;
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
    renderSearch().catch(console.error);
  }, 180);
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

document.addEventListener("click", (e) => {
  if (!e.target.closest(".topbar-filter")) {
    closeTopbarMenus();
  }
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && userPanel.querySelector("details[open]")) {
    e.preventDefault();
    closeUserMenu();
    return;
  }
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

document.addEventListener("click", (e) => {
  if (!userPanel.contains(e.target)) closeUserMenu();
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
