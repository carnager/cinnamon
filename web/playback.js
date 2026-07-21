/* ── Generic DOM helpers (shared across the app) ── */
function el(tag, className, children) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (typeof children === "string") e.textContent = children;
  else if (Array.isArray(children)) children.forEach((c) => { if (c) e.append(c); });
  else if (children instanceof Node) e.append(children);
  return e;
}

function setView(content) {
  mountAlphabetRail(null);
  view.innerHTML = "";
  if (typeof content === "string") view.innerHTML = content;
  else if (content) view.append(content);
  // Nav goes translucent only when a full-bleed hero is on screen.
  if (typeof appShell !== "undefined" && appShell) {
    appShell.classList.toggle("home-mode", Boolean(view.querySelector(".home-hero")));
  }
  view.classList.toggle("has-backdrop", Boolean(view.querySelector(".page-backdrop")));
  view.classList.remove("view-fade");
  void view.offsetWidth;
  view.classList.add("view-fade");
  window.scrollTo({ top: 0, behavior: "instant" });
}

/* ── Player elements ── */
const player = document.querySelector("#player");
const theater = document.querySelector("#theater");
const nowPlaying = document.querySelector("#nowPlaying");
const timeline = document.querySelector("#timeline");
const seekPlayed = document.querySelector("#seekPlayed");
const seekBuffered = document.querySelector("#seekBuffered");
const currentTimeEl = document.querySelector("#currentTime");
const durationEl = document.querySelector("#duration");
const playPause = document.querySelector("#playPause");
const centerToggle = document.querySelector("#centerToggle");
const rewBtn = document.querySelector("#rewBtn");
const ffwdBtn = document.querySelector("#ffwdBtn");
const muteBtn = document.querySelector("#muteBtn");
const volume = document.querySelector("#volume");
const fullscreenBtn = document.querySelector("#fullscreenBtn");
const closePlayerBtn = document.querySelector("#closePlayer");
const audioBtn = document.querySelector("#audioBtn");
const subsBtn = document.querySelector("#subsBtn");
const qualityBtn = document.querySelector("#qualityBtn");
const audioMenu = document.querySelector("#audioMenu");
const subsMenu = document.querySelector("#subsMenu");
const qualityMenu = document.querySelector("#qualityMenu");
const nextEpBtn = document.querySelector("#nextEpBtn");
const upNextEl = document.querySelector("#upNext");
const playerStatus = document.querySelector("#playerStatus");
const playerStatusText = document.querySelector("#playerStatusText");

/* ── Playback state ── */
const QUALITY_OPTIONS = [
  { label: "Auto", kbps: null },
  { label: "15 Mbps", kbps: 15000 },
  { label: "10 Mbps", kbps: 10000 },
  { label: "8 Mbps", kbps: 8000 },
  { label: "5 Mbps", kbps: 5000 },
  { label: "3 Mbps", kbps: 3000 },
];
const UP_NEXT_LEAD_SEC = 25;
const TEXT_SUB_CODECS = new Set(["subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text", "dvb_subtitle"]);

const ICONS = {
  play: `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>`,
  pause: `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>`,
  rewind: `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z"/></svg>`,
  forward: `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M13 6v12l8.5-6L13 6zm-.5 6L4 6v12l8.5-6z"/></svg>`,
  volHigh: `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>`,
  volLow: `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M18.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM5 9v6h4l5 5V4L9 9H5z"/></svg>`,
  volMute: `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73 4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>`,
  fullscreen: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>`,
};

let hls = null;
let savedVolume = Number(localStorage.getItem("popcornVolume") || "1");
let preferredBandwidthKbps = (() => {
  const stored = localStorage.getItem("popcornBandwidthKbps");
  if (stored === null || stored === "") return null;
  const n = Number(stored);
  return Number.isFinite(n) && n > 0 ? n : null;
})();

const pb = {
  item: null,
  streams: [],
  audioStreams: [],
  subtitleStreams: [],
  audioIndex: null,
  subtitleIndex: null,
  bandwidthKbps: null,
  plan: null,
  mode: "direct",
  durationMs: 0,
  startOffsetSec: 0,
  episodes: [],
  nextItem: null,
  reportTimer: 0,
  lastReportedMs: -1,
  continuousMs: 0,
  evidenceAt: performance.now(),
  upNextShown: false,
  upNextDismissed: false,
  upNextTimer: 0,
  switching: false,
};

function playerOpen() {
  return !theater.classList.contains("hidden");
}

/* ── Browser capability profile ── */
function browserProfile() {
  const probe = document.createElement("video");
  const canPlay = (mime) => {
    try { return probe.canPlayType(mime) !== ""; } catch (_) { return false; }
  };
  const canMse = (mime) => {
    try { return Boolean(window.MediaSource && MediaSource.isTypeSupported(mime)); } catch (_) { return false; }
  };
  const video = [{ codec: "h264" }];
  if (canPlay('video/mp4; codecs="hev1.1.6.L93.B0"') || canMse('video/mp4; codecs="hev1.1.6.L93.B0"')) video.push({ codec: "hevc" });
  if (canPlay('video/webm; codecs="vp9"') || canMse('video/mp4; codecs="vp09.00.10.08"')) video.push({ codec: "vp9" });
  if (canMse('video/mp4; codecs="av01.0.05M.08"')) video.push({ codec: "av1" });

  const audio = [{ codec: "aac" }, { codec: "mp3" }];
  if (canPlay('audio/mp4; codecs="ac-3"')) audio.push({ codec: "ac3" });
  if (canPlay('audio/mp4; codecs="ec-3"')) audio.push({ codec: "eac3" });
  if (canPlay('audio/ogg; codecs="opus"') || canMse('audio/webm; codecs="opus"')) audio.push({ codec: "opus" });
  if (canPlay("audio/flac") || canPlay('audio/ogg; codecs="flac"')) audio.push({ codec: "flac" });

  const hlsFmp4 = Boolean(window.Hls && window.Hls.isSupported()) || canPlay("application/vnd.apple.mpegurl");

  return {
    schemaVersion: 1,
    client: "web",
    appVersionName: "web",
    deviceModel: (navigator.platform || "browser").slice(0, 40),
    display: {
      width: (window.screen && screen.width) || 1920,
      height: (window.screen && screen.height) || 1080,
      hdrFormats: [],
      refreshRates: [],
    },
    protocols: { directFile: true, httpRange: true, hlsFmp4: hlsFmp4 },
    containers: ["mp4", "m4v", "mov", "webm"],
    video,
    audio,
    subtitles: ["webvtt"],
  };
}

/* ── Position helpers ── */
function logicalSeconds() {
  const local = player.currentTime || 0;
  const total = movieSeconds(pb.item) || (pb.durationMs / 1000) || player.duration || 0;
  if (pb.mode !== "direct") return Math.min(total || Infinity, pb.startOffsetSec + local);
  return Math.min(total || Infinity, local);
}

function totalSeconds() {
  return movieSeconds(pb.item) || Math.round(pb.durationMs / 1000) || player.duration || 0;
}

function isFinishedMs(positionMs, durationMs) {
  if (!durationMs || durationMs <= 0) return false;
  return durationMs - positionMs <= 90000 || positionMs >= durationMs * 0.92;
}

/* ── Open / close ── */
function openTheater() {
  theater.classList.remove("hidden");
  theater.classList.remove("idle");
  document.body.classList.add("theater-open");
  document.body.style.overflow = "hidden";
  resetIdleTimer();
}

function closeTheater() {
  theater.classList.add("hidden");
  theater.classList.remove("idle");
  document.body.classList.remove("theater-open");
  document.body.style.overflow = "";
  clearTimeout(idleTimer);
  closePlayerMenus();
  hideUpNext();
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}

function stopPlayer() {
  if (pb.item) reportProgress("stopped", true);
  stopProgressTimer();
  player.pause();
  player.removeAttribute("src");
  clearTextTracks();
  player.load();
  destroyHLS();
  stopServerSession();
  closeTheater();
  pb.item = null;
  pb.plan = null;
}

function stopServerSession() {
  if (pb.plan && pb.plan.sessionId) {
    const session = pb.plan.sessionId;
    fetch(`/api/hls/${encodeURIComponent(session)}`, { method: "DELETE", keepalive: true }).catch(() => {});
  }
}

function destroyHLS() {
  if (hls) { try { hls.destroy(); } catch (_) {} hls = null; }
}

/* ── Public entry point ── */
async function play(item, opts = {}) {
  if (!item) return;
  openTheater();
  setPlayerStatus(`Loading ${item.title}…`);
  pb.item = item;
  currentItem = item;
  pb.bandwidthKbps = opts.bandwidthKbps !== undefined ? opts.bandwidthKbps : preferredBandwidthKbps;
  pb.audioIndex = opts.audioIndex ?? null;
  pb.subtitleIndex = opts.subtitleIndex ?? null;
  pb.upNextShown = false;
  pb.upNextDismissed = false;
  pb.lastReportedMs = -1;
  pb.continuousMs = 0;
  pb.evidenceAt = performance.now();

  // Streams power the audio/subtitle menus; tolerate failure (e.g. trailers).
  try {
    pb.streams = (await api(`/api/items/${item.id}/streams`)) || [];
  } catch (_) {
    pb.streams = [];
  }
  pb.audioStreams = pb.streams.filter((s) => s.type === "audio");
  pb.subtitleStreams = pb.streams.filter((s) => s.type === "subtitle");
  if (pb.audioIndex === null) {
    const def = pb.audioStreams.find((s) => s.default) || pb.audioStreams[0];
    pb.audioIndex = def ? def.index : null;
  }

  let startMs = opts.startMs || 0;
  if (!startMs && item.kind !== "trailer") {
    try {
      const progress = await api(`/api/items/${item.id}/progress`).catch(() => null);
      if (progress && !progress.completed && progress.positionMs > 30000) startMs = progress.positionMs;
    } catch (_) { /* ignore */ }
  }

  await resolveNextEpisode(item);
  await startPlan(startMs);
  renderQualityButton();
}

async function startPlan(startMs) {
  destroyHLS();
  stopServerSession();
  clearTextTracks();
  const total = movieSeconds(pb.item);
  const startSec = Math.max(0, Math.min((startMs || 0) / 1000, total || Infinity));

  let plan;
  try {
    plan = await requestPlan(Math.round(startSec * 1000));
  } catch (err) {
    setPlayerStatus(`${pb.item.title} · could not start (${cleanError(err)})`);
    return;
  }
  if (!plan || !plan.playable) {
    // Retry once forcing a safe transcode.
    try {
      plan = await requestPlan(Math.round(startSec * 1000), "transcode", true);
    } catch (_) { /* fall through */ }
  }
  if (!plan || !plan.playable) {
    setPlayerStatus(`${pb.item.title} · this title cannot be played`);
    return;
  }
  pb.plan = plan;
  pb.mode = plan.mode;
  pb.durationMs = plan.durationMs || pb.item.durationMs || 0;
  pb.startOffsetSec = (plan.startPositionMs || 0) / 1000;
  if (plan.selected) {
    if (plan.selected.audioIndex !== undefined && plan.selected.audioIndex !== null) pb.audioIndex = plan.selected.audioIndex;
    pb.subtitleIndex = plan.selected.subtitleIndex ?? pb.subtitleIndex;
  }

  attachPlan(plan, startSec);
  renderNowPlaying();
  renderTrackButtons();
}

async function requestPlan(startPositionMs, forceModeOverride, isFallback) {
  const forceMode = forceModeOverride || (pb.bandwidthKbps ? "transcode" : "auto");
  const body = {
    itemId: pb.item.id,
    startPositionMs: startPositionMs,
    audioIndex: pb.audioIndex,
    subtitleIndex: pb.mode === "direct" && !isFallback ? null : pb.subtitleIndex,
    bandwidthKbps: pb.bandwidthKbps,
    forceMode,
    profile: browserProfile(),
  };
  return api("/api/playback/plan", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function attachPlan(plan, startSec) {
  player.onerror = () => handlePlaybackError(player.error);
  if (plan.mode === "direct") {
    setPlayerStatus(`${pb.item.title} · direct play`);
    player.src = plan.url;
    if (startSec > 0) {
      player.addEventListener("loadedmetadata", () => { try { player.currentTime = startSec; } catch (_) {} }, { once: true });
    }
    applyDirectSubtitle();
    player.play().catch(() => setPlayerStatus(`${pb.item.title} · press play to start`));
    return;
  }
  // HLS (remux / transcode). Position is baked into the plan start.
  if (window.Hls && Hls.isSupported()) {
    setPlayerStatus(`${pb.item.title} · preparing stream…`);
    hls = new Hls({ lowLatencyMode: false, enableWorker: true });
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data && data.fatal) handleHlsError(data);
    });
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      player.play().catch(() => setPlayerStatus(`${pb.item.title} · press play to start`));
    });
    hls.on(Hls.Events.FRAG_BUFFERED, () => clearPlayerStatus());
    hls.loadSource(plan.url);
    hls.attachMedia(player);
  } else if (player.canPlayType("application/vnd.apple.mpegurl")) {
    player.src = plan.url;
    player.play().catch(() => setPlayerStatus(`${pb.item.title} · press play to start`));
  } else {
    setPlayerStatus(`${pb.item.title} · HLS is not supported by this browser`);
  }
}

async function handlePlaybackError(err) {
  if (!pb.item || pb.switching) return;
  if (await tryFailureFallback(err && err.message ? err.message : "media error")) return;
  setPlayerStatus(`${pb.item.title} · playback failed`);
}

async function handleHlsError(data) {
  destroyHLS();
  if (await tryFailureFallback(`${data.type || "error"}: ${data.details || "unknown"}`)) return;
  setPlayerStatus(`${pb.item.title} · transcode failed`);
}

async function tryFailureFallback(message) {
  if (!pb.plan) return false;
  try {
    const res = await api("/api/playback/failure", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        itemId: pb.item.id,
        planId: pb.plan.id || pb.plan.planId,
        mode: pb.mode,
        client: "web",
        message: String(message || ""),
        positionMs: Math.round(logicalSeconds() * 1000),
      }),
    });
    if (res && res.retry && res.plan) {
      pb.plan = res.plan;
      pb.mode = res.plan.mode;
      pb.startOffsetSec = (res.plan.startPositionMs || 0) / 1000;
      destroyHLS();
      attachPlan(res.plan, 0);
      renderNowPlaying();
      return true;
    }
  } catch (_) { /* ignore */ }
  return false;
}

function playTrailer(item) {
  stopPlayer();
  openTheater();
  pb.item = { id: item.id, title: item.title, kind: "trailer", durationMs: 0 };
  pb.mode = "direct";
  pb.streams = []; pb.audioStreams = []; pb.subtitleStreams = [];
  pb.nextItem = null; pb.plan = null; pb.startOffsetSec = 0;
  audioBtn.classList.add("hidden");
  subsBtn.classList.add("hidden");
  qualityBtn.classList.add("hidden");
  nextEpBtn.classList.add("hidden");
  nowPlaying.textContent = `${item.title} · Trailer`;
  setPlayerStatus(`${item.title} · Trailer`);
  player.onerror = () => setPlayerStatus("Trailer unavailable");
  player.src = `/api/items/${item.id}/trailer`;
  player.play().catch(() => setPlayerStatus(`${item.title} · press play`));
}

function youtubeTrailerSearch(item) {
  const q = [item.title, item.year, "trailer"].filter(Boolean).join(" ");
  window.open(`https://www.youtube.com/results?search_query=${encodeURIComponent(q)}`, "_blank", "noopener");
}

/* ── Now-playing + status ── */
function renderNowPlaying() {
  const quality = pb.mode === "direct" ? "Direct" : (pb.bandwidthKbps ? `${pb.bandwidthKbps / 1000} Mbps` : "Transcode");
  const bits = [pb.item.kind === "episode" ? episodeLabel(pb.item) : pb.item.title, quality];
  nowPlaying.textContent = bits.filter(Boolean).join(" · ");
}

function episodeLabel(item) {
  const code = `S${String(item.seasonNumber || 0).padStart(2, "0")}E${String(item.episodeNumber || 0).padStart(2, "0")}`;
  const title = item.episodeTitle || item.title;
  return [item.showTitle, code, title].filter(Boolean).join(" · ");
}

function setPlayerStatus(text) {
  playerStatusText.textContent = text || "";
  playerStatus.classList.toggle("hidden", !text);
}

function clearPlayerStatus() {
  playerStatus.classList.add("hidden");
}

/* ── Progress reporting ── */
function startProgressTimer() {
  stopProgressTimer();
  pb.reportTimer = window.setInterval(() => reportProgress("playing"), 10000);
}

function stopProgressTimer() {
  if (pb.reportTimer) { clearInterval(pb.reportTimer); pb.reportTimer = 0; }
}

function reportProgress(state, force = false) {
  if (!pb.item || pb.item.kind === "trailer") return;
  sampleProgressEvidence();
  const positionMs = Math.round(logicalSeconds() * 1000);
  const durationMs = pb.durationMs || pb.item.durationMs || Math.round((player.duration || 0) * 1000);
  const completed = isFinishedMs(positionMs, durationMs);
  if (!force && !completed && positionMs < 5000) return;
  if (!force && state === "playing" && Math.abs(positionMs - pb.lastReportedMs) < 8000) return;
  pb.lastReportedMs = positionMs;
  const body = JSON.stringify({ positionMs, durationMs, completed, state: state || "playing", continuousMs: Math.round(pb.continuousMs) });
  const itemId = pb.item.id;
  api(`/api/items/${itemId}/progress`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body,
    keepalive: true,
  }).then(() => { resumeFractionDirty = true; }).catch(() => {});
}

function sampleProgressEvidence() {
  const now = performance.now();
  if (!player.paused && !player.seeking) pb.continuousMs += Math.max(0, Math.min(2000, now - pb.evidenceAt));
  pb.evidenceAt = now;
}

function resetProgressEvidence() {
  pb.continuousMs = 0;
  pb.evidenceAt = performance.now();
}

/* ── Track + quality menus ── */
function renderTrackButtons() {
  audioBtn.classList.toggle("hidden", pb.audioStreams.length < 2);
  subsBtn.classList.toggle("hidden", pb.subtitleStreams.length < 1);
  audioBtn.textContent = trackButtonLabel("Audio", pb.audioStreams.find((s) => s.index === pb.audioIndex));
  const sub = pb.subtitleStreams.find((s) => s.index === pb.subtitleIndex);
  subsBtn.textContent = pb.subtitleIndex === null ? "Subtitles" : trackButtonLabel("Subs", sub);
}

function trackButtonLabel(prefix, stream) {
  if (!stream) return prefix;
  return `${prefix}: ${(stream.language || "und").toUpperCase()}`;
}

function renderQualityButton() {
  qualityBtn.classList.remove("hidden");
  const current = QUALITY_OPTIONS.find((o) => o.kbps === pb.bandwidthKbps);
  qualityBtn.textContent = current ? current.label : "Auto";
}

function buildMenu(menu, options, isActive, onPick) {
  menu.innerHTML = "";
  for (const opt of options) {
    const row = el("button", isActive(opt) ? "player-menu-item active" : "player-menu-item");
    row.type = "button";
    row.append(el("span", "player-menu-check", isActive(opt) ? "✓" : ""), el("span", "player-menu-label", opt.label));
    row.addEventListener("click", () => { closePlayerMenus(); onPick(opt); });
    menu.append(row);
  }
}

function openAudioMenu() {
  const options = pb.audioStreams.map((s) => ({ label: streamLabel(s), index: s.index }));
  buildMenu(audioMenu, options, (o) => o.index === pb.audioIndex, (o) => switchAudio(o.index));
  toggleMenu(audioMenu, audioBtn);
}

function openSubsMenu() {
  const options = [{ label: "Off", index: null }, ...pb.subtitleStreams.map((s) => ({ label: streamLabel(s), index: s.index }))];
  buildMenu(subsMenu, options, (o) => o.index === pb.subtitleIndex, (o) => switchSubtitle(o.index));
  toggleMenu(subsMenu, subsBtn);
}

function openQualityMenu() {
  buildMenu(qualityMenu, QUALITY_OPTIONS, (o) => o.kbps === pb.bandwidthKbps, (o) => switchQuality(o.kbps));
  toggleMenu(qualityMenu, qualityBtn);
}

function toggleMenu(menu, button) {
  const isOpen = !menu.classList.contains("hidden");
  closePlayerMenus();
  if (!isOpen) {
    menu.classList.remove("hidden");
    button.setAttribute("aria-expanded", "true");
    resetIdleTimer();
  }
}

function closePlayerMenus() {
  for (const [menu, button] of [[audioMenu, audioBtn], [subsMenu, subsBtn], [qualityMenu, qualityBtn]]) {
    menu.classList.add("hidden");
    button.setAttribute("aria-expanded", "false");
  }
}

async function switchAudio(index) {
  if (index === pb.audioIndex) return;
  pb.audioIndex = index;
  await replanAtCurrent();
  renderTrackButtons();
}

async function switchSubtitle(index) {
  pb.subtitleIndex = index;
  // Text subtitle over direct play: overlay a WebVTT track without re-encoding.
  if (pb.mode === "direct" && (index === null || isTextSubtitle(index))) {
    applyDirectSubtitle();
    renderTrackButtons();
    return;
  }
  await replanAtCurrent();
  renderTrackButtons();
}

async function switchQuality(kbps) {
  if (kbps === pb.bandwidthKbps) return;
  pb.bandwidthKbps = kbps;
  preferredBandwidthKbps = kbps;
  localStorage.setItem("popcornBandwidthKbps", kbps === null ? "" : String(kbps));
  renderQualityButton();
  await replanAtCurrent();
}

async function replanAtCurrent() {
  if (!pb.item || pb.switching) return;
  pb.switching = true;
  const wasPlaying = !player.paused;
  const startMs = Math.round(logicalSeconds() * 1000);
  setPlayerStatus(`${pb.item.title} · switching…`);
  try {
    await startPlan(startMs);
    if (wasPlaying) player.play().catch(() => {});
  } finally {
    pb.switching = false;
  }
}

function isTextSubtitle(index) {
  const s = pb.subtitleStreams.find((x) => x.index === index);
  return s ? TEXT_SUB_CODECS.has(String(s.codec || "").toLowerCase()) : false;
}

function clearTextTracks() {
  player.querySelectorAll("track").forEach((t) => t.remove());
}

function applyDirectSubtitle() {
  clearTextTracks();
  if (pb.subtitleIndex === null || !pb.item) return;
  if (!isTextSubtitle(pb.subtitleIndex)) return;
  const track = document.createElement("track");
  track.kind = "subtitles";
  track.label = "Subtitles";
  track.default = true;
  track.src = `/api/items/${pb.item.id}/subtitles/${pb.subtitleIndex}.vtt`;
  player.append(track);
  // Enable once the browser parses it.
  setTimeout(() => {
    for (const t of player.textTracks) t.mode = "showing";
  }, 150);
}

/* ── Up Next ── */
async function resolveNextEpisode(item) {
  pb.episodes = [];
  pb.nextItem = null;
  if (!item || item.kind !== "episode") return;
  const libraryId = item.libraryId || activeLibraryId;
  const showTitle = item.showTitle;
  if (!libraryId || !showTitle) return;
  try {
    const episodes = await api(`/api/tv/episodes?libraryId=${encodeURIComponent(libraryId)}&showTitle=${encodeURIComponent(showTitle)}&season=${encodeURIComponent(String(item.seasonNumber || 0))}`);
    pb.episodes = episodes || [];
    const idx = pb.episodes.findIndex((e) => Number(e.id) === Number(item.id));
    if (idx >= 0 && idx + 1 < pb.episodes.length) pb.nextItem = pb.episodes[idx + 1];
  } catch (_) { /* ignore */ }
  nextEpBtn.classList.toggle("hidden", !pb.nextItem);
}

function maybeShowUpNext() {
  if (!pb.nextItem || pb.upNextShown || pb.upNextDismissed) return;
  const total = totalSeconds();
  if (!total) return;
  const remaining = total - logicalSeconds();
  if (remaining > UP_NEXT_LEAD_SEC || remaining < 0) return;
  showUpNext();
}

function showUpNext() {
  pb.upNextShown = true;
  upNextEl.innerHTML = "";
  const next = pb.nextItem;
  const card = el("div", "up-next-card");
  const thumb = el("div", "up-next-thumb");
  if (next.backdropPath) thumb.style.backgroundImage = `url(${imageURL(next, "backdrop", 400)})`;
  else if (next.posterPath) thumb.style.backgroundImage = `url(${imageURL(next, "poster", 400)})`;
  const body = el("div", "up-next-body");
  body.append(
    el("div", "up-next-label", "Up next"),
    el("div", "up-next-title", next.episodeTitle || next.title),
    el("div", "up-next-sub", `S${String(next.seasonNumber || 0).padStart(2, "0")}E${String(next.episodeNumber || 0).padStart(2, "0")}`),
  );
  const actions = el("div", "up-next-actions");
  const playNow = el("button", "primary up-next-play", "Play now");
  playNow.type = "button";
  playNow.addEventListener("click", () => playNext());
  const cancel = el("button", "secondary up-next-cancel", "Cancel");
  cancel.type = "button";
  cancel.addEventListener("click", () => { pb.upNextDismissed = true; hideUpNext(); });
  actions.append(playNow, cancel);
  body.append(actions);
  card.append(thumb, body);
  upNextEl.append(card);
  upNextEl.classList.remove("hidden");
}

function hideUpNext() {
  upNextEl.classList.add("hidden");
  upNextEl.innerHTML = "";
}

function playNext() {
  if (!pb.nextItem) return;
  const next = pb.nextItem;
  hideUpNext();
  reportProgress("stopped", true);
  play(next, { startMs: 0 }).catch(console.error);
}

/* ── Timeline / scrubbing ── */
let draggingTimeline = false;

function updateTimeline(displayValue) {
  if (!pb.item) return;
  const total = totalSeconds();
  const value = displayValue ?? logicalSeconds();
  timeline.max = String(total || 0);
  if (!draggingTimeline) timeline.value = String(Math.round(value));
  const pct = total ? Math.min(100, (value / total) * 100) : 0;
  seekPlayed.style.width = `${pct}%`;
  currentTimeEl.textContent = fmtClock(value);
  durationEl.textContent = fmtClock(total);
  const playing = !player.paused;
  playPause.innerHTML = playing ? ICONS.pause : ICONS.play;
  playPause.setAttribute("aria-label", playing ? "Pause" : "Play");
  theater.classList.toggle("paused", !playing);
  updateBufferedBar();
}

function updateBufferedBar() {
  const total = totalSeconds();
  if (!total || !player.buffered || !player.buffered.length) { seekBuffered.style.width = "0%"; return; }
  try {
    const end = player.buffered.end(player.buffered.length - 1);
    const logicalEnd = pb.mode === "direct" ? end : pb.startOffsetSec + end;
    seekBuffered.style.width = `${Math.min(100, (logicalEnd / total) * 100)}%`;
  } catch (_) { /* ignore */ }
}

function seekTo(seconds) {
  if (!pb.item) return;
  resetProgressEvidence();
  const target = Math.max(0, Math.min(seconds, totalSeconds()));
  if (pb.mode === "direct") {
    player.currentTime = target;
  } else {
    // Re-request the transcode at the new position.
    startPlan(Math.round(target * 1000)).then(() => player.play().catch(() => {})).catch(console.error);
  }
  updateTimeline(target);
}

function nudge(deltaSeconds) {
  seekTo(logicalSeconds() + deltaSeconds);
}

function togglePlay() {
  if (!pb.item) return;
  if (player.paused) player.play().catch(() => {});
  else player.pause();
}

/* ── Volume ── */
function updateVolumeUI() {
  volume.value = String(player.muted ? 0 : player.volume);
  const muted = player.muted || player.volume === 0;
  muteBtn.innerHTML = muted ? ICONS.volMute : (player.volume < 0.5 ? ICONS.volLow : ICONS.volHigh);
  muteBtn.setAttribute("aria-label", muted ? "Unmute" : "Mute");
}

/* ── Idle auto-hide ── */
let idleTimer = 0;
function resetIdleTimer() {
  theater.classList.remove("idle");
  clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    if (playerOpen() && !player.paused && !anyMenuOpen()) theater.classList.add("idle");
  }, 3200);
}

function anyMenuOpen() {
  return !audioMenu.classList.contains("hidden") || !subsMenu.classList.contains("hidden") || !qualityMenu.classList.contains("hidden");
}

/* ── Event wiring ── */
rewBtn.innerHTML = ICONS.rewind;
ffwdBtn.innerHTML = ICONS.forward;
fullscreenBtn.innerHTML = ICONS.fullscreen;
playPause.innerHTML = ICONS.play;
centerToggle.innerHTML = ICONS.play;
player.volume = Number.isFinite(savedVolume) ? Math.max(0, Math.min(1, savedVolume)) : 1;
updateVolumeUI();

theater.addEventListener("mousemove", resetIdleTimer);
theater.addEventListener("mousedown", resetIdleTimer);

player.addEventListener("click", togglePlay);
player.addEventListener("timeupdate", () => { sampleProgressEvidence(); updateTimeline(); maybeShowUpNext(); });
player.addEventListener("seeking", resetProgressEvidence);
player.addEventListener("progress", updateBufferedBar);
player.addEventListener("loadedmetadata", () => updateTimeline());
player.addEventListener("play", () => { updateTimeline(); resetIdleTimer(); reportProgress("playing", true); startProgressTimer(); clearPlayerStatus(); });
player.addEventListener("pause", () => { updateTimeline(); resetIdleTimer(); reportProgress("paused", true); stopProgressTimer(); });
player.addEventListener("ended", () => {
  reportProgress("stopped", true);
  stopProgressTimer();
  if (pb.nextItem && !pb.upNextDismissed) playNext();
  else updateTimeline();
});
player.addEventListener("volumechange", updateVolumeUI);

playPause.addEventListener("click", togglePlay);
centerToggle.addEventListener("click", togglePlay);
rewBtn.addEventListener("click", () => nudge(-10));
ffwdBtn.addEventListener("click", () => nudge(30));
closePlayerBtn.addEventListener("click", () => stopPlayer());
nextEpBtn.addEventListener("click", () => playNext());

audioBtn.addEventListener("click", (e) => { e.stopPropagation(); openAudioMenu(); });
subsBtn.addEventListener("click", (e) => { e.stopPropagation(); openSubsMenu(); });
qualityBtn.addEventListener("click", (e) => { e.stopPropagation(); openQualityMenu(); });
document.addEventListener("click", (e) => { if (!e.target.closest(".menu-anchor")) closePlayerMenus(); });

timeline.addEventListener("input", () => { draggingTimeline = true; updateTimeline(Number(timeline.value)); });
timeline.addEventListener("change", () => { draggingTimeline = false; seekTo(Number(timeline.value)); });

volume.addEventListener("input", () => {
  player.volume = Number(volume.value);
  player.muted = player.volume === 0;
  if (player.volume > 0) { savedVolume = player.volume; localStorage.setItem("popcornVolume", String(savedVolume)); }
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

fullscreenBtn.addEventListener("click", () => {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  else theater.requestFullscreen().catch(() => {});
});
document.addEventListener("fullscreenchange", () => {
  fullscreenBtn.classList.toggle("active", Boolean(document.fullscreenElement));
});

document.addEventListener("keydown", (e) => {
  if (!playerOpen()) return;
  const typing = document.activeElement && document.activeElement.tagName === "INPUT" && document.activeElement.type !== "range";
  if (typing) return;
  if (e.key === " " || e.key === "k") { e.preventDefault(); togglePlay(); }
  else if (e.key === "ArrowLeft") { e.preventDefault(); nudge(-10); }
  else if (e.key === "ArrowRight") { e.preventDefault(); nudge(30); }
  else if (e.key === "f") { e.preventDefault(); fullscreenBtn.click(); }
  else if (e.key === "m") { e.preventDefault(); muteBtn.click(); }
  else if (e.key === "Escape" && !document.fullscreenElement) {
    if (anyMenuOpen()) { closePlayerMenus(); return; }
    e.preventDefault();
    stopPlayer();
  }
});

// Persist progress when the tab is hidden or closed.
window.addEventListener("pagehide", () => { if (pb.item) reportProgress("stopped", true); });
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden" && pb.item) reportProgress(player.paused ? "paused" : "playing", true);
});
