// Notices finished library scans (any client or popcorn-watch can start one)
// and refreshes what is on screen, without a reload.
import { api } from "./api.js";
import { invalidate } from "./cache.js";
import { loadUserState } from "./userstate.js";

const POLL_MS = 10_000;
const LIBRARY_DATA = ["lib:", "home", "genres:", "decades:", "alphabet:", "show:", "seasons:", "episodes:", "item:", "search:", "watchlist", "actor:"];
let timer = 0;
let last = "";

export function scanSignature(statuses) {
  return (statuses || [])
    .filter((s) => s?.finishedAt)
    .map((s) => [s.libraryId, s.finishedAt, s.status, s.mediaFound || 0, s.itemsImported || 0, s.errors || 0].join(":"))
    .sort()
    .join("|");
}

export const scanRunning = (statuses) => (statuses || []).some((s) => s?.status === "running");

export function refreshLibraryData() {
  for (const prefix of LIBRARY_DATA) invalidate(prefix);
  loadUserState({ force: true }).catch(() => {});
}

async function poll() {
  if (document.hidden) return;
  const statuses = await api("/api/scan").catch(() => null);
  if (!statuses || scanRunning(statuses)) return;
  const signature = scanSignature(statuses);
  if (!signature) return;
  if (last && signature !== last) refreshLibraryData();
  last = signature;
}

export function startScanPolling() {
  if (timer) return;
  poll();
  timer = setInterval(poll, POLL_MS);
}

export function stopScanPolling() {
  clearInterval(timer);
  timer = 0;
  last = "";
}
