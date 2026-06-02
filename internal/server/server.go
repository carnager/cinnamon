package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/media"
	"popcorn/web"
)

type Options struct {
	Config config.Config
	Log    *slog.Logger
	Store  *media.Store
	Auth   *auth.Store
}

type App struct {
	cfg         config.Config
	log         *slog.Logger
	store       *media.Store
	auth        *auth.Store
	ctx         context.Context
	cancel      context.CancelFunc
	hlsMu       sync.Mutex
	hlsSessions map[string]*hlsSession
}

func New(opts Options) *App {
	ctx, cancel := context.WithCancel(context.Background())
	return &App{cfg: opts.Config, log: opts.Log, store: opts.Store, auth: opts.Auth, ctx: ctx, cancel: cancel, hlsSessions: map[string]*hlsSession{}}
}

func (a *App) Close() {
	a.cancel()
	a.hlsMu.Lock()
	sessions := a.hlsSessions
	a.hlsSessions = map[string]*hlsSession{}
	a.hlsMu.Unlock()
	for id, sess := range sessions {
		a.stopHLSSession(id, sess)
	}
}

func (a *App) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/health", a.health)
	mux.HandleFunc("GET /api/libraries", a.libraries)
	mux.HandleFunc("POST /api/scan", a.scan)
	mux.HandleFunc("GET /api/scan", a.scanStatus)
	mux.HandleFunc("GET /api/items", a.items)
	mux.HandleFunc("GET /api/search", a.search)
	mux.HandleFunc("GET /api/genres", a.genres)
	mux.HandleFunc("GET /api/tv/shows", a.tvShows)
	mux.HandleFunc("GET /api/tv/seasons", a.tvSeasons)
	mux.HandleFunc("GET /api/tv/episodes", a.tvEpisodes)
	mux.HandleFunc("GET /api/items/{id}", a.item)
	mux.HandleFunc("GET /api/items/{id}/ratings", a.itemRatings)
	mux.HandleFunc("POST /api/auth/login", a.login)
	mux.HandleFunc("POST /api/auth/logout", a.logout)
	mux.HandleFunc("GET /api/auth/me", a.me)
	mux.HandleFunc("POST /api/auth/qr/start", a.authQRStart)
	mux.HandleFunc("GET /api/auth/qr/poll", a.authQRPoll)
	mux.HandleFunc("POST /api/auth/qr/complete", a.authQRComplete)
	mux.HandleFunc("GET /api/users", a.users)
	mux.HandleFunc("POST /api/users", a.createUser)
	mux.HandleFunc("GET /api/progress", a.progressList)
	mux.HandleFunc("GET /api/progress/tv", a.progressShows)
	mux.HandleFunc("PUT /api/progress/tv", a.progressShowSave)
	mux.HandleFunc("DELETE /api/progress/tv", a.progressShowDelete)
	mux.HandleFunc("GET /api/items/{id}/progress", a.progressGet)
	mux.HandleFunc("PUT /api/items/{id}/progress", a.progressSave)
	mux.HandleFunc("DELETE /api/items/{id}/progress", a.progressDelete)
	mux.HandleFunc("GET /api/watchlist", a.watchlistGet)
	mux.HandleFunc("PUT /api/items/{id}/watchlist", a.watchlistItemSave)
	mux.HandleFunc("DELETE /api/items/{id}/watchlist", a.watchlistItemDelete)
	mux.HandleFunc("PUT /api/watchlist/tv", a.watchlistShowSave)
	mux.HandleFunc("DELETE /api/watchlist/tv", a.watchlistShowDelete)
	mux.HandleFunc("GET /api/trakt/status", a.traktStatus)
	mux.HandleFunc("POST /api/trakt/device", a.traktDeviceCode)
	mux.HandleFunc("POST /api/trakt/device/token", a.traktDeviceToken)
	mux.HandleFunc("POST /api/trakt/import-watched", a.traktImportWatched)
	mux.HandleFunc("POST /api/trakt/import-watchlist", a.traktImportWatchlist)
	mux.HandleFunc("POST /api/trakt/import-export", a.traktImportExport)
	mux.HandleFunc("DELETE /api/trakt", a.traktDisconnect)
	mux.HandleFunc("POST /api/devices/register", a.remoteRegisterDevice)
	mux.HandleFunc("GET /api/devices", a.remoteListDevices)
	mux.HandleFunc("POST /api/devices/{id}/pairing-code", a.remotePairingCode)
	mux.HandleFunc("POST /api/devices/pair", a.remotePairDevice)
	mux.HandleFunc("POST /api/devices/{id}/commands", a.remotePostCommand)
	mux.HandleFunc("GET /api/devices/{id}/commands", a.remoteGetCommands)
	mux.HandleFunc("PUT /api/devices/{id}/state", a.remotePutState)
	mux.HandleFunc("GET /api/devices/{id}/state", a.remoteGetState)
	mux.HandleFunc("GET /api/items/{id}/streams", a.streams)
	mux.HandleFunc("GET /api/items/{id}/stream", a.stream)
	mux.HandleFunc("GET /api/items/{id}/transcode", a.transcode)
	mux.HandleFunc("GET /api/items/{id}/hls/{session}/index.m3u8", a.hlsPlaylist)
	mux.HandleFunc("GET /api/items/{id}/hls/{session}/{segment}", a.hlsSegment)
	mux.HandleFunc("DELETE /api/hls/{session}", a.hlsStop)
	mux.HandleFunc("GET /api/items/{id}/image/{kind}", a.image)
	mux.Handle("/", noCache(spaFiles()))
	return logging(a.log, mux)
}

type hlsSession struct {
	dir     string
	cmd     *exec.Cmd
	started time.Time
	done    chan struct{}
}

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "version": "0.1.0"})
}

func (a *App) libraries(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, a.cfg.Libraries)
}

func (a *App) scan(w http.ResponseWriter, r *http.Request) {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), a.cfg.ScanTimeout)
		defer cancel()
		if err := media.NewScanner(a.cfg, a.store, a.log).Scan(ctx); err != nil {
			a.log.Error("scan failed", "error", err)
		}
	}()
	w.WriteHeader(http.StatusAccepted)
}

func (a *App) scanStatus(w http.ResponseWriter, r *http.Request) {
	status, err := a.store.ScanStatus(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, status)
}

func (a *App) items(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	items, err := a.store.ListItems(r.Context(), r.URL.Query().Get("libraryId"), r.URL.Query().Get("q"), r.URL.Query().Get("genre"), r.URL.Query().Get("sort"), limit, offset)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, items)
}

func (a *App) genres(w http.ResponseWriter, r *http.Request) {
	genres, err := a.store.ListGenres(r.Context(), r.URL.Query().Get("libraryId"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, genres)
}

func (a *App) search(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	items, err := a.store.SearchItems(r.Context(), media.SearchOptions{
		Query:     r.URL.Query().Get("q"),
		LibraryID: r.URL.Query().Get("libraryId"),
		Kind:      r.URL.Query().Get("kind"),
		Limit:     limit,
		Offset:    offset,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"query":  r.URL.Query().Get("q"),
		"items":  items,
		"limit":  limit,
		"offset": offset,
	})
}

func (a *App) tvShows(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	shows, err := a.store.ListShows(r.Context(), r.URL.Query().Get("libraryId"), r.URL.Query().Get("q"), r.URL.Query().Get("genre"), r.URL.Query().Get("sort"), limit, offset)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, shows)
}

func (a *App) tvSeasons(w http.ResponseWriter, r *http.Request) {
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return
	}
	seasons, err := a.store.ListSeasons(r.Context(), libraryID, showTitle)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, seasons)
}

func (a *App) tvEpisodes(w http.ResponseWriter, r *http.Request) {
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return
	}
	season := -1
	if v := r.URL.Query().Get("season"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil {
			http.Error(w, "invalid season", http.StatusBadRequest)
			return
		}
		season = n
	}
	episodes, err := a.store.ListEpisodes(r.Context(), libraryID, showTitle, season)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, episodes)
}

func (a *App) login(w http.ResponseWriter, r *http.Request) {
	if a.auth == nil {
		http.Error(w, "auth unavailable", http.StatusServiceUnavailable)
		return
	}
	var in struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	user, err := a.auth.Authenticate(r.Context(), in.Username, in.Password)
	if err != nil {
		status := http.StatusUnauthorized
		if errors.Is(err, auth.ErrForbidden) {
			status = http.StatusForbidden
		}
		http.Error(w, err.Error(), status)
		return
	}
	token, err := a.auth.CreateSession(r.Context(), user.ID, 30*24*time.Hour)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     "popcorn_token",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Expires:  time.Now().Add(30 * 24 * time.Hour),
	})
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "user": user})
}

func (a *App) logout(w http.ResponseWriter, r *http.Request) {
	if a.auth == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	token := bearerToken(r)
	if token == "" {
		if cookie, err := r.Cookie("popcorn_token"); err == nil {
			token = cookie.Value
		}
	}
	if token != "" {
		_ = a.auth.DeleteSession(r.Context(), token)
	}
	http.SetCookie(w, &http.Cookie{Name: "popcorn_token", Value: "", Path: "/", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteLaxMode})
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) me(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, user)
}

func (a *App) users(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	users, err := a.auth.Users(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, users)
}

func (a *App) createUser(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	var in struct {
		Username    string `json:"username"`
		DisplayName string `json:"displayName"`
		Password    string `json:"password"`
		IsAdmin     bool   `json:"isAdmin"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	user, err := a.auth.CreateUser(r.Context(), auth.CreateUserInput{
		Username:    in.Username,
		DisplayName: in.DisplayName,
		Password:    in.Password,
		IsAdmin:     in.IsAdmin,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusCreated, user)
}

func (a *App) item(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, item)
}

func (a *App) streams(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	streams, err := probeStreams(r.Context(), a.cfg.FFprobePath, item.Path)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, streams)
}

func (a *App) stream(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	f, err := os.Open(item.Path)
	if err != nil {
		http.Error(w, "media unavailable", http.StatusNotFound)
		return
	}
	defer f.Close()
	http.ServeContent(w, r, filepath.Base(item.Path), time.Unix(item.MTimeUnix, 0), f)
}

func (a *App) image(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	path := item.PosterPath
	switch r.PathValue("kind") {
	case "poster":
		if item.Kind == "episode" {
			if poster, _ := media.TVShowArtworkPaths(a.libraryRoot(item.LibraryID), item.Path); poster != "" {
				path = poster
			}
		}
	case "backdrop":
		path = item.BackdropPath
	case "season":
		path = seasonImage(item)
	}
	if path == "" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, path)
}

func (a *App) libraryRoot(id string) string {
	for _, lib := range a.cfg.Libraries {
		if lib.ID == id {
			return lib.Path
		}
	}
	return ""
}

func seasonImage(item media.Item) string {
	return media.SeasonArtworkPath(item.Path, item.SeasonNumber)
}

func (a *App) transcode(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	bandwidth := parseBandwidth(r.URL.Query().Get("bandwidth"))
	start := parseStart(r.URL.Query().Get("start"), item.DurationMS)
	audio := parseOptionalInt(r.URL.Query().Get("audio"))
	requestedSubtitle := parseOptionalInt(r.URL.Query().Get("subtitle"))
	subtitle := a.textSubtitleStream(r.Context(), item, requestedSubtitle, "transcode")
	args := transcodeArgs(a.cfg, item.Path, bandwidth, start, audio, subtitle)
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Popcorn-Start", strconv.FormatFloat(start, 'f', 3, 64))
	cmd := exec.CommandContext(r.Context(), a.cfg.FFmpegPath, args...)
	stderr, _ := cmd.StderrPipe()
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if err := cmd.Start(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	go func() {
		b, _ := io.ReadAll(io.LimitReader(stderr, 64*1024))
		if len(b) > 0 {
			a.log.Debug("ffmpeg", "item", item.ID, "stderr", string(b))
		}
	}()
	_, copyErr := io.Copy(w, stdout)
	waitErr := cmd.Wait()
	if copyErr != nil && !errors.Is(copyErr, context.Canceled) {
		a.log.Debug("transcode copy", "error", copyErr)
	}
	if waitErr != nil && r.Context().Err() == nil {
		a.log.Debug("transcode exit", "error", waitErr)
	}
}

func (a *App) hlsPlaylist(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	sessionID := cleanSessionID(r.PathValue("session"))
	if sessionID == "" {
		http.Error(w, "invalid session", http.StatusBadRequest)
		return
	}
	bandwidth := parseBandwidth(r.URL.Query().Get("bandwidth"))
	start := parseStart(r.URL.Query().Get("start"), item.DurationMS)
	audio := parseOptionalInt(r.URL.Query().Get("audio"))
	requestedSubtitle := parseOptionalInt(r.URL.Query().Get("subtitle"))
	subtitle := a.textSubtitleStream(r.Context(), item, requestedSubtitle, "hls")
	a.log.Info("hls playlist requested", "item", item.ID, "session", sessionID, "bandwidth", bandwidth, "start", start, "audio", optionalIntValue(audio), "subtitle", optionalIntValue(subtitle), "requestedSubtitle", optionalIntValue(requestedSubtitle))
	sess, err := a.ensureHLSSession(r.Context(), sessionID, item, bandwidth, start, audio, subtitle)
	if err != nil {
		a.log.Warn("hls session failed", "item", item.ID, "session", sessionID, "error", err)
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	playlist := filepath.Join(sess.dir, "index.m3u8")
	if err := waitForFileOrDone(r.Context(), playlist, 8*time.Second, sess.done); err != nil {
		a.log.Warn("hls playlist timeout", "item", item.ID, "session", sessionID, "error", err)
		http.Error(w, err.Error(), http.StatusGatewayTimeout)
		return
	}
	if err := rewritePlaylistSegments(playlist, fmt.Sprintf("/api/items/%d/hls/%s/", item.ID, sessionID)); err != nil {
		a.log.Warn("hls playlist rewrite failed", "item", item.ID, "session", sessionID, "error", err)
	}
	if info, err := os.Stat(playlist); err == nil {
		a.log.Info("hls playlist served", "item", item.ID, "session", sessionID, "bytes", info.Size())
	}
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, playlist)
}

func (a *App) hlsSegment(w http.ResponseWriter, r *http.Request) {
	sessionID := cleanSessionID(r.PathValue("session"))
	segment := filepath.Base(r.PathValue("segment"))
	if sessionID == "" || segment == "." || segment == "" {
		http.Error(w, "invalid segment", http.StatusBadRequest)
		return
	}
	a.hlsMu.Lock()
	sess := a.hlsSessions[sessionID]
	a.hlsMu.Unlock()
	if sess == nil {
		http.NotFound(w, r)
		return
	}
	path := filepath.Join(sess.dir, segment)
	if err := waitForFileOrDone(r.Context(), path, 10*time.Second, sess.done); err != nil {
		a.log.Warn("hls segment unavailable", "session", sessionID, "segment", segment, "error", err)
		http.NotFound(w, r)
		return
	}
	if info, err := os.Stat(path); err == nil {
		a.log.Info("hls segment served", "session", sessionID, "segment", segment, "bytes", info.Size())
	}
	if strings.HasSuffix(segment, ".m4s") {
		w.Header().Set("Content-Type", "video/iso.segment")
	} else {
		w.Header().Set("Content-Type", "video/mp4")
	}
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFile(w, r, path)
}

func (a *App) hlsStop(w http.ResponseWriter, r *http.Request) {
	sessionID := cleanSessionID(r.PathValue("session"))
	if sessionID == "" {
		http.Error(w, "invalid session", http.StatusBadRequest)
		return
	}
	a.hlsMu.Lock()
	sess := a.hlsSessions[sessionID]
	delete(a.hlsSessions, sessionID)
	a.hlsMu.Unlock()
	if sess != nil {
		a.log.Info("hls session stopped by client", "session", sessionID)
		a.stopHLSSession(sessionID, sess)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) ensureHLSSession(ctx context.Context, sessionID string, item media.Item, bandwidth int, start float64, audio, subtitle *int) (*hlsSession, error) {
	a.hlsMu.Lock()
	if sess := a.hlsSessions[sessionID]; sess != nil {
		a.hlsMu.Unlock()
		return sess, nil
	}
	oldSessions := a.hlsSessions
	a.hlsSessions = map[string]*hlsSession{}
	dir, err := os.MkdirTemp("", "popcorn-hls-"+sessionID+"-")
	if err != nil {
		a.hlsMu.Unlock()
		return nil, err
	}
	args := hlsArgs(a.cfg, item.Path, filepath.Join(dir, "seg_%05d.m4s"), filepath.Join(dir, "index.m3u8"), bandwidth, start, audio, subtitle)
	cmd := exec.CommandContext(a.ctx, a.cfg.FFmpegPath, args...)
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		a.hlsMu.Unlock()
		_ = os.RemoveAll(dir)
		return nil, err
	}
	sess := &hlsSession{dir: dir, cmd: cmd, started: time.Now(), done: make(chan struct{})}
	a.hlsSessions[sessionID] = sess
	a.hlsMu.Unlock()
	for id, old := range oldSessions {
		a.stopHLSSession(id, old)
	}
	a.log.Info("hls session started", "item", item.ID, "session", sessionID, "start", start, "bandwidth", bandwidth, "args", strings.Join(args, " "))
	go func() {
		defer close(sess.done)
		b, _ := io.ReadAll(io.LimitReader(stderr, 128*1024))
		if len(b) > 0 {
			a.log.Info("hls ffmpeg stderr", "item", item.ID, "session", sessionID, "stderr", string(b))
		}
		err := cmd.Wait()
		if err != nil {
			a.log.Warn("hls ffmpeg exited", "item", item.ID, "session", sessionID, "error", err)
		} else {
			a.log.Info("hls ffmpeg finished", "item", item.ID, "session", sessionID)
		}
		_ = ctx
	}()
	return sess, nil
}

func (a *App) stopHLSSession(id string, sess *hlsSession) {
	if sess == nil {
		return
	}
	if sess.cmd != nil && sess.cmd.Process != nil {
		a.log.Info("hls session stopping", "session", id, "pid", sess.cmd.Process.Pid)
		_ = sess.cmd.Process.Kill()
	}
	if sess.done != nil {
		select {
		case <-sess.done:
		case <-time.After(2 * time.Second):
			a.log.Warn("hls session did not exit promptly", "session", id)
		}
	}
	if sess.dir != "" {
		_ = os.RemoveAll(sess.dir)
	}
}

func (a *App) lookupItem(w http.ResponseWriter, r *http.Request) (media.Item, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid item id", http.StatusBadRequest)
		return media.Item{}, false
	}
	item, err := a.store.GetItem(r.Context(), id)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return media.Item{}, false
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return media.Item{}, false
	}
	return item, true
}

func (a *App) requireUser(w http.ResponseWriter, r *http.Request) (auth.User, bool) {
	if a.auth == nil {
		http.Error(w, "auth unavailable", http.StatusServiceUnavailable)
		return auth.User{}, false
	}
	token := bearerToken(r)
	if token == "" {
		if cookie, err := r.Cookie("popcorn_token"); err == nil {
			token = cookie.Value
		}
	}
	user, err := a.auth.UserByToken(r.Context(), token)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return auth.User{}, false
	}
	return user, true
}

func (a *App) requireAdmin(w http.ResponseWriter, r *http.Request) (auth.User, bool) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return auth.User{}, false
	}
	if !user.IsAdmin {
		http.Error(w, "forbidden", http.StatusForbidden)
		return auth.User{}, false
	}
	return user, true
}

func bearerToken(r *http.Request) string {
	authHeader := r.Header.Get("Authorization")
	if len(authHeader) > 7 && strings.EqualFold(authHeader[:7], "Bearer ") {
		return strings.TrimSpace(authHeader[7:])
	}
	return ""
}

func parseBandwidth(v string) int {
	n, err := strconv.Atoi(v)
	if err != nil || n <= 0 {
		return 4000
	}
	if n < 500 {
		return 500
	}
	if n > 50000 {
		return 50000
	}
	return n
}

func parseStart(v string, durationMS int64) float64 {
	start, err := strconv.ParseFloat(v, 64)
	if err != nil || start < 0 {
		return 0
	}
	if durationMS > 0 {
		maxStart := float64(durationMS/1000) - 2
		if maxStart > 0 && start > maxStart {
			return maxStart
		}
	}
	return start
}

func parseOptionalInt(v string) *int {
	if v == "" || v == "none" {
		return nil
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return nil
	}
	return &n
}

func optionalIntValue(v *int) any {
	if v == nil {
		return nil
	}
	return *v
}

func (a *App) textSubtitleStream(ctx context.Context, item media.Item, subtitle *int, target string) *int {
	if subtitle == nil {
		return nil
	}
	streams, err := probeStreams(ctx, a.cfg.FFprobePath, item.Path)
	if err != nil {
		a.log.Warn("subtitle probe failed", "item", item.ID, "subtitle", *subtitle, "target", target, "error", err)
		return nil
	}
	for _, st := range streams {
		if st.Index != *subtitle || st.Type != "subtitle" {
			continue
		}
		if isTextSubtitleCodec(st.Codec) {
			return subtitle
		}
		a.log.Info("subtitle disabled", "item", item.ID, "subtitle", *subtitle, "target", target, "codec", st.Codec, "reason", "non-text subtitle cannot be converted")
		return nil
	}
	a.log.Info("subtitle disabled", "item", item.ID, "subtitle", *subtitle, "target", target, "reason", "subtitle stream not found")
	return nil
}

func isTextSubtitleCodec(codec string) bool {
	switch strings.ToLower(codec) {
	case "subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text":
		return true
	default:
		return false
	}
}

func transcodeArgs(cfg config.Config, input string, bandwidth int, start float64, audio, subtitle *int) []string {
	videoRate := bandwidth * 85 / 100
	audioRate := bandwidth - videoRate
	if audioRate < 96 {
		audioRate = 96
	}
	args := []string{"-hide_banner", "-loglevel", "warning"}
	inputSeek, outputSeek := transcodeSeekArgs(start)
	args = append(args, inputSeek...)
	args = append(args, hwInputArgs(cfg.HWAccel)...)
	args = append(args, "-i", input)
	args = append(args, outputSeek...)
	args = append(args, "-map", "0:v:0")
	if audio != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *audio))
	} else {
		args = append(args, "-map", "0:a:0?")
	}
	if subtitle != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *subtitle))
	} else {
		args = append(args, "-sn")
	}
	args = append(args, "-dn")
	args = append(args, hwCodecArgs(cfg.HWAccel, cfg.HWDevice)...)
	args = append(args,
		"-b:v", fmt.Sprintf("%dk", videoRate),
		"-maxrate", fmt.Sprintf("%dk", videoRate),
		"-bufsize", fmt.Sprintf("%dk", videoRate*2),
		"-c:a", "aac",
		"-b:a", fmt.Sprintf("%dk", audioRate),
		"-ac", "2",
		"-c:s", "mov_text",
		"-movflags", "frag_keyframe+empty_moov+default_base_moof",
		"-f", "mp4",
		"pipe:1",
	)
	return args
}

func hlsArgs(cfg config.Config, input, segmentPattern, playlist string, bandwidth int, start float64, audio, subtitle *int) []string {
	videoRate := bandwidth * 85 / 100
	audioRate := bandwidth - videoRate
	if audioRate < 96 {
		audioRate = 96
	}
	args := []string{"-hide_banner", "-loglevel", "warning"}
	inputSeek, outputSeek := transcodeSeekArgs(start)
	args = append(args, inputSeek...)
	args = append(args, hwInputArgs(cfg.HWAccel)...)
	args = append(args, "-i", input)
	args = append(args, outputSeek...)
	args = append(args, "-map", "0:v:0")
	if audio != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *audio))
	} else {
		args = append(args, "-map", "0:a:0?")
	}
	if subtitle != nil {
		args = append(args, "-map", fmt.Sprintf("0:%d?", *subtitle))
	} else {
		args = append(args, "-sn")
	}
	args = append(args, "-dn")
	args = append(args, hwCodecArgs(cfg.HWAccel, cfg.HWDevice)...)
	args = append(args,
		"-b:v", fmt.Sprintf("%dk", videoRate),
		"-maxrate", fmt.Sprintf("%dk", videoRate),
		"-bufsize", fmt.Sprintf("%dk", videoRate*2),
		"-force_key_frames", "expr:gte(t,n_forced*4)",
		"-c:a", "aac",
		"-b:a", fmt.Sprintf("%dk", audioRate),
		"-ac", "2",
		"-c:s", "webvtt",
		"-f", "hls",
		"-hls_time", "4",
		"-hls_init_time", "1",
		"-hls_list_size", "0",
		"-hls_flags", "independent_segments",
		"-hls_segment_type", "fmp4",
		"-hls_fmp4_init_filename", "init.mp4",
		"-hls_segment_filename", segmentPattern,
		playlist,
	)
	return args
}

func transcodeSeekArgs(start float64) ([]string, []string) {
	if start <= 0 {
		return nil, nil
	}
	const accurateWindow = 8.0
	if start <= accurateWindow {
		return nil, []string{"-ss", strconv.FormatFloat(start, 'f', 3, 64)}
	}
	return []string{"-ss", strconv.FormatFloat(start-accurateWindow, 'f', 3, 64)}, []string{"-ss", strconv.FormatFloat(accurateWindow, 'f', 3, 64)}
}

func cleanSessionID(v string) string {
	if len(v) > 64 {
		return ""
	}
	for _, r := range v {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			continue
		}
		return ""
	}
	return v
}

func waitForFile(ctx context.Context, path string, timeout time.Duration) error {
	return waitForFileOrDone(ctx, path, timeout, nil)
}

func waitForFileOrDone(ctx context.Context, path string, timeout time.Duration, done <-chan struct{}) error {
	deadline := time.Now().Add(timeout)
	for {
		info, err := os.Stat(path)
		if err == nil && info.Size() > 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timed out waiting for %s", filepath.Base(path))
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-done:
			return fmt.Errorf("transcoder exited before %s was written", filepath.Base(path))
		case <-time.After(100 * time.Millisecond):
		}
	}
}

func rewritePlaylistSegments(path, prefix string) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	lines := strings.Split(string(b), "\n")
	changed := false
	for i, line := range lines {
		if strings.HasPrefix(line, `#EXT-X-MAP:URI="`) && strings.Contains(line, `init.mp4`) && !strings.Contains(line, prefix) {
			lines[i] = strings.Replace(line, `URI="init.mp4"`, `URI="`+prefix+`init.mp4"`, 1)
			changed = true
			continue
		}
		if strings.HasPrefix(line, "seg_") && !strings.HasPrefix(line, prefix) {
			lines[i] = prefix + line
			changed = true
		}
	}
	if !changed {
		return nil
	}
	return os.WriteFile(path, []byte(strings.Join(lines, "\n")), 0o644)
}

type streamInfo struct {
	Index    int    `json:"index"`
	Type     string `json:"type"`
	Codec    string `json:"codec,omitempty"`
	Language string `json:"language,omitempty"`
	Title    string `json:"title,omitempty"`
	Default  bool   `json:"default,omitempty"`
	Forced   bool   `json:"forced,omitempty"`
}

func probeStreams(ctx context.Context, ffprobe, path string) ([]streamInfo, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, ffprobe, "-v", "error", "-show_streams", "-of", "json", path)
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	var raw struct {
		Streams []struct {
			Index       int               `json:"index"`
			CodecType   string            `json:"codec_type"`
			CodecName   string            `json:"codec_name"`
			Tags        map[string]string `json:"tags"`
			Disposition struct {
				Default int `json:"default"`
				Forced  int `json:"forced"`
			} `json:"disposition"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(out, &raw); err != nil {
		return nil, err
	}
	outStreams := []streamInfo{}
	for _, st := range raw.Streams {
		if st.CodecType != "audio" && st.CodecType != "subtitle" {
			continue
		}
		outStreams = append(outStreams, streamInfo{
			Index:    st.Index,
			Type:     st.CodecType,
			Codec:    st.CodecName,
			Language: st.Tags["language"],
			Title:    st.Tags["title"],
			Default:  st.Disposition.Default == 1,
			Forced:   st.Disposition.Forced == 1,
		})
	}
	return outStreams, nil
}

func hwInputArgs(mode string) []string {
	switch strings.ToLower(mode) {
	case "none", "":
		return nil
	case "vaapi":
		return nil
	case "qsv":
		return []string{"-hwaccel", "qsv"}
	case "cuda", "nvenc", "auto":
		return []string{"-hwaccel", "auto"}
	default:
		return []string{"-hwaccel", mode}
	}
}

func hwCodecArgs(mode, device string) []string {
	switch strings.ToLower(mode) {
	case "nvenc", "cuda":
		return []string{"-c:v", "h264_nvenc", "-preset", "p4"}
	case "qsv":
		return []string{"-c:v", "h264_qsv", "-preset", "veryfast"}
	case "vaapi":
		args := []string{}
		if device != "" {
			args = append(args, "-vaapi_device", device)
		}
		return append(args, "-vf", "format=nv12,hwupload", "-c:v", "h264_vaapi")
	default:
		return []string{"-c:v", "libx264", "-preset", "veryfast"}
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func logging(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Debug("request", "method", r.Method, "path", r.URL.Path, "elapsed", time.Since(start))
	})
}

func noCache(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func spaFiles() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		path := strings.TrimPrefix(r.URL.Path, "/")
		if path == "" {
			path = "index.html"
		}
		if path == "remote" {
			path = "remote.html"
		}
		if f, err := web.Files.Open(path); err == nil {
			_ = f.Close()
		} else {
			path = "index.html"
		}
		http.ServeFileFS(w, r, web.Files, path)
	})
}
