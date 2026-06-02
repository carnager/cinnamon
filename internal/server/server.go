package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
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
