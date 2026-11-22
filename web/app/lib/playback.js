// PlaybackSession: everything that makes a title play. The UI is separate
// (views/watch.js) and only reads `state` and calls the public methods.
//
// Hard-won rules carried over (each was a real bug):
// - Subtitles never go into the media pipeline: subtitleIndex is always null
//   in the plan, because muxing WebVTT into HLS shifted the A/V timestamps.
//   Text tracks are external <track>s; ASS/SSA goes to libass (JASSUB).
// - A copied-video (remux) plan can only start on a keyframe, so the server may
//   start earlier than asked; the difference is seeked off locally.
// - HLS plans restart at 0 from their start position: logical time is
//   startOffset + currentTime, and JASSUB needs that offset as timeOffset.
// - Changing subtitles never replaces the media source.
// - Progress reports carry continuousMs (uninterrupted play since the last
//   seek); the server ignores reports without enough of it. Seeks reset it.
// - Progress is saved on pagehide and when the tab is hidden.
// - Image-based subtitle tracks are not offered (HTML video cannot show them).

import { api, q } from "./api.js";
import { loadScript } from "./scripts.js";
import { noteProgress } from "./userstate.js";
import { invalidate } from "./cache.js";

export const QUALITY_OPTIONS = [
  { label: "Auto", kbps: null },
  { label: "15 Mbps", kbps: 15000 },
  { label: "10 Mbps", kbps: 10000 },
  { label: "8 Mbps", kbps: 8000 },
  { label: "5 Mbps", kbps: 5000 },
  { label: "3 Mbps", kbps: 3000 },
  { label: "1.5 Mbps", kbps: 1500 },
];
export const UP_NEXT_LEAD_SEC = 25;
const TEXT_SUB_CODECS = new Set(["subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text"]);
const SSA_SUB_CODECS = new Set(["ass", "ssa"]);

const store = {
  get(key, fallback) { try { return localStorage.getItem(key) ?? fallback; } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* not persisted */ } },
};

export function normalizeLanguage(value) {
  const lang = String(value || "").trim().toLowerCase();
  return ({ ger: "de", deu: "de", eng: "en", fre: "fr", fra: "fr", spa: "es", ita: "it", jpn: "ja", kor: "ko" })[lang] || lang;
}

// Key names predate this client; keeping them keeps everyone's saved choices.
export const preferences = {
  audio: () => store.get("cinnamonPreferredAudio", ""),
  subtitle: () => store.get("cinnamonPreferredSubtitle", "off"),
  setAudio: (v) => store.set("cinnamonPreferredAudio", v),
  setSubtitle: (v) => store.set("cinnamonPreferredSubtitle", v),
  bandwidth: () => {
    const raw = store.get("popcornBandwidthKbps", "");
    const n = Number(raw);
    return raw !== "" && Number.isFinite(n) && n > 0 ? n : null;
  },
  setBandwidth: (kbps) => store.set("popcornBandwidthKbps", kbps === null ? "" : String(kbps)),
  volume: () => {
    const v = Number(store.get("popcornVolume", "1"));
    return Number.isFinite(v) ? Math.max(0, Math.min(1, v)) : 1;
  },
  setVolume: (v) => store.set("popcornVolume", String(v)),
};

export function preferredAudioIndex(tracks) {
  const preferred = normalizeLanguage(preferences.audio());
  return tracks.find((t) => preferred && normalizeLanguage(t.language) === preferred)?.index
    ?? tracks.find((t) => t.default)?.index
    ?? tracks[0]?.index
    ?? null;
}

export function preferredSubtitleIndex(tracks) {
  const preferred = preferences.subtitle();
  if (preferred === "off") return null;
  if (preferred === "default") return tracks.find((t) => t.default)?.index ?? null;
  return tracks.find((t) => normalizeLanguage(t.language) === normalizeLanguage(preferred))?.index ?? null;
}

export function trackLabel(stream) {
  if (!stream) return "";
  const lang = (stream.language || "und").toUpperCase();
  const names = { 1: "Mono", 2: "Stereo", 6: "5.1", 8: "7.1" };
  return [lang, stream.title, (stream.codec || "").toUpperCase(), names[stream.channels], stream.forced && "forced"].filter(Boolean).join(" · ");
}

export const isTextSubtitleStream = (s) => TEXT_SUB_CODECS.has(String(s?.codec || "").toLowerCase());
const isSSASubtitleStream = (s) => SSA_SUB_CODECS.has(String(s?.codec || "").toLowerCase());

function isFinishedMs(positionMs, durationMs) {
  if (!durationMs || durationMs <= 0) return false;
  return durationMs - positionMs <= 90_000 || positionMs >= durationMs * 0.85;
}

function browserProfile() {
  const probe = document.createElement("video");
  const canPlay = (mime) => { try { return probe.canPlayType(mime) !== ""; } catch { return false; } };
  const canMse = (mime) => { try { return Boolean(window.MediaSource && MediaSource.isTypeSupported(mime)); } catch { return false; } };
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
    appVersionName: "web-next",
    deviceModel: (navigator.platform || "browser").slice(0, 40),
    display: { width: window.screen?.width || 1920, height: window.screen?.height || 1080, hdrFormats: [], refreshRates: [] },
    protocols: { directFile: true, httpRange: true, hlsFmp4 },
    containers: ["mp4", "m4v", "mov", "webm"],
    video,
    audio,
    subtitles: ["webvtt"],
  };
}

export class PlaybackSession {
  constructor(video) {
    this.video = video;
    this.hls = null;
    this.ass = null;
    this.assAvailable = true;
    this.reportTimer = 0;
    this.lastReportedMs = -1;
    this.continuousMs = 0;
    this.evidenceAt = performance.now();
    this.switching = false;
    this.disposed = false;
    this.listeners = new Set();
    this.state = {
      item: null,
      status: "",          // loading / error text; "" when playing normally
      error: false,
      mode: "direct",
      plan: null,
      durationMs: 0,
      startOffsetSec: 0,
      audioStreams: [],
      subtitleStreams: [],
      audioIndex: null,
      subtitleIndex: null,
      bandwidthKbps: preferences.bandwidth(),
      nextItem: null,
      upNextDismissed: false,
      trailer: false,
    };
    video.volume = preferences.volume();
    this.onTime = () => { this.sampleEvidence(); this.emit(); };
    this.onSeeking = () => this.resetEvidence();
    this.onPlay = () => { this.report("playing", true); this.startTimer(); if (!this.state.error) this.set({ status: "" }); };
    this.onPause = () => { this.report("paused", true); this.stopTimer(); this.emit(); };
    this.onEnded = () => { this.report("stopped", true); this.stopTimer(); this.emit(); this.listeners.forEach((fn) => fn({ ended: true })); };
    this.onHide = () => { if (this.state.item) this.report("stopped", true); };
    this.onVisibility = () => {
      if (document.visibilityState === "hidden" && this.state.item) this.report(video.paused ? "paused" : "playing", true);
    };
    video.addEventListener("timeupdate", this.onTime);
    video.addEventListener("seeking", this.onSeeking);
    video.addEventListener("play", this.onPlay);
    video.addEventListener("pause", this.onPause);
    video.addEventListener("ended", this.onEnded);
    window.addEventListener("pagehide", this.onHide);
    document.addEventListener("visibilitychange", this.onVisibility);
  }

  subscribe(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  emit() {
    this.listeners.forEach((fn) => fn(null));
  }

  set(patch) {
    Object.assign(this.state, patch);
    this.emit();
  }

  // ---- time --------------------------------------------------------------

  totalSeconds() {
    const s = this.state;
    return Math.round((s.item?.durationMs || 0) / 1000) || Math.round(s.durationMs / 1000) || this.video.duration || 0;
  }

  logicalSeconds() {
    const local = this.video.currentTime || 0;
    const total = this.totalSeconds() || Infinity;
    return Math.min(total, this.state.mode !== "direct" ? this.state.startOffsetSec + local : local);
  }

  bufferedSeconds() {
    const v = this.video;
    if (!v.buffered?.length) return 0;
    try {
      const end = v.buffered.end(v.buffered.length - 1);
      return this.state.mode === "direct" ? end : this.state.startOffsetSec + end;
    } catch {
      return 0;
    }
  }

  // ---- lifecycle ---------------------------------------------------------

  async play(item, { startMs = null, audioIndex, subtitleIndex } = {}) {
    // Moving straight to another title (Up Next): save where this one ended.
    if (this.state.item) this.report("stopped", true);
    this.stopMedia();
    this.lastReportedMs = -1;
    this.resetEvidence();
    this.set({ item, status: `Loading ${item.title}…`, error: false, nextItem: null, upNextDismissed: false, plan: null, trailer: false });

    let streams = [];
    try { streams = (await api(`/api/items/${item.id}/streams`)) || []; } catch { streams = []; }
    if (this.disposed) return;
    const audioStreams = streams.filter((s) => s.type === "audio");
    const subtitleStreams = streams.filter((s) => s.type === "subtitle" && isTextSubtitleStream(s));
    this.set({
      audioStreams,
      subtitleStreams,
      // A choice made on the detail page wins over the saved preference.
      audioIndex: audioIndex !== undefined ? audioIndex : preferredAudioIndex(audioStreams),
      subtitleIndex: subtitleIndex !== undefined && subtitleStreams.some((t) => t.index === subtitleIndex)
        ? subtitleIndex
        : subtitleIndex === null ? null : preferredSubtitleIndex(subtitleStreams),
    });

    // An explicit start (including 0, "from the beginning") wins; only an
    // absent one falls back to the saved resume point.
    let start = Number(startMs) || 0;
    if (startMs == null) {
      const progress = await api(`/api/items/${item.id}/progress`).catch(() => null);
      if (progress && !progress.completed && progress.positionMs > 30_000) start = progress.positionMs;
    }
    if (this.disposed) return;
    this.resolveNext(item);
    await this.startPlan(start);
  }

  // A trailer is a plain file: no plan, no tracks, no progress.
  playTrailer(item) {
    if (this.state.item) this.report("stopped", true);
    this.stopMedia();
    const v = this.video;
    this.set({
      item: { id: item.id, libraryId: item.libraryId, title: item.title, year: item.year, kind: "trailer", durationMs: 0 },
      trailer: true, status: "", error: false, mode: "direct", plan: null, durationMs: 0, startOffsetSec: 0,
      audioStreams: [], subtitleStreams: [], audioIndex: null, subtitleIndex: null, nextItem: null,
    });
    v.onerror = () => this.set({ status: "Trailer unavailable", error: true });
    v.src = `/api/items/${encodeURIComponent(item.id)}/trailer`;
    v.play().catch(() => this.set({ status: "Press play to start" }));
  }

  dispose() {
    if (this.disposed) return;
    if (this.state.item) this.report("stopped", true);
    this.disposed = true;
    this.stopMedia();
    const v = this.video;
    v.removeEventListener("timeupdate", this.onTime);
    v.removeEventListener("seeking", this.onSeeking);
    v.removeEventListener("play", this.onPlay);
    v.removeEventListener("pause", this.onPause);
    v.removeEventListener("ended", this.onEnded);
    window.removeEventListener("pagehide", this.onHide);
    document.removeEventListener("visibilitychange", this.onVisibility);
    this.listeners.clear();
    invalidate("home");
  }

  stopMedia() {
    this.stopTimer();
    try { this.video.pause(); } catch { /* not started */ }
    this.video.removeAttribute("src");
    this.clearSubtitles();
    try { this.video.load(); } catch { /* ignore */ }
    this.destroyHls();
    this.stopServerSession();
  }

  stopServerSession() {
    const session = this.state.plan?.sessionId;
    if (session) fetch(`/api/hls/${encodeURIComponent(session)}`, { method: "DELETE", keepalive: true }).catch(() => {});
  }

  destroyHls() {
    if (this.hls) {
      try { this.hls.destroy(); } catch { /* already gone */ }
      this.hls = null;
    }
  }

  // ---- plans ---------------------------------------------------------------

  async requestPlan(startPositionMs, forceMode) {
    const s = this.state;
    return api("/api/playback/plan", {
      method: "POST",
      body: {
        itemId: s.item.id,
        startPositionMs,
        audioIndex: s.audioIndex,
        subtitleIndex: null,
        bandwidthKbps: s.bandwidthKbps,
        forceMode: forceMode || (s.bandwidthKbps ? "transcode" : "auto"),
        profile: browserProfile(),
      },
    });
  }

  async startPlan(startMs) {
    this.destroyHls();
    this.stopServerSession();
    this.clearSubtitles();
    const total = this.totalSeconds();
    const startSec = Math.max(0, Math.min((startMs || 0) / 1000, total || Infinity));
    const title = this.state.item.title;
    let plan;
    try {
      plan = await this.requestPlan(Math.round(startSec * 1000));
      if (!plan?.playable) plan = await this.requestPlan(Math.round(startSec * 1000), "transcode").catch(() => plan);
    } catch (err) {
      this.set({ status: `${title} could not start: ${err.message}`, error: true });
      return;
    }
    if (this.disposed) return;
    if (!plan?.playable) {
      this.set({ status: `${title} cannot be played in this browser`, error: true });
      return;
    }
    const patch = {
      plan,
      mode: plan.mode,
      durationMs: plan.durationMs || this.state.item.durationMs || 0,
      startOffsetSec: (plan.startPositionMs || 0) / 1000,
    };
    if (plan.selected) {
      if (plan.selected.audioIndex != null) patch.audioIndex = plan.selected.audioIndex;
    }
    this.set(patch);
    await this.attachPlan(plan, startSec);
  }

  async attachPlan(plan, startSec) {
    const v = this.video;
    const title = this.state.item.title;
    v.onerror = () => this.handleError(v.error?.message || "media error");
    const tryPlay = () => v.play().catch(() => this.set({ status: "Press play to start" }));
    if (plan.mode === "direct") {
      v.src = plan.url;
      if (startSec > 0) v.addEventListener("loadedmetadata", () => { try { v.currentTime = startSec; } catch { /* ignore */ } }, { once: true });
      this.applySubtitle();
      tryPlay();
      return;
    }
    // Remux/transcode over HLS. Seek off any keyframe snap locally.
    const trimSec = Math.max(0, startSec - (this.state.startOffsetSec || 0));
    const localStart = trimSec > 0.5 ? trimSec : 0;
    await loadScript("/hls.min.js").catch(() => {});
    if (this.disposed) return;
    if (window.Hls?.isSupported()) {
      this.set({ status: "Preparing stream…" });
      // The playlist has no end marker while ffmpeg is still writing it, so
      // hls.js takes it for a live stream and would start near the newest
      // segment: far into the film on a fast remux. Always say where to start.
      const hls = new window.Hls({ lowLatencyMode: false, enableWorker: true, startPosition: localStart });
      this.hls = hls;
      hls.on(window.Hls.Events.ERROR, (_, data) => { if (data?.fatal) this.handleHlsError(data); });
      hls.on(window.Hls.Events.MANIFEST_PARSED, tryPlay);
      hls.on(window.Hls.Events.FRAG_BUFFERED, () => { if (!this.state.error && this.state.status) this.set({ status: "" }); });
      hls.loadSource(plan.url);
      hls.attachMedia(v);
      this.applySubtitle();
    } else if (v.canPlayType("application/vnd.apple.mpegurl")) {
      if (localStart > 0) v.addEventListener("loadedmetadata", () => { try { v.currentTime = localStart; } catch { /* ignore */ } }, { once: true });
      v.src = plan.url;
      this.applySubtitle();
      tryPlay();
    } else {
      this.set({ status: `${title}: this browser cannot play HLS`, error: true });
    }
  }

  async handleError(message) {
    if (!this.state.item || this.switching || this.disposed) return;
    if (await this.tryFallback(message)) return;
    this.set({ status: "Playback failed", error: true });
  }

  async handleHlsError(data) {
    this.destroyHls();
    if (await this.tryFallback(`${data.type || "error"}: ${data.details || "unknown"}`)) return;
    this.set({ status: "The stream failed", error: true });
  }

  async tryFallback(message) {
    const s = this.state;
    if (!s.plan) return false;
    try {
      const res = await api("/api/playback/failure", {
        method: "POST",
        body: {
          itemId: s.item.id,
          planId: s.plan.id || s.plan.planId,
          mode: s.mode,
          client: "web",
          message: String(message || ""),
          positionMs: Math.round(this.logicalSeconds() * 1000),
        },
      });
      if (res?.retry && res.plan) {
        // Resume where the failure happened; the new plan may start earlier
        // (keyframe), and attachPlan trims the difference.
        const at = Math.round(this.logicalSeconds());
        this.set({ plan: res.plan, mode: res.plan.mode, startOffsetSec: (res.plan.startPositionMs || 0) / 1000 });
        this.destroyHls();
        await this.attachPlan(res.plan, at);
        return true;
      }
    } catch { /* no fallback */ }
    return false;
  }

  async replanAtCurrent() {
    if (!this.state.item || this.switching) return;
    this.switching = true;
    const wasPlaying = !this.video.paused;
    const at = Math.round(this.logicalSeconds() * 1000);
    this.set({ status: "Switching…" });
    try {
      await this.startPlan(at);
      if (wasPlaying) this.video.play().catch(() => {});
    } finally {
      this.switching = false;
    }
  }

  // ---- controls ------------------------------------------------------------

  togglePlay() {
    if (!this.state.item) return;
    if (this.video.paused) this.video.play().catch(() => {});
    else this.video.pause();
  }

  seekTo(seconds) {
    if (!this.state.item) return;
    this.resetEvidence();
    const target = Math.max(0, Math.min(seconds, this.totalSeconds()));
    if (this.state.mode === "direct") {
      this.video.currentTime = target;
    } else {
      // An HLS plan covers one start position; seeking asks for a new one.
      this.startPlan(Math.round(target * 1000)).then(() => this.video.play().catch(() => {}));
    }
    this.emit();
  }

  nudge(delta) {
    this.seekTo(this.logicalSeconds() + delta);
  }

  setVolume(volume) {
    this.video.volume = volume;
    this.video.muted = volume === 0;
    if (volume > 0) preferences.setVolume(volume);
    this.emit();
  }

  toggleMute() {
    const v = this.video;
    if (v.muted || v.volume === 0) {
      v.muted = false;
      if (v.volume === 0) v.volume = preferences.volume() || 1;
    } else {
      v.muted = true;
    }
    this.emit();
  }

  async setAudio(index) {
    if (index === this.state.audioIndex) return;
    this.set({ audioIndex: index });
    await this.replanAtCurrent();
  }

  setSubtitle(index) {
    this.set({ subtitleIndex: index });
    this.applySubtitle();
  }

  async setQuality(kbps) {
    if (kbps === this.state.bandwidthKbps) return;
    preferences.setBandwidth(kbps);
    this.set({ bandwidthKbps: kbps });
    await this.replanAtCurrent();
  }

  dismissUpNext() {
    this.set({ upNextDismissed: true });
  }

  // ---- subtitles -----------------------------------------------------------

  clearSubtitles() {
    const v = this.video;
    for (const t of v.textTracks) t.mode = "disabled";
    v.querySelectorAll("track").forEach((t) => t.remove());
    if (this.ass) {
      try { this.ass.destroy(); } catch { /* torn down */ }
      this.ass = null;
    }
  }

  applySubtitle() {
    this.clearSubtitles();
    const s = this.state;
    if (s.subtitleIndex === null || !s.item) return;
    const stream = s.subtitleStreams.find((t) => t.index === s.subtitleIndex);
    if (!stream || !isTextSubtitleStream(stream)) return;
    if (isSSASubtitleStream(stream)) {
      this.applySSA(stream);
      return;
    }
    this.applyVTT(stream);
  }

  applyVTT(stream) {
    const v = this.video;
    const s = this.state;
    const track = document.createElement("track");
    track.kind = "subtitles";
    track.label = trackLabel(stream);
    if (stream.language) track.srclang = normalizeLanguage(stream.language);
    track.default = true;
    const start = s.mode === "direct" ? 0 : Math.max(0, s.startOffsetSec || 0);
    track.src = `/api/items/${s.item.id}/subtitles/${stream.index}.vtt?start=${start.toFixed(3)}`;
    const show = () => { for (const t of v.textTracks) t.mode = "showing"; };
    track.addEventListener("load", show, { once: true });
    v.append(track);
    // Some browsers never fire load for a cached text track.
    setTimeout(show, 150);
  }

  async applySSA(stream) {
    const index = stream.index;
    if (this.assAvailable) {
      await loadScript("/jassub/jassub.js").catch(() => { this.assAvailable = false; });
    }
    // The selection may have changed while the renderer loaded.
    if (this.disposed || this.state.subtitleIndex !== index) return;
    if (!this.assAvailable || typeof window.JASSUB === "undefined") {
      this.applyVTT(stream);
      return;
    }
    const s = this.state;
    try {
      this.ass = new window.JASSUB({
        video: this.video,
        subUrl: `/api/items/${s.item.id}/subtitles/${index}.ass`,
        workerUrl: "/jassub/jassub-worker.js",
        wasmUrl: "/jassub/jassub-worker.wasm",
        availableFonts: { "liberation sans": "/jassub/default.woff2" },
        fallbackFont: "liberation sans",
        // The .ass file keeps absolute timestamps; an HLS plan restarts at 0.
        timeOffset: s.mode === "direct" ? 0 : Math.max(0, s.startOffsetSec || 0),
      });
    } catch {
      this.ass = null;
      this.assAvailable = false;
      this.applyVTT(stream);
      return;
    }
    // Worker/wasm failures arrive later: fall back to WebVTT for the session.
    const renderer = this.ass;
    renderer.addEventListener("error", () => {
      this.assAvailable = false;
      if (this.ass !== renderer) return;
      this.clearSubtitles();
      if (this.state.subtitleIndex === index) this.applyVTT(stream);
    }, { once: true });
  }

  // ---- up next -------------------------------------------------------------

  // The next episode, crossing into the next season when this one ends.
  async resolveNext(item) {
    if (item?.kind !== "episode" || !item.libraryId || !item.showTitle) return;
    const episodes = (season) => api(q("/api/tv/episodes", { libraryId: item.libraryId, showTitle: item.showTitle, season }));
    try {
      const current = (await episodes(item.seasonNumber || 0)) || [];
      const idx = current.findIndex((e) => Number(e.id) === Number(item.id));
      let next = idx >= 0 ? current[idx + 1] : null;
      if (!next && item.seasonNumber > 0) {
        const following = (await episodes(item.seasonNumber + 1).catch(() => [])) || [];
        next = following[0] || null;
      }
      if (!this.disposed && this.state.item?.id === item.id) this.set({ nextItem: next || null });
    } catch { /* no up next */ }
  }

  // ---- progress --------------------------------------------------------------

  startTimer() {
    this.stopTimer();
    this.reportTimer = setInterval(() => this.report("playing"), 10_000);
  }

  stopTimer() {
    if (this.reportTimer) clearInterval(this.reportTimer);
    this.reportTimer = 0;
  }

  sampleEvidence() {
    const now = performance.now();
    if (!this.video.paused && !this.video.seeking) this.continuousMs += Math.max(0, Math.min(2000, now - this.evidenceAt));
    this.evidenceAt = now;
  }

  resetEvidence() {
    this.continuousMs = 0;
    this.evidenceAt = performance.now();
  }

  report(state, force = false) {
    const item = this.state.item;
    if (!item || item.kind === "trailer") return;
    this.sampleEvidence();
    const positionMs = Math.round(this.logicalSeconds() * 1000);
    const durationMs = this.state.durationMs || item.durationMs || Math.round((this.video.duration || 0) * 1000);
    const completed = isFinishedMs(positionMs, durationMs);
    if (!force && !completed && positionMs < 5000) return;
    if (!force && state === "playing" && Math.abs(positionMs - this.lastReportedMs) < 8000) return;
    this.lastReportedMs = positionMs;
    api(`/api/items/${item.id}/progress`, {
      method: "PUT",
      keepalive: true,
      body: { positionMs, durationMs, completed, state: state || "playing", continuousMs: Math.round(this.continuousMs) },
    }).then((saved) => {
      // The server may refuse a report without enough settled playback; trust
      // what it stored, not what was sent.
      if (saved && saved.itemId) noteProgress(saved);
    }).catch(() => {});
  }
}
