// Top navigation: brand, sections, search, user menu.
import { html, useState, useEffect, useRef, cx } from "../lib/html.js";
import { Link, navigate, replaceQuery, useLocation } from "../lib/router.js";
import { useResource } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { paths } from "../lib/paths.js";
import { api, setToken } from "../lib/api.js";
import { clearAll } from "../lib/cache.js";
import { Icon } from "./icons.js";

function Brand() {
  return html`<${Link} href="/" class="brand" aria-label="Cinnamon home">
    <svg class="brand-mark" viewBox="0 0 48 48" aria-hidden="true"><path d="M39.3 35.1a18.5 18.5 0 1 1 0-22.2"/><path d="M34.5 30.8a12.5 12.5 0 1 1 0-13.6"/><path d="M29.8 26.6a6.4 6.4 0 1 1 0-5.2"/></svg>
    <span>Cinnamon</span>
  <//>`;
}

// Typing starts a search with one history entry; further keystrokes replace
// it, so Back leaves search in one step instead of one per letter.
function SearchBox() {
  const loc = useLocation();
  const onSearch = loc.parts[0] === "search";
  const [value, setValue] = useState(onSearch ? loc.query.get("q") || "" : "");
  const inputRef = useRef(null);
  const timer = useRef(0);

  useEffect(() => {
    if (!onSearch) setValue("");
  }, [onSearch]);

  useEffect(() => {
    const onKey = (e) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName || "");
      if (e.key === "/" && !typing) {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const update = (text) => {
    setValue(text);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      if (window.location.pathname.endsWith("/search")) replaceQuery({ q: text.trim() });
      else if (text.trim()) navigate(paths.search(text.trim()));
    }, 160);
  };

  return html`<label class=${cx("search", value && "has-value")}>
    <${Icon} name="search" size=${18} />
    <input ref=${inputRef} type="search" placeholder="Search" aria-label="Search the library" value=${value}
      onInput=${(e) => update(e.currentTarget.value)}
      onKeyDown=${(e) => { if (e.key === "Escape") { update(""); e.currentTarget.blur(); } }} />
    <kbd>/</kbd>
  </label>`;
}

function UserMenu({ user }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return undefined;
    const close = (e) => { if (!ref.current?.contains(e.target)) setOpen(false); };
    const esc = (e) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("pointerdown", close);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);

  const logout = async () => {
    await api("/api/auth/logout", { method: "POST" }).catch(() => {});
    setToken("");
    clearAll();
    location.href = "/next/";
  };

  const initial = (user?.displayName || user?.username || "?").slice(0, 1).toUpperCase();
  return html`<div class="usermenu" ref=${ref}>
    <button type="button" class="avatar" aria-haspopup="menu" aria-expanded=${open} onClick=${() => setOpen(!open)}>
      ${user?.avatar ? html`<img src=${`/api/users/${user.id}/avatar?v=${encodeURIComponent(user.avatar)}`} alt="" />` : initial}
    </button>
    ${open && html`<div class="menu" role="menu">
      <div class="menu-head"><strong>${user?.displayName || user?.username}</strong><span>${user?.isAdmin ? "Admin" : "User"}</span></div>
      <a role="menuitem" href="/history"><${Icon} name="history" size=${18} /> Watch history <em>classic</em></a>
      <a role="menuitem" href="/settings"><${Icon} name="settings" size=${18} /> Settings <em>classic</em></a>
      <a role="menuitem" href="/"><${Icon} name="layers" size=${18} /> Open classic client</a>
      <button type="button" role="menuitem" onClick=${logout}><${Icon} name="logout" size=${18} /> Sign out</button>
    </div>`}
  </div>`;
}

export function TopBar({ user, overlay = false }) {
  const loc = useLocation();
  const { data: libraries } = useResource(R.libraries());
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);
  const section = loc.parts[0] === "library" ? `library:${loc.parts[1]}` : loc.parts[0] || "home";
  const links = [
    { key: "home", label: "Home", href: "/" },
    ...(libraries || []).map((l) => ({ key: `library:${l.id}`, label: l.name, href: paths.library(l.id) })),
    { key: "watchlist", label: "Watchlist", href: paths.watchlist() },
  ];
  return html`<header class=${cx("topbar", overlay && "is-overlay", scrolled && "is-scrolled")}>
    <${Brand} />
    <nav class="sections" aria-label="Sections">
      ${links.map((l) => html`<${Link} key=${l.key} href=${l.href} class=${cx("section-link", section === l.key && "is-active")} aria-current=${section === l.key ? "page" : undefined}>${l.label}<//>`)}
    </nav>
    <div class="topbar-end">
      <${SearchBox} />
      <${UserMenu} user=${user} />
    </div>
  </header>`;
}
