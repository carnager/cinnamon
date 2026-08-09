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

let libraries = [];
let activeView = "home";
let activeLibraryId = "";
let libraryItems = [];
let currentPage = 1;
const perPage = 50;
let libraryBaseOffset = 0;
let currentLetter = "";
let alphabetIndex = [];
let searchTimer = 0;
let currentItem = null;
let currentShow = null;
let authToken = localStorage.getItem("popcornToken") || "";
let currentUser = null;
let resumeFractionMap = new Map();
let resumeFractionDirty = false;
let watchedItemIds = new Set();
let mediaProgressRows = [];
let watchedShowKeys = new Set();
// Personal 1-10 ratings, synced with Trakt: item id -> rating, show key -> rating.
let userItemRatings = new Map();
let userShowRatings = new Map();
let watchlistItemIds = new Set();
let watchlistShowKeys = new Set();
let recommendationExclusionKeys = new Set();
let currentGenre = "";
let currentSort = "";
let currentSeenStatus = "";
let currentMinRating = 0;
let currentDecades = "";
let currentSeason = null;
let libraryGenres = [];
let libraryDecades = [];
let searchScope = "both";
let searchFields = new Set(["title"]);
let pageHasNext = false;
let libraryGridEl = null;
let libraryObserver = null;
let libraryTopObserver = null;
let libraryLoading = false;
let scanPollTimer = 0;
let lastScanSignature = "";
let scanRefreshInFlight = false;
let alphabetScrollFrame = 0;
let homeData = {
  sections: [],
  watchlistMovies: [],
  watchlistShows: [],
};

async function api(path, options) {
  const init = { ...(options || {}) };
  const headers = new Headers(init.headers || {});
  if (authToken) headers.set("Authorization", `Bearer ${authToken}`);
  init.headers = headers;
  const method = (init.method || "GET").toUpperCase();
  const res = await fetch(path, init);
  if (!res.ok) throw new Error(await res.text());
  /* Anything that writes can invalidate any cached read — marking an episode
     seen changes item lists, watchlists, progress and home shelves at once.
     Rather than track which reads a given write affects, drop the lot; reads
     are cheap and this keeps the cache from ever serving something the user
     just changed. */
  if (method !== "GET") clearAPICache();
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text.trim()) return null;
  return JSON.parse(text);
}

/* ── Read cache ──────────────────────────────────────────────────────
   Navigating between libraries used to re-issue every request each time,
   so returning to a view you had just left cost a full round of fetches
   even though nothing could have changed in the meantime.

   Within the TTL a cached response is returned as-is and no request is
   made. Past it, the fetch happens normally. Deliberately not
   stale-while-revalidate: serving stale data and swapping it out underneath
   the user needs every render path to be re-entrant, which this UI's
   build-the-DOM-once render functions are not. */
const API_CACHE_TTL_MS = 60_000;
const apiCache = new Map();

function clearAPICache() {
  apiCache.clear();
}

/* apiCached is for idempotent GETs whose staleness for up to a minute is
   harmless. Never use it for anything a write should be visible in
   immediately without going through api() — writes clear the whole cache. */
async function apiCached(path, ttl = API_CACHE_TTL_MS) {
  const hit = apiCache.get(path);
  if (hit && Date.now() - hit.at < ttl) return hit.data;
  const data = await api(path);
  apiCache.set(path, { at: Date.now(), data });
  return data;
}

function setAuthenticated(user, token) {
  currentUser = user;
  authToken = token || authToken;
  if (authToken) localStorage.setItem("popcornToken", authToken);
  authScreen.classList.add("hidden");
  appShell.classList.remove("hidden");
  renderUserPanel();
  startScanStatusPolling();
}

function setUnauthenticated(message = "") {
  currentUser = null;
  authToken = "";
  localStorage.removeItem("popcornToken");
  stopScanStatusPolling();
  appShell.classList.add("hidden");
  authScreen.classList.remove("hidden");
  loginError.textContent = message;
  loginPass.value = "";
  loginUser.focus();
  stopPlayer();
}

function renderUserPanel() {
  userPanel.innerHTML = "";
  if (!currentUser) return;
  const menu = document.createElement("details");
  menu.className = "user-menu";
  const summary = document.createElement("summary");
  summary.className = "user-menu-trigger";
  summary.setAttribute("aria-label", "User menu");
  summary.append(
    userAvatarElement(currentUser, "user-menu-avatar"),
    el("span", "user-menu-name", currentUser.displayName || currentUser.username),
    el("span", "user-menu-caret", "▾"),
  );
  const actions = el("div", "user-menu-popover");
  const renderRoot = () => {
    actions.innerHTML = "";
    const head = el("div", "user-menu-head");
    head.append(el("span", null, "USER MENU"), el("strong", null, currentUser.displayName || currentUser.username));
    actions.append(head);
    actions.append(userMenuAction("Watch history", "Your recently watched movies and episodes", () => {
      menu.open = false;
      renderHistory().catch(console.error);
    }, "history"));
    actions.append(userMenuAction("Preferred audio", playbackPreferenceLabel("audio"), () => renderPreferencePage("audio"), "audio"));
    actions.append(userMenuAction("Preferred subtitles", playbackPreferenceLabel("subtitle"), () => renderPreferencePage("subtitle"), "subtitles"));
    if (currentUser?.isAdmin) {
      actions.append(userMenuAction("Update libraries", "Scan for new media", (button) => scanLibraries(button).catch(console.error), "refresh"));
      actions.append(userMenuAction("Server settings", "Users, Trakt and app builds", () => {
        menu.open = false;
        renderSettings().catch(console.error);
      }, "settings"));
    }
    actions.append(userMenuAction("Logout", "Sign out of Cinnamon", () => {
      menu.open = false;
      logoutUser();
    }, "logout", true));
  };
  const renderPreferencePage = (kind) => {
    actions.innerHTML = "";
    const head = el("div", "user-menu-head");
    head.append(el("span", null, kind === "audio" ? "PREFERRED AUDIO" : "PREFERRED SUBTITLES"), el("strong", null, "Playback languages"));
    actions.append(head, userMenuAction("Back", "User menu", () => renderRoot(), "back"));
    const options = kind === "audio" ? AUDIO_PREFERENCES : SUBTITLE_PREFERENCES;
    const current = kind === "audio" ? preferredAudioLanguage() : preferredSubtitleLanguage();
    for (const [value, label] of options) {
      const button = userMenuAction(label, value === current ? "Selected" : "", () => {
        localStorage.setItem(kind === "audio" ? "cinnamonPreferredAudio" : "cinnamonPreferredSubtitle", value);
        renderRoot();
      }, kind === "audio" ? "audio" : "subtitles");
      if (value === current) button.classList.add("selected");
      actions.append(button);
    }
  };
  renderRoot();
  menu.append(summary, actions);
  userPanel.append(menu);
}

const LANGUAGE_PREFERENCES = [["de", "Deutsch"], ["en", "English"], ["fr", "Français"], ["es", "Español"], ["it", "Italiano"], ["ja", "日本語"]];
const AUDIO_PREFERENCES = [["", "Track default"], ...LANGUAGE_PREFERENCES];
const SUBTITLE_PREFERENCES = [["off", "Off"], ["default", "Track default"], ...LANGUAGE_PREFERENCES];

function userMenuAction(label, detail, onClick, icon, danger = false) {
  const button = el("button", danger ? "user-menu-action danger-action" : "user-menu-action");
  button.type = "button";
  button.dataset.icon = icon || "";
  button.append(el("span", "user-menu-action-icon"), el("span", "user-menu-action-copy", [el("strong", null, label), detail ? el("small", null, detail) : null]));
  button.addEventListener("click", (event) => {
    // Preference pages replace the clicked button while the event is still
    // bubbling. Keep that detached target from looking like an outside click.
    event.stopPropagation();
    onClick(button);
  });
  return button;
}

function preferredAudioLanguage() { return localStorage.getItem("cinnamonPreferredAudio") || ""; }
function preferredSubtitleLanguage() { return localStorage.getItem("cinnamonPreferredSubtitle") || "off"; }
function normalizeLanguage(value) {
  const lang = String(value || "").trim().toLowerCase();
  return ({ ger: "de", deu: "de", eng: "en", fre: "fr", fra: "fr", spa: "es", ita: "it", jpn: "ja", kor: "ko" })[lang] || lang;
}
function preferredAudioIndex(tracks) {
  const preferred = normalizeLanguage(preferredAudioLanguage());
  return tracks.find((track) => preferred && normalizeLanguage(track.language) === preferred)?.index
    ?? tracks.find((track) => track.default)?.index
    ?? tracks[0]?.index
    ?? null;
}
function preferredSubtitleIndex(tracks) {
  const preferred = preferredSubtitleLanguage();
  if (preferred === "off") return null;
  if (preferred === "default") return tracks.find((track) => track.default)?.index ?? null;
  return tracks.find((track) => normalizeLanguage(track.language) === normalizeLanguage(preferred))?.index ?? null;
}
function playbackPreferenceLabel(kind) {
  const value = kind === "audio" ? preferredAudioLanguage() : preferredSubtitleLanguage();
  const options = kind === "audio" ? AUDIO_PREFERENCES : SUBTITLE_PREFERENCES;
  return options.find(([candidate]) => candidate === value)?.[1] || "Track default";
}

async function scanLibraries(button) {
  const oldLabel = button?.textContent || "";
  if (button) {
    button.disabled = true;
    button.textContent = "Updating...";
  }
  try {
    await api("/api/scan", { method: "POST" });
    await waitForLibraryUpdate();
    await refreshCurrentRoute();
  } finally {
    if (button) {
      button.disabled = false;
      button.textContent = oldLabel;
    }
  }
}

function scanSignature(statuses) {
  return (statuses || [])
    .filter((status) => status?.finishedAt)
    .map((status) => [
      status.libraryId || "",
      status.finishedAt || "",
      status.status || "",
      status.mediaFound || 0,
      status.itemsImported || 0,
      status.errors || 0,
    ].join(":"))
    .sort()
    .join("|");
}

function scanRunning(statuses) {
  return (statuses || []).some((status) => status?.status === "running");
}

async function scanStatus() {
  return api("/api/scan").catch(() => []);
}

function startScanStatusPolling() {
  if (scanPollTimer) return;
  scanStatus().then((statuses) => {
    lastScanSignature = scanSignature(statuses);
  }).catch(() => {});
  scanPollTimer = window.setInterval(() => {
    refreshAfterCompletedScan().catch(console.error);
  }, 10000);
}

function stopScanStatusPolling() {
  if (scanPollTimer) {
    clearInterval(scanPollTimer);
    scanPollTimer = 0;
  }
  lastScanSignature = "";
  scanRefreshInFlight = false;
}

async function refreshAfterCompletedScan() {
  if (!authToken || scanRefreshInFlight) return;
  const statuses = await scanStatus();
  if (scanRunning(statuses)) return;
  const signature = scanSignature(statuses);
  if (!signature) return;
  if (!lastScanSignature) {
    lastScanSignature = signature;
    return;
  }
  if (signature === lastScanSignature) return;
  lastScanSignature = signature;
  scanRefreshInFlight = true;
  try {
    await refreshCurrentRoute();
  } finally {
    scanRefreshInFlight = false;
  }
}

async function waitForLibraryUpdate() {
  const started = Date.now();
  let sawRunning = false;
  const previous = lastScanSignature;
  while (Date.now() - started < 30 * 60 * 1000) {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    const statuses = await scanStatus();
    const running = scanRunning(statuses);
    sawRunning = sawRunning || running;
    const signature = scanSignature(statuses);
    if (signature && signature !== previous && !running) {
      lastScanSignature = signature;
      return statuses;
    }
    if (sawRunning && !running) {
      if (signature) lastScanSignature = signature;
      return statuses;
    }
  }
  return [];
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

function libraryById(id) {
  return libraries.find((library) => library.id === id);
}

// Navigate to a library's grid, resetting filters/show context. Used by the
// library nav and by breadcrumbs that may point at a different library than the
// one currently active (e.g. opening a TV episode from the home Continue row).
function goToLibrary(libraryId, skipHistory) {
  search.value = "";
  activeView = "library";
  if (libraryId) activeLibraryId = libraryId;
  currentPage = 1;
  currentShow = null;
  currentSeason = null;
  currentGenre = "";
  currentSort = "";
  currentSeenStatus = "";
  currentMinRating = 0;
  currentDecades = "";
  renderNav();
  return loadLibraryPage(skipHistory);
}

function showKey(libraryId, title) {
  return `${String(libraryId || "").toLowerCase()}\u0000${String(title || "").trim().toLowerCase()}`;
}

function rebuildResumeFractions(rows) {
  resumeFractionMap = new Map();
  for (const row of rows || []) {
    const position = Number(row?.positionMs || 0);
    const duration = Number(row?.durationMs || 0);
    if (row?.completed || duration <= 0 || position < 30000) continue;
    const cutoff = Math.max(duration - 90000, 30000);
    if (position >= cutoff) continue;
    resumeFractionMap.set(Number(row.itemId), Math.max(0.02, Math.min(0.98, position / duration)));
  }
  resumeFractionDirty = false;
}

function resumeFraction(item) {
  return resumeFractionMap.get(Number(item?.id || 0)) || 0;
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

function itemRecommendationExcluded(item) {
  return recommendationExclusionKeys.has(`item:${Number(item?.id || 0)}`);
}

/* "Not interested" hides the entry straight away; the next home refresh
   rebuilds the shelf without it. */
function dropRecommendation(sections, key) {
  return (sections || [])
    .map((section) => ({ ...section, entries: (section.entries || []).filter((entry) => entry.key !== key) }))
    .filter((section) => section.entries?.length || section.items?.length || section.shows?.length);
}

function showRecommendationKey(show) {
  return `show:${String(show?.libraryId || "").trim().toLowerCase()}:${String(show?.title || "").trim().toLowerCase()}`;
}

function showRecommendationExcluded(show) {
  return recommendationExclusionKeys.has(showRecommendationKey(show));
}

async function setItemRecommendationExcluded(item, excluded) {
  if (!item?.id) return;
  const key = `item:${Number(item.id)}`;
  await api(`/api/items/${encodeURIComponent(String(item.id))}/recommendation-exclusion`, { method: excluded ? "PUT" : "DELETE" });
  if (excluded) recommendationExclusionKeys.add(key);
  else recommendationExclusionKeys.delete(key);
  homeData.sections = dropRecommendation(homeData.sections, key);
}

async function setShowRecommendationExcluded(show, excluded) {
  const libraryId = show?.libraryId || activeLibraryId;
  const title = show?.title || "";
  if (!libraryId || !title) return;
  const key = showRecommendationKey({ libraryId, title });
  const query = new URLSearchParams({ libraryId, showTitle: title });
  await api(`/api/recommendations/exclusions/tv?${query}`, { method: excluded ? "PUT" : "DELETE" });
  if (excluded) recommendationExclusionKeys.add(key);
  else recommendationExclusionKeys.delete(key);
  homeData.sections = dropRecommendation(homeData.sections, key);
}

async function setItemPreference(item, preference) {
  if (preference === "unwatched") {
    await setItemRecommendationExcluded(item, false);
    await setItemSeen(item, false);
  } else if (preference === "seen") {
    await setItemRecommendationExcluded(item, false);
    await setItemSeen(item, true);
  } else if (preference === "not_interested") {
    await setItemSeen(item, false);
    await setItemRecommendationExcluded(item, true);
  }
}

async function setShowPreference(show, preference) {
  if (preference === "unwatched") {
    await setShowRecommendationExcluded(show, false);
    await setShowSeen(show, false);
  } else if (preference === "seen") {
    await setShowRecommendationExcluded(show, false);
    await setShowSeen(show, true);
  } else if (preference === "not_interested") {
    await setShowSeen(show, false);
    await setShowRecommendationExcluded(show, true);
  }
}

async function refreshMediaState() {
  if (!authToken) return;
  const [progress, showProgress, watchlist, ratings] = await Promise.all([
    loadAllProgress().catch(() => []),
    apiCached("/api/progress/tv").catch(() => []),
    apiCached("/api/watchlist?limit=1000").catch(() => ({ items: [], shows: [] })),
    apiCached("/api/ratings/user").catch(() => []),
  ]);
  applyUserRatings(ratings);

  mediaProgressRows = progress || [];
  rebuildResumeFractions(mediaProgressRows);
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
    const page = await apiCached(`/api/progress?limit=${limit}&offset=${offset}`);
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

function renderNav() {
  appShell.classList.toggle("settings-mode", activeView === "settings" || activeView === "users");
  /* home-mode is NOT set here. renderNav() runs at the start of a navigation,
     while the previous page is still on screen, so toggling it on intent
     stripped the view header's top padding and yanked the outgoing content
     up ~24px before the transition had even begun. setView() owns this: it
     derives the flag from whether the committed content actually contains a
     hero, and applies it in the same frame as the content itself. */
  libraryNav.innerHTML = "";

  const navLink = (label, active, onClick) => {
    const a = el("button", active ? "nav-link active" : "nav-link", label);
    a.type = "button";
    a.addEventListener("click", onClick);
    return a;
  };

  libraryNav.append(navLink("Home", activeView === "home", () => {
    search.value = "";
    currentPage = 1;
    activeView = "home";
    renderNav();
    loadHome().catch(console.error);
  }));

  for (const library of libraries) {
    libraryNav.append(navLink(library.name, activeView === "library" && library.id === activeLibraryId, () => {
      goToLibrary(library.id).catch(console.error);
    }));
  }

  libraryNav.append(navLink("Watchlist", activeView === "watchlist", () => {
    search.value = "";
    activeView = "watchlist";
    currentPage = 1;
    renderNav();
    renderWatchlist().catch(console.error);
  }));

  markTopbarDirty();
}

/* The filter bar is chrome, and chrome must change in the same frame as the
   content it belongs to. renderNav() runs at the start of a navigation and
   the new view lands ~90ms later, so rebuilding the bar there left the old
   page on screen with its filter bar already collapsed to zero height — the
   page visibly jumped, then changed. Marking it dirty instead lets setView()
   commit both together. */
let topbarDirty = false;

function markTopbarDirty() {
  topbarDirty = true;
}

function flushTopbarControls() {
  if (!topbarDirty) return;
  topbarDirty = false;
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
      ["", "Name A‑Z"],
      ["title_desc", "Name Z‑A"],
      ["mtime", "Added newest"],
      ["mtime_asc", "Added oldest"],
      ["year_desc", "Year newest"],
      ["year", "Year oldest"],
      ["rating", "Rating high"],
      ["rating_asc", "Rating low"],
    ], (value) => {
      currentSort = value;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }),
    topbarSelect("Seen", currentSeenStatus, [
      ["", "All"],
      ["started", "Started"],
      ["seen", "Seen"],
      ["unseen", "Unseen"],
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
    ], (value) => {
      currentMinRating = Number(value || 0);
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }),
    topbarMultiSelect("Genre", currentGenre, libraryGenres, (value) => {
      currentGenre = value;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }, "All genres"),
    topbarMultiSelect("Decade", currentDecades, libraryDecades.map((decade) => [String(decade), `${decade}s`]), (value) => {
      currentDecades = value;
      currentPage = 1;
      loadLibraryPage().catch(console.error);
    }, "All decades"),
  );
}

function topbarMultiSelect(label, value, options, onChange, allLabel = "All") {
  const selected = new Set(String(value || "").split(",").map((g) => g.trim()).filter(Boolean));
  const field = el("div", "topbar-filter");
  const trigger = el("button", "topbar-filter-trigger");
  trigger.type = "button";
  const selectedValue = selected.size === 1 ? [...selected][0] : "";
  const selectedOption = options.find((option) => (Array.isArray(option) ? option[0] : option) === selectedValue);
  const valueLabel = selected.size === 0 ? "All" : (selected.size === 1 ? (Array.isArray(selectedOption) ? selectedOption[1] : selectedValue) : `${selected.size} selected`);
  trigger.append(
    el("span", "topbar-filter-label", label),
    el("strong", "topbar-filter-value", valueLabel),
    el("span", "topbar-filter-caret", "▾"),
  );
  const menu = el("div", "topbar-filter-menu");
  trigger.addEventListener("click", (event) => {
    event.stopPropagation();
    const willOpen = !field.classList.contains("open");
    closeTopbarMenus();
    field.classList.toggle("open", willOpen);
  });
  menu.addEventListener("click", (event) => event.stopPropagation());
  const emit = () => onChange([...selected].join(","));
  const allBtn = el("button", selected.size === 0 ? "topbar-filter-option active" : "topbar-filter-option");
  allBtn.type = "button";
  allBtn.append(el("span", "topbar-filter-check", selected.size === 0 ? "✓" : ""), el("span", null, allLabel));
  allBtn.addEventListener("click", () => { selected.clear(); emit(); });
  menu.append(allBtn);
  for (const option of options) {
    const [optionValue, optionLabel] = Array.isArray(option) ? option : [option, option];
    const active = selected.has(optionValue);
    const button = el("button", active ? "topbar-filter-option active" : "topbar-filter-option");
    button.type = "button";
    button.append(el("span", "topbar-filter-check", active ? "✓" : ""), el("span", null, optionLabel));
    button.addEventListener("click", () => {
      if (selected.has(optionValue)) selected.delete(optionValue);
      else selected.add(optionValue);
      emit();
    });
    menu.append(button);
  }
  field.append(trigger, menu);
  return field;
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

function updateAlphabetRailFromScroll() {
  alphabetScrollFrame = 0;
  if (activeView !== "library") return;
  const rail = document.getElementById("alphaRail");
  if (!rail || !libraryGridEl) return;
  const threshold = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--topbar-height")) + 72;
  const cards = [...libraryGridEl.querySelectorAll(".item[data-title]")];
  const visible = cards.find((card) => card.getBoundingClientRect().bottom > threshold) || cards.at(-1);
  if (!visible) return;
  const first = String(visible.dataset.title || "#").trim().charAt(0).toUpperCase();
  const letter = /^[A-Z]$/.test(first) ? first : "#";
  if (currentLetter === letter) return;
  currentLetter = letter;
  rail.querySelectorAll(".alpha-letter").forEach((button) => {
    button.classList.toggle("active", button.textContent.trim().toUpperCase() === letter);
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

async function fetchItemsPage(libraryId, { limit = perPage, offset = 0, sort = "", genre = "", seen = "", minRating = 0, decades = "" } = {}) {
  const params = new URLSearchParams({ libraryId, limit: String(limit), offset: String(offset) });
  if (sort) params.set("sort", sort);
  if (genre) params.set("genre", genre);
  if (seen) params.set("seen", seen);
  if (minRating) params.set("minRating", String(minRating));
  if (decades) params.set("decades", decades);
  return apiCached(`/api/items?${params}`);
}

async function fetchItem(itemId) {
  return api(`/api/items/${encodeURIComponent(String(itemId))}`);
}

async function fetchShowsPage(libraryId, { limit = perPage, offset = 0, sort = "", genre = "", seen = "", minRating = 0, decades = "" } = {}) {
  const params = new URLSearchParams({ libraryId, limit: String(limit), offset: String(offset) });
  if (sort) params.set("sort", sort);
  if (genre) params.set("genre", genre);
  if (seen) params.set("seen", seen);
  if (minRating) params.set("minRating", String(minRating));
  if (decades) params.set("decades", decades);
  return apiCached(`/api/tv/shows?${params}`);
}

async function fetchLibraryGenres(libraryId) {
  return apiCached(`/api/genres?libraryId=${encodeURIComponent(libraryId)}`).catch(() => []);
}

async function fetchLibraryDecades(library) {
  const kind = library.type === "tv" ? "tv" : "movie";
  return apiCached(`/api/decades?libraryId=${encodeURIComponent(library.id)}&kind=${kind}`).catch(() => []);
}

async function fetchWatchlist() {
  const list = await api("/api/watchlist?limit=1000").catch(() => ({ items: [], shows: [] }));
  homeData.watchlistMovies = (list.items || []).filter((item) => item.kind === "movie");
  homeData.watchlistShows = list.shows || [];
  watchlistItemIds = new Set((list.items || []).map((item) => Number(item.id)).filter(Boolean));
  watchlistShowKeys = new Set((list.shows || []).map((show) => showKey(show.libraryId, show.title)));
  return list;
}

/* How long a navigation may take before it is worth telling the user
   anything. Measured on a real library, a library switch completes in
   ~110-180ms, so the old unconditional placeholder meant every navigation
   flashed content -> "Loading…" -> content. Blanking the page you are
   looking at, to show a word, for a tenth of a second, is what made
   browsing feel heavy. Past this threshold the wait is real and silence
   would feel broken instead. */
const LOADING_PLACEHOLDER_DELAY_MS = 250;
let pendingLoadingTimer = null;

function cancelPendingLoading() {
  if (pendingLoadingTimer !== null) {
    clearTimeout(pendingLoadingTimer);
    pendingLoadingTimer = null;
  }
}

function setLoading(label = "Loading...") {
  cancelPendingLoading();
  pendingLoadingTimer = setTimeout(() => {
    pendingLoadingTimer = null;
    view.innerHTML = "";
    view.append(el("div", "empty", label));
  }, LOADING_PLACEHOLDER_DELAY_MS);
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
  } else if (activeView === "history") {
    await renderHistory(skipHistory);
  } else {
    await loadLibraryPage(skipHistory);
  }
}

async function refreshCurrentRoute() {
  const state = history.state || routeFromLocation();
  if (state?.view) {
    await navigate(state, true);
  } else {
    await loadCurrentView(true);
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
  if (activeView === "history") {
    renderHistory(skipHistory).catch(console.error);
    return;
  }
  const frag = document.createDocumentFragment();
  const library = activeLibrary();
  currentShow = null;
  currentSeason = null;
  markTopbarDirty();
  if (!skipHistory) pushState({ view: "library", libraryId: library?.id, page: currentPage, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating, decades: currentDecades });

  if (!library) {
    frag.append(el("div", "empty", "No libraries configured"));
    setView(frag);
    return;
  }
  stopPlayer();

  const header = el("div", "view-header");
  const genreLabel = currentGenre ? currentGenre.split(",").filter(Boolean).join(" · ") : "";
  header.append(
    el("h1", null, genreLabel ? `${library.name} / ${genreLabel}` : library.name),
    el("span", "library-count", library.type === "tv" ? `${libraryItems.length} shows` : `${libraryItems.length} movies`),
  );
  frag.append(header);

  if (!libraryItems.length) {
    frag.append(el("div", "empty", "No media found"));
    setView(frag);
    return;
  }

  let topSentinel = null;
  if (libraryBaseOffset > 0) {
    topSentinel = el("div", "scroll-sentinel");
    frag.append(topSentinel);
  }

  libraryGridEl = el("div", library.type === "tv" ? "grid show-grid" : "grid");
  for (const item of libraryItems) {
    libraryGridEl.append(library.type === "tv" ? showCard(item) : itemCard(item));
  }
  frag.append(libraryGridEl);

  const sentinel = el("div", "scroll-sentinel");
  frag.append(sentinel);
  setView(frag);
  // The rail lives on <body>, not inside .view: the view-fade animation
  // leaves a transform on .view, which would turn position:fixed into
  // "fixed inside the scrolling content".
  mountAlphabetRail(alphabetRailEligible() && alphabetIndex.length > 1
    ? alphabetRail(alphabetIndex, currentLetter, (entry) => jumpToLetter(entry).catch(console.error))
    : null);
  observeLibraryScroll(sentinel);
  observeLibraryScrollTop(topSentinel);
}

function observeLibraryScroll(sentinel) {
  if (libraryObserver) libraryObserver.disconnect();
  if (!pageHasNext) return;
  libraryObserver = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) loadMoreLibrary().catch(console.error);
  }, { rootMargin: "600px 0px" });
  libraryObserver.observe(sentinel);
}

/* After a letter jump the list starts mid-library; scrolling up loads the
   pages before the jump point and prepends them. */
function observeLibraryScrollTop(sentinel) {
  if (libraryTopObserver) { libraryTopObserver.disconnect(); libraryTopObserver = null; }
  if (!sentinel || libraryBaseOffset <= 0) return;
  libraryTopObserver = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) loadPrevLibrary().catch(console.error);
  }, { rootMargin: "200px 0px" });
  libraryTopObserver.observe(sentinel);
}

async function loadPrevLibrary() {
  if (libraryLoading || libraryBaseOffset <= 0) return;
  const library = activeLibrary();
  if (!library || !libraryGridEl) return;
  libraryLoading = true;
  try {
    const start = Math.max(0, libraryBaseOffset - perPage);
    const limit = libraryBaseOffset - start;
    const page = library.type === "tv"
      ? await fetchShowsPage(library.id, { limit, offset: start, genre: currentGenre, decades: currentDecades })
      : await fetchItemsPage(library.id, { limit, offset: start, genre: currentGenre, decades: currentDecades });
    const items = page || [];
    if (items.length) {
      libraryItems = items.concat(libraryItems);
      const scrollBefore = window.scrollY;
      const heightBefore = document.documentElement.scrollHeight;
      const cards = document.createDocumentFragment();
      for (const item of items) cards.append(library.type === "tv" ? showCard(item) : itemCard(item));
      libraryGridEl.prepend(cards);
      // Keep the viewport anchored on what the user was looking at. The
      // absolute scrollTo (from the captured scrollY) also neutralizes any
      // native scroll-anchoring adjustment, so it can't double-compensate.
      const delta = document.documentElement.scrollHeight - heightBefore;
      window.scrollTo({ top: scrollBefore + delta, behavior: "instant" });
      const count = document.querySelector(".library-count");
      if (count) count.textContent = library.type === "tv" ? `${libraryItems.length} shows` : `${libraryItems.length} movies`;
    }
    libraryBaseOffset = start;
    if (libraryBaseOffset <= 0 && libraryTopObserver) { libraryTopObserver.disconnect(); libraryTopObserver = null; }
  } finally {
    libraryLoading = false;
  }
}

async function loadMoreLibrary() {
  if (libraryLoading || !pageHasNext) return;
  const library = activeLibrary();
  if (!library) return;
  libraryLoading = true;
  try {
    currentPage += 1;
    // The loaded window can grow upward after an A–Z jump. Derive the next
    // offset from that window, rather than the page counter, to avoid loading
    // duplicate rows after the user scrolls back down.
    const offset = libraryBaseOffset + libraryItems.length;
    const page = library.type === "tv"
      ? await fetchShowsPage(library.id, { limit: perPage, offset, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating, decades: currentDecades })
      : await fetchItemsPage(library.id, { limit: perPage, offset, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating, decades: currentDecades });
    const items = page || [];
    pageHasNext = items.length >= perPage;
    if (items.length && libraryGridEl) {
      libraryItems = libraryItems.concat(items);
      for (const item of items) {
        libraryGridEl.append(library.type === "tv" ? showCard(item) : itemCard(item));
      }
      const count = document.querySelector(".library-count");
      if (count) count.textContent = library.type === "tv" ? `${libraryItems.length} shows` : `${libraryItems.length} movies`;
    }
    if (!pageHasNext && libraryObserver) libraryObserver.disconnect();
  } finally {
    libraryLoading = false;
  }
}

function renderHome(skipHistory) {
  activeView = "home";
  renderNav();
  currentShow = null;
  currentSeason = null;
  if (!skipHistory) pushState({ view: "home" });
  stopPlayer();

  const frag = document.createDocumentFragment();
  const featured = pickHeroItem(homeData);
  if (featured) frag.append(homeHero(featured.item, featured.kick));
  else frag.append(el("div", "view-header home-header", el("h1", null, "Home"), el("span", null, "Ready to watch")));
  frag.append(curatedHome(homeData));
  setView(frag);
}

/* Recommendation ranking lives on the server. The web client only presents
   the first eligible entry from the shared per-user feed. */
function pickHeroItem(data) {
  const hero = (data.sections || []).find((section) => section.layout === "hero");
  for (const recommendation of hero?.entries || []) {
    const entry = recommendation.item || recommendation.show;
    if (entry && (entry.backdropItemId || entry.backdropPath)) {
      return { item: entry, kick: recommendation.reason || "Recommended for you" };
    }
  }
  return null;
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

async function renderHistory(skipHistory) {
  activeView = "history";
  renderNav();
  currentShow = null;
  currentSeason = null;
  search.value = "";
  if (!skipHistory) pushState({ view: "history" });
  stopPlayer();
  setLoading("Loading watch history…");
  const data = await api("/api/history?limit=200").catch(() => ({ items: [], source: "local" }));
  const frag = document.createDocumentFragment();
  const header = el("div", "view-header history-header");
  header.append(el("div", null, [el("span", "eyebrow", "YOUR ACTIVITY"), el("h1", null, "Watch history")]), el("span", null, data.traktLinked ? "Local + Trakt" : "This device"));
  frag.append(header);
  if (!data.items?.length) {
    frag.append(el("div", "empty", "Nothing watched yet"));
    setView(frag);
    return;
  }
  const list = el("div", "history-list");
  for (const entry of data.items) {
    const row = el(entry.item ? "button" : "div", entry.item ? "history-row interactive" : "history-row");
    if (entry.item) {
      row.type = "button";
      row.addEventListener("click", () => openDetail(entry.item).catch(console.error));
    }
    const art = el("div", "history-art");
    if (entry.item && hasPosterImage(entry.item)) artworkInto(art, entry.item, "poster", 160);
    else art.textContent = entry.kind === "episode" ? "TV" : "FILM";
    const copy = el("div", "history-copy");
    copy.append(el("strong", null, entry.title), el("span", null, [entry.subtitle, entry.year].filter(Boolean).join(" · ")));
    const watched = el("time", null, formatHistoryTime(entry.watchedAt));
    row.append(art, copy, watched);
    if (!entry.item) row.append(el("span", "history-source", "Trakt"));
    list.append(row);
  }
  frag.append(list);
  setView(frag);
}

function formatHistoryTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value || "").replace("T", " ").slice(0, 16);
  return date.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
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

  frag.append(searchOptions());

  if (query.length < 2) {
    frag.append(el("div", "search-state", [el("strong", null, "Search your library"), el("span", null, "Titles are searched by default. Add other fields when you need them.")]));
    setView(frag);
    return;
  }

  const fields = encodeURIComponent([...searchFields].join(","));
  const [movieResults, showResults] = await Promise.all([
    searchScope === "tv" ? Promise.resolve([]) : api(`/api/search?limit=500&kind=movie&q=${encodeURIComponent(query)}&fields=${fields}`).then((r) => r.items || []).catch(() => []),
    searchScope === "movies" ? Promise.resolve([]) : api(`/api/tv/shows?limit=500&q=${encodeURIComponent(query)}&fields=${fields}`).catch(() => []),
  ]);

  frag.append(el("div", "search-result-count", `${movieResults.length + showResults.length} results`));

  if (!movieResults.length && !showResults.length) {
    frag.append(el("div", "search-state", [el("strong", null, `Nothing found for “${query}”`), el("span", null, "Try another phrase or include another search field.")]));
    setView(frag);
    return;
  }

  const grid = el("div", "grid search-grid");
  for (const show of showResults) grid.append(showCard(show));
  for (const movie of movieResults) grid.append(itemCard(movie));
  frag.append(grid);
  setView(frag);
}

function searchOptions() {
  const wrap = el("section", "search-page-head");
  wrap.append(el("span", "eyebrow", "DISCOVER"), el("h1", null, "Search"));
  const scope = el("div", "search-option-row");
  scope.append(el("span", "search-option-label", "SCOPE"));
  for (const [value, label] of [["both", "Movies & TV"], ["movies", "Movies"], ["tv", "TV shows"]]) {
    const button = el("button", searchScope === value ? "search-chip active" : "search-chip", label);
    button.type = "button";
    button.addEventListener("click", () => { searchScope = value; renderSearch(true).catch(console.error); });
    scope.append(button);
  }
  const fields = el("div", "search-option-row");
  fields.append(el("span", "search-option-label", "SEARCH IN"));
  for (const [value, label] of [["title", "Title"], ["original", "Original title"], ["people", "People"], ["description", "Description"]]) {
    const button = el("button", searchFields.has(value) ? "search-chip active" : "search-chip", label);
    button.type = "button";
    button.addEventListener("click", () => {
      if (searchFields.has(value) && searchFields.size > 1) searchFields.delete(value);
      else searchFields.add(value);
      renderSearch(true).catch(console.error);
    });
    fields.append(button);
  }
  wrap.append(scope, fields);
  return wrap;
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
  for (const section of data.sections || []) {
    // Layouts this build can draw. Anything else is a section type from a
    // newer server: skip it rather than render an empty shelf.
    if (section.layout !== "poster" && section.layout !== "progress") continue;
    if (section.shows?.length) {
      appendShelf(wrap, section.title, section.shows, (shows) => renderShelfGrid(shows.map((show) => showCard(show))));
    } else {
      appendShelf(wrap, section.title, section.items, (items) => renderShelfGrid(items));
    }
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
  section.append(hScroller(renderItems(items.slice(0, 18))));
  return section;
}

/* hScroller wraps a horizontally scrolling row with paddle buttons so mouse
   users can page through it — the row's native scrollbar is hidden and a
   plain wheel only scrolls vertically. Buttons fade in on hover and disable
   at either end; touch devices keep native swiping (buttons hidden in CSS). */
function hScroller(row) {
  const wrap = el("div", "shelf-scroller");
  const prev = el("button", "shelf-nav prev", "‹");
  prev.type = "button";
  prev.setAttribute("aria-label", "Scroll left");
  const next = el("button", "shelf-nav next", "›");
  next.type = "button";
  next.setAttribute("aria-label", "Scroll right");
  const step = () => Math.max(Math.round(row.clientWidth * 0.85), 220);
  prev.addEventListener("click", () => row.scrollBy({ left: -step(), behavior: "smooth" }));
  next.addEventListener("click", () => row.scrollBy({ left: step(), behavior: "smooth" }));
  const sync = () => {
    const max = row.scrollWidth - row.clientWidth;
    wrap.classList.toggle("scrollable", max > 8);
    prev.disabled = row.scrollLeft <= 4;
    next.disabled = row.scrollLeft >= max - 4;
  };
  row.addEventListener("scroll", sync, { passive: true });
  new ResizeObserver(sync).observe(row);
  wrap.append(prev, row, next);
  return wrap;
}

function renderShelfGrid(itemsOrNodes) {
  const row = el("div", "shelf-row");
  for (const entry of itemsOrNodes) row.append(entry instanceof Node ? entry : itemCard(entry));
  return row;
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

  const payload = await api("/api/home").catch(() => null);
  if (payload) applyHomePayload(payload);
  renderHome(skipHistory);
}

function applyUserRatings(rows) {
  userItemRatings = new Map();
  userShowRatings = new Map();
  for (const row of rows || []) {
    if (row.kind === "show") userShowRatings.set(showKey(row.libraryId, row.showTitle), Number(row.rating));
    else if (row.itemId) userItemRatings.set(Number(row.itemId), Number(row.rating));
  }
}

async function refreshUserRatings() {
  applyUserRatings(await api("/api/ratings/user").catch(() => []));
}

function userItemRating(item) {
  return userItemRatings.get(Number(item?.id || 0)) || 0;
}

function userShowRating(show) {
  return userShowRatings.get(showKey(show?.libraryId, show?.title)) || 0;
}

async function setItemRating(item, rating) {
  if (rating > 0) {
    await api(`/api/items/${encodeURIComponent(String(item.id))}/rating`, { method: "PUT", body: JSON.stringify({ rating }) });
    userItemRatings.set(Number(item.id), rating);
  } else {
    await api(`/api/items/${encodeURIComponent(String(item.id))}/rating`, { method: "DELETE" });
    userItemRatings.delete(Number(item.id));
  }
}

async function setShowRating(show, rating) {
  const params = new URLSearchParams({ libraryId: show.libraryId, showTitle: show.title });
  if (rating > 0) {
    await api(`/api/ratings/tv?${params}`, { method: "PUT", body: JSON.stringify({ rating }) });
    userShowRatings.set(showKey(show.libraryId, show.title), rating);
  } else {
    await api(`/api/ratings/tv?${params}`, { method: "DELETE" });
    userShowRatings.delete(showKey(show.libraryId, show.title));
  }
}

function applyHomePayload(payload) {
  refreshUserRatings().catch(() => {});
  mediaProgressRows = payload.progress || [];
  rebuildResumeFractions(mediaProgressRows);
  watchedItemIds = new Set(mediaProgressRows.filter((r) => r?.completed).map((r) => Number(r.itemId)).filter(Boolean));
  watchedShowKeys = new Set((payload.showProgress || []).filter((r) => r?.completed).map((r) => showKey(r.libraryId, r.showTitle)));
  const watchlist = payload.watchlist || { items: [], shows: [] };
  watchlistItemIds = new Set((watchlist.items || []).map((i) => Number(i.id)).filter(Boolean));
  watchlistShowKeys = new Set((watchlist.shows || []).map((s) => showKey(s.libraryId, s.title)));
  homeData.sections = payload.sections || [];
  homeData.watchlistMovies = (watchlist.items || []).filter((i) => i.kind === "movie");
  homeData.watchlistShows = watchlist.shows || [];
  recommendationExclusionKeys = new Set(payload.excludedRecommendationKeys || []);
}

async function loadLibraryPage(skipHistory) {
  const library = activeLibrary();
  if (!library) return;
  activeView = "library";
  currentShow = null;
  currentSeason = null;
  currentPage = 1;
  if (libraryObserver) { libraryObserver.disconnect(); libraryObserver = null; }
  if (libraryTopObserver) { libraryTopObserver.disconnect(); libraryTopObserver = null; }
  search.value = "";
  renderNav();
  setLoading();
  const offset = 0;
  libraryBaseOffset = 0;
  currentLetter = "";
  await refreshMediaState();
  const [genres, decades, page, alphabet] = await Promise.all([
    fetchLibraryGenres(library.id),
    fetchLibraryDecades(library),
    library.type === "tv"
      ? fetchShowsPage(library.id, { limit: perPage, offset, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating, decades: currentDecades })
      : fetchItemsPage(library.id, { limit: perPage, offset, genre: currentGenre, sort: currentSort, seen: currentSeenStatus, minRating: currentMinRating, decades: currentDecades }),
    alphabetRailEligible() ? fetchAlphabet(library) : Promise.resolve([]),
  ]);
  libraryGenres = genres || [];
  libraryDecades = decades || [];
  libraryItems = page || [];
  alphabetIndex = alphabet || [];
  pageHasNext = libraryItems.length >= perPage;
  render(skipHistory);
}

/* The A-Z rail jumps by offset into the title-sorted listing, so it only
   applies while the list is title-sorted and unfiltered by seen/rating
   (the alphabet endpoint knows nothing about those). */
function alphabetRailEligible() {
  return !currentSort && !currentSeenStatus && !currentMinRating;
}

async function fetchAlphabet(library) {
  const params = new URLSearchParams({ libraryId: library.id });
  if (library.type === "tv") params.set("kind", "tv");
  if (currentGenre) params.set("genre", currentGenre);
  if (currentDecades) params.set("decades", currentDecades);
  return apiCached(`/api/alphabet?${params}`).catch(() => []);
}

async function jumpToLetter(entry) {
  const library = activeLibrary();
  if (!library || libraryLoading) return;
  libraryLoading = true;
  try {
    currentLetter = entry.letter;
    libraryBaseOffset = entry.offset;
    currentPage = 1;
    const page = library.type === "tv"
      ? await fetchShowsPage(library.id, { limit: perPage, offset: entry.offset, genre: currentGenre, decades: currentDecades })
      : await fetchItemsPage(library.id, { limit: perPage, offset: entry.offset, genre: currentGenre, decades: currentDecades });
    libraryItems = page || [];
    pageHasNext = libraryItems.length >= perPage;
  } finally {
    libraryLoading = false;
  }
  render(true);
  window.scrollTo({ top: 0 });
}

async function load() {
  libraries = (await api("/api/libraries")) || [];
  const route = routeFromLocation();
  if (route.q) search.value = route.q;
  if (route.page) currentPage = route.page;
  if (route.genre) currentGenre = route.genre;
  if (route.seen) currentSeenStatus = route.seen;
  if (route.minRating) currentMinRating = Number(route.minRating || 0);
  if (route.decades) currentDecades = route.decades;
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
  currentDecades = state.decades || "";
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
  } else if (state.view === "history") {
    await renderHistory(true);
  } else if (state.view === "settings") {
    await renderSettings(true);
  } else if (state.view === "users") {
    await renderUsers(true);
  } else if (state.view === "homeLayout") {
    await renderHomeLayout(true);
  } else if (state.view === "library") {
    await loadLibraryPage(true);
  } else if ((state.view === "show" || state.view === "season") && state.showTitle) {
    activeView = "library";
    renderNav();
    const show = await fetchShowSummary(state.libraryId || activeLibraryId, state.showTitle);
    if (show) await openShow(show, true, state.season != null ? Number(state.season) : undefined);
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
    decades: params.get("decades") || "",
    q: params.get("q") || "",
  };
  if (parts[0] === "search") {
    state.view = "search";
  } else if (parts[0] === "watchlist") {
    state.view = "watchlist";
  } else if (parts[0] === "history") {
    state.view = "history";
  } else if (parts[0] === "settings") {
    state.view = "settings";
    if (parts[1] === "users") state.view = "users";
    if (parts[1] === "home") state.view = "homeLayout";
  } else if (parts[0] === "library") {
    state.view = "library";
  }
  if (parts[2] === "show" && parts[3]) {
    state.view = "show";
    state.showTitle = parts.slice(3).join("/");
    if (params.has("season")) state.season = Number(params.get("season") || "0");
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
  if (state.view === "home") path = "/";
  else if (state.view === "search") path = "/search";
  else if (state.view === "watchlist") path = "/watchlist";
  else if (state.view === "history") path = "/history";
  else if (state.view === "settings") path = "/settings";
  else if (state.view === "users") path = "/settings/users";
  else if (state.view === "homeLayout") path = "/settings/home";
  else if (libraryId) path = `/library/${libraryId}`;
  if ((state.view === "show" || state.view === "season") && state.showTitle) {
    path += `/show/${encodeURIComponent(state.showTitle)}`;
  } else if (state.view === "detail" && state.itemId) {
    path += `/item/${encodeURIComponent(String(state.itemId))}`;
  }
  const params = new URLSearchParams();
  if (state.view === "library" && state.page && state.page > 1) params.set("page", String(state.page));
  if (state.view === "library" && state.genre) params.set("genre", state.genre);
  if (state.view === "library" && state.sort) params.set("sort", state.sort);
  if (state.view === "library" && state.seen) params.set("seen", state.seen);
  if (state.view === "library" && state.minRating) params.set("minRating", String(state.minRating));
  if (state.view === "library" && state.decades) params.set("decades", state.decades);
  if ((state.view === "show" || state.view === "season") && state.season !== undefined && state.season !== null) params.set("season", String(state.season));
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

document.addEventListener("click", (e) => {
  if (!e.target.closest(".topbar-filter")) {
    closeTopbarMenus();
  }
});

window.addEventListener("scroll", () => {
  if (!alphabetScrollFrame) alphabetScrollFrame = requestAnimationFrame(updateAlphabetRailFromScroll);
}, { passive: true });

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && userPanel.querySelector("details[open]")) {
    e.preventDefault();
    closeUserMenu();
  }
});

document.addEventListener("click", (e) => {
  if (!userPanel.contains(e.target)) closeUserMenu();
});

bootstrapAuth().catch(console.error);
