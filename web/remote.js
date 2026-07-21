const $ = (id) => document.getElementById(id);
const serverInput = $("server");
const tokenKey = "popcornRemoteToken";
const serverKey = "popcornRemoteServer";
let token = localStorage.getItem(tokenKey) || "";
let base = localStorage.getItem(serverKey) || location.origin;
let state = null;
let stateTimer = 0;
serverInput.value = base;

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const res = await fetch(`${base}${path}`, { ...options, headers });
  if (!res.ok) throw new Error(await res.text());
  if (res.status === 204 || res.status === 202) return null;
  return res.json();
}

function setAuthed(on) {
  $("loginBox").classList.toggle("hidden", on);
  $("remoteBox").classList.toggle("hidden", !on);
  $("searchBox").classList.toggle("hidden", !on);
  $("controlsBox").classList.toggle("hidden", !on);
  if (on) {
    loadDevices();
    clearInterval(stateTimer);
    stateTimer = setInterval(loadState, 1000);
  }
}

async function loadDevices() {
  const devices = await api("/api/devices");
  const select = $("device");
  select.innerHTML = "";
  for (const d of devices) {
    const option = document.createElement("option");
    option.value = d.id;
    option.textContent = `${d.name} (${d.kind})`;
    select.append(option);
  }
  loadState();
}

async function loadState() {
  const id = $("device").value;
  if (!id) return;
  state = await api(`/api/devices/${encodeURIComponent(id)}/state`).catch(() => null);
  if (!state) return;
  const pos = Math.round((state.positionMs || 0) / 1000);
  const dur = Math.round((state.durationMs || 0) / 1000);
  $("state").textContent = `${state.state || "idle"}${state.title ? ` · ${state.title}` : ""} · ${fmt(pos)} / ${fmt(dur)}`;
}

function fmt(s) {
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h ? `${h}:${String(m).padStart(2,"0")}:${String(sec).padStart(2,"0")}` : `${m}:${String(sec).padStart(2,"0")}`;
}

async function send(type, payload = {}) {
  const id = $("device").value;
  if (!id) return;
  await api(`/api/devices/${encodeURIComponent(id)}/commands`, {
    method: "POST",
    body: JSON.stringify({ type, payload }),
  });
}

$("login").onclick = async () => {
  base = serverInput.value.trim().replace(/\/$/, "") || location.origin;
  localStorage.setItem(serverKey, base);
  $("loginError").textContent = "";
  try {
    const res = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username: $("username").value, password: $("password").value }),
    });
    token = res.token;
    localStorage.setItem(tokenKey, token);
    setAuthed(true);
  } catch (e) {
    $("loginError").textContent = e.message;
  }
};

$("logout").onclick = () => {
  token = "";
  localStorage.removeItem(tokenKey);
  setAuthed(false);
};

$("refreshDevices").onclick = () => loadDevices().catch(console.error);
$("pairSubmit").onclick = async () => {
  await api("/api/devices/pair", { method: "POST", body: JSON.stringify({ code: $("pairCode").value }) });
  await loadDevices();
};
$("pair").onclick = () => alert("Open Cinnamon on the Shield. Same-account devices should appear automatically; pairing code UI on TV comes next.");

let searchTimer = 0;
$("query").oninput = () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(search, 250);
};

async function search() {
  const q = $("query").value.trim();
  const results = $("results");
  results.innerHTML = "";
  if (q.length < 2) return;
  const [shows, movies] = await Promise.all([
    api(`/api/tv/shows?limit=20&q=${encodeURIComponent(q)}`).catch(() => []),
    api(`/api/search?limit=30&kind=movie&q=${encodeURIComponent(q)}`).then(r => r.items || []).catch(() => []),
  ]);
  for (const show of shows) addResult(`${show.title}`, `${show.seasonCount} seasons · ${show.episodeCount} episodes`, null);
  for (const item of movies) addResult(item.title, [item.year, item.durationMs ? `${Math.round(item.durationMs / 60000)}m` : ""].filter(Boolean).join(" · "), item.id);
}

function addResult(title, meta, itemId) {
  const button = document.createElement("button");
  button.className = "item";
  button.innerHTML = `<div class="title"></div><div class="meta"></div>`;
  button.querySelector(".title").textContent = title;
  button.querySelector(".meta").textContent = itemId ? meta : `${meta} · open TV show on native app coming next`;
  button.disabled = !itemId;
  button.onclick = () => send("playItem", { itemId });
  $("results").append(button);
}

document.querySelectorAll("[data-command]").forEach((button) => {
  button.onclick = () => send(button.dataset.command);
});
document.querySelectorAll("[data-seek]").forEach((button) => {
  button.onclick = () => {
    const current = state?.positionMs || 0;
    send("seek", { positionMs: Math.max(0, current + Number(button.dataset.seek)) });
  };
});

if (token) setAuthed(true);
