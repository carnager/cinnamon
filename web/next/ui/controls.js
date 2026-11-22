// Small shared controls: rating stars, toggle buttons, toasts, empty states.
import { html, useState, useEffect, cx } from "../lib/html.js";
import { onToast } from "../lib/toast.js";
import { Icon } from "./icons.js";

// 1–10 personal rating as five stars with half steps (Trakt's scale).
// Clicking the current value clears it.
export function RatingStars({ value = 0, onChange, label = "Your rating" }) {
  const [hover, setHover] = useState(0);
  const shown = hover || value;
  const pick = (n) => onChange(n === value ? 0 : n).catch?.(() => {});
  return html`
    <div class="stars" role="radiogroup" aria-label=${label} onPointerLeave=${() => setHover(0)}>
      ${[1, 2, 3, 4, 5].map((star) => {
        const full = shown >= star * 2;
        const half = !full && shown === star * 2 - 1;
        return html`<span class=${cx("star", full && "is-full", half && "is-half")}>
          <${Icon} name="starFilled" size=${22} />
          <button type="button" class="star-hit left" aria-label=${`${star * 2 - 1} of 10`}
            onPointerEnter=${() => setHover(star * 2 - 1)} onClick=${() => pick(star * 2 - 1)}></button>
          <button type="button" class="star-hit right" aria-label=${`${star * 2} of 10`}
            onPointerEnter=${() => setHover(star * 2)} onClick=${() => pick(star * 2)}></button>
        </span>`;
      })}
      <span class="stars-value">${shown ? `${shown}/10` : "Rate"}</span>
    </div>`;
}

// A button that shows an on/off state (watchlist, seen).
export function ToggleButton({ on, onLabel, offLabel, icon, onIcon, onClick }) {
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    try { await onClick(); } catch { /* the store reports and rolls back */ } finally { setBusy(false); }
  };
  return html`<button type="button" class=${cx("btn", "btn-ghost", on && "is-on")} aria-pressed=${on} disabled=${busy} onClick=${run}>
    <${Icon} name=${on ? onIcon || icon : icon} size=${18} /> <span>${on ? onLabel : offLabel}</span>
  </button>`;
}

export function Toasts() {
  const [items, setItems] = useState([]);
  useEffect(() => onToast((entry) => {
    setItems((list) => [...list, entry]);
    setTimeout(() => setItems((list) => list.filter((t) => t.id !== entry.id)), entry.ms);
  }), []);
  return html`<div class="toasts" role="status" aria-live="polite">
    ${items.map((t) => html`<div key=${t.id} class=${cx("toast", `toast-${t.tone}`)}>${t.message}</div>`)}
  </div>`;
}

export function Empty({ title, children }) {
  return html`<div class="empty"><strong>${title}</strong>${children && html`<p>${children}</p>`}</div>`;
}

export function ErrorState({ error, retry }) {
  return html`<div class="empty is-error"><strong>Couldn't load this</strong><p>${error?.message || String(error)}</p>
    ${retry && html`<button type="button" class="btn btn-ghost" onClick=${retry}>Try again</button>`}</div>`;
}
