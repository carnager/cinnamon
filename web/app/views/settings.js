// Settings: one page per concern, one row style. Everything server-driven
// (home shelf catalog, users, app builds) is read from the API as before; only
// the Users, Library and App builds sections are admin-only.
import { html, useState, useEffect, useRef, cx } from "../lib/html.js";
import { api, setToken } from "../lib/api.js";
import { useResource, invalidate, setData, clearAll } from "../lib/cache.js";
import { R } from "../lib/resources.js";
import { Link } from "../lib/router.js";
import { paths, formatBytes } from "../lib/paths.js";
import { preferences, QUALITY_OPTIONS } from "../lib/playback.js";
import { loadUserState, setItemExcluded, setShowExcluded } from "../lib/userstate.js";
import { refreshLibraryData, scanRunning } from "../lib/scan.js";
import { toast } from "../lib/toast.js";
import { Artwork } from "../ui/artwork.js";
import { Icon } from "../ui/icons.js";
import { Empty, ErrorState, SelectField } from "../ui/controls.js";

const SECTIONS = [
  { id: "", label: "Account", icon: "settings" },
  { id: "playback", label: "Playback", icon: "play" },
  { id: "home", label: "Home layout", icon: "layers" },
  { id: "trakt", label: "Trakt", icon: "history" },
  { id: "hidden", label: "Not interested", icon: "eyeOff" },
  { id: "library", label: "Library", icon: "film", admin: true },
  { id: "users", label: "Users", icon: "sliders", admin: true },
  { id: "apps", label: "App builds", icon: "download", admin: true },
];

const LANGUAGES = [["de", "Deutsch"], ["en", "English"], ["fr", "Français"], ["es", "Español"], ["it", "Italiano"], ["ja", "日本語"]];

const formatDate = (value) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value || "") : date.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
};

// ---- the building blocks every section uses ---------------------------------

function Group({ title, description, children }) {
  return html`<section class="set-group">
    ${(title || description) && html`<header>${title && html`<h2>${title}</h2>`}${description && html`<p>${description}</p>`}</header>`}
    <div class="set-rows">${children}</div>
  </section>`;
}

function Row({ title, description, children, stacked = false }) {
  return html`<div class=${cx("set-row", stacked && "is-stacked")}>
    <div class="set-copy"><strong>${title}</strong>${description && html`<span>${description}</span>`}</div>
    ${children && html`<div class="set-control">${children}</div>`}
  </div>`;
}

function Switch({ checked, disabled, onChange, label }) {
  return html`<label class=${cx("switch", disabled && "is-disabled")}>
    <input type="checkbox" role="switch" aria-label=${label} checked=${checked} disabled=${disabled} onChange=${(e) => onChange(e.currentTarget.checked)} />
    <span></span>
  </label>`;
}

function TextField({ label, ...props }) {
  return html`<label class="field"><span>${label}</span><input ...${props} /></label>`;
}

// Runs an action with busy state and shows its result right under the group.
function useAction() {
  const [state, setState] = useState({ busy: null, result: null, error: "" });
  const run = async (name, action) => {
    setState({ busy: name, result: null, error: "" });
    try {
      const result = await action();
      setState({ busy: null, result: result ?? "Done.", error: "" });
    } catch (err) {
      setState({ busy: null, result: null, error: err.message || String(err) });
    }
  };
  return { ...state, run };
}

function ActionResult({ action }) {
  if (action.busy) return html`<div class="set-result is-busy" role="status"><span class="dot-spinner"></span> Working…</div>`;
  if (action.error) return html`<div class="set-result is-error" role="alert">${action.error}</div>`;
  if (!action.result) return null;
  return html`<div class="set-result" role="status">${action.result}</div>`;
}

function ActionButton({ action, name, label, primary, run, danger }) {
  return html`<button type="button" class=${cx("btn", primary ? "btn-primary" : danger ? "btn-danger" : "btn-ghost")}
    disabled=${Boolean(action.busy)} onClick=${() => action.run(name, run)}>${action.busy === name ? "Working…" : label}</button>`;
}

function Summary({ rows, samples = [] }) {
  return html`<div class="summary">
    <dl>${rows.map(([k, v]) => html`<div key=${k}><dt>${k}</dt><dd>${v}</dd></div>`)}</dl>
    ${samples.filter(([, list]) => list?.length).map(([title, list]) => html`<details key=${title}><summary>${title} <span class="count">${list.length}</span></summary>
      <ul>${list.slice(0, 40).map((value, i) => html`<li key=${i}>${value}</li>`)}</ul></details>`)}
  </div>`;
}

const watchedSummary = (s) => html`<${Summary}
  rows=${[
    ["Movies", `${s.moviesMatched || 0} of ${s.moviesSeen || 0} matched`],
    ["Episodes", `${s.episodesMatched || 0} of ${s.episodesSeen || 0} matched`],
    ["Not in this library", String((s.moviesUnmatched || 0) + (s.episodesUnmatched || 0))],
  ]}
  samples=${[["Unmatched movies", s.debug?.unmatchedMovies], ["Unmatched episodes", s.debug?.unmatchedEpisodes]]} />`;

const listSummary = (s) => html`<${Summary}
  rows=${[
    ["Entries", String(s.entriesSeen || 0)],
    ["Movies", `${s.moviesMatched || 0} of ${s.moviesSeen || 0} matched`],
    ["Shows", `${s.showsMatched || 0} of ${s.showsSeen || 0} matched`],
    ["Episodes", `${s.episodesMatched || 0} of ${s.episodesSeen || 0} matched`],
  ]}
  samples=${[["Matched", s.matched]]} />`;

async function afterImport() {
  invalidate("home");
  invalidate("watchlist");
  invalidate("history");
  invalidate("exclusions");
  await loadUserState({ force: true }).catch(() => {});
}

// ---- Account ------------------------------------------------------------------

function AvatarImage({ user, size = 64 }) {
  const initial = (user?.displayName || user?.username || "?").slice(0, 1).toUpperCase();
  return html`<span class="set-avatar" style=${`width:${size}px;height:${size}px;font-size:${size * 0.4}px`}>
    ${user?.avatar ? html`<img src=${`/api/users/${user.id}/avatar?v=${encodeURIComponent(user.avatar)}`} alt="" />` : initial}
  </span>`;
}

function AccountSection({ user }) {
  const logout = async () => {
    await api("/api/auth/logout", { method: "POST" }).catch(() => {});
    setToken("");
    clearAll();
    location.href = "/";
  };
  return html`
    <${Group}>
      <div class="set-identity">
        <${AvatarImage} user=${user} />
        <div><strong>${user.displayName || user.username}</strong><span>${user.username} · ${user.isAdmin ? "Admin" : "User"}</span></div>
        <button type="button" class="btn btn-ghost" onClick=${logout}><${Icon} name="logout" size=${18} /> Sign out</button>
      </div>
    <//>
    <${Group} title="Other clients">
      <${Row} title="Remote control" description="Control a paired TV from this device.">
        <a class="btn btn-ghost" href="/remote">Open</a>
      <//>
    <//>`;
}

// ---- Playback -----------------------------------------------------------------

function PlaybackSection() {
  const [audio, setAudio] = useState(preferences.audio());
  const [subtitle, setSubtitle] = useState(preferences.subtitle());
  const [quality, setQuality] = useState(preferences.bandwidth());
  return html`<${Group} title="Defaults" description="Saved in this browser. You can still change tracks for a single title on its page or in the player.">
    <${Row} title="Preferred audio" description="Used when a title has this language.">
      <${SelectField} label="Preferred audio" class="is-bare" value=${audio}
        options=${[["", "Track default"], ...LANGUAGES]}
        onChange=${(v) => { preferences.setAudio(v); setAudio(v); }} />
    <//>
    <${Row} title="Preferred subtitles" description="Turned on automatically when a title has them.">
      <${SelectField} label="Preferred subtitles" class="is-bare" value=${subtitle}
        options=${[["off", "Off"], ["default", "Track default"], ...LANGUAGES]}
        onChange=${(v) => { preferences.setSubtitle(v); setSubtitle(v); }} />
    <//>
    <${Row} title="Streaming quality" description="Auto plays the original when it can. A limit always transcodes.">
      <${SelectField} label="Streaming quality" class="is-bare" value=${quality ?? ""}
        options=${QUALITY_OPTIONS.map((o) => [o.kbps ?? "", o.label])}
        onChange=${(v) => { const kbps = v === "" ? null : Number(v); preferences.setBandwidth(kbps); setQuality(kbps); }} />
    <//>
  <//>`;
}

// ---- Home layout --------------------------------------------------------------

function ShelfParam({ param, entry, match, onChange }) {
  const choices = param.options?.length ? param.options : param.choices;
  const value = entry.params[param.name] || param.default || (choices?.[0] ?? "");
  const write = (name, next, fallback) => {
    const params = { ...entry.params };
    const clean = String(next ?? "").trim();
    if (clean && clean !== fallback) params[name] = clean;
    else delete params[name];
    onChange({ ...entry, params });
  };
  const type = param.type === "int" || param.type === "number" ? "number" : "text";
  return html`<div class="shelf-param">
    ${choices?.length
      ? html`<${SelectField} label=${param.label || param.name} value=${value}
          options=${choices.map((o) => [o, o === "" ? "Any" : param.suffix ? `${o} ${param.suffix}` : o])}
          onChange=${(v) => write(param.name, v)} />`
      : html`<${TextField} label=${param.label || param.name} type=${type} value=${entry.params[param.name] || param.default || ""}
          min=${param.type === "int" ? 1 : param.type === "number" ? 0 : undefined} step=${param.type === "number" ? 0.5 : undefined}
          placeholder=${param.multi ? (param.required ? "required, comma separated" : "any of these, comma separated") : param.required ? "required" : ""}
          onChange=${(e) => write(param.name, e.currentTarget.value)} />`}
    ${match && html`<${SelectField} label="Match" value=${entry.params[match.name] || match.default || "any"}
      options=${(match.options || []).map((o) => [o, o === "all" ? "all of them" : "any of them"])}
      onChange=${(v) => write(match.name, v, match.default)} />`}
  </div>`;
}

function ShelfRow({ entry, definition, index, count, update, move, remove }) {
  const [open, setOpen] = useState(false);
  const params = definition?.params || [];
  const byName = new Map(params.map((p) => [p.name, p]));
  const visible = params.filter((p) => !p.hidden);
  return html`<li class=${cx("shelf-edit", !entry.enabled && "is-off")}>
    <div class="shelf-edit-main">
      <span class="shelf-move">
        <button type="button" class="btn btn-icon" aria-label="Move up" disabled=${index === 0} onClick=${() => move(index, -1)}><${Icon} name="chevronLeft" class="rot-up" size=${16} /></button>
        <button type="button" class="btn btn-icon" aria-label="Move down" disabled=${index === count - 1} onClick=${() => move(index, 1)}><${Icon} name="chevronLeft" class="rot-down" size=${16} /></button>
      </span>
      <div class="set-copy">
        <strong>${entry.title || definition?.label || entry.type}</strong>
        <span>${definition ? definition.description || "" : "This server no longer offers this shelf."}</span>
      </div>
      <span class="shelf-tools">
        ${visible.length > 0 && html`<button type="button" class=${cx("btn", "btn-text", open && "is-on")} aria-expanded=${open} onClick=${() => setOpen(!open)}><${Icon} name="sliders" size=${17} /> Options</button>`}
        <button type="button" class="btn btn-text" onClick=${() => remove(index)}>Remove</button>
        <${Switch} label=${`Show ${definition?.label || entry.type}`} checked=${entry.enabled} onChange=${(on) => update(index, { ...entry, enabled: on })} />
      </span>
    </div>
    ${open && html`<div class="shelf-params">
      ${visible.map((p) => html`<${ShelfParam} key=${p.name} param=${p} entry=${entry} match=${byName.get(p.matchParam)} onChange=${(next) => update(index, next)} />`)}
    </div>`}
  </li>`;
}

function HomeLayoutSection() {
  const { data: catalog, error: catalogError } = useResource(R.homeCatalog());
  const { data: layout, error } = useResource(R.homeLayout());
  const [draft, setDraft] = useState(null);
  const [dirty, setDirty] = useState(false);
  const action = useAction();

  const types = new Map((catalog?.sections || []).map((e) => [e.type, e]));
  const isHero = (s) => types.get(s.type)?.layout === "hero";

  // The hero draws at the top of home wherever it sits, so it is carried
  // through untouched rather than listed as a shelf you can move.
  useEffect(() => {
    if (!catalog || !layout || dirty) return;
    setDraft((layout.sections || []).filter((s) => !isHero(s)).map((s) => ({ ...s, params: { ...(s.params || {}) } })));
  }, [catalog, layout]);

  if (error || catalogError) return html`<${ErrorState} error=${error || catalogError} />`;
  if (!draft) return html`<div class="set-loading"></div>`;

  const change = (next) => { setDraft(next); setDirty(true); };
  const update = (i, entry) => change(draft.map((e, j) => (j === i ? entry : e)));
  const move = (i, delta) => {
    const next = [...draft];
    next.splice(i + delta, 0, next.splice(i, 1)[0]);
    change(next);
  };
  const remove = (i) => change(draft.filter((_, j) => j !== i));
  const add = (type) => {
    const entry = types.get(type);
    if (!entry) return;
    const params = {};
    for (const p of entry.params || []) if (p.default) params[p.name] = p.default;
    change([...draft, { id: "", type, enabled: true, title: "", params }]);
  };
  const addable = (catalog.sections || []).filter((e) => e.layout !== "hero" && (e.repeatable || !draft.some((s) => s.type === e.type)));
  const save = () => action.run("save", async () => {
    const pinned = (layout.sections || []).filter(isHero);
    const sections = [...pinned, ...draft].map(({ id, type, enabled, title, params }) => ({ id, type, enabled, title, params }));
    await api("/api/home/layout", { method: "PUT", body: { sections } });
    setDirty(false);
    invalidate("settings:home:layout");
    invalidate("home");
    return "Saved. Home uses this layout on every device you sign in from.";
  });
  const reset = () => action.run("reset", async () => {
    await api("/api/home/layout", { method: "DELETE" });
    setDirty(false);
    setDraft(null);
    invalidate("settings:home:layout");
    invalidate("home");
    return "Back to the default layout.";
  });

  return html`<${Group} title="Shelves" description=${layout.source === "user" ? "Your layout, used on every device you sign in from." : "Currently the default layout. Saving makes it your own, on every device."}>
    ${draft.length === 0 && html`<${Empty} title="No shelves">Add one below, or reset to the default layout.<//>`}
    <ul class="shelf-list">
      ${draft.map((entry, i) => html`<${ShelfRow} key=${`${entry.id || entry.type}:${i}`} entry=${entry} definition=${types.get(entry.type)} index=${i} count=${draft.length} update=${update} move=${move} remove=${remove} />`)}
    </ul>
    <div class="set-actions">
      <span class="select-wrap"><select aria-label="Add a shelf" value="" onChange=${(e) => { add(e.currentTarget.value); e.currentTarget.value = ""; }}>
        <option value="">Add a shelf…</option>
        ${addable.map((e) => html`<option key=${e.type} value=${e.type}>${e.label}</option>`)}
      </select><${Icon} name="chevronDown" size=${16} /></span>
      <span class="set-spacer"></span>
      <button type="button" class="btn btn-ghost" disabled=${Boolean(action.busy)} onClick=${reset}>Reset to default</button>
      <button type="button" class="btn btn-primary" disabled=${Boolean(action.busy) || !dirty} onClick=${save}>${dirty ? "Save layout" : "Saved"}</button>
    </div>
    <${ActionResult} action=${action} />
  <//>`;
}

// ---- Trakt --------------------------------------------------------------------

function TraktLink({ onLinked }) {
  const [device, setDevice] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const stop = useRef(false);
  useEffect(() => () => { stop.current = true; }, []);

  // Poll until the code is authorized or expires, so nobody has to come back
  // and press a button.
  useEffect(() => {
    if (!device) return undefined;
    let timer = 0;
    const deadline = Date.now() + (device.expires_in || 600) * 1000;
    const tick = async () => {
      if (stop.current) return;
      if (Date.now() > deadline) {
        setDevice(null);
        setError("The code expired. Start again.");
        return;
      }
      try {
        const result = await api("/api/trakt/device/token", { method: "POST", body: { deviceCode: device.device_code } });
        if (result?.connected) {
          onLinked();
          return;
        }
      } catch (err) {
        setDevice(null);
        setError(err.message);
        return;
      }
      timer = setTimeout(tick, Math.max(2, device.interval || 5) * 1000);
    };
    timer = setTimeout(tick, Math.max(2, device.interval || 5) * 1000);
    return () => clearTimeout(timer);
  }, [device]);

  const start = async () => {
    setBusy(true);
    setError("");
    try {
      setDevice(await api("/api/trakt/device", { method: "POST" }));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  if (device) {
    const url = device.verification_url || device.verification_url_complete || "https://trakt.tv/activate";
    return html`<div class="trakt-code-box">
      <span>Open Trakt and enter this code:</span>
      <code>${device.user_code}</code>
      <a class="btn btn-primary" href=${url} target="_blank" rel="noreferrer">Open Trakt</a>
      <small><span class="dot-spinner"></span> Waiting for you to confirm. This page updates by itself.</small>
    </div>`;
  }
  return html`<button type="button" class="btn btn-primary" disabled=${busy} onClick=${start}>Link account</button>
    ${error && html`<span class="set-inline-error" role="alert">${error}</span>`}`;
}

function TraktSection() {
  const { data: status, error } = useResource(R.traktStatus());
  const action = useAction();
  const fileRef = useRef(null);
  const [fileCount, setFileCount] = useState(0);
  if (error) return html`<${ErrorState} error=${error} />`;
  if (!status) return html`<div class="set-loading"></div>`;
  const refresh = () => invalidate("settings:trakt");
  const disconnect = async () => {
    if (!confirm("Disconnect this user from Trakt?")) return "Still connected.";
    await api("/api/trakt", { method: "DELETE" });
    refresh();
    return "Disconnected.";
  };
  const sync = (prune) => async () => {
    const s = await api(`/api/trakt/sync-collection${prune ? "?prune=1" : ""}`, { method: "POST" });
    const parts = [];
    if (s.movies) parts.push(`${s.movies} movies`);
    if (s.episodes) parts.push(`${s.episodes} episodes from ${s.shows} shows`);
    if (s.removed) parts.push(`removed ${s.removed} no longer here`);
    const limit = s.limitReached ? ` Trakt stopped accepting more: a free account caps how much it will hold${s.upgradeUrl ? ` (${s.upgradeUrl})` : ""}.` : "";
    if (!parts.length) return `${s.alreadyCollected ? `Trakt already had ${s.alreadyCollected} of your titles.` : "Nothing to send."}${limit}`;
    return `Collected ${parts.join(" · ")}${s.alreadyCollected ? ` · ${s.alreadyCollected} already there` : ""}${s.skipped ? ` · ${s.skipped} could not be identified` : ""}.${limit}`;
  };
  const upload = async () => {
    const files = fileRef.current?.files;
    if (!files?.length) return "Choose a Trakt export zip or JSON files first.";
    const form = new FormData();
    for (const file of files) form.append("files", file, file.webkitRelativePath || file.name);
    const summary = await api("/api/trakt/import-export-upload", { method: "POST", body: form });
    await afterImport();
    return watchedSummary(summary);
  };
  return html`
    <${Group} title="Account">
      <${Row} title=${status.connected ? "Connected" : "Not connected"}
        description=${status.connected ? (status.expiresAt ? `Token valid until ${formatDate(status.expiresAt)}` : "") : status.configured ? "Link Trakt to sync what you watch, your watchlist and your ratings." : "Set the Trakt client id and secret in the server config."}>
        ${status.configured && (status.connected
          ? html`<${ActionButton} action=${action} name="disconnect" label="Disconnect" danger run=${disconnect} />`
          : html`<${TraktLink} onLinked=${() => { refresh(); toast("Trakt account linked."); }} />`)}
      <//>
    <//>
    ${status.configured && status.connected && html`
      <${Group} title="Import from Trakt" description="Bring what Trakt knows into Cinnamon. Nothing is removed.">
        <${Row} title="Seen status" description="Marks everything you watched on Trakt as seen.">
          <${ActionButton} action=${action} name="seen" label="Import" run=${async () => { const s = await api("/api/trakt/import-watched", { method: "POST" }); await afterImport(); return watchedSummary(s); }} />
        <//>
        <${Row} title="Watchlist">
          <${ActionButton} action=${action} name="watchlist" label="Import" run=${async () => { const s = await api("/api/trakt/import-watchlist", { method: "POST" }); await afterImport(); return listSummary(s); }} />
        <//>
        <${Row} title="Ratings">
          <${ActionButton} action=${action} name="ratings" label="Import" run=${async () => { const s = await api("/api/trakt/import-ratings", { method: "POST" }); await afterImport(); return listSummary(s); }} />
        <//>
        <${Row} title="Not interested" description="Titles you hid from Trakt's recommendations.">
          <${ActionButton} action=${action} name="hidden" label="Import" run=${async () => { const s = await api("/api/trakt/import-recommendation-exclusions", { method: "POST" }); await afterImport(); return `Imported ${s.moviesImported || 0} movies and ${s.showsImported || 0} shows${s.unmatched ? ` · ${s.unmatched} not in this library` : ""}.`; }} />
        <//>
      <//>
      <${Group} title="Send your library to Trakt" description="Marks your libraries as collected, so Trakt stops recommending films you already have. Removing takes back only entries Cinnamon added.">
        <${Row} title="Collection">
          <${ActionButton} action=${action} name="sync" label="Sync library" run=${sync(false)} />
          <${ActionButton} action=${action} name="prune" label="Sync and remove deleted" run=${sync(true)} />
        <//>
      <//>`}
    <${Group} title="Import a Trakt export" description="Upload the export zip or its JSON files. Works without linking an account.">
      <${Row} title=${fileCount ? `${fileCount} file${fileCount === 1 ? "" : "s"} selected` : "Export files"}>
        <input ref=${fileRef} type="file" multiple hidden accept=".zip,.json,application/zip,application/json" onChange=${(e) => setFileCount(e.currentTarget.files.length)} />
        <button type="button" class="btn btn-ghost" onClick=${() => fileRef.current?.click()}>Choose files</button>
        <${ActionButton} action=${action} name="upload" label="Upload" run=${upload} />
      <//>
    <//>
    <${ActionResult} action=${action} />`;
}

// ---- Not interested -----------------------------------------------------------

function HiddenSection() {
  const { data: entries, error } = useResource(R.exclusions());
  if (error) return html`<${ErrorState} error=${error} />`;
  if (!entries) return html`<div class="set-loading"></div>`;
  const restore = async (entry) => {
    setData("exclusions", (list) => list.filter((e) => e.key !== entry.key));
    try {
      if (entry.item) await setItemExcluded(entry.item, false);
      else if (entry.show) await setShowExcluded(entry.show, false);
    } catch {
      invalidate("exclusions");
    }
  };
  if (!entries.length) return html`<${Empty} title="Nothing hidden">Titles you mark as Not interested appear here.<//>`;
  return html`<${Group} title=${`${entries.length} hidden`} description="Left out of every recommendation. Their files stay in the library.">
    <ul class="hidden-list">
      ${entries.map((entry) => {
        const subject = entry.item || entry.show || {};
        return html`<li key=${entry.key}>
          <div class="hidden-art"><${Artwork} item=${subject} kind="poster" width=${160} /></div>
          <div class="set-copy"><strong>${subject.title || "Unavailable title"}</strong>
            <span title=${entry.path}>${[entry.path, formatBytes(entry.sizeBytes)].filter(Boolean).join(" · ")}</span></div>
          <button type="button" class="btn btn-ghost" onClick=${() => restore(entry)}>Restore</button>
        </li>`;
      })}
    </ul>
  <//>`;
}

// ---- Library (admin) ----------------------------------------------------------

function LibrarySection() {
  const { data: libraries } = useResource(R.libraries());
  const { data: statuses } = useResource(R.scanStatus());
  const [starting, setStarting] = useState(false);
  const running = scanRunning(statuses);
  useEffect(() => {
    if (!running) return undefined;
    const timer = setInterval(() => invalidate("settings:scan"), 1500);
    return () => {
      clearInterval(timer);
      refreshLibraryData();
    };
  }, [running]);
  const scan = async () => {
    setStarting(true);
    try {
      await api("/api/scan", { method: "POST" });
      invalidate("settings:scan");
    } catch (err) {
      toast(`Scan not started: ${err.message}`, { tone: "error" });
    } finally {
      setStarting(false);
    }
  };
  return html`<${Group} title="Libraries" description="New files are picked up automatically when the watcher is running.">
    ${(libraries || []).map((library) => {
      const s = (statuses || []).find((x) => x.libraryId === library.id);
      const detail = !s ? "Not scanned yet"
        : s.status === "running" ? `Scanning… ${s.mediaFound || 0} found`
        : `${s.mediaFound || 0} titles · last scan ${formatDate(s.finishedAt)}${s.errors ? ` · ${s.errors} errors` : ""}`;
      return html`<${Row} key=${library.id} title=${library.name} description=${detail} />`;
    })}
    <div class="set-actions"><span class="set-spacer"></span>
      <button type="button" class="btn btn-primary" disabled=${starting || running} onClick=${scan}>${running ? "Scanning…" : "Update libraries"}</button>
    </div>
  <//>`;
}

// ---- Users (admin) ------------------------------------------------------------

function UserTrakt({ user, isCurrent }) {
  const { data: status, error } = useResource(R.userTrakt(user.id));
  const action = useAction();
  const text = error ? error.message
    : !status ? "Checking…"
    : !status.configured ? "Trakt is not configured on this server."
    : !status.connected ? "Not linked. They link their own account under Settings."
    : `Linked${status.expiresAt ? ` · token valid until ${formatDate(status.expiresAt)}` : ""}`;
  return html`<${Row} title="Trakt" description=${text}>
    ${status?.connected && html`<${ActionButton} action=${action} name="import" label="Import seen status" run=${async () => {
      const s = await api(`/api/users/${user.id}/trakt/import-watched`, { method: "POST" });
      if (isCurrent) await afterImport();
      return watchedSummary(s);
    }} />`}
  <//><${ActionResult} action=${action} />`;
}

function UserEditor({ user, me, onSaved }) {
  const isCurrent = Number(user.id) === Number(me.id);
  const [displayName, setDisplayName] = useState(user.displayName || "");
  const [password, setPassword] = useState("");
  const [isAdmin, setIsAdmin] = useState(Boolean(user.isAdmin));
  const [disabled, setDisabled] = useState(Boolean(user.disabled));
  const action = useAction();
  const fileRef = useRef(null);
  const changed = displayName !== (user.displayName || "") || password !== "" || isAdmin !== Boolean(user.isAdmin) || disabled !== Boolean(user.disabled);
  const save = (e) => {
    e.preventDefault();
    action.run("save", async () => {
      await api(`/api/users/${user.id}`, { method: "PUT", body: { displayName, password, isAdmin, disabled: isCurrent ? false : disabled } });
      setPassword("");
      onSaved(isCurrent);
      return "Saved.";
    });
  };
  const uploadAvatar = (file) => action.run("avatar", async () => {
    const data = new FormData();
    data.append("avatar", file);
    await api(`/api/users/${user.id}/avatar`, { method: "POST", body: data });
    onSaved(isCurrent);
    return "Avatar updated.";
  });
  return html`<form class="user-editor" onSubmit=${save}>
    <div class="set-identity">
      <${AvatarImage} user=${user} size=${56} />
      <div><strong>${user.displayName || user.username}</strong><span>${user.username}</span></div>
      <span class="avatar-tools">
        <input ref=${fileRef} type="file" hidden accept="image/jpeg,image/png,image/gif,image/webp" onChange=${(e) => { const f = e.currentTarget.files?.[0]; if (f) uploadAvatar(f); e.currentTarget.value = ""; }} />
        <button type="button" class="btn btn-ghost" onClick=${() => fileRef.current?.click()}>${user.avatar ? "Change avatar" : "Upload avatar"}</button>
        ${user.avatar && html`<button type="button" class="btn btn-text" onClick=${() => action.run("avatar", async () => { await api(`/api/users/${user.id}/avatar`, { method: "DELETE" }); onSaved(isCurrent); return "Avatar removed."; })}>Remove</button>`}
      </span>
    </div>
    <div class="form-grid">
      <${TextField} label="Display name" value=${displayName} onInput=${(e) => setDisplayName(e.currentTarget.value)} />
      <${TextField} label="New password" type="password" autocomplete="new-password" placeholder="Leave empty to keep it" value=${password} onInput=${(e) => setPassword(e.currentTarget.value)} />
    </div>
    <div class="set-rows">
      <${Row} title="Admin" description="Can manage users, libraries and app builds."><${Switch} label="Admin" checked=${isAdmin} onChange=${setIsAdmin} /><//>
      <${Row} title="Disabled" description=${isCurrent ? "You can't disable your own account." : "Blocks sign-in without deleting anything."}><${Switch} label="Disabled" checked=${disabled} disabled=${isCurrent} onChange=${setDisabled} /><//>
      <${UserTrakt} user=${user} isCurrent=${isCurrent} />
    </div>
    <div class="set-actions"><span class="set-spacer"></span>
      <button type="submit" class="btn btn-primary" disabled=${!changed || Boolean(action.busy)}>Save changes</button>
    </div>
    <${ActionResult} action=${action} />
  </form>`;
}

function UserCreate({ onCreated }) {
  const action = useAction();
  const submit = (e) => {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    action.run("create", async () => {
      const created = await api("/api/users", {
        method: "POST",
        body: { username: data.get("username"), displayName: data.get("displayName"), password: data.get("password"), isAdmin: data.get("isAdmin") === "on" },
      });
      form.reset();
      onCreated(created);
      return `Created ${created.username}.`;
    });
  };
  return html`<form class="user-editor" onSubmit=${submit}>
    <h3>New user</h3>
    <div class="form-grid">
      <${TextField} label="Username" name="username" required autocomplete="off" />
      <${TextField} label="Display name" name="displayName" autocomplete="off" />
      <${TextField} label="Password" name="password" type="password" required autocomplete="new-password" />
    </div>
    <label class="check-row"><input type="checkbox" name="isAdmin" /> Admin</label>
    <div class="set-actions"><span class="set-spacer"></span>
      <button type="submit" class="btn btn-primary" disabled=${Boolean(action.busy)}>Create user</button>
    </div>
    <${ActionResult} action=${action} />
  </form>`;
}

function UsersSection({ me }) {
  const { data: users, error } = useResource(R.users());
  const [selected, setSelected] = useState(me.id);
  if (error) return html`<${ErrorState} error=${error} />`;
  if (!users) return html`<div class="set-loading"></div>`;
  const user = selected === "new" ? null : users.find((u) => Number(u.id) === Number(selected)) || users[0];
  const saved = (isCurrent) => {
    invalidate("settings:users");
    if (isCurrent) invalidate("me");
  };
  return html`<div class="users-layout">
    <aside class="user-list">
      ${users.map((u) => html`<button type="button" key=${u.id} class=${cx("user-pick", user && Number(u.id) === Number(user.id) && "is-active")} onClick=${() => setSelected(u.id)}>
        <${AvatarImage} user=${u} size=${36} />
        <span><strong>${u.displayName || u.username}</strong><small>${[u.isAdmin ? "Admin" : "User", u.disabled && "Disabled", Number(u.id) === Number(me.id) && "You"].filter(Boolean).join(" · ")}</small></span>
      </button>`)}
      <button type="button" class=${cx("user-pick", "is-new", selected === "new" && "is-active")} onClick=${() => setSelected("new")}>+ New user</button>
    </aside>
    <div class="user-pane">
      ${user
        ? html`<${UserEditor} key=${`${user.id}:${user.avatar || ""}:${user.displayName || ""}:${user.isAdmin}:${user.disabled}`} user=${user} me=${me} onSaved=${saved} />`
        : html`<${UserCreate} onCreated=${(created) => { invalidate("settings:users"); setSelected(created.id); }} />`}
    </div>
  </div>`;
}

// ---- App builds (admin) -------------------------------------------------------

function AppUpload({ title, app }) {
  const action = useAction();
  const submit = (e) => {
    e.preventDefault();
    const form = e.currentTarget;
    action.run("upload", async () => {
      const data = new FormData(form);
      const file = data.get("apk");
      if (!file?.name) return "Choose an APK first.";
      const updated = await api(`/api/app/${app}/upload`, { method: "POST", body: data });
      invalidate("settings:apps");
      form.reset();
      return `${title} ${updated.versionName} (${updated.versionCode}) uploaded.`;
    });
  };
  return html`<details class="app-upload"><summary>Upload a new build</summary>
    <form onSubmit=${submit}>
      <label class="field"><span>APK file</span><input type="file" name="apk" required accept=".apk,application/vnd.android.package-archive" /></label>
      <label class="field"><span>Release notes</span><textarea name="notes" rows="3" placeholder="What changed in this build"></textarea></label>
      <details><summary>Version override</summary>
        <div class="form-grid">
          <${TextField} label="Version code" name="versionCode" type="number" min="1" placeholder="Read from APK" />
          <${TextField} label="Version name" name="versionName" placeholder="Read from APK" />
        </div>
      </details>
      <div class="set-actions"><span class="set-spacer"></span>
        <button type="submit" class="btn btn-primary" disabled=${Boolean(action.busy)}>${action.busy ? "Uploading…" : `Upload ${title}`}</button>
      </div>
      <${ActionResult} action=${action} />
    </form>
  </details>`;
}

function AppsSection() {
  const { data, error } = useResource(R.appUpdates());
  if (error) return html`<${ErrorState} error=${error} />`;
  if (!data) return html`<div class="set-loading"></div>`;
  const row = (title, app, info) => {
    const description = info?.configured
      ? [`${info.versionName || "?"} (${info.versionCode || "?"})`, formatBytes(info.sizeBytes), info.error].filter(Boolean).join(" · ")
      : info?.error || "No build uploaded yet.";
    return html`<${Row} title=${title} description=${description} stacked><${AppUpload} title=${title} app=${app} /><//>`;
  };
  return html`<${Group} title="Android builds" description="Served to your devices as updates.">
    ${row("Android TV", "tv", data.tv)}
    ${row("Phone Companion", "companion", data.companion)}
  <//>`;
}

// ---- Page ---------------------------------------------------------------------

export function SettingsView({ section = "", user }) {
  const available = SECTIONS.filter((s) => !s.admin || user?.isAdmin);
  const current = available.find((s) => s.id === section) || available[0];
  return html`<main class="page settings">
    <header class="page-head"><h1 class="display">Settings</h1></header>
    <div class="set-layout">
      <nav class="set-nav" aria-label="Settings sections">
        ${available.map((s) => html`<${Link} key=${s.id} href=${paths.settings(s.id)} class=${cx("set-link", s.id === current.id && "is-active")} aria-current=${s.id === current.id ? "page" : undefined}>
          <${Icon} name=${s.icon} size=${18} /> ${s.label}
        <//>`)}
      </nav>
      <div class="set-panel" key=${current.id}>
        <h2 class="set-title">${current.label}</h2>
        ${current.id === "" && html`<${AccountSection} user=${user} />`}
        ${current.id === "playback" && html`<${PlaybackSection} />`}
        ${current.id === "home" && html`<${HomeLayoutSection} />`}
        ${current.id === "trakt" && html`<${TraktSection} />`}
        ${current.id === "hidden" && html`<${HiddenSection} />`}
        ${current.id === "library" && html`<${LibrarySection} />`}
        ${current.id === "users" && html`<${UsersSection} me=${user} />`}
        ${current.id === "apps" && html`<${AppsSection} />`}
      </div>
    </div>
  </main>`;
}
