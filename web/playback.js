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


function updateBandwidthVisibility() {
  bandwidth.style.display = transcode.checked ? "" : "none";
}
