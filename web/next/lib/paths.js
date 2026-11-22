// App paths (relative to /next). Same shapes as the classic client's URLs, so
// a link copied from one works in the other by adding or removing /next.

const enc = encodeURIComponent;

export const paths = {
  home: () => "/",
  library: (libraryId) => `/library/${enc(libraryId)}`,
  item: (item) => `/library/${enc(item.libraryId)}/item/${enc(item.id)}`,
  show: (libraryId, title, season) =>
    `/library/${enc(libraryId)}/show/${enc(title)}${season != null ? `?season=${enc(season)}` : ""}`,
  search: (term) => `/search${term ? `?q=${enc(term)}` : ""}`,
  watchlist: () => "/watchlist",
  // Not rebuilt yet: these open the classic client.
  classic: (appPath) => appPath,
};

export function formatRuntime(ms) {
  if (!ms) return "";
  const mins = Math.round(ms / 60000);
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h ? `${h}h ${m}m` : `${m}m`;
}

export function formatClock(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(h ? 2 : 1, "0");
  return h ? `${h}:${mm}:${String(s).padStart(2, "0")}` : `${mm}:${String(s).padStart(2, "0")}`;
}

export function endsAt(remainingMs) {
  const end = new Date(Date.now() + remainingMs);
  return end.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export function resolutionLabel(item) {
  const w = item?.width || 0;
  const h = item?.height || 0;
  if (w >= 3200 || h >= 2000) return "4K";
  if (w >= 1800 || h >= 1000) return "1080p";
  if (w >= 1200 || h >= 700) return "720p";
  return h ? `${h}p` : "";
}

export function episodeCode(item) {
  if (!item?.seasonNumber && !item?.episodeNumber) return "";
  return `S${String(item.seasonNumber || 0).padStart(2, "0")}E${String(item.episodeNumber || 0).padStart(2, "0")}`;
}

export function firstGenre(genres) {
  return String(genres || "").split(",")[0].trim();
}

export function genreList(genres) {
  return [...new Set(String(genres || "").split(",").map((g) => g.trim()).filter(Boolean))];
}

export function formatBytes(bytes) {
  if (!bytes) return "";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return `${value.toFixed(value >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}
