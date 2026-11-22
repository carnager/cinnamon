// Inline SVG icons (24px grid, stroke = currentColor). Inline so they inherit
// colour and never flash while loading.
import { html } from "../lib/html.js";

const paths = {
  play: html`<path d="M7 4.5v15l12-7.5z" fill="currentColor" stroke="none"/>`,
  bookmark: html`<path d="M6.5 4h11v16l-5.5-4-5.5 4z"/>`,
  bookmarkFilled: html`<path d="M6.5 4h11v16l-5.5-4-5.5 4z" fill="currentColor"/>`,
  check: html`<path d="M5 12.5l4.5 4.5L19 7.5"/>`,
  checkCircle: html`<circle cx="12" cy="12" r="9"/><path d="M8 12.3l2.8 2.8L16.2 9.5"/>`,
  star: html`<path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.6 9.7l5.8-.8z"/>`,
  starFilled: html`<path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8L3.6 9.7l5.8-.8z" fill="currentColor"/>`,
  search: html`<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>`,
  x: html`<path d="M6 6l12 12M18 6L6 18"/>`,
  chevronLeft: html`<path d="M14.5 5.5L8 12l6.5 6.5"/>`,
  chevronRight: html`<path d="M9.5 5.5L16 12l-6.5 6.5"/>`,
  chevronDown: html`<path d="M6 9.5l6 6 6-6"/>`,
  download: html`<path d="M12 4v11M7 10.5l5 5 5-5M5 19.5h14"/>`,
  film: html`<rect x="3.5" y="5" width="17" height="14" rx="2"/><path d="M3.5 9.5h17M3.5 14.5h17M8 5v14M16 5v14"/>`,
  restart: html`<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.5 4.5v4h4"/>`,
  eyeOff: html`<path d="M3 3l18 18"/><path d="M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 8.5 4.5 9.5 6-.4.6-1.2 1.7-2.4 2.8M6.6 7.5C4.6 8.8 3.1 10.8 2.5 12c1 1.5 4.5 6 9.5 6 1.5 0 2.9-.4 4.1-1"/>`,
  sliders: html`<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>`,
  logout: html`<path d="M14 4.5H6.5v15H14M10 12h10M16.5 8.5L20 12l-3.5 3.5"/>`,
  settings: html`<circle cx="12" cy="12" r="3"/><path d="M19.4 13.5a7.6 7.6 0 0 0 0-3l2-1.6-2-3.4-2.4 1a7.5 7.5 0 0 0-2.6-1.5L14 2.5h-4l-.4 2.5A7.5 7.5 0 0 0 7 6.5l-2.4-1-2 3.4 2 1.6a7.6 7.6 0 0 0 0 3l-2 1.6 2 3.4 2.4-1a7.5 7.5 0 0 0 2.6 1.5l.4 2.5h4l.4-2.5a7.5 7.5 0 0 0 2.6-1.5l2.4 1 2-3.4z"/>`,
  history: html`<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.5 4.5v4h4M12 8v4.5l3 2"/>`,
  layers: html`<path d="M12 4l8.5 4.5L12 13 3.5 8.5z"/><path d="M3.5 12.5L12 17l8.5-4.5M3.5 16.5L12 21l8.5-4.5"/>`,
};

export function Icon({ name, size = 20, class: className = "" }) {
  return html`<svg class=${`icon ${className}`} width=${size} height=${size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
}
