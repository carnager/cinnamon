// The player screen. All playback logic lives in lib/playback.js; this file is
// controls, menus, keyboard and the Up Next card.
import { html, useEffect, useLayoutEffect, useReducer, useRef, useState, cx } from "../lib/html.js";
import { useResource } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { navigate } from "../lib/router.js";
import { paths, episodeCode, formatClock } from "../lib/paths.js";
import { PlaybackSession, QUALITY_OPTIONS, UP_NEXT_LEAD_SEC, trackLabel } from "../lib/playback.js";
import { playItem, takeStart, hasPlayerOrigin } from "../lib/play.js";
import { Artwork } from "../ui/artwork.js";
import { Icon } from "../ui/icons.js";

const IDLE_MS = 3200;
const glyphs = {
  pause: html`<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>`,
  play: html`<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>`,
  back10: html`<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.5 4.5v4h4"/><text x="12" y="15.2" text-anchor="middle" font-size="7.5" font-weight="700" fill="currentColor" stroke="none">10</text></svg>`,
  forward30: html`<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.5 4.5v4h-4"/><text x="12" y="15.2" text-anchor="middle" font-size="7.5" font-weight="700" fill="currentColor" stroke="none">30</text></svg>`,
  volume: html`<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>`,
  muted: html`<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true"><path d="M3 9v6h4l5 5V4L7 9H3z"/><path d="M16 9l5 6M21 9l-5 6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>`,
  fullscreen: html`<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>`,
  exitFullscreen: html`<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/></svg>`,
  next: html`<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true"><path d="M6 5v14l9-7z"/><rect x="16" y="5" width="2.4" height="14" rx="1"/></svg>`,
  subtitles: html`<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M7 15h4M14 15h3M7 11h2M12 11h5"/></svg>`,
  audio: html`<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><path d="M4 10v4M8 7v10M12 4v16M16 8v8M20 10v4"/></svg>`,
};

// Seek bar: drags preview locally and only seek on release, because seeking an
// HLS plan requests a new stream from the server.
function Timeline({ session, position, total, buffered }) {
  const ref = useRef(null);
  const [drag, setDrag] = useState(null);
  const [hover, setHover] = useState(null);
  const at = (e) => {
    const box = ref.current.getBoundingClientRect();
    const f = Math.max(0, Math.min(1, (e.clientX - box.left) / box.width));
    return { f, seconds: f * total };
  };
  const shown = drag != null ? drag : position;
  const pct = total ? Math.min(100, (shown / total) * 100) : 0;
  const bufPct = total ? Math.min(100, (buffered / total) * 100) : 0;
  const onKey = (e) => {
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") return; // handled globally
    if (e.key === "Home") { e.preventDefault(); session.seekTo(0); }
    if (e.key === "End") { e.preventDefault(); session.seekTo(Math.max(0, total - 5)); }
  };
  return html`<div class=${cx("timeline", drag != null && "is-dragging")} ref=${ref}
      role="slider" tabindex="0" aria-label="Seek" aria-valuemin="0" aria-valuemax=${Math.round(total)} aria-valuenow=${Math.round(shown)} aria-valuetext=${formatClock(shown * 1000)}
      onKeyDown=${onKey}
      onPointerDown=${(e) => { e.currentTarget.setPointerCapture(e.pointerId); setDrag(at(e).seconds); }}
      onPointerMove=${(e) => { const p = at(e); setHover(p); if (drag != null) setDrag(p.seconds); }}
      onPointerUp=${(e) => { if (drag != null) { session.seekTo(at(e).seconds); setDrag(null); } }}
      onPointerCancel=${() => setDrag(null)}
      onPointerLeave=${() => setHover(null)}>
    <div class="timeline-track">
      <div class="timeline-buffered" style=${`width:${bufPct}%`}></div>
      <div class="timeline-played" style=${`width:${pct}%`}></div>
    </div>
    <div class="timeline-thumb" style=${`left:${pct}%`}></div>
    ${hover && total > 0 && html`<div class="timeline-tip" style=${`left:clamp(28px, ${hover.f * 100}%, calc(100% - 28px))`}>${formatClock(hover.seconds * 1000)}</div>`}
  </div>`;
}

function PlayerMenu({ id, open, setOpen, label, button, options, isActive, onPick }) {
  const isOpen = open === id;
  return html`<div class="pmenu">
    <button type="button" class=${cx("pbtn", isOpen && "is-on")} aria-label=${label} title=${label} aria-haspopup="menu" aria-expanded=${isOpen}
      onClick=${(e) => { e.stopPropagation(); setOpen(isOpen ? null : id); }}>${button}</button>
    ${isOpen && html`<div class="pmenu-list" role="menu" onClick=${(e) => e.stopPropagation()}>
      <div class="pmenu-title">${label}</div>
      ${options.map((o) => html`<button type="button" role="menuitemradio" aria-checked=${isActive(o)} key=${String(o.key)}
          class=${cx("pmenu-item", isActive(o) && "is-selected")} onClick=${() => { setOpen(null); onPick(o); }}>
        <span>${o.label}</span>${isActive(o) && html`<${Icon} name="check" size=${16} />`}
      </button>`)}
    </div>`}
  </div>`;
}

function UpNext({ next, remaining, onPlay, onCancel }) {
  const fraction = Math.max(0, Math.min(1, remaining / UP_NEXT_LEAD_SEC));
  return html`<aside class="upnext" aria-label="Up next">
    <div class="upnext-art"><${Artwork} item=${next} kind="backdrop" width=${480} /></div>
    <div class="upnext-body">
      <span class="kicker">Up next · ${Math.ceil(Math.max(0, remaining))}s</span>
      <strong>${next.episodeTitle || next.title}</strong>
      <span class="upnext-code">${episodeCode(next)}</span>
      <div class="upnext-actions">
        <button type="button" class="btn btn-primary" onClick=${onPlay}><${Icon} name="play" size=${16} /> Play now</button>
        <button type="button" class="btn btn-glass" onClick=${onCancel}>Cancel</button>
      </div>
    </div>
    <div class="upnext-bar"><span style=${`transform:scaleX(${fraction})`}></span></div>
  </aside>`;
}

export function WatchView({ itemId }) {
  const { data: item, error } = useResource(R.item(itemId));
  const rootRef = useRef(null);
  const videoRef = useRef(null);
  const sessionRef = useRef(null);
  const startedFor = useRef(null);
  const [, rerender] = useReducer((n) => n + 1, 0);
  const [idle, setIdle] = useState(false);
  const [menu, setMenu] = useState(null);
  const [buffering, setBuffering] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [flash, setFlash] = useState(null);
  const idleTimer = useRef(0);
  const flashTimer = useRef(0);
  const latest = useRef({});

  const close = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (hasPlayerOrigin()) history.back();
    else {
      const it = sessionRef.current?.state.item || item;
      navigate(it ? (it.kind === "episode" ? paths.show(it.libraryId, it.showTitle, it.seasonNumber) : paths.item(it)) : "/", { replace: true });
    }
  };
  const playNext = () => {
    const next = sessionRef.current?.state.nextItem;
    if (next) playItem(next, { fromStart: true, replace: true, instant: true });
  };
  latest.current = { close, playNext, menu };

  // One session for the life of the screen: the <video> element (and so
  // fullscreen) survives one episode rolling into the next.
  useLayoutEffect(() => {
    const session = new PlaybackSession(videoRef.current);
    sessionRef.current = session;
    const off = session.subscribe((event) => {
      if (event?.ended) {
        if (session.state.nextItem && !session.state.upNextDismissed) latest.current.playNext();
        return;
      }
      rerender();
    });
    return () => {
      off();
      session.dispose();
      sessionRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!item || startedFor.current === itemId) return;
    startedFor.current = itemId;
    sessionRef.current.play(item, { startMs: takeStart(itemId) }).catch(() => {});
  }, [itemId, item]);

  useEffect(() => {
    if (item) document.title = item.kind === "episode" ? `${item.showTitle} ${episodeCode(item)}` : item.title;
  }, [item]);

  // Controls hide while playing with no menu open.
  const wake = () => {
    setIdle(false);
    clearTimeout(idleTimer.current);
    idleTimer.current = setTimeout(() => {
      const v = videoRef.current;
      if (v && !v.paused && !latest.current.menu) setIdle(true);
    }, IDLE_MS);
  };
  useEffect(() => {
    wake();
    return () => clearTimeout(idleTimer.current);
  }, [menu]);

  useEffect(() => {
    const onFs = () => setFullscreen(Boolean(document.fullscreenElement));
    const closeMenu = () => setMenu(null);
    document.addEventListener("fullscreenchange", onFs);
    document.addEventListener("click", closeMenu);
    return () => {
      document.removeEventListener("fullscreenchange", onFs);
      document.removeEventListener("click", closeMenu);
      if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    };
  }, []);

  const showFlash = (kind) => {
    clearTimeout(flashTimer.current);
    setFlash({ kind, id: Date.now() });
    flashTimer.current = setTimeout(() => setFlash(null), 600);
  };
  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else rootRef.current?.requestFullscreen?.().catch(() => {});
  };

  useEffect(() => {
    const onKey = (e) => {
      const s = sessionRef.current;
      if (!s || e.metaKey || e.ctrlKey || e.altKey) return;
      const el = document.activeElement;
      if (el?.tagName === "INPUT" && el.type !== "range") return;
      const v = s.video;
      switch (e.key) {
        case " ":
          // A focused control's own Space press must not also toggle playback.
          if (el?.tagName === "BUTTON") return;
          e.preventDefault(); s.togglePlay(); showFlash(v.paused ? "play" : "pause"); break;
        case "k": e.preventDefault(); s.togglePlay(); showFlash(v.paused ? "play" : "pause"); break;
        case "ArrowLeft": e.preventDefault(); s.nudge(-10); showFlash("back10"); break;
        case "ArrowRight": e.preventDefault(); s.nudge(30); showFlash("forward30"); break;
        case "ArrowUp": e.preventDefault(); s.setVolume(Math.min(1, Math.round((v.volume + 0.05) * 100) / 100)); break;
        case "ArrowDown": e.preventDefault(); s.setVolume(Math.max(0, Math.round((v.volume - 0.05) * 100) / 100)); break;
        case "f": e.preventDefault(); toggleFullscreen(); break;
        case "m": e.preventDefault(); s.toggleMute(); break;
        case "n": if (s.state.nextItem) { e.preventDefault(); latest.current.playNext(); } break;
        case "Escape":
          if (document.fullscreenElement) return;
          e.preventDefault();
          if (latest.current.menu) setMenu(null);
          else latest.current.close();
          return;
        default: return;
      }
      wake();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const s = sessionRef.current;
  const st = s?.state;
  const v = videoRef.current;
  const shownItem = st?.item || item;
  const total = s ? s.totalSeconds() : 0;
  const position = s ? s.logicalSeconds() : 0;
  const paused = v ? v.paused : true;
  const muted = v ? v.muted || v.volume === 0 : false;
  const remaining = total - position;
  const showUpNext = st?.nextItem && !st.upNextDismissed && total > 0 && remaining <= UP_NEXT_LEAD_SEC && remaining >= 0 && !st.error;
  const isEpisode = shownItem?.kind === "episode";
  const title = isEpisode ? shownItem.showTitle : shownItem?.title;
  const subtitle = isEpisode ? [episodeCode(shownItem), shownItem.episodeTitle].filter(Boolean).join(" · ") : shownItem?.year;
  const modeLabel = !st?.plan ? "" : st.mode === "direct" ? "Direct" : st.bandwidthKbps ? `${st.bandwidthKbps / 1000} Mbps` : st.mode === "remux" ? "Remux" : "Transcode";
  const statusText = error ? error.message : st?.status;
  const statusIsError = Boolean(error || st?.error);

  return html`<div ref=${rootRef} class=${cx("watch", idle && "is-idle", paused && "is-paused")}
      onPointerMove=${wake} onPointerDown=${wake}>
    <video ref=${videoRef} class="watch-video" playsinline
      onClick=${() => { if (menu) { setMenu(null); return; } s?.togglePlay(); if (v) showFlash(v.paused ? "play" : "pause"); }}
      onDblClick=${toggleFullscreen}
      onWaiting=${() => setBuffering(true)} onPlaying=${() => setBuffering(false)} onCanPlay=${() => setBuffering(false)}
      onPlay=${rerender} onPause=${() => { setIdle(false); rerender(); }} onVolumeChange=${rerender} onProgress=${rerender} onLoadedMetadata=${rerender}></video>

    ${flash && html`<div class="watch-flash" key=${flash.id}>${glyphs[flash.kind]}</div>`}
    ${(buffering || (statusText && !statusIsError)) && !paused && html`<div class="watch-spinner" aria-hidden="true"></div>`}
    ${statusText && html`<div class=${cx("watch-status", statusIsError && "is-error")} role="status">
      ${!statusIsError && paused && html`<div class="watch-spinner is-inline"></div>`}
      <span>${statusText}</span>
      ${statusIsError && html`<button type="button" class="btn btn-glass" onClick=${close}>Back</button>`}
    </div>`}
    ${paused && !statusText && html`<button type="button" class="watch-center" aria-label="Play" onClick=${() => s?.togglePlay()}>${glyphs.play}</button>`}

    <header class="watch-top">
      <button type="button" class="pbtn" aria-label="Close player" title="Back (Esc)" onClick=${close}><${Icon} name="chevronLeft" size=${26} /></button>
      <div class="watch-title">
        <strong>${title || ""}</strong>
        ${subtitle && html`<span>${subtitle}</span>`}
      </div>
      ${modeLabel && html`<span class="watch-mode">${modeLabel}</span>`}
    </header>

    ${showUpNext && html`<${UpNext} next=${st.nextItem} remaining=${remaining} onPlay=${playNext} onCancel=${() => s.dismissUpNext()} />`}

    <footer class="watch-controls">
      ${s && html`<${Timeline} session=${s} position=${position} total=${total} buffered=${s.bufferedSeconds()} />`}
      <div class="watch-row">
        <button type="button" class="pbtn pbtn-main" aria-label=${paused ? "Play" : "Pause"} title="Play/Pause (Space)" onClick=${() => s?.togglePlay()}>${paused ? glyphs.play : glyphs.pause}</button>
        <button type="button" class="pbtn" aria-label="Back 10 seconds" title="Back 10s (←)" onClick=${() => s?.nudge(-10)}>${glyphs.back10}</button>
        <button type="button" class="pbtn" aria-label="Forward 30 seconds" title="Forward 30s (→)" onClick=${() => s?.nudge(30)}>${glyphs.forward30}</button>
        <div class="watch-volume">
          <button type="button" class="pbtn" aria-label=${muted ? "Unmute" : "Mute"} title="Mute (M)" onClick=${() => s?.toggleMute()}>${muted ? glyphs.muted : glyphs.volume}</button>
          <input type="range" min="0" max="1" step="0.01" aria-label="Volume" value=${muted ? 0 : v?.volume ?? 1}
            style=${`--fill:${(muted ? 0 : v?.volume ?? 1) * 100}%`}
            onInput=${(e) => s?.setVolume(Number(e.currentTarget.value))} />
        </div>
        <span class="watch-time">${formatClock(position * 1000)} <i>/</i> ${formatClock(total * 1000)}</span>
        <span class="watch-spacer"></span>
        ${st?.nextItem && html`<button type="button" class="pbtn" aria-label="Next episode" title="Next episode (N)" onClick=${playNext}>${glyphs.next}</button>`}
        ${st?.audioStreams.length > 1 && html`<${PlayerMenu} id="audio" open=${menu} setOpen=${setMenu} label="Audio" button=${glyphs.audio}
          options=${st.audioStreams.map((t) => ({ key: t.index, label: trackLabel(t), index: t.index }))}
          isActive=${(o) => o.index === st.audioIndex} onPick=${(o) => s.setAudio(o.index)} />`}
        ${st?.subtitleStreams.length > 0 && html`<${PlayerMenu} id="subs" open=${menu} setOpen=${setMenu} label="Subtitles" button=${glyphs.subtitles}
          options=${[{ key: "off", label: "Off", index: null }, ...st.subtitleStreams.map((t) => ({ key: t.index, label: trackLabel(t), index: t.index }))]}
          isActive=${(o) => o.index === st.subtitleIndex} onPick=${(o) => s.setSubtitle(o.index)} />`}
        ${st && html`<${PlayerMenu} id="quality" open=${menu} setOpen=${setMenu} label="Quality"
          button=${html`<span class="pbtn-text">${QUALITY_OPTIONS.find((o) => o.kbps === st.bandwidthKbps)?.label || "Auto"}</span>`}
          options=${QUALITY_OPTIONS.map((o) => ({ ...o, key: o.kbps ?? "auto" }))}
          isActive=${(o) => o.kbps === st.bandwidthKbps} onPick=${(o) => s.setQuality(o.kbps)} />`}
        <button type="button" class="pbtn" aria-label=${fullscreen ? "Exit fullscreen" : "Fullscreen"} title="Fullscreen (F)" onClick=${toggleFullscreen}>${fullscreen ? glyphs.exitFullscreen : glyphs.fullscreen}</button>
      </div>
    </footer>
  </div>`;
}
