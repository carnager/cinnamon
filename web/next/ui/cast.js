// Cast row; each person links to their page. The photo is a background so a
// missing one simply leaves the initials showing.
import { html } from "../lib/html.js";
import { Link } from "../lib/router.js";
import { paths } from "../lib/paths.js";
import { Shelf } from "./shelf.js";

export function initials(name) {
  const parts = String(name || "").trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] || "?") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

export function actorPhoto(actor) {
  return actor.thumb || `/api/actors/image?name=${encodeURIComponent(actor.name)}`;
}

export function Cast({ actors }) {
  const people = (actors || []).filter((a) => a?.name).slice(0, 28);
  if (!people.length) return null;
  return html`<${Shelf} title="Cast" count=${people.length} class="shelf-cast">
    ${people.map((a) => html`<${Link} key=${a.name} class="person" href=${paths.actor(a.name)}>
      <span class="person-photo" style=${`background-image:url("${actorPhoto(a).replace(/"/g, "%22")}")`}>${initials(a.name)}</span>
      <strong>${a.name}</strong>${a.role && html`<span>${a.role}</span>`}
    <//>`)}
  <//>`;
}
