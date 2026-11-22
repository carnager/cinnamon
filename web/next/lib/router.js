// Client-side routing under /next.
//
// - Every history entry carries a key; scroll position is saved per key, so
//   Back returns to the same place in a long grid instead of the top.
// - Navigation runs inside document.startViewTransition when available, so
//   pages cross-fade and a clicked poster can morph into the detail poster.
// - Links prefetch their target's data on hover/focus/touch, which is what
//   makes the following click render from cache.

import { html, useEffect, useLayoutEffect, useReducer, useRef } from "./html.js";

export const BASE = "/next";

let routes = [];
let current = parse(location);
const subs = new Set();
const scrollPositions = new Map();
let pendingCommit = null; // resolves once the new route has rendered
let pendingScroll = null; // { key, restore } applied after commit
// The entry on screen. history.state has already moved on by the time popstate
// fires, so the outgoing page's scroll is saved under this key instead.
let activeKey = null;

const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

function newKey() {
  return Math.random().toString(36).slice(2, 10);
}

function parse(loc) {
  const path = loc.pathname.startsWith(BASE) ? loc.pathname.slice(BASE.length) : loc.pathname;
  const parts = path.split("/").filter(Boolean).map(decodeURIComponent);
  return { path: path || "/", parts, query: new URLSearchParams(loc.search), href: loc.pathname + loc.search };
}

export function setRoutes(table) {
  routes = table;
}

// match finds the route for a parsed location: { route, params }.
export function match(loc) {
  for (const route of routes) {
    const params = route.match(loc.parts, loc.query);
    if (params) return { route, params };
  }
  return { route: routes[routes.length - 1], params: {} };
}

function emit() {
  for (const fn of subs) fn();
}

function commit(loc, scroll) {
  if (activeKey) scrollPositions.set(activeKey, window.scrollY);
  activeKey = scroll.key;
  current = loc;
  pendingScroll = scroll;
  const done = new Promise((resolve) => { pendingCommit = resolve; });
  emit();
  return done;
}

function withTransition(update) {
  if (!document.startViewTransition || reducedMotion()) {
    update();
    return;
  }
  const transition = document.startViewTransition(() => Promise.race([
    update(),
    // Never hold the page frozen for long: a cold load renders its skeleton.
    new Promise((r) => setTimeout(r, 250)),
  ]));
  transition.finished.finally(() => {
    for (const el of document.querySelectorAll("[data-vt-temp]")) {
      el.style.viewTransitionName = "";
      el.removeAttribute("data-vt-temp");
    }
  });
}

export function href(path) {
  return BASE + (path.startsWith("/") ? path : `/${path}`);
}

// navigate to an app path ("/library/movies") or a full /next URL.
export function navigate(to, { replace = false, morph = null } = {}) {
  const url = to.startsWith(BASE) ? to : href(to);
  if (url === current.href && !replace) return;
  const state = { key: newKey() };
  if (morph) {
    // Name the clicked artwork for this one transition; the destination page
    // gives its poster the same name. A detail page on screen already owns
    // that name, and two elements sharing it would cancel the transition.
    for (const owner of document.querySelectorAll(".detail-poster")) {
      owner.style.viewTransitionName = "none";
      owner.setAttribute("data-vt-temp", "");
    }
    morph.style.viewTransitionName = "morph";
    morph.setAttribute("data-vt-temp", "");
  }
  withTransition(() => {
    if (replace) history.replaceState(state, "", url);
    else history.pushState(state, "", url);
    return commit(parse(location), { key: state.key, restore: false });
  });
}

// Updates the query string of the current entry without a new history entry
// or a transition (filters, search-as-you-type).
export function replaceQuery(params) {
  const search = new URLSearchParams(current.query);
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") search.delete(k);
    else search.set(k, String(v));
  }
  const qs = search.toString();
  const url = location.pathname + (qs ? `?${qs}` : "");
  history.replaceState(history.state, "", url);
  current = parse(location);
  emit();
}

export function back(fallback = "/") {
  if (history.state?.key && history.length > 1) history.back();
  else navigate(fallback, { replace: true });
}

export function prefetchHref(url) {
  const loc = parse(new URL(url, location.origin));
  const { route, params } = match(loc);
  route.prefetch?.(params, loc);
}

window.addEventListener("popstate", (e) => {
  const key = e.state?.key;
  withTransition(() => commit(parse(location), { key, restore: true }));
});

if (!history.state?.key) history.replaceState({ key: newKey() }, "", location.href);
activeKey = history.state.key;
if ("scrollRestoration" in history) history.scrollRestoration = "manual";

export function useLocation() {
  const [, rerender] = useReducer((n) => n + 1, 0);
  const seen = useRef(current);
  seen.current = current;
  useEffect(() => {
    subs.add(rerender);
    if (seen.current !== current) rerender();
    return () => subs.delete(rerender);
  }, []);
  // Resolve the transition and apply scroll once the new route is in the DOM.
  // Resolve directly, not in requestAnimationFrame: the browser does not run
  // animation frames while a view transition waits for its update callback,
  // so a rAF here stalled every navigation until the safety timeout.
  useLayoutEffect(() => {
    if (pendingCommit) {
      const resolve = pendingCommit;
      pendingCommit = null;
      resolve();
    }
    if (pendingScroll) {
      const { key, restore } = pendingScroll;
      pendingScroll = null;
      const y = restore ? scrollPositions.get(key) ?? 0 : 0;
      window.scrollTo(0, y);
      // Content that arrives a moment later (a cold grid) may need a second try.
      if (restore && y > 0) setTimeout(() => { if (Math.abs(window.scrollY - y) > 4) window.scrollTo(0, y); }, 120);
    }
  });
  return current;
}

// <Link href="/library/movies"> — client navigation plus hover prefetch.
// morph: true makes the first [data-morph] element inside the link morph into
// the destination's poster.
export function Link({ href: to, children, class: className, morph = false, replace = false, onClick, onPointerEnter, ...rest }) {
  const url = to.startsWith(BASE) ? to : href(to);
  const go = (e) => {
    onClick?.(e);
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    const el = morph ? e.currentTarget.querySelector("[data-morph]") : null;
    navigate(url, { replace, morph: el });
  };
  const warm = (e) => {
    onPointerEnter?.(e);
    prefetchHref(url);
  };
  return html`<a href=${url} class=${className} onClick=${go} onPointerEnter=${warm} onFocus=${warm} onTouchStart=${warm} ...${rest}>${children}</a>`;
}
