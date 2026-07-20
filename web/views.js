function posterBlock(item, fallbackTitle, badges = {}, width = 400) {
  const poster = el("div", "poster");
  if (hasPosterImage(item)) {
    poster.style.backgroundImage = `url(${imageURL(item, "poster", width)})`;
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

/* imageURL builds a versioned artwork URL. Pass width to request a reduced
   server-side thumbnail (walls, episode lists); omit it for full-bleed uses
   like page backdrops and the home hero. */
function imageURL(item, kind, width) {
  const id = imageItemID(item, kind);
  const mtime = kind === "backdrop" ? item?.backdropMtimeUnix : item?.posterMtimeUnix;
  const version = encodeURIComponent(`${id}-${mtime || item?.mtimeUnix || 0}`);
  return `/api/items/${id}/image/${kind}?v=${version}${width ? `&w=${width}` : ""}`;
}

function hasPosterImage(item) {
  return Boolean(item?.posterPath || item?.posterItemId);
}

function imageItemID(item, kind) {
  if (kind === "backdrop") return item?.backdropItemId || item?.id;
  return item?.posterItemId || item?.id;
}

/* userRatingControl renders a self-updating 1-10 star row for a personal
   rating. Clicking the current rating again clears it. */
function userRatingControl(getCurrent, save) {
  const wrap = el("div", "user-rating");
  const render = () => {
    wrap.innerHTML = "";
    const current = getCurrent();
    wrap.append(el("span", "user-rating-label", current ? `Your rating ${current}/10` : "Rate"));
    const stars = el("div", "user-rating-stars");
    for (let i = 1; i <= 10; i++) {
      const btn = el("button", i <= current ? "rating-star active" : "rating-star", "\u2605");
      btn.type = "button";
      btn.title = i === current ? "Clear rating" : `${i}/10`;
      btn.addEventListener("click", async () => {
        try {
          await save(i === current ? 0 : i);
        } catch (err) {
          console.error(err);
        }
        render();
      });
      stars.append(btn);
    }
    wrap.append(stars);
  };
  render();
  return wrap;
}

function ratingBadge(rating, className) {
  return el("div", className, `\u2605 ${Number(rating).toFixed(1)}`);
}

function posterProgressBar(item) {
  const fraction = typeof resumeFraction === "function" ? resumeFraction(item) : 0;
  if (!fraction) return null;
  const wrap = el("div", "poster-progress");
  const fill = el("div", "poster-progress-fill");
  fill.style.width = `${Math.round(fraction * 100)}%`;
  wrap.append(fill);
  return wrap;
}

/* pageBackdrop renders the full-bleed artwork behind a detail/show page.
   Returns null when the item has no backdrop so pages degrade gracefully. */
function pageBackdrop(source) {
  if (!source?.backdropItemId && !source?.backdropPath) return null;
  const wrap = el("div", "page-backdrop");
  const img = el("div", "page-backdrop-img");
  img.style.backgroundImage = `url(${imageURL(source, "backdrop")})`;
  wrap.append(img);
  return wrap;
}

/* showYears renders a show's run as "2019" or "2009–2012". */
function showYears(show) {
  const start = Number(show?.year || 0);
  if (!start) return "";
  const end = Number(show?.endYear || 0);
  return end > start ? `${start}–${end}` : String(start);
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
  const progress = posterProgressBar(item);
  if (progress) poster.append(progress);

  const title = el("div", "title", item.kind === "episode" ? (item.episodeTitle || item.title) : item.title);
  const meta = el("div", "meta", metaText);

  card.append(poster, title, meta);
  const genre = genreList(item.genres)[0];
  if (genre && item.kind !== "episode") card.append(el("div", "card-genre", genre));
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
  const seasons = show.seasonCount || 0;
  const cardMeta = [showYears(show), seasons ? `${seasons} season${seasons === 1 ? "" : "s"}` : ""].filter(Boolean).join(" · ") || showCountText(show);

  const overlay = el("div", "poster-overlay");
  overlay.append(el("div", "overlay-title", show.title), el("div", "overlay-meta", [showYears(show), showCountText(show)].filter(Boolean).join(" · ")));
  poster.append(overlay);

  card.append(poster, el("div", "title", show.title), el("div", "meta", cardMeta));
  const genre = genreList(show.genres)[0];
  if (genre) card.append(el("div", "card-genre", genre));
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

/* ── A-Z rail ── */
function mountAlphabetRail(rail) {
  document.getElementById("alphaRail")?.remove();
  if (rail) {
    rail.id = "alphaRail";
    document.body.append(rail);
  }
}

function alphabetRail(entries, activeLetter, onJump) {
  const rail = el("nav", "alpha-rail");
  rail.setAttribute("aria-label", "Jump to letter");
  for (const entry of entries) {
    const letter = String(entry.letter || "#");
    const btn = el("button", letter === activeLetter ? "alpha-letter active" : "alpha-letter", letter.toUpperCase());
    btn.type = "button";
    btn.title = `${letter.toUpperCase()} · ${entry.count}`;
    btn.addEventListener("click", () => onJump(entry));
    rail.append(btn);
  }
  return rail;
}

function renderGrid(items) {
  const grid = el("div", "grid");
  for (const item of items) grid.append(itemCard(item));
  return grid;
}

/* ── Home hero (rotating suggestion) ── */
function homeHero(item, kick) {
  const isShow = !item.kind && (item.episodeCount != null || item.seasonCount != null);
  const hero = el("section", "home-hero");

  const bg = el("div", "home-hero-bg");
  const backdropSrc = isShow ? showBackdropSource(item) : item;
  if (backdropSrc.backdropItemId || backdropSrc.backdropPath) {
    const img = document.createElement("img");
    img.src = imageURL(backdropSrc, "backdrop");
    bg.append(img);
  }
  hero.append(bg);

  const content = el("div", "home-hero-content");
  content.append(el("div", "home-hero-kick", kick || (item.kind === "episode" ? "Continue watching" : (isShow ? "Featured series" : "Featured"))));
  content.append(el("h1", "home-hero-title", item.kind === "episode" ? (item.showTitle || item.title) : item.title));

  const meta = el("div", "hmeta");
  const rating = item.rating || (isShow ? showRating(item) : 0);
  if (rating) meta.append(el("span", "hstar", `★ ${Number(rating).toFixed(1)}`));
  const parts = [];
  if (item.kind === "episode") {
    if (item.seasonNumber || item.episodeNumber) parts.push(`S${String(item.seasonNumber || 0).padStart(2, "0")}E${String(item.episodeNumber || 0).padStart(2, "0")}`);
  } else if (isShow) {
    const years = showYears(item);
    if (years) parts.push(years);
  } else if (item.year) parts.push(String(item.year));
  if (isShow) parts.push(showCountText(item));
  if (item.durationMs) parts.push(fmtDuration(item.durationMs));
  parts.forEach((p) => { if (meta.childElementCount) meta.append(el("span", "dot-sep")); meta.append(el("span", null, p)); });
  content.append(meta);

  if (item.overview) content.append(el("p", "home-hero-syn", item.overview));

  const actions = el("div", "home-hero-actions");
  if (isShow) {
    const view = el("button", "primary", "View show");
    view.type = "button";
    view.addEventListener("click", () => openShow(item).catch(console.error));
    actions.append(view);
  } else {
    const resuming = typeof resumeFraction === "function" && resumeFraction(item) > 0;
    const playBtn = el("button", "primary", resuming ? "▶ Resume" : "▶ Play");
    playBtn.type = "button";
    playBtn.addEventListener("click", () => play(item));
    const info = el("button", "secondary", "More info");
    info.type = "button";
    info.addEventListener("click", () => openDetail(item).catch(console.error));
    actions.append(playBtn, info);
  }
  content.append(actions);
  hero.append(content);
  return hero;
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
  headerText.append(el("h1", null, "Settings"));
  header.append(headerText);

  const content = el("div", "settings-view");
  content.append(header);

  // Account
  const account = settingsSection("Account");
  const logout = el("button", "secondary", "Logout");
  logout.type = "button";
  logout.addEventListener("click", logoutUser);
  account.append(settingRow({
    title: currentUser?.displayName || currentUser?.username || "User",
    description: [currentUser?.username, currentUser?.isAdmin ? "Admin" : "User"].filter(Boolean).join(" · "),
    control: logout,
  }));
  content.append(account);

  content.append(traktSection(traktStatus));

  if (currentUser?.isAdmin) {
    const users = settingsSection("Users");
    const manage = el("button", "secondary", "Manage users");
    manage.type = "button";
    manage.addEventListener("click", () => renderUsers().catch(console.error));
    users.append(settingRow({
      title: "User accounts",
      description: "Create local logins and assign admin access.",
      control: manage,
    }));
    content.append(users);
    content.append(appUpdatesSection(appUpdates));
  }

  setView(settingsShell("overview", content));
}

/* One setting per row: label and explanation on the left, the control on the
   right. Sections are plain headings above a run of rows. */
function settingsSection(title, subtitle) {
  const section = el("section", "settings-section");
  const head = el("div", "settings-section-head");
  head.append(el("h2", null, title));
  if (subtitle) head.append(el("span", null, subtitle));
  section.append(head);
  return section;
}

function settingRow({ title, description, control, stacked = false }) {
  const row = el("div", stacked ? "setting-row stacked" : "setting-row");
  const copy = el("div", "setting-copy");
  copy.append(el("strong", null, title));
  if (description) copy.append(el("span", null, description));
  const ctrl = el("div", "setting-control");
  if (Array.isArray(control)) control.forEach((node) => node && ctrl.append(node));
  else if (control) ctrl.append(control);
  row.append(copy, ctrl);
  return row;
}

function traktSection(status) {
  const configured = Boolean(status?.configured);
  const connected = Boolean(status?.connected);
  const section = settingsSection("Trakt.tv");
  const output = el("div", "settings-output");

  let statusControl = null;
  if (configured && !connected) {
    const link = el("button", "primary", "Link account");
    link.type = "button";
    link.addEventListener("click", () => startTraktLink(section, output, link).catch((err) => {
      output.textContent = cleanError(err);
      output.classList.add("error");
    }));
    statusControl = link;
  } else if (configured && connected) {
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
    statusControl = disconnect;
  }
  section.append(settingRow({
    title: "Account link",
    description: connected
      ? `Connected${status?.expiresAt ? ` · token valid until ${formatDateTime(status.expiresAt)}` : ""}`
      : configured ? "Not connected." : "Not configured — set the Trakt client id and secret in the server config.",
    control: statusControl,
  }));
  if (status?.error) section.append(el("div", "settings-message error", status.error));

  if (configured && connected) {
    const importSeen = el("button", "secondary", "Import seen status");
    importSeen.type = "button";
    importSeen.addEventListener("click", () => runSettingsAction(importSeen, output, async () => {
      const summary = await api("/api/trakt/import-watched", { method: "POST" });
      await refreshMediaState();
      return renderTraktWatchedSummary(summary);
    }));
    const importWatchlist = el("button", "secondary", "Import watchlist");
    importWatchlist.type = "button";
    importWatchlist.addEventListener("click", () => runSettingsAction(importWatchlist, output, async () => {
      const summary = await api("/api/trakt/import-watchlist", { method: "POST" });
      await refreshMediaState();
      await fetchWatchlist();
      return renderTraktWatchlistSummary(summary);
    }));
    const importRatings = el("button", "secondary", "Import ratings");
    importRatings.type = "button";
    importRatings.addEventListener("click", () => runSettingsAction(importRatings, output, async () => {
      const summary = await api("/api/trakt/import-ratings", { method: "POST" });
      await refreshUserRatings();
      return renderTraktWatchlistSummary(summary);
    }));
    section.append(settingRow({
      title: "Import from Trakt",
      description: "Pull your watched history, watchlist or ratings into Popcorn.",
      control: [importSeen, importWatchlist, importRatings],
    }));
  }

  const exportInput = document.createElement("input");
  exportInput.type = "file";
  exportInput.multiple = true;
  exportInput.accept = ".zip,.json,application/zip,application/json";
  const importExport = el("button", "secondary", "Upload");
  importExport.type = "button";
  importExport.addEventListener("click", () => runSettingsAction(importExport, output, async () => {
    if (!exportInput.files.length) return "Choose a Trakt export zip or JSON files first.";
    const form = new FormData();
    for (const file of exportInput.files) form.append("files", file, file.webkitRelativePath || file.name);
    const summary = await api("/api/trakt/import-export-upload", { method: "POST", body: form });
    await refreshMediaState();
    return renderTraktWatchedSummary(summary);
  }));
  section.append(settingRow({
    title: "Import a Trakt export",
    description: "Upload the export zip or the JSON files. Works without linking an account.",
    control: [exportInput, importExport],
    stacked: true,
  }));

  section.append(output);
  return section;
}

function appUpdatesSection(status) {
  const section = settingsSection("App updates", "Android builds served to your devices");
  if (status?.error) section.append(el("div", "settings-message error", status.error));
  section.append(appUpdateRow("Android TV", "tv", status?.tv));
  section.append(appUpdateRow("Phone Companion", "companion", status?.companion));
  return section;
}

function appUpdateRow(title, app, info) {
  const configured = Boolean(info?.configured);
  const description = configured
    ? [`${info.versionName || "?"} (${info.versionCode || "?"})`, info.sizeBytes ? formatBytes(info.sizeBytes) : "", info.error || ""].filter(Boolean).join(" · ")
    : (info?.error || "No build uploaded yet.");
  const details = el("details", "setting-upload");
  details.append(el("summary", null, "Upload APK"));
  details.append(appUploadForm(title, app));
  return settingRow({ title, description, control: details, stacked: true });
}

function appUploadForm(title, app) {
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
  return form;
}

function settingsShell(active, content) {
  const shell = el("div", "settings-shell settings-page");
  const sidebar = el("aside", "settings-sidebar");
  const nav = el("nav", "settings-sidebar-nav");
  nav.append(settingsNavButton("Overview", active === "overview", () => renderSettings().catch(console.error)));
  if (currentUser?.isAdmin) {
    nav.append(settingsNavButton("Users", active === "users", () => renderUsers().catch(console.error)));
  }
  sidebar.append(nav);
  const main = el("main", "settings-main");
  main.append(content);
  // Tabs belong between the page heading and the sections.
  const header = main.querySelector(".view-header");
  if (header) header.after(sidebar);
  else main.prepend(sidebar);
  shell.append(main);
  return shell;
}

function settingsNavButton(label, selected, onClick) {
  const button = el("button", selected ? "settings-sidebar-item active" : "settings-sidebar-item", label);
  button.type = "button";
  button.addEventListener("click", onClick);
  return button;
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
  if (value >= 1024 * 1024 * 1024) return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`;
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
    thumb.style.backgroundImage = `url(${imageURL(episode, "backdrop", 400)})`;
  } else if (episode.posterPath) {
    thumb.style.backgroundImage = `url(${imageURL(episode, "poster", 400)})`;
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

/* \u2500\u2500 Episode Card (16:9 still) \u2500\u2500 */
function episodeCard(episode) {
  const card = el("div", "episode-card");
  const open = () => openDetail(episode, false, { show: currentShow, season: currentSeason }).catch(console.error);

  const still = el("button", "ep-still");
  still.type = "button";
  still.addEventListener("click", open);
  if (episode.backdropPath) {
    still.style.backgroundImage = `url(${imageURL(episode, "backdrop", 800)})`;
  } else if (episode.posterPath) {
    still.style.backgroundImage = `url(${imageURL(episode, "poster", 800)})`;
  }
  still.append(el("span", "ep-still-badge", `E${String(episode.episodeNumber || 0).padStart(2, "0")}`));
  if (episode.rating) still.append(ratingBadge(episode.rating, "poster-rating"));
  const badges = posterBadges({ seen: itemSeen(episode), watchlisted: itemWatchlisted(episode) });
  if (badges) still.append(badges);
  const playOverlay = el("button", "ep-play-overlay");
  playOverlay.type = "button";
  playOverlay.setAttribute("aria-label", "Play episode");
  const epGlyph = el("span", "ep-play-glyph");
  epGlyph.innerHTML = ICONS.play;
  playOverlay.append(epGlyph);
  playOverlay.addEventListener("click", (e) => { e.stopPropagation(); play(episode, { startMs: 0 }); });
  still.append(playOverlay);
  const progress = posterProgressBar(episode);
  if (progress) still.append(progress);

  const info = el("button", "ep-card-info");
  info.type = "button";
  info.addEventListener("click", open);
  const code = `S${String(episode.seasonNumber || 0).padStart(2, "0")}E${String(episode.episodeNumber || 0).padStart(2, "0")}`;
  info.append(
    el("div", "ep-card-title", episode.episodeTitle || episode.title),
    el("div", "ep-card-meta", [code, fmtDuration(episode.durationMs)].filter(Boolean).join(" \u00b7 ")),
  );
  if (episode.overview) info.append(el("div", "ep-card-overview", episode.overview));

  card.append(still, info);
  return card;
}

/* ── Detail View (movie / episode) ── */
async function openDetail(item, skipHistory, parent = {}) {
  currentItem = item;
  if (item.kind === "episode") {
    if (parent.show) currentShow = parent.show;
    if (parent.season !== undefined && parent.season !== null) currentSeason = Number(parent.season || 0);
    else currentSeason = currentSeason ?? Number(item.seasonNumber || 0);
  }
  stopPlayer();
  setLoading();

  const [fresh, streams, externalRatings, sidecars, progress, similar] = await Promise.all([
    fetchItem(item.id).catch(() => null),
    api(`/api/items/${item.id}/streams`).catch(() => []),
    api(`/api/items/${item.id}/ratings`).catch(() => null),
    api(`/api/items/${item.id}/sidecars`).catch(() => null),
    api(`/api/items/${item.id}/progress`).catch(() => null),
    item.kind === "movie" ? api(`/api/items/${item.id}/similar`).catch(() => []) : Promise.resolve([]),
  ]);
  const d = Object.assign({}, item, fresh || {});
  const audioStreams = (streams || []).filter((s) => s.type === "audio");
  const subtitleStreams = (streams || []).filter((s) => s.type === "subtitle");
  const chosenAudio = (audioStreams.find((s) => s.default) || audioStreams[0]);
  let chosenAudioIdx = chosenAudio ? chosenAudio.index : null;
  let chosenSubIdx = null;

  const resumeMs = (progress && !progress.completed && progress.positionMs > 30000) ? progress.positionMs : 0;

  if (!skipHistory) {
    const detailState = { view: "detail", libraryId: activeLibraryId, itemId: item.id };
    if (item.kind === "episode") {
      detailState.showTitle = (currentShow?.title === item.showTitle ? currentShow.title : item.showTitle) || "";
      detailState.season = currentSeason ?? Number(item.seasonNumber || 0);
    }
    pushState(detailState);
  }

  const frag = document.createDocumentFragment();
  const backdrop = pageBackdrop(d);
  if (backdrop) frag.append(backdrop);
  const isEpisode = item.kind === "episode";
  // Resolve the breadcrumb from the item's own library/show, not the globally
  // active library — otherwise opening an item from the home shelves (where the
  // active library may be anything) mislabels the crumb, e.g. a movie reading
  // "TV Shows" or an episode reading "Movies".
  const itemLibraryId = d.libraryId || item.libraryId;
  const library = libraryById(itemLibraryId) || activeLibrary();

  const crumbs = [{ label: library?.name || "Library", action: () => goToLibrary(library?.id).catch(console.error) }];
  if (isEpisode) {
    const showTitle = (currentShow?.title) || d.showTitle || item.showTitle;
    if (showTitle) {
      const showRef = currentShow && currentShow.title === showTitle ? currentShow : { libraryId: itemLibraryId, title: showTitle };
      crumbs.push({ label: showTitle, action: () => openShow(showRef, false, currentSeason ?? Number(item.seasonNumber || 0)).catch(console.error) });
    }
  }
  crumbs.push({ label: isEpisode ? (d.episodeTitle || d.title) : d.title });
  frag.append(makeBreadcrumb(crumbs));

  const detail = el("article", "detail");
  const posterCol = el("div", "detail-poster-col");
  const poster = posterBlock(d, d.title, { seen: itemSeen(d), watchlisted: itemWatchlisted(d) }, 800);
  poster.classList.add("detail-poster");
  posterCol.append(poster);
  const posterGenres = genreList(d.genres).slice(0, 3);
  if (posterGenres.length) {
    const g = el("div", "detail-poster-genres");
    for (const genre of posterGenres) g.append(el("span", "poster-genre", genre));
    posterCol.append(g);
  }

  const body = el("div", "detail-body");
  const titleText = item.kind === "episode" ? (d.episodeTitle || d.title) : d.title;
  body.append(el("h1", null, titleText));
  if (d.originalTitle && d.originalTitle !== titleText) body.append(el("div", "detail-original-title", d.originalTitle));

  body.append(detailMetaLine(d));
  body.append(detailRatingsRow(d, externalRatings));

  const allGenres = genreList(d.genres);
  if (allGenres.length) {
    const genres = el("div", "detail-genres");
    for (const g of allGenres) genres.append(el("span", "genre-pill", g));
    body.append(genres);
  }

  if (d.overview || d.tagline) body.append(overviewPanel(d));

  // Inline track selectors used as playback defaults.
  if (audioStreams.length > 1 || subtitleStreams.length) {
    const selectors = el("div", "stream-selectors");
    if (audioStreams.length) {
      selectors.append(selectField("Audio", audioStreams, String(chosenAudioIdx ?? ""), (v) => { chosenAudioIdx = v === "" ? null : Number(v); }));
    }
    if (subtitleStreams.length) {
      selectors.append(selectField("Subtitles", subtitleStreams, "", (v) => { chosenSubIdx = v === "" ? null : Number(v); }, true));
    }
    body.append(selectors);
  }

  const actions = el("div", "detail-actions");
  const playOpts = () => ({ audioIndex: chosenAudioIdx, subtitleIndex: chosenSubIdx });
  if (resumeMs) {
    const resumeBtn = el("button", "primary detail-play", `Resume · ${fmtClock(resumeMs / 1000)}`);
    resumeBtn.type = "button";
    resumeBtn.addEventListener("click", () => play(d, Object.assign({ startMs: resumeMs }, playOpts())));
    const fromStart = el("button", "secondary detail-play-secondary", "Play from start");
    fromStart.type = "button";
    fromStart.addEventListener("click", () => play(d, Object.assign({ startMs: 0 }, playOpts())));
    actions.append(resumeBtn, fromStart);
  } else {
    const playBtn = el("button", "primary detail-play", "Play");
    playBtn.type = "button";
    playBtn.addEventListener("click", () => play(d, Object.assign({ startMs: 0 }, playOpts())));
    actions.append(playBtn);
  }

  const trailerBtn = el("button", "secondary", "Trailer");
  trailerBtn.type = "button";
  trailerBtn.addEventListener("click", () => { if (sidecars?.trailer) playTrailer(d); else youtubeTrailerSearch(d); });
  actions.append(trailerBtn);

  const downloadBtn = el("a", "secondary link-button", d.sizeBytes ? `Download · ${formatBytes(d.sizeBytes)}` : "Download");
  downloadBtn.href = `/api/items/${encodeURIComponent(String(d.id))}/download`;
  downloadBtn.setAttribute("download", "");
  actions.append(downloadBtn);

  actions.append(
    toggleActionButton({
      active: itemSeen(d),
      activeLabel: "Seen",
      inactiveLabel: "Mark Seen",
      onToggle: (seen) => setItemSeen(d, seen),
    }),
    toggleActionButton({
      active: itemWatchlisted(d),
      activeLabel: "In Watchlist",
      inactiveLabel: "Add Watchlist",
      onToggle: (watchlisted) => setItemWatchlisted(d, watchlisted),
    }),
  );

  body.append(actions);
  body.append(userRatingControl(() => userItemRating(d), (value) => setItemRating(d, value)));

  detail.append(posterCol, body);
  frag.append(detail);
  if (d.actors?.length) frag.append(castShelf(d.actors));
  if (similar && similar.length) {
    frag.append(shelf("More like this", similar, (items) => renderShelfGrid(items)));
  }
  setView(frag);
}

function detailMetaLine(d) {
  const meta = el("div", "detail-meta");
  const parts = [];
  if (d.kind === "episode") {
    if (d.showTitle) parts.push(d.showTitle);
    if (d.seasonNumber || d.episodeNumber) parts.push(`S${String(d.seasonNumber || 0).padStart(2, "0")}E${String(d.episodeNumber || 0).padStart(2, "0")}`);
  } else if (d.year) {
    parts.push(String(d.year));
  }
  if (d.durationMs) { parts.push(fmtDuration(d.durationMs)); parts.push(fmtEndsAround(d.durationMs)); }
  parts.forEach((part, i) => {
    if (i > 0) meta.append(el("span", "meta-dot"));
    meta.append(el("span", null, part));
  });
  if (d.officialRating) meta.append(el("span", "content-rating-chip", d.officialRating));
  for (const chip of techChips(d)) meta.append(chip);
  return meta;
}

function techChips(d) {
  const chips = [];
  const res = resolutionLabel(d.height);
  if (res) chips.push(el("span", "tech-chip", res));
  if (d.videoCodec) chips.push(el("span", "tech-chip", String(d.videoCodec).toUpperCase()));
  return chips;
}

function resolutionLabel(height) {
  const h = Number(height || 0);
  if (h <= 0) return "";
  if (h >= 2000) return "4K";
  if (h >= 1000) return "1080p";
  if (h >= 700) return "720p";
  return `${h}p`;
}

function detailRatingsRow(d, ext) {
  const row = el("div", "detail-ratings");
  let any = false;
  if (ext?.imdbRating) { row.append(sourceRatingBadge("IMDb", Number(ext.imdbRating).toFixed(1))); any = true; }
  if (ext?.tmdbRating) { row.append(sourceRatingBadge("TMDb", Number(ext.tmdbRating).toFixed(1))); any = true; }
  if (ext?.rottenTomatoesRating) { row.append(sourceRatingBadge("RT", `${ext.rottenTomatoesRating}%`)); any = true; }
  if (ext?.metacriticRating) { row.append(sourceRatingBadge("MC", String(ext.metacriticRating))); any = true; }
  if (!any && d.rating) row.append(sourceRatingBadge("NFO", Number(d.rating).toFixed(1)));
  return row;
}

function genreList(value) {
  return String(value || "").split(/[,/]/).map((g) => g.trim()).filter(Boolean);
}

function overviewPanel(d) {
  const panel = el("button", "overview overview-clickable");
  panel.type = "button";
  if (d.tagline) panel.append(el("span", "overview-tagline", d.tagline));
  panel.append(el("span", "overview-text", d.overview || d.tagline || ""));
  panel.addEventListener("click", () => openFullText(d));
  return panel;
}

function openFullText(d) {
  const overlay = el("div", "modal-overlay");
  const dialog = el("div", "modal-dialog");
  dialog.append(el("h2", null, d.kind === "episode" ? (d.episodeTitle || d.title) : d.title));
  if (d.tagline) dialog.append(el("p", "modal-tagline", d.tagline));
  if (d.overview) dialog.append(el("p", "modal-overview", d.overview));
  const facts = [
    ["Director", listText(d.directors)],
    ["Writers", listText(d.writers)],
    ["Studios", listText(d.studios)],
    ["Country", listText(d.countries)],
  ].filter(([, v]) => v);
  if (facts.length) {
    const dl = el("dl", "modal-facts");
    for (const [k, v] of facts) { dl.append(el("dt", null, k), el("dd", null, v)); }
    dialog.append(dl);
  }
  const close = el("button", "secondary modal-close", "Close");
  close.type = "button";
  const dismiss = () => overlay.remove();
  close.addEventListener("click", dismiss);
  overlay.addEventListener("click", (e) => { if (e.target === overlay) dismiss(); });
  document.addEventListener("keydown", function onKey(e) { if (e.key === "Escape") { dismiss(); document.removeEventListener("keydown", onKey); } });
  dialog.append(close);
  overlay.append(dialog);
  document.body.append(overlay);
}

function listText(value) {
  if (Array.isArray(value)) return value.filter(Boolean).join(", ");
  return String(value || "").split(/[,/]/).map((s) => s.trim()).filter(Boolean).join(", ");
}

/* ── Show View (inline seasons + episodes) ── */
async function openShow(show, skipHistory, initialSeason) {
  currentShow = show;
  stopPlayer();
  setLoading();

  const libraryId = show.libraryId || activeLibraryId;
  // Breadcrumbs and deep links may hand over a bare {libraryId, title} stub
  // (e.g. episode opened straight from a home shelf). Hydrate the full
  // summary so the header gets artwork, overview, years and counts.
  if (!show.episodeCount && !show.posterItemId) {
    const full = await fetchShowSummary(libraryId, show.title).catch(() => null);
    if (full) {
      show = full;
      currentShow = full;
    }
  }
  const seasons = await api(`/api/tv/seasons?libraryId=${encodeURIComponent(libraryId)}&showTitle=${encodeURIComponent(show.title)}`).catch(() => groupSeasons(show.episodes || []));
  const seasonNumbers = seasons.map((s) => Number(s.seasonNumber ?? s.number ?? 0));
  let activeSeason = initialSeason != null && seasonNumbers.includes(Number(initialSeason))
    ? Number(initialSeason)
    : (seasonNumbers.find((n) => n > 0) ?? seasonNumbers[0] ?? 0);
  currentSeason = activeSeason;

  const frag = document.createDocumentFragment();
  const backdrop = pageBackdrop(showBackdropSource(show));
  if (backdrop) frag.append(backdrop);
  const library = libraryById(show.libraryId) || activeLibrary();

  frag.append(makeBreadcrumb([
    { label: library?.name || "Library", action: () => goToLibrary(library?.id).catch(console.error) },
    { label: show.title },
  ]));

  const header = el("div", "show-detail");
  const poster = posterBlock(showPosterSource(show), show.title, { seen: showSeen(show), watchlisted: showWatchlisted(show) });
  poster.classList.add("detail-poster");

  const body = el("div", "detail-body");
  body.append(el("h1", null, show.title));
  const meta = el("div", "detail-meta");
  const years = showYears(show);
  if (years) meta.append(el("span", null, years), el("span", "meta-dot"));
  meta.append(el("span", null, showCountText(show)));
  if (showRating(show)) meta.append(el("span", "meta-dot"), el("span", "content-rating-chip", `★ ${Number(showRating(show)).toFixed(1)}`));
  body.append(meta);

  const allGenres = genreList(show.genres);
  if (allGenres.length) {
    const genres = el("div", "detail-genres");
    for (const g of allGenres) genres.append(el("span", "genre-pill", g));
    body.append(genres);
  }
  if (show.overview) body.append(overviewPanel(show));

  const actions = el("div", "detail-actions");
  actions.append(
    toggleActionButton({ active: showSeen(show), activeLabel: "Seen", inactiveLabel: "Mark Seen", onToggle: (seen) => setShowSeen(show, seen) }),
    toggleActionButton({ active: showWatchlisted(show), activeLabel: "In Watchlist", inactiveLabel: "Add Watchlist", onToggle: (w) => setShowWatchlisted(show, w) }),
  );
  body.append(actions);
  body.append(userRatingControl(() => userShowRating(show), (value) => setShowRating(show, value)));
  header.append(poster, body);
  frag.append(header);

  if (show.actors?.length) frag.append(castShelf(show.actors));

  // Season selector + inline episode area.
  const seasonWrap = el("div", "season-browser");
  const episodeArea = el("div", "episode-area");
  if (seasons.length > 1) {
    const selector = el("div", "season-selector");
    const setActiveSeason = (num, btn) => {
      activeSeason = num;
      currentSeason = num;
      selector.querySelectorAll(".season-pill").forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      loadShowEpisodes(show, num, episodeArea);
      pushState({ view: "show", libraryId, showTitle: show.title, season: num }, true);
    };
    for (const season of seasons) {
      const num = Number(season.seasonNumber ?? season.number ?? 0);
      const label = season.title || (num ? `Season ${num}` : "Specials");
      const pill = el("button", num === activeSeason ? "season-pill active" : "season-pill");
      pill.type = "button";
      pill.append(el("span", "season-pill-label", label), el("span", "season-pill-count", `${season.episodeCount || season.episodes?.length || 0}`));
      pill.addEventListener("click", () => setActiveSeason(num, pill));
      selector.append(pill);
    }
    seasonWrap.append(selector);
  }
  seasonWrap.append(episodeArea);
  frag.append(seasonWrap);

  if (!skipHistory) pushState({ view: "show", libraryId, showTitle: show.title, season: activeSeason });
  setView(frag);
  await loadShowEpisodes(show, activeSeason, episodeArea);
}

async function loadShowEpisodes(show, seasonNumber, container) {
  container.innerHTML = "";
  container.append(el("div", "empty", "Loading episodes…"));
  const libraryId = show.libraryId || activeLibraryId;
  const episodes = await api(`/api/tv/episodes?libraryId=${encodeURIComponent(libraryId)}&showTitle=${encodeURIComponent(show.title)}&season=${encodeURIComponent(String(seasonNumber))}`).catch(() => []);
  container.innerHTML = "";
  const seasonLabel = seasonNumber ? `Season ${seasonNumber}` : "Specials";
  const header = el("div", "section-header");
  const allSeen = episodes.length > 0 && episodes.every((e) => itemSeen(e));
  const seasonActions = el("div", "view-header-actions");
  seasonActions.append(toggleActionButton({
    active: allSeen,
    activeLabel: "Season Seen",
    inactiveLabel: "Mark Season Seen",
    onToggle: (seen) => setSeasonSeen(show, seasonNumber, seen, episodes),
  }));
  if (episodes.length) {
    const dlBtn = el("button", "secondary", "Download");
    dlBtn.type = "button";
    dlBtn.addEventListener("click", () => openSeasonDownloads(show, seasonLabel, episodes));
    seasonActions.append(dlBtn);
  }
  header.append(el("h2", null, seasonLabel), el("span", null, `${episodes.length} episodes`), seasonActions);
  container.append(header);
  if (!episodes.length) { container.append(el("div", "empty", "No episodes found")); return; }
  const grid = el("div", "episode-grid");
  for (const episode of episodes) grid.append(episodeCard(episode));
  container.append(grid);
}

/* ── Modal ── */
function openModal(title, body) {
  const backdrop = el("div", "modal-backdrop");
  const panel = el("div", "modal");
  const head = el("div", "modal-head");
  head.append(el("h2", null, title));
  const close = el("button", "modal-close", "✕");
  close.type = "button";
  close.setAttribute("aria-label", "Close");
  head.append(close);
  panel.append(head, body);
  backdrop.append(panel);
  const remove = () => { backdrop.remove(); document.removeEventListener("keydown", onKey); };
  const onKey = (e) => { if (e.key === "Escape") remove(); };
  close.addEventListener("click", remove);
  backdrop.addEventListener("click", (e) => { if (e.target === backdrop) remove(); });
  document.addEventListener("keydown", onKey);
  document.body.append(backdrop);
  return remove;
}

function openSeasonDownloads(show, seasonLabel, episodes) {
  const list = el("div", "download-list");
  for (const episode of episodes) {
    const row = el("div", "download-row");
    const code = `S${String(episode.seasonNumber || 0).padStart(2, "0")}E${String(episode.episodeNumber || 0).padStart(2, "0")}`;
    const info = el("div", "download-info");
    info.append(el("div", "download-title", `${code} · ${episode.episodeTitle || episode.title}`));
    const meta = [formatBytes(episode.sizeBytes), resolutionLabel(episode.height)].filter(Boolean).join(" · ");
    if (meta) info.append(el("div", "download-meta", meta));
    const dl = el("a", "secondary link-button", "Download");
    dl.href = `/api/items/${encodeURIComponent(String(episode.id))}/download`;
    dl.setAttribute("download", "");
    row.append(info, dl);
    list.append(row);
  }
  openModal(`${show.title} · ${seasonLabel}`, list);
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
    .map(([number, eps]) => ({ number, seasonNumber: number, episodes: eps, episodeCount: eps.length }));
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

function castShelf(actors) {
  const section = el("section", "cast-shelf");
  const filtered = (actors || []).filter((actor) => actor?.name).slice(0, 28);
  section.append(sectionTitle("Cast", `${filtered.length}`));
  const row = el("div", "cast-row hscroll-row");
  for (const actor of filtered) {
    const card = el("button", "cast-card");
    card.type = "button";
    card.addEventListener("click", () => openActor(actor).catch(console.error));
    const avatar = el("div", "cast-avatar", actorInitials(actor.name));
    const thumb = actor.thumb || `/api/actors/image?name=${encodeURIComponent(actor.name)}`;
    avatar.style.backgroundImage = `url(${thumb})`;
    card.append(
      avatar,
      el("div", "cast-name", actor.name),
      el("div", "cast-role", actor.role || ""),
    );
    row.append(card);
  }
  section.append(hScroller(row));
  return section;
}

async function openActor(actor) {
  const detail = await api(`/api/actors?name=${encodeURIComponent(actor.name)}`);
  const frag = document.createDocumentFragment();
  frag.append(makeBreadcrumb([
    { label: "Cast", action: () => history.back() },
    { label: detail?.actor?.name || actor.name },
  ]));
  const info = detail?.info || {};
  const hero = el("article", "actor-page");
  const avatar = el("div", "actor-page-avatar", actorInitials(actor.name));
  const profile = detail?.profileUrl || actor.thumb || `/api/actors/image?name=${encodeURIComponent(actor.name)}`;
  avatar.style.backgroundImage = `url(${profile})`;
  const copy = el("div", "actor-page-copy");
  copy.append(el("h1", null, detail?.actor?.name || actor.name));
  const meta = [info.knownForDepartment, info.birthday, info.placeOfBirth].filter(Boolean).join(" · ");
  if (meta) copy.append(el("div", "detail-meta", meta));
  if (info.biography) copy.append(el("p", "overview", info.biography));
  hero.append(avatar, copy);
  frag.append(hero);
  if (detail?.movies?.length) frag.append(sectionTitle("Movies", `${detail.movies.length}`), renderGrid(detail.movies));
  if (detail?.shows?.length) {
    frag.append(sectionTitle("TV Shows", `${detail.shows.length}`));
    renderTVShows(frag, detail.shows);
  }
  setView(frag);
}

function actorInitials(name) {
  const parts = String(name || "").trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] || "?") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}
