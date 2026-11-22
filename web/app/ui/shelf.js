// A titled horizontal row of cards. Scroll snaps to cards; on devices with a
// mouse, paddle buttons page through it (the scrollbar is hidden and a plain
// wheel only scrolls vertically).
import { html, useRef, useState, useEffect, cx } from "../lib/html.js";
import { Link } from "../lib/router.js";
import { Icon } from "./icons.js";

export function Shelf({ title, count, moreHref, children, class: className }) {
  const rowRef = useRef(null);
  const [edges, setEdges] = useState({ start: true, end: false });

  useEffect(() => {
    const row = rowRef.current;
    if (!row) return undefined;
    const update = () => setEdges({
      start: row.scrollLeft <= 4,
      end: row.scrollLeft + row.clientWidth >= row.scrollWidth - 4,
    });
    update();
    row.addEventListener("scroll", update, { passive: true });
    const observer = new ResizeObserver(update);
    observer.observe(row);
    return () => {
      row.removeEventListener("scroll", update);
      observer.disconnect();
    };
  }, []);

  const page = (dir) => {
    const row = rowRef.current;
    row?.scrollBy({ left: dir * row.clientWidth * 0.85, behavior: "smooth" });
  };

  return html`
    <section class=${cx("shelf", className)}>
      <header class="shelf-head">
        <h2>${title}</h2>
        ${count != null && html`<span class="count">${count}</span>`}
        ${moreHref && html`<${Link} href=${moreHref} class="shelf-more">See all <${Icon} name="chevronRight" size=${16} /><//>`}
      </header>
      <div class="shelf-body">
        <button type="button" class="paddle paddle-left" hidden=${edges.start} aria-label="Scroll left" onClick=${() => page(-1)}><${Icon} name="chevronLeft" /></button>
        <div class="shelf-row" ref=${rowRef}>${children}</div>
        <button type="button" class="paddle paddle-right" hidden=${edges.end} aria-label="Scroll right" onClick=${() => page(1)}><${Icon} name="chevronRight" /></button>
      </div>
    </section>`;
}
