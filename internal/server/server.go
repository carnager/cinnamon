package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"popcorn/internal/auth"
	"popcorn/internal/config"
	"popcorn/internal/media"
	"popcorn/internal/version"
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
	playbackMu  sync.Mutex
	plans       map[string]PlaybackPlan
	failHints   map[string]time.Time
	loginMu     sync.Mutex
	loginFails  map[string]loginAttempt
}

type loginAttempt struct {
	Failures     int
	FirstFailure time.Time
	BlockedUntil time.Time
}

func New(opts Options) *App {
	ctx, cancel := context.WithCancel(context.Background())
	return &App{
		cfg:         opts.Config,
		log:         opts.Log,
		store:       opts.Store,
		auth:        opts.Auth,
		ctx:         ctx,
		cancel:      cancel,
		hlsSessions: map[string]*hlsSession{},
		plans:       map[string]PlaybackPlan{},
		failHints:   map[string]time.Time{},
		loginFails:  map[string]loginAttempt{},
	}
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
	mux.HandleFunc("GET /api/app/tv/update", a.tvAppUpdate)
	mux.HandleFunc("GET /api/app/tv/apk", a.tvAppAPK)
	mux.HandleFunc("GET /api/app/companion/update", a.companionAppUpdate)
	mux.HandleFunc("GET /api/app/companion/apk", a.companionAppAPK)
	mux.HandleFunc("GET /api/app/updates", a.appUpdates)
	mux.HandleFunc("POST /api/app/tv/upload", a.uploadTVApp)
	mux.HandleFunc("POST /api/app/companion/upload", a.uploadCompanionApp)
	mux.HandleFunc("GET /api/libraries", a.libraries)
	mux.HandleFunc("POST /api/scan", a.scan)
	mux.HandleFunc("GET /api/scan", a.scanStatus)
	mux.HandleFunc("GET /api/items", a.items)
	mux.HandleFunc("GET /api/search", a.search)
	mux.HandleFunc("GET /api/genres", a.genres)
	mux.HandleFunc("GET /api/alphabet", a.alphabet)
	mux.HandleFunc("GET /api/actors", a.actorDetail)
	mux.HandleFunc("GET /api/actors/image", a.actorImage)
	mux.HandleFunc("GET /api/tv/shows", a.tvShows)
	mux.HandleFunc("GET /api/tv/shows/actors", a.tvShowActors)
	mux.HandleFunc("GET /api/tv/seasons", a.tvSeasons)
	mux.HandleFunc("GET /api/tv/seasons/actors", a.tvSeasonActors)
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
	mux.HandleFunc("PUT /api/users/{id}", a.updateUser)
	mux.HandleFunc("GET /api/progress", a.progressList)
	mux.HandleFunc("GET /api/progress/tv", a.progressShows)
	mux.HandleFunc("PUT /api/progress/tv", a.progressShowSave)
	mux.HandleFunc("DELETE /api/progress/tv", a.progressShowDelete)
	mux.HandleFunc("PUT /api/progress/tv/season", a.progressSeasonSave)
	mux.HandleFunc("DELETE /api/progress/tv/season", a.progressSeasonDelete)
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
	mux.HandleFunc("POST /api/trakt/import-export-upload", a.traktImportExportUpload)
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
	mux.HandleFunc("POST /api/playback/plan", a.playbackPlan)
	mux.HandleFunc("POST /api/playback/failure", a.playbackFailure)
	mux.HandleFunc("GET /api/items/{id}/sidecars", a.itemSidecars)
	mux.HandleFunc("GET /api/items/{id}/trailer", a.itemTrailer)
	mux.HandleFunc("GET /api/tv/theme", a.showTheme)
	mux.HandleFunc("GET /api/items/{id}/stream", a.stream)
	mux.HandleFunc("GET /api/items/{id}/subtitles/{subtitle}", a.subtitle)
	mux.HandleFunc("GET /api/items/{id}/transcode", a.transcode)
	mux.HandleFunc("GET /api/items/{id}/hls/{session}/index.m3u8", a.hlsPlaylist)
	mux.HandleFunc("GET /api/items/{id}/hls/{session}/{segment}", a.hlsSegment)
	mux.HandleFunc("DELETE /api/hls/{session}", a.hlsStop)
	mux.HandleFunc("GET /api/items/{id}/image/{kind}", a.image)
	mux.Handle("GET /web", noCache(popcornWebFiles("/web")))
	mux.Handle("GET /web/", noCache(popcornWebFiles("/web")))
	mux.Handle("GET /popcorn", noCache(popcornWebFiles("/popcorn")))
	mux.Handle("GET /popcorn/", noCache(popcornWebFiles("/popcorn")))
	mux.Handle("/", noCache(popcornWebFiles("")))
	return logging(a.log, a.authGate(mux))
}

func (a *App) authGate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		needsAuth := strings.HasPrefix(r.URL.Path, "/api/")
		if !needsAuth || publicAPIRoute(r) {
			next.ServeHTTP(w, r)
			return
		}
		if _, ok := a.requireUser(w, r); !ok {
			return
		}
		next.ServeHTTP(w, r)
	})
}

func publicAPIRoute(r *http.Request) bool {
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/api/health":
		return true
	case r.Method == http.MethodPost && r.URL.Path == "/api/auth/login":
		return true
	case r.Method == http.MethodPost && r.URL.Path == "/api/auth/qr/start":
		return true
	case r.Method == http.MethodGet && r.URL.Path == "/api/auth/qr/poll":
		return true
	default:
		return false
	}
}

func (a *App) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":                         true,
		"version":                    version.Version,
		"commit":                     version.Commit,
		"buildTime":                  version.BuildTime,
		"tvUpdateConfigured":         a.cfg.AppUpdate.TVAPKPath != "",
		"tvUpdateVersionCode":        a.cfg.AppUpdate.TVVersionCode,
		"tvUpdateVersionName":        a.cfg.AppUpdate.TVVersionName,
		"companionUpdateConfigured":  a.cfg.AppUpdate.CompanionAPKPath != "",
		"companionUpdateVersionCode": a.cfg.AppUpdate.CompanionVersionCode,
		"companionUpdateVersionName": a.cfg.AppUpdate.CompanionVersionName,
	})
}

func (a *App) libraries(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, a.cfg.Libraries)
}

func (a *App) scan(w http.ResponseWriter, r *http.Request) {
	release, ok := media.TryStartScan()
	if !ok {
		http.Error(w, "scan already running", http.StatusConflict)
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), a.cfg.ScanTimeout)
		defer cancel()
		if err := media.NewScanner(a.cfg, a.store, a.log).ScanWithLease(ctx, release); err != nil {
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
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	minRating, _ := strconv.ParseFloat(r.URL.Query().Get("minRating"), 64)
	items, err := a.store.ListItemsForUser(r.Context(), r.URL.Query().Get("libraryId"), r.URL.Query().Get("q"), r.URL.Query().Get("genre"), r.URL.Query().Get("sort"), r.URL.Query().Get("seen"), user.ID, minRating, limit, offset)
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

func (a *App) alphabet(w http.ResponseWriter, r *http.Request) {
	entries, err := a.store.AlphabetIndex(r.Context(), media.AlphabetOptions{
		LibraryID: r.URL.Query().Get("libraryId"),
		Kind:      r.URL.Query().Get("kind"),
		Genre:     r.URL.Query().Get("genre"),
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, entries)
}

func (a *App) search(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	items, err := a.store.SearchItems(r.Context(), media.SearchOptions{
		Query:     r.URL.Query().Get("q"),
		LibraryID: r.URL.Query().Get("libraryId"),
		Kind:      r.URL.Query().Get("kind"),
		Genre:     r.URL.Query().Get("genre"),
		Sort:      r.URL.Query().Get("sort"),
		MinRating: queryFloat(r, "minRating"),
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
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	shows, err := a.store.ListShowsForUser(r.Context(), r.URL.Query().Get("libraryId"), r.URL.Query().Get("q"), r.URL.Query().Get("genre"), r.URL.Query().Get("sort"), r.URL.Query().Get("seen"), user.ID, queryFloat(r, "minRating"), limit, offset)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, shows)
}

func (a *App) actorDetail(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if name == "" {
		http.Error(w, "name is required", http.StatusBadRequest)
		return
	}
	actor, err := a.store.ActorByName(r.Context(), name)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	info := a.actorInfo(r.Context(), actor)
	apiActor := a.actorForResponse(actor)
	profileURL := ""
	if strings.TrimSpace(apiActor.Thumb) != "" {
		profileURL = strings.TrimSpace(apiActor.Thumb)
	} else {
		profileURL = tmdbProfileURL(info.ProfilePath)
	}
	movies, err := a.store.ListItemsByActor(r.Context(), actor.Name, "", "movie", "recent", 60, 0)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	shows, err := a.store.ListShowsByActor(r.Context(), actor.Name, "", "recent", 60, 0)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"actor":      apiActor,
		"info":       info,
		"profileUrl": profileURL,
		"movies":     movies,
		"shows":      shows,
	})
}

func (a *App) actorImage(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if name == "" {
		http.Error(w, "name is required", http.StatusBadRequest)
		return
	}
	actor, err := a.store.ActorByName(r.Context(), name)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	path := localActorThumbPath(actor.Thumb)
	if path == "" {
		http.NotFound(w, r)
		return
	}
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, path)
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

func (a *App) tvShowActors(w http.ResponseWriter, r *http.Request) {
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return
	}
	actors, err := a.store.ListShowActors(r.Context(), libraryID, showTitle)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, a.actorsForResponse(actors))
}

func (a *App) tvSeasonActors(w http.ResponseWriter, r *http.Request) {
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	season, err := strconv.Atoi(r.URL.Query().Get("season"))
	if libraryID == "" || showTitle == "" || err != nil {
		http.Error(w, "libraryId, showTitle, and season are required", http.StatusBadRequest)
		return
	}
	actors, err := a.store.ListSeasonActors(r.Context(), libraryID, showTitle, season)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, a.actorsForResponse(actors))
}

func queryFloat(r *http.Request, key string) float64 {
	value, _ := strconv.ParseFloat(r.URL.Query().Get(key), 64)
	if value < 0 {
		return 0
	}
	return value
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
	item.Actors = a.enrichedItemActors(r.Context(), item)
	item.Actors = a.actorsForResponse(item.Actors)
	writeJSON(w, http.StatusOK, item)
}

func (a *App) streams(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	streams, err := a.itemStreams(r.Context(), item)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, streams)
}

func (a *App) itemStreams(ctx context.Context, item media.Item) ([]media.MediaStream, error) {
	streams, err := a.store.MediaStreams(ctx, item.ID)
	if err != nil {
		return nil, err
	}
	if len(streams) > 0 {
		return streams, nil
	}
	probe := media.ProbeMedia(ctx, a.cfg.FFprobePath, item.Path)
	for i := range probe.Streams {
		probe.Streams[i].ItemID = item.ID
	}
	if len(probe.Streams) > 0 {
		if err := a.store.ReplaceMediaStreams(ctx, item.ID, probe.Streams); err != nil {
			return nil, err
		}
		return probe.Streams, nil
	}
	streams = legacyItemStreams(item)
	if len(streams) > 0 {
		a.log.Warn("using legacy media stream summary", "item", item.ID, "video", item.VideoCodec, "audio", item.AudioCodec, "reason", "ffprobe returned no streams")
		return streams, nil
	}
	if err := a.store.ReplaceMediaStreams(ctx, item.ID, nil); err != nil {
		return nil, err
	}
	return nil, nil
}

func legacyItemStreams(item media.Item) []media.MediaStream {
	streams := []media.MediaStream{}
	if item.VideoCodec != "" {
		streams = append(streams, media.MediaStream{
			ItemID:    item.ID,
			Index:     0,
			Type:      "video",
			Codec:     item.VideoCodec,
			Width:     item.Width,
			Height:    item.Height,
			HDRFormat: "sdr",
			BitRate:   item.BitRate,
		})
	}
	if item.AudioCodec != "" {
		streams = append(streams, media.MediaStream{
			ItemID:   item.ID,
			Index:    1,
			Type:     "audio",
			Codec:    item.AudioCodec,
			Channels: 2,
			Default:  true,
		})
	}
	return streams
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
	case "clearlogo":
		path = media.ClearLogoArtworkPath(a.libraryRoot(item.LibraryID), item)
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

func (a *App) enrichedItemActors(ctx context.Context, item media.Item) []media.Actor {
	actors := append([]media.Actor(nil), item.Actors...)
	if item.Kind != "episode" || strings.TrimSpace(item.LibraryID) == "" || strings.TrimSpace(item.ShowTitle) == "" {
		return actors
	}
	showActors, err := a.store.ListShowActors(ctx, item.LibraryID, item.ShowTitle)
	if err != nil || len(showActors) == 0 {
		return actors
	}

	showByName := make(map[string]media.Actor, len(showActors))
	for _, actor := range showActors {
		key := actorNameKey(actor.Name)
		if key != "" {
			showByName[key] = actor
		}
	}

	seen := make(map[string]struct{}, len(actors)+len(showActors))
	for i := range actors {
		key := actorNameKey(actors[i].Name)
		if key == "" {
			continue
		}
		seen[key] = struct{}{}
		if strings.TrimSpace(actors[i].Thumb) == "" {
			if showActor, ok := showByName[key]; ok && strings.TrimSpace(showActor.Thumb) != "" {
				actors[i].Thumb = showActor.Thumb
			}
		}
	}

	for _, actor := range showActors {
		key := actorNameKey(actor.Name)
		if key == "" {
			continue
		}
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		actors = append(actors, actor)
	}
	return actors
}

func actorNameKey(name string) string {
	return strings.ToLower(strings.Join(strings.Fields(strings.TrimSpace(name)), " "))
}

func (a *App) actorsForResponse(actors []media.Actor) []media.Actor {
	out := make([]media.Actor, len(actors))
	for i, actor := range actors {
		out[i] = a.actorForResponse(actor)
	}
	return out
}

func (a *App) actorForResponse(actor media.Actor) media.Actor {
	if localActorThumbPath(actor.Thumb) == "" {
		return actor
	}
	values := url.Values{}
	values.Set("name", actor.Name)
	if info, err := os.Stat(actor.Thumb); err == nil {
		values.Set("v", strconv.FormatInt(info.ModTime().Unix(), 10))
	}
	actor.Thumb = "/api/actors/image?" + values.Encode()
	return actor
}

func localActorThumbPath(thumb string) string {
	thumb = strings.TrimSpace(thumb)
	if thumb == "" || strings.HasPrefix(thumb, "http://") || strings.HasPrefix(thumb, "https://") || strings.HasPrefix(thumb, "/api/") {
		return ""
	}
	if !filepath.IsAbs(thumb) {
		return ""
	}
	return thumb
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

func popcornWebFiles(mount string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		path := r.URL.Path
		if mount != "" {
			path = strings.TrimPrefix(path, mount)
		}
		path = strings.TrimPrefix(path, "/")
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
