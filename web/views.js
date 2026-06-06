function posterBlock(item, fallbackTitle, badges = {}) {
  const poster = el("div", "poster");
  if (hasPosterImage(item)) {
    poster.style.backgroundImage = `url(${imageURL(item, "poster")})`;
  } else {
    poster.textContent = (fallbackTitle || "?").slice(0, 1).toUpperCase();
  }
  const badgeStack = posterBadges(badges);
  if (badgeStack) poster.append(badgeStack);
  if (item?.rating) poster.append(ratingBadge(item.rating, "poster-rating"));
  return poster;
}

function posterBadges({ seen = false, watchlisted = false } = {}) {
  if (!seen && !watchlisted) return null;
  const stack = el("div", "poster-badges");
  if (seen) stack.append(el("span", "poster-badge seen", "Seen"));
  if (watchlisted) stack.append(el("span", "poster-badge watchlist", "Watchlist"));
  return stack;
}

function imageURL(item, kind) {
  const id = imageItemID(item, kind);
  const mtime = kind === "backdrop" ? item?.backdropMtimeUnix : item?.posterMtimeUnix;
  return `/api/items/${id}/image/${kind}?v=${encodeURIComponent(`${id}-${mtime || item?.mtimeUnix || 0}`)}`;
}

function hasPosterImage(item) {
  return Boolean(item?.posterPath || item?.posterItemId);
}

function imageItemID(item, kind) {
  if (kind === "backdrop") return item?.backdropItemId || item?.id;
  return item?.posterItemId || item?.id;
}

function ratingBadge(rating, className) {
  return el("div", className, `\u2605 ${Number(rating).toFixed(1)}`);
}

function sourceRatingBadge(label, value) {
  const badge = el("span", "source-rating");
  badge.append(el("span", "source-rating-label", label), el("span", "source-rating-value", value));
  return badge;
}

function itemCard(item) {
  const card = el("button", "item");
  card.type = "button";
  card.addEventListener("click", () => openDetail(item).catch(console.error));

  const poster = posterBlock(item, item.title, { seen: itemSeen(item), watchlisted: itemWatchlisted(item) });

  // Hover overlay
  const overlay = el("div", "poster-overlay");
  const oTitle = el("div", "overlay-title", item.kind === "episode" ? (item.episodeTitle || item.title) : item.title);
  const metaText = item.kind === "episode"
    ? [`S${String(item.seasonNumber || 0).padStart(2, "0")}E${String(item.episodeNumber || 0).padStart(2, "0")}`, fmtDuration(item.durationMs)].filter(Boolean).join(" \u00b7 ")
    : [item.year || "", fmtDuration(item.durationMs)].filter(Boolean).join(" \u00b7 ");
  const oMeta = el("div", "overlay-meta", metaText);
  overlay.append(oTitle, oMeta);

  if (item.rating) {
    overlay.append(ratingBadge(item.rating, "overlay-rating"));
  }
  poster.append(overlay);

  const title = el("div", "title", item.kind === "episode" ? (item.episodeTitle || item.title) : item.title);
  const meta = el("div", "meta", metaText);

  card.append(poster, title, meta);
  return card;
}

function showCard(show) {
  const card = el("button", "item show-card");
  card.type = "button";
  card.addEventListener("click", () => openShow(show).catch(console.error));

  const poster = posterBlock(showPosterSource(show), show.title, {
    seen: showSeen(show),
    watchlisted: showWatchlisted(show),
  });
  const metaText = showCountText(show);

  const overlay = el("div", "poster-overlay");
  overlay.append(el("div", "overlay-title", show.title), el("div", "overlay-meta", metaText));
  poster.append(overlay);

  card.append(poster, el("div", "title", show.title), el("div", "meta", metaText));
  return card;
}

function showRating(show) {
  if (show.rating) return show.rating;
  const rated = (show.episodes || []).find((episode) => episode.rating);
  return rated?.rating || 0;
}

function showPosterSource(show) {
  return {
    ...(show.posterItem || {}),
    id: show.posterItemId || show.posterItem?.id,
    posterItemId: show.posterItemId,
    posterMtimeUnix: show.posterMtimeUnix || show.posterItem?.posterMtimeUnix,
    rating: showRating(show),
  };
}

function showBackdropSource(show) {
  return {
    id: show.backdropItemId,
    backdropItemId: show.backdropItemId,
    backdropMtimeUnix: show.backdropMtimeUnix,
  };
}

function showCountText(show) {
  const episodes = show.episodes || [];
  const seasons = show.seasonCount || new Set(episodes.map((e) => e.seasonNumber).filter(Boolean)).size || 1;
  const episodeCount = show.episodeCount || episodes.length;
  return `${seasons} season${seasons === 1 ? "" : "s"} \u00b7 ${episodeCount} episode${episodeCount === 1 ? "" : "s"}`;
}

function renderGrid(items) {
  const grid = el("div", "grid");
  for (const item of items) grid.append(itemCard(item));
  return grid;
}

async function renderUsers(skipHistory, selectedUserId = 0) {
  stopPlayer();
  activeView = "settings";
  search.value = "";
  renderNav();
  if (!skipHistory) pushState({ view: "users" });
  const users = await api("/api/users");
  const creating = selectedUserId === "new";
  const selectedUser = creating ? null : users.find((user) => Number(user.id) === Number(selectedUserId))
    || users.find((user) => user.username === currentUser?.username)
    || users[0];

  const header = el("div", "view-header users-header settings-hero");
  const headerText = el("div", "settings-hero-copy");
  headerText.append(el("h1", null, "Users"), el("span", null, `${users.length} account${users.length === 1 ? "" : "s"} configured`));
  const add = el("button", "primary", "New User");
  add.type = "button";
  add.addEventListener("click", () => renderUsers(true, "new").catch(console.error));
  header.append(headerText, add);

  const layout = el("div", "users-layout");
  const listCard = el("section", "settings-card user-list-card");
  const listHead = el("div", "settings-card-header");
  listHead.append(el("h2", null, "Accounts"), el("span", null, "Select a user to edit"));
  const list = el("div", "user-list");
  for (const user of users) {
    const row = el("button", !creating && Number(user.id) === Number(selectedUser?.id) ? "user-row selected" : "user-row");
    row.type = "button";
    row.addEventListener("click", () => renderUsers(true, user.id).catch(console.error));
    const display = user.displayName || user.username;
    const avatar = el("div", "user-avatar", initials(display));
    const main = el("div", "user-row-main");
    main.append(el("div", "user-row-name", display), el("div", "user-row-meta", user.username));
    const badges = el("div", "user-row-badges");
    badges.append(el("span", user.isAdmin ? "role-badge admin" : "role-badge", user.isAdmin ? "Admin" : "User"));
    if (currentUser?.username === user.username) badges.append(el("span", "role-badge current", "Current"));
    row.append(
      avatar,
      main,
      badges,
    );
    list.append(row);
  }
  listCard.append(listHead, list);
  layout.append(listCard, creating ? userCreateCard() : userEditorCard(selectedUser));
  const content = el("div", "users-view");
  content.append(header, layout);
  setView(settingsShell("users", content));
}

function userCreateCard() {
  const create = el("section", "settings-card user-create-card");
  const createHead = el("div", "settings-card-header");
  createHead.append(el("h2", null, "New User"), el("span", null, "Create a local Popcorn login"));
  const form = el("form", "user-form");
  form.append(
    inputField("Username", "text", "username", true, "rasi"),
    inputField("Display Name", "text", "displayName", false, "Rasi"),
    inputField("Password", "password", "password", true, "Temporary password"),
  );
  const adminLabel = el("label", "toggle user-admin");
  const adminInput = document.createElement("input");
  adminInput.type = "checkbox";
  adminInput.name = "isAdmin";
  adminLabel.append(adminInput, el("span", null, "Admin"));
  const submit = el("button", "primary", "Create User");
  submit.type = "submit";
  const error = el("div", "form-error");
  form.append(adminLabel, submit, error);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    error.textContent = "";
    submit.disabled = true;
    const data = new FormData(form);
    try {
      const created = await api("/api/users", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          username: data.get("username"),
          displayName: data.get("displayName"),
          password: data.get("password"),
          isAdmin: data.get("isAdmin") === "on",
        }),
      });
      await renderUsers(true, created.id);
    } catch (err) {
      error.textContent = cleanError(err);
    } finally {
      submit.disabled = false;
    }
  });
  create.append(createHead, form);
  return create;
}

function userEditorCard(user) {
  if (!user) return userCreateCard();
  const isCurrent = Number(user.id) === Number(currentUser?.id);
  const card = el("section", "settings-card user-editor-card");
  const head = el("div", "settings-card-header");
  head.append(el("h2", null, "Edit Account"), el("span", null, user.username));

  const identity = el("div", "account-identity compact");
  identity.append(
    el("div", "user-avatar", initials(user.displayName || user.username)),
    el("div", "account-identity-copy", [
      el("strong", null, user.displayName || user.username),
      el("span", null, user.username),
    ]),
  );

  const form = el("form", "user-edit-form");
  const display = inputField("Display Name", "text", "displayName", false, "Display name");
  display.querySelector("input").value = user.displayName || "";
  const password = inputField("New Password", "password", "password", false, "Leave empty to keep current password");

  const adminLabel = el("label", "toggle user-admin");
  const adminInput = document.createElement("input");
  adminInput.type = "checkbox";
  adminInput.name = "isAdmin";
  adminInput.checked = Boolean(user.isAdmin);
  adminLabel.append(adminInput, el("span", null, "Admin"));

  const disabledLabel = el("label", "toggle user-admin");
  const disabledInput = document.createElement("input");
  disabledInput.type = "checkbox";
  disabledInput.name = "disabled";
  disabledInput.checked = Boolean(user.disabled);
  disabledInput.disabled = isCurrent;
  disabledLabel.append(disabledInput, el("span", null, isCurrent ? "Current user cannot be disabled here" : "Disabled"));

  const error = el("div", "form-error");
  const save = el("button", "primary", "Save Changes");
  save.type = "submit";
  form.append(display, password, adminLabel, disabledLabel, save, error);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    error.textContent = "";
    save.disabled = true;
    const data = new FormData(form);
    try {
      const saved = await api(`/api/users/${encodeURIComponent(String(user.id))}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          displayName: data.get("displayName"),
          password: data.get("password"),
          isAdmin: data.get("isAdmin") === "on",
          disabled: isCurrent ? false : data.get("disabled") === "on",
        }),
      });
      if (isCurrent) {
        currentUser = saved;
        renderUserPanel();
      }
      await renderUsers(true, saved.id);
    } catch (err) {
      error.textContent = cleanError(err);
    } finally {
      save.disabled = false;
    }
  });

  card.append(head, identity, form);
  return card;
}

async function renderSettings(skipHistory) {
  stopPlayer();
  activeView = "settings";
  currentShow = null;
  currentSeason = null;
  search.value = "";
  renderNav();
  if (!skipHistory) pushState({ view: "settings" });

  const [traktStatus, appUpdates] = await Promise.all([
    api("/api/trakt/status").catch((err) => ({ configured: false, connected: false, error: cleanError(err) })),
    currentUser?.isAdmin ? api("/api/app/updates").catch((err) => ({ error: cleanError(err) })) : Promise.resolve(null),
  ]);

  const header = el("div", "view-header settings-hero");
  const headerText = el("div", "settings-hero-copy");
  headerText.append(el("h1", null, "Settings"), el("span", null, `${currentUser?.displayName || currentUser?.username || "User"} · Popcorn preferences`));
  header.append(headerText);

  const grid = el("div", "settings-grid");
  grid.append(accountSettingsCard());
  grid.append(traktSettingsCard(traktStatus));
  if (currentUser?.isAdmin) {
    grid.append(adminSettingsCard());
    grid.append(appUpdatesCard(appUpdates));
  }

  const content = el("div", "settings-view");
  content.append(header, grid);
  setView(settingsShell("overview", content));
}

function accountSettingsCard() {
  const card = settingsCard("Account", "Current browser session", "account-card");
  const identity = el("div", "account-identity");
  identity.append(
    el("div", "user-avatar large", initials(currentUser?.displayName || currentUser?.username || "User")),
    el("div", "account-identity-copy", [
      el("strong", null, currentUser?.displayName || currentUser?.username || "Unknown"),
      el("span", null, currentUser?.username || ""),
    ]),
  );
  card.append(identity);
  card.append(settingsRows([
    ["Role", currentUser?.isAdmin ? "Admin" : "User"],
  ]));
  const actions = el("div", "settings-actions");
  const logout = el("button", "secondary", "Logout");
  logout.type = "button";
  logout.addEventListener("click", logoutUser);
  actions.append(logout);
  card.append(actions);
  return card;
}

function adminSettingsCard() {
  const card = settingsCard("Users", "Manage Popcorn accounts", "admin-card");
  card.append(el("p", "settings-copy", "Create local users and assign admin access."));
  const actions = el("div", "settings-actions");
  const users = el("button", "primary", "Manage Users");
  users.type = "button";
  users.addEventListener("click", () => renderUsers().catch(console.error));
  actions.append(users);
  card.append(actions);
  return card;
}

function appUpdatesCard(status) {
  const card = settingsCard("App Updates", "Upload internal Android releases", "updates-card");
  if (status?.error) {
    card.append(el("div", "settings-message error", status.error));
  }
  const grid = el("div", "app-update-grid");
  grid.append(appUploadPanel("Android TV", "tv", status?.tv));
  grid.append(appUploadPanel("Phone Companion", "companion", status?.companion));
  card.append(grid);
  return card;
}

function appUploadPanel(title, app, info) {
  const panel = el("div", "app-update-panel");
  const configured = Boolean(info?.configured);
  const state = el("div", configured ? "settings-status ok" : "settings-status warn");
  state.append(el("span", "settings-status-dot"), el("span", null, configured ? `${info.versionName || "Configured"} (${info.versionCode || "?"})` : "Not configured"));
  panel.append(el("h3", null, title), state);
  if (info?.source || info?.sizeBytes) {
    panel.append(settingsRows([
      ["Source", info.source || ""],
      ["APK size", info.sizeBytes ? formatBytes(info.sizeBytes) : ""],
    ]));
  }
  if (info?.error) panel.append(el("div", "settings-message error", info.error));

  const form = el("form", "app-upload-form");
  const fileField = el("label", "field");
  fileField.append(el("span", null, "APK File"));
  const fileInput = document.createElement("input");
  fileInput.type = "file";
  fileInput.name = "apk";
  fileInput.accept = ".apk,application/vnd.android.package-archive";
  fileInput.required = true;
  fileField.append(fileInput);
  const code = inputField("Version Code Override", "number", "versionCode", false, "Read from APK");
  const codeInput = code.querySelector("input");
  codeInput.min = "1";
  const name = inputField("Version Name Override", "text", "versionName", false, "Read from APK");
  const nameInput = name.querySelector("input");
  const notes = el("label", "field");
  notes.append(el("span", null, "Release Notes"));
  const notesInput = document.createElement("textarea");
  notesInput.name = "notes";
  notesInput.rows = 3;
  notesInput.placeholder = "What changed in this build";
  notes.append(notesInput);
  const advanced = el("details", "app-upload-advanced");
  advanced.append(el("summary", null, "Advanced version override"));
  advanced.append(code, name);
  const output = el("div", "settings-output");
  const submit = el("button", "primary", `Upload ${title}`);
  submit.type = "submit";
  form.append(fileField, notes, advanced, submit, output);
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    runSettingsAction(submit, output, async () => {
      const data = new FormData(form);
      const file = data.get("apk");
      if (!file || !file.name) return "Choose an APK first.";
      const updated = await api(`/api/app/${app}/upload`, { method: "POST", body: data });
      await renderSettings(true);
      return `${title} ${updated.versionName} (${updated.versionCode}) uploaded.`;
    });
  });
  panel.append(form);
  return panel;
}

function settingsShell(active, content) {
  const shell = el("div", "settings-shell settings-page");
  const sidebar = el("aside", "settings-sidebar");
  sidebar.append(el("div", "settings-sidebar-title", "Settings"));
  const nav = el("nav", "settings-sidebar-nav");
  nav.append(settingsNavButton("Overview", active === "overview", () => renderSettings().catch(console.error)));
  if (currentUser?.isAdmin) {
    nav.append(settingsNavButton("Users", active === "users", () => renderUsers().catch(console.error)));
  }
  sidebar.append(nav);
  const main = el("main", "settings-main");
  main.append(content);
  shell.append(sidebar, main);
  return shell;
}

function settingsNavButton(label, selected, onClick) {
  const button = el("button", selected ? "settings-sidebar-item active" : "settings-sidebar-item", label);
  button.type = "button";
  button.addEventListener("click", onClick);
  return button;
}

function traktSettingsCard(status) {
  const configured = Boolean(status?.configured);
  const connected = Boolean(status?.connected);
  const card = settingsCard("Trakt.tv", configured ? "Per-user Trakt integration" : "Server integration is not configured", "trakt-card");
  const state = el("div", connected ? "settings-status ok" : configured ? "settings-status" : "settings-status warn");
  state.append(
    el("span", "settings-status-dot"),
    el("span", null, connected ? "Connected" : configured ? "Not connected" : "Not configured"),
  );
  card.append(state);
  if (status?.expiresAt) {
    card.append(settingsRows([["Token expires", formatDateTime(status.expiresAt)]]));
  }
  if (status?.error) {
    card.append(el("div", "settings-message error", status.error));
  }

  const output = el("div", "settings-output");
  const actions = el("div", "settings-actions");
  const exportField = el("label", "field settings-export-field");
  exportField.append(el("span", null, "Trakt Export File"));
  const exportInput = document.createElement("input");
  exportInput.type = "file";
  exportInput.multiple = true;
  exportInput.accept = ".zip,.json,application/zip,application/json";
  exportField.append(exportInput);
  card.append(exportField, el("p", "settings-copy", "Upload a Trakt export zip, or select the export JSON files. This does not require linking Trakt."));

  const importExport = el("button", "secondary", "Upload Trakt Export");
  importExport.type = "button";
  importExport.addEventListener("click", () => runSettingsAction(importExport, output, async () => {
    if (!exportInput.files.length) return "Choose a Trakt export zip or JSON files first.";
    const form = new FormData();
    for (const file of exportInput.files) form.append("files", file, file.webkitRelativePath || file.name);
    const summary = await api("/api/trakt/import-export-upload", {
      method: "POST",
      body: form,
    });
    await refreshMediaState();
    return renderTraktWatchedSummary(summary);
  }));

  if (configured && !connected) {
    const link = el("button", "primary", "Link Trakt Account");
    link.type = "button";
    link.addEventListener("click", () => startTraktLink(card, output, link).catch((err) => {
      output.textContent = cleanError(err);
      output.classList.add("error");
    }));
    actions.append(link);
  }
  if (configured && connected) {
    const importSeen = el("button", "primary", "Import Seen Status");
    importSeen.type = "button";
    importSeen.addEventListener("click", () => runSettingsAction(importSeen, output, async () => {
      const summary = await api("/api/trakt/import-watched", { method: "POST" });
      await refreshMediaState();
      return renderTraktWatchedSummary(summary);
    }));

    const importWatchlist = el("button", "secondary", "Import Watchlist");
    importWatchlist.type = "button";
    importWatchlist.addEventListener("click", () => runSettingsAction(importWatchlist, output, async () => {
      const summary = await api("/api/trakt/import-watchlist", { method: "POST" });
      await refreshMediaState();
      await fetchWatchlist();
      return renderTraktWatchlistSummary(summary);
    }));

    const disconnect = el("button", "danger", "Disconnect");
    disconnect.type = "button";
    disconnect.addEventListener("click", async () => {
      if (!confirm("Disconnect this Popcorn user from Trakt.tv?")) return;
      await runSettingsAction(disconnect, output, async () => {
        await api("/api/trakt", { method: "DELETE" });
        await renderSettings(true);
        return "Disconnected Trakt account.";
      });
    });
    actions.append(importSeen, importWatchlist, disconnect);
  }
  actions.append(importExport);
  if (!configured) {
    card.append(el("p", "settings-copy", "Set the Trakt client id and secret in the server config to enable direct Trakt linking and API imports."));
  }
  card.append(actions, output);
  return card;
}

async function startTraktLink(card, output, button) {
  output.className = "settings-output";
  output.textContent = "Requesting Trakt device code...";
  button.disabled = true;
  try {
    const device = await api("/api/trakt/device", { method: "POST" });
    output.innerHTML = "";
    const linkBox = el("div", "trakt-link-box");
    const code = el("div", "trakt-code", device.user_code || "");
    const url = device.verification_url || device.verification_url_complete || "https://trakt.tv/activate";
    const open = el("a", "secondary link-button", "Open Trakt");
    open.href = url;
    open.target = "_blank";
    open.rel = "noreferrer";
    linkBox.append(
      el("div", "settings-copy", "Open Trakt, enter this code, then confirm here."),
      code,
      open,
    );
    const confirmBtn = el("button", "primary", "I Authorized Popcorn");
    confirmBtn.type = "button";
    confirmBtn.addEventListener("click", () => finishTraktLink(device.device_code, confirmBtn, output).catch((err) => {
      output.textContent = cleanError(err);
      output.classList.add("error");
    }));
    linkBox.append(confirmBtn);
    output.append(linkBox);
  } finally {
    button.disabled = false;
  }
}

async function finishTraktLink(deviceCode, button, output) {
  if (!deviceCode) return;
  await runSettingsAction(button, output, async () => {
    const result = await api("/api/trakt/device/token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceCode }),
    });
    if (result?.pending) return result.message || "Trakt authorization is still pending.";
    await renderSettings(true);
    return "Trakt account linked.";
  });
}

async function runSettingsAction(button, output, action) {
  const old = button.textContent;
  button.disabled = true;
  output.className = "settings-output";
  output.textContent = "Working...";
  try {
    const result = await action();
    output.textContent = "";
    if (result instanceof Node) output.append(result);
    else output.textContent = String(result || "Done.");
  } catch (err) {
    output.classList.add("error");
    output.textContent = cleanError(err);
  } finally {
    button.disabled = false;
    button.textContent = old;
  }
}

function renderTraktWatchedSummary(summary) {
  const rows = [
    ["Movies", `${summary.moviesMatched || 0}/${summary.moviesSeen || 0} matched`],
    ["Episodes", `${summary.episodesMatched || 0}/${summary.episodesSeen || 0} matched`],
    ["Items marked", String(summary.itemsMarked || 0)],
    ["Unmatched", `${(summary.moviesUnmatched || 0) + (summary.episodesUnmatched || 0)}`],
  ];
  const sources = summary.debug?.traktSources || {};
  if (sources.exportMovies || sources.exportHistory || sources.allHistoryMovies || sources.syncWatchedMovies || sources.userWatchedMovies || sources.historyMovies) {
    rows.push(["Source movie rows", String(
      (sources.exportMovies?.items || 0) +
      (sources.syncWatchedMovies?.items || 0) +
      (sources.userWatchedMovies?.items || 0) +
      (sources.historyMovies?.items || 0) +
      (sources.allHistoryMovies?.items || 0),
    )]);
    rows.push(["Source history rows", String((sources.exportHistory?.items || 0) + (sources.allHistory?.items || 0))]);
    if (sources.exportEpisodes) rows.push(["Source episode rows", String(sources.exportEpisodes.items || 0)]);
  }
  const samples = el("div", null);
  const matched = sampleBlock("Matched movies", summary.debug?.matchedMovies);
  const unmatched = sampleBlock("Unmatched movies", summary.debug?.unmatchedMovies);
  if (matched) samples.append(matched);
  if (unmatched) samples.append(unmatched);
  return summaryBlock(rows, samples.childElementCount ? samples : null);
}

function renderTraktWatchlistSummary(summary) {
  return summaryBlock([
    ["Entries", String(summary.entriesSeen || 0)],
    ["Movies", `${summary.moviesMatched || 0}/${summary.moviesSeen || 0} matched`],
    ["Shows", `${summary.showsMatched || 0}/${summary.showsSeen || 0} matched`],
    ["Episodes", `${summary.episodesMatched || 0}/${summary.episodesSeen || 0} matched`],
    ["Marked", `${(summary.itemsMarked || 0) + (summary.showsMarked || 0)}`],
  ], sampleBlock("Matched", summary.matched));
}

function summaryBlock(rows, extra) {
  const wrap = el("div", "summary-block");
  wrap.append(settingsRows(rows));
  if (extra) wrap.append(extra);
  return wrap;
}

function sampleBlock(title, values) {
  const list = (values || []).filter(Boolean).slice(0, 12);
  if (!list.length) return null;
  const block = el("details", "summary-samples");
  block.append(el("summary", null, title));
  const ul = el("ul", null);
  for (const value of list) ul.append(el("li", null, value));
  block.append(ul);
  return block;
}

function settingsCard(title, subtitle, extraClass = "") {
  const card = el("section", `settings-card ${extraClass}`.trim());
  const header = el("div", "settings-card-header");
  header.append(el("h2", null, title), el("span", null, subtitle || ""));
  card.append(header);
  return card;
}

function settingsRows(rows) {
  const list = el("dl", "settings-rows");
  for (const [label, value] of rows) {
    list.append(el("dt", null, label), el("dd", null, value || "—"));
  }
  return list;
}

function formatDateTime(value) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value || "";
  return d.toLocaleString();
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value <= 0) return "";
  if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${value} B`;
}

function inputField(label, type, name, required, placeholder = "") {
  const field = el("label", "field");
  field.append(el("span", null, label));
  const input = document.createElement("input");
  input.type = type;
  input.name = name;
  input.required = required;
  input.placeholder = placeholder;
  field.append(input);
  return field;
}

function initials(value) {
  const parts = String(value || "U").trim().split(/\s+/).filter(Boolean);
  const first = parts[0]?.[0] || "U";
  const second = parts.length > 1 ? parts[parts.length - 1][0] : "";
  return `${first}${second}`.toUpperCase();
}

function toggleActionButton({ active, activeLabel, inactiveLabel, onToggle, className = "secondary" }) {
  const button = el("button", active ? `${className} active` : className, active ? activeLabel : inactiveLabel);
  button.type = "button";
  button.setAttribute("aria-pressed", active ? "true" : "false");
  button.addEventListener("click", async () => {
    const next = button.getAttribute("aria-pressed") !== "true";
    const oldLabel = button.textContent;
    button.disabled = true;
    button.textContent = "Working...";
    try {
      await onToggle(next);
      button.setAttribute("aria-pressed", next ? "true" : "false");
      button.classList.toggle("active", next);
      button.textContent = next ? activeLabel : inactiveLabel;
    } catch (err) {
      button.textContent = cleanError(err) || oldLabel;
      setTimeout(() => { button.textContent = oldLabel; }, 2200);
    } finally {
      button.disabled = false;
    }
  });
  return button;
}

/* ── Episode Row ── */
function episodeRow(episode) {
  const row = el("button", "episode-row");
  row.type = "button";
  row.addEventListener("click", () => openDetail(episode, false, { show: currentShow, season: currentSeason }).catch(console.error));

  // Thumbnail with episode number badge
  const thumb = el("div", "ep-thumb");
  if (episode.backdropPath) {
    thumb.style.backgroundImage = `url(${imageURL(episode, "backdrop")})`;
  } else if (episode.posterPath) {
    thumb.style.backgroundImage = `url(${imageURL(episode, "poster")})`;
  }
  const epNum = String(episode.episodeNumber || 0).padStart(2, "0");
  thumb.append(el("span", "ep-thumb-badge", epNum));
  const badges = posterBadges({ seen: itemSeen(episode), watchlisted: itemWatchlisted(episode) });
  if (badges) thumb.append(badges);

  // Info
  const info = el("div", "ep-info");
  info.append(el("div", "ep-title", episode.episodeTitle || episode.title));
  if (episode.overview) {
    info.append(el("div", "ep-overview", episode.overview));
  }

  // Meta (duration + rating)
  const meta = el("div", "ep-meta");
  meta.append(el("span", "ep-duration", fmtDuration(episode.durationMs)));
  if (episode.rating) {
    meta.append(el("span", "ep-rating", `\u2605 ${Number(episode.rating).toFixed(1)}`));
  }

  row.append(thumb, info, meta);
  return row;
}

/* ── Detail View ── */
async function openDetail(item, skipHistory, parent = {}) {
  currentItem = item;
  if (item.kind === "episode") {
    if (parent.show) currentShow = parent.show;
    if (parent.season !== undefined && parent.season !== null) currentSeason = Number(parent.season || 0);
    else currentSeason = currentSeason ?? Number(item.seasonNumber || 0);
  }
  selectedAudio = "";
  selectedSubtitle = "";
  const [streams, externalRatings] = await Promise.all([
    api(`/api/items/${item.id}/streams`),
    api(`/api/items/${item.id}/ratings`).catch(() => null),
  ]);
  const audioStreams = streams.filter((s) => s.type === "audio");
  const subtitleStreams = streams.filter((s) => s.type === "subtitle");
  const defaultAudio = audioStreams.find((s) => s.default) || audioStreams[0];
  if (defaultAudio) selectedAudio = String(defaultAudio.index);

  stopPlayer();
  if (!skipHistory) {
    const detailState = { view: "detail", libraryId: activeLibraryId, itemId: item.id };
    if (item.kind === "episode") {
      const showTitle = currentShow?.title === item.showTitle ? currentShow.title : item.showTitle || "";
      detailState.showTitle = showTitle;
      detailState.season = currentSeason ?? Number(item.seasonNumber || 0);
    }
    pushState(detailState);
  }

  const frag = document.createDocumentFragment();
  const library = activeLibrary();

  // Breadcrumb
  const crumbs = [{ label: library?.name || "Library", action: () => loadLibraryPage().catch(console.error) }];
  if (item.kind === "episode" && currentShow) {
    crumbs.push({ label: currentShow.title, action: () => openShow(currentShow).catch(console.error) });
    if (currentSeason !== null) {
      crumbs.push({ label: currentSeason ? `Season ${currentSeason}` : "Specials", action: () => openSeason(currentShow, currentSeason).catch(console.error) });
    }
  }
  crumbs.push({ label: item.kind === "episode" ? (item.episodeTitle || item.title) : item.title });
  frag.append(makeBreadcrumb(crumbs));

  // Hero backdrop
  if (item.backdropPath) {
    const hero = el("div", "detail-hero");
    const bd = el("div", "detail-backdrop");
    bd.style.backgroundImage = `url(${imageURL(item, "backdrop")})`;
    hero.append(bd, el("div", "detail-backdrop-overlay"));
    frag.append(hero);
  }

  // Detail layout
  const detail = el("article", "detail");
  const poster = posterBlock(item, item.title, { seen: itemSeen(item), watchlisted: itemWatchlisted(item) });
  poster.classList.add("detail-poster");

  const body = el("div", "detail-body");
  body.append(el("h1", null, item.title));

  // Meta line
  const meta = el("div", "detail-meta");
  const parts = detailMetaParts(item);
  parts.forEach((part, i) => {
    if (i > 0) meta.append(el("span", "meta-dot"));
    meta.append(el("span", null, part));
  });
  const hasSourceRatings = Boolean(externalRatings?.imdbRating || externalRatings?.tmdbRating || externalRatings?.rottenTomatoesRating);
  if (item.rating && !hasSourceRatings) {
    meta.append(el("span", "detail-rating", `\u2605 ${Number(item.rating).toFixed(1)}`));
  }
  if (externalRatings?.imdbRating) {
    meta.append(sourceRatingBadge("IMDb", Number(externalRatings.imdbRating).toFixed(1)));
  }
  if (externalRatings?.tmdbRating) {
    meta.append(sourceRatingBadge("TMDb", Number(externalRatings.tmdbRating).toFixed(1)));
  }
  if (externalRatings?.rottenTomatoesRating) {
    meta.append(sourceRatingBadge("RT", `${externalRatings.rottenTomatoesRating}%`));
  }
  body.append(meta);

  // Genres
  if (item.genres) {
    const genreList = item.genres.split(/[,/]/).map((g) => g.trim()).filter(Boolean);
    if (genreList.length) {
      const genres = el("div", "detail-genres");
      for (const g of genreList) genres.append(el("span", "genre-pill", g));
      body.append(genres);
    }
  }

  // Overview
  if (item.overview || item.tagline) {
    body.append(el("p", "overview", item.overview || item.tagline));
  }

  // Stream selectors
  const selectors = el("div", "stream-selectors");
  selectors.append(
    selectField("Audio", audioStreams, selectedAudio, (v) => { selectedAudio = v; }),
    selectField("Subtitles", subtitleStreams, selectedSubtitle, (v) => { selectedSubtitle = v; }, true),
  );
  body.append(selectors);

  // Play button
  const actions = el("div", "detail-actions");
  const playBtn = el("button", "primary detail-play", "Play");
  playBtn.type = "button";
  playBtn.addEventListener("click", () => play(item));
  const seenBtn = toggleActionButton({
    active: itemSeen(item),
    activeLabel: "Seen",
    inactiveLabel: "Mark Seen",
    onToggle: (seen) => setItemSeen(item, seen),
  });
  const watchlistBtn = toggleActionButton({
    active: itemWatchlisted(item),
    activeLabel: "In Watchlist",
    inactiveLabel: "Add Watchlist",
    onToggle: (watchlisted) => setItemWatchlisted(item, watchlisted),
  });
  actions.append(playBtn, seenBtn, watchlistBtn);
  body.append(actions);

  detail.append(poster, body);
  frag.append(detail);
  setView(frag);
}

/* ── Show View ── */
async function openShow(show, skipHistory) {
  currentShow = show;
  currentSeason = null;
  stopPlayer();
  if (!skipHistory) pushState({ view: "show", libraryId: show.libraryId || activeLibraryId, showTitle: show.title });

  const frag = document.createDocumentFragment();
  const library = activeLibrary();

  frag.append(makeBreadcrumb([
    { label: library?.name || "Library", action: () => loadLibraryPage().catch(console.error) },
    { label: show.title },
  ]));

  // Hero backdrop
  const backdrop = showBackdropSource(show);
  if (show.backdropItemId) {
    const hero = el("div", "detail-hero");
    const bd = el("div", "detail-backdrop");
    bd.style.backgroundImage = `url(${imageURL(backdrop, "backdrop")})`;
    hero.append(bd, el("div", "detail-backdrop-overlay"));
    frag.append(hero);
  }

  // Show header
  const header = el("div", "show-detail");
  const poster = posterBlock(showPosterSource(show), show.title, { seen: showSeen(show), watchlisted: showWatchlisted(show) });
  poster.classList.add("detail-poster");

  const body = el("div", "detail-body");
  body.append(el("h1", null, show.title));

  body.append(el("div", "detail-meta", showCountText(show)));

  if (show.overview) body.append(el("p", "overview", show.overview));

  // Genre pills
  if (show.genres) {
    const genreList = show.genres.split(/[,/]/).map((g) => g.trim()).filter(Boolean);
    if (genreList.length) {
      const genres = el("div", "detail-genres");
      for (const g of genreList) genres.append(el("span", "genre-pill", g));
      body.append(genres);
    }
  }

  const actions = el("div", "detail-actions");
  actions.append(
    toggleActionButton({
      active: showSeen(show),
      activeLabel: "Seen",
      inactiveLabel: "Mark Seen",
      onToggle: (seen) => setShowSeen(show, seen),
    }),
    toggleActionButton({
      active: showWatchlisted(show),
      activeLabel: "In Watchlist",
      inactiveLabel: "Add Watchlist",
      onToggle: (watchlisted) => setShowWatchlisted(show, watchlisted),
    }),
  );
  body.append(actions);

  header.append(poster, body);
  frag.append(header, await seasonBrowser(show));
  setView(frag);
}

/* ── Season Browser ── */
async function seasonBrowser(show) {
  const wrap = el("div", "season-browser");
  const seasons = await api(`/api/tv/seasons?libraryId=${encodeURIComponent(show.libraryId || activeLibraryId)}&showTitle=${encodeURIComponent(show.title)}`).catch(() => groupSeasons(show.episodes || []));
  const grid = el("div", "season-grid");

  for (const season of seasons) {
    grid.append(seasonCard(show, season));
  }

  wrap.append(sectionTitle("Seasons", `${seasons.length}`), grid);
  return wrap;
}

function seasonCard(show, season) {
  const seasonNumber = Number(season.seasonNumber ?? season.number ?? 0);
  const label = season.title || (seasonNumber ? `Season ${seasonNumber}` : "Specials");
  const card = el("button", "season-card");
  card.type = "button";
  card.addEventListener("click", () => openSeason(show, seasonNumber).catch(console.error));

  const poster = el("div", "season-poster");
  const posterID = season.posterItemId || season.posterItemID || season.id;
  if (posterID) {
    poster.style.backgroundImage = `url(/api/items/${posterID}/image/season?v=${encodeURIComponent(`${posterID}-${season.posterMtimeUnix || 0}`)})`;
  } else {
    poster.textContent = String(seasonNumber || "*");
  }
  if (season.rating) poster.append(ratingBadge(season.rating, "poster-rating"));
  card.append(
    poster,
    el("div", "season-title", label),
    el("div", "season-meta", `${season.episodeCount || season.episodes?.length || 0} episodes${season.durationMs ? ` \u00b7 ${fmtDuration(season.durationMs)}` : ""}`),
  );
  return card;
}

async function openSeason(show, seasonNumber, skipHistory) {
  currentShow = show;
  currentSeason = Number(seasonNumber || 0);
  stopPlayer();
  if (!skipHistory) pushState({ view: "season", libraryId: show.libraryId || activeLibraryId, showTitle: show.title, season: currentSeason });

  const episodes = await api(`/api/tv/episodes?libraryId=${encodeURIComponent(show.libraryId || activeLibraryId)}&showTitle=${encodeURIComponent(show.title)}&season=${encodeURIComponent(String(currentSeason))}`);
  const frag = document.createDocumentFragment();
  const library = activeLibrary();
  const seasonLabel = currentSeason ? `Season ${currentSeason}` : "Specials";
  frag.append(makeBreadcrumb([
    { label: library?.name || "Library", action: () => loadLibraryPage().catch(console.error) },
    { label: show.title, action: () => openShow(show).catch(console.error) },
    { label: seasonLabel },
  ]));
  const header = el("div", "view-header");
  const seasonSeen = episodes.length > 0 && episodes.every((episode) => itemSeen(episode));
  const seasonActions = el("div", "view-header-actions");
  seasonActions.append(toggleActionButton({
    active: seasonSeen,
    activeLabel: "Season Seen",
    inactiveLabel: "Mark Season Seen",
    onToggle: (seen) => setSeasonSeen(show, currentSeason, seen, episodes),
  }));
  header.append(el("h1", null, `${show.title} / ${seasonLabel}`), el("span", null, `${episodes.length} episodes`), seasonActions);
  frag.append(header);
  const list = el("div", "episode-list season-episode-list");
  for (const episode of episodes) list.append(episodeRow(episode));
  frag.append(list);
  setView(frag);
}

function groupSeasons(episodes) {
  const seasons = new Map();
  for (const episode of episodes) {
    const key = episode.seasonNumber || 0;
    if (!seasons.has(key)) seasons.set(key, []);
    seasons.get(key).push(episode);
  }
  return [...seasons.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([number, eps]) => ({ number, episodes: eps }));
}

/* ── Helpers ── */
function detailMetaParts(item) {
  const parts = [];
  if (item.kind === "episode") {
    if (item.showTitle) parts.push(item.showTitle);
    if (item.seasonNumber || item.episodeNumber) {
      parts.push(`S${String(item.seasonNumber || 0).padStart(2, "0")}E${String(item.episodeNumber || 0).padStart(2, "0")}`);
    }
  } else if (item.year) {
    parts.push(String(item.year));
  }
  if (item.durationMs) {
    parts.push(fmtDuration(item.durationMs));
    parts.push(fmtEndsAround(item.durationMs));
  }
  if (item.videoCodec) parts.push(item.videoCodec.toUpperCase());
  if (item.width && item.height) parts.push(`${item.width}\u00d7${item.height}`);
  return parts;
}

function selectField(label, streams, value, onChange, allowNone = false) {
  const field = el("label", "field stream-field");
  field.append(el("span", null, label));
  const select = document.createElement("select");
  if (allowNone) {
    const none = document.createElement("option");
    none.value = "";
    none.textContent = "None";
    select.append(none);
  }
  for (const stream of streams) {
    const option = document.createElement("option");
    option.value = String(stream.index);
    option.textContent = streamLabel(stream);
    select.append(option);
  }
  select.value = value;
  select.addEventListener("change", () => onChange(select.value));
  field.append(select);
  return field;
}

function streamLabel(stream) {
  return [
    stream.language || "und",
    stream.title || "",
    stream.codec || "",
    stream.default ? "default" : "",
    stream.forced ? "forced" : "",
  ].filter(Boolean).join(" \u00b7 ");
}
