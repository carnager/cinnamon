// Playback entry point. The player has not been rebuilt yet, so for now this
// hands off to the classic client's page for the item, where Play works.
// Every Play button goes through here, so swapping in the new player later
// touches only this file.
import { toast } from "./toast.js";

export function playItem(item, { fromStart = false } = {}) {
  if (!item?.id) return;
  if (fromStart) toast("Opening the classic player — choose “Play from beginning” there.", { ms: 3000 });
  const base = `/library/${encodeURIComponent(item.libraryId)}/item/${encodeURIComponent(item.id)}`;
  const params = new URLSearchParams();
  if (item.kind === "episode" && item.showTitle) {
    params.set("show", item.showTitle);
    if (item.seasonNumber != null) params.set("season", String(item.seasonNumber));
  }
  const qs = params.toString();
  window.location.href = base + (qs ? `?${qs}` : "");
}
