// Cinnamon — next web client. Entry point: routes, login, app shell.
import { html, render, useState, useEffect } from "./lib/html.js";
import { api, setToken, getToken, onUnauthorized } from "./lib/api.js";
import { useResource, fetchNow, peek, clearAll } from "./lib/cache.js";
import { R } from "./lib/resources.js";
import { setRoutes, match, useLocation } from "./lib/router.js";
import { loadUserState } from "./lib/userstate.js";
import { TopBar } from "./ui/topbar.js";
import { Toasts, Empty } from "./ui/controls.js";
import { HomeView } from "./views/home.js";
import { LibraryView, prefetchLibrary } from "./views/library.js";
import { DetailView, prefetchDetail } from "./views/detail.js";
import { ShowView, prefetchShow } from "./views/show.js";
import { SearchView, WatchlistView } from "./views/lists.js";

setRoutes([
  {
    name: "home",
    match: (p) => (p.length === 0 ? {} : null),
    view: () => html`<${HomeView} />`,
  },
  {
    name: "detail",
    match: (p) => (p[0] === "library" && p[2] === "item" && p[3] ? { libraryId: p[1], itemId: p[3] } : null),
    view: ({ itemId }, loc) => html`<${DetailView} key=${itemId} itemId=${itemId} query=${loc.query} />`,
    prefetch: ({ itemId }) => prefetchDetail(itemId),
    hero: true,
  },
  {
    name: "show",
    match: (p) => (p[0] === "library" && p[2] === "show" && p[3] ? { libraryId: p[1], title: p.slice(3).join("/") } : null),
    view: ({ libraryId, title }, loc) => html`<${ShowView} key=${`${libraryId}:${title}`} libraryId=${libraryId} title=${title} query=${loc.query} />`,
    prefetch: ({ libraryId, title }) => prefetchShow(libraryId, title),
    hero: true,
  },
  {
    name: "library",
    match: (p) => (p[0] === "library" && p[1] && p.length === 2 ? { libraryId: p[1] } : null),
    view: ({ libraryId }, loc) => html`<${LibraryView} libraryId=${libraryId} query=${loc.query} />`,
    prefetch: ({ libraryId }, loc) => prefetchLibrary((peek("libraries") || []).find((l) => l.id === libraryId), loc?.query),
  },
  { name: "search", match: (p) => (p[0] === "search" ? {} : null), view: (_, loc) => html`<${SearchView} query=${loc.query} />` },
  { name: "watchlist", match: (p) => (p[0] === "watchlist" ? {} : null), view: () => html`<${WatchlistView} />` },
  {
    name: "notfound",
    match: () => ({}),
    view: () => html`<main class="page"><${Empty} title="Nothing here">This part of Cinnamon hasn't been rebuilt yet. <a href="/">Open the classic client</a>.<//></main>`,
  },
]);

function Shell({ user }) {
  const loc = useLocation();
  const { route, params } = match(loc);
  useEffect(() => {
    document.title = route.name === "home" ? "Cinnamon" : `Cinnamon · ${route.name[0].toUpperCase()}${route.name.slice(1)}`;
  }, [route.name]);
  const overlay = route.name === "home" || route.hero;
  return html`<div class=${`shell route-${route.name}`}>
    <${TopBar} user=${user} overlay=${overlay} />
    ${route.view(params, loc)}
    <${Toasts} />
  </div>`;
}

function Login({ onDone }) {
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setBusy(true);
    setError("");
    try {
      const res = await api("/api/auth/login", { method: "POST", body: { username: form.get("username"), password: form.get("password") } });
      setToken(res.token);
      onDone();
    } catch (err) {
      setError(err.status === 401 ? "Wrong username or password." : err.message);
    } finally {
      setBusy(false);
    }
  };
  return html`<main class="login">
    <form class="login-card" onSubmit=${submit}>
      <div class="brand"><svg class="brand-mark" viewBox="0 0 48 48" aria-hidden="true"><path d="M39.3 35.1a18.5 18.5 0 1 1 0-22.2"/><path d="M34.5 30.8a12.5 12.5 0 1 1 0-13.6"/><path d="M29.8 26.6a6.4 6.4 0 1 1 0-5.2"/></svg><span>Cinnamon</span></div>
      <label class="field"><span>Username</span><input name="username" autocomplete="username" required autofocus /></label>
      <label class="field"><span>Password</span><input name="password" type="password" autocomplete="current-password" required /></label>
      <button class="btn btn-primary btn-lg" type="submit" disabled=${busy}>${busy ? "Signing in…" : "Sign in"}</button>
      ${error && html`<p class="form-error" role="alert">${error}</p>`}
    </form>
  </main>`;
}

function App() {
  const [phase, setPhase] = useState(getToken() ? "checking" : "login");
  const { data: user } = useResource(phase === "ready" ? R.me() : null);

  const start = async () => {
    setPhase("checking");
    try {
      await fetchNow(R.me());
      // Warm what every page needs; none of it blocks the first render.
      fetchNow(R.libraries()).catch(() => {});
      fetchNow(R.home()).catch(() => {});
      loadUserState().catch(() => {});
      setPhase("ready");
    } catch (err) {
      // A 503 is the server unable to check the session, not a rejection.
      setPhase(err.status === 401 ? "login" : "offline");
    }
  };

  useEffect(() => {
    if (phase === "checking") start();
    return onUnauthorized(() => {
      setToken("");
      clearAll();
      setPhase("login");
    });
  }, []);

  if (phase === "login") return html`<${Login} onDone=${start} />`;
  if (phase === "offline") {
    return html`<main class="login"><div class="login-card"><${Empty} title="Can't reach the server">
      <button type="button" class="btn btn-primary" onClick=${start}>Try again</button><//></div></main>`;
  }
  if (phase !== "ready" || !user) return html`<div class="boot" aria-busy="true"></div>`;
  return html`<${Shell} user=${user} />`;
}

render(html`<${App} />`, document.getElementById("app"));
