// HTTP access to popcornd. The bearer token lives in localStorage; the server's
// HttpOnly cookie authorises <img>/<video> requests that cannot send headers.

const TOKEN_KEY = "popcornToken";

export class ApiError extends Error {
  constructor(status, message) {
    super(message || `HTTP ${status}`);
    this.status = status;
  }
}

const unauthorizedListeners = new Set();
export function onUnauthorized(fn) {
  unauthorizedListeners.add(fn);
  return () => unauthorizedListeners.delete(fn);
}

export function getToken() {
  try { return localStorage.getItem(TOKEN_KEY) || ""; } catch { return ""; }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* storage unavailable: session lasts as long as the cookie */ }
}

export async function api(path, { method = "GET", body, signal, keepalive } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload = body;
  if (body !== undefined && !(body instanceof FormData) && typeof body !== "string") {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  const res = await fetch(path, { method, headers, body: payload, signal, keepalive, credentials: "same-origin" });
  if (res.status === 401) {
    // Only a real 401 ends the session. A 503 means the server could not
    // check the token (e.g. a busy database) and is not a verdict on it.
    unauthorizedListeners.forEach((fn) => fn());
    throw new ApiError(401, "Session expired");
  }
  if (!res.ok) {
    const text = (await res.text().catch(() => "")).trim();
    throw new ApiError(res.status, text || res.statusText);
  }
  if (res.status === 204) return null;
  const type = res.headers.get("Content-Type") || "";
  return type.includes("application/json") ? res.json() : res.text();
}

// Builds "/api/x?a=1&b=2", dropping empty values.
export function q(path, params = {}) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === "") continue;
    search.set(key, String(value));
  }
  const query = search.toString();
  return query ? `${path}?${query}` : path;
}
