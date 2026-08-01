package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
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
	cfg               config.Config
	log               *slog.Logger
	store             *media.Store
	auth              *auth.Store
	ctx               context.Context
	cancel            context.CancelFunc
	hlsMu             sync.Mutex
	hlsSessions       map[string]*hlsSession
	playbackMu        sync.Mutex
	plans             map[string]PlaybackPlan
	failHints         map[string]time.Time
	loginMu           sync.Mutex
	loginFails        map[string]loginAttempt
	cache             responseCache
	cacheGen          atomic.Uint64
	historyMu         sync.Mutex
	history           map[int64]historyCacheEntry
	traktLastWrite    time.Time
	traktUpgradeURL   string
	collectionMu      sync.Mutex
	collectionPending bool

	traktLiveMu       sync.Mutex
	traktLiveBuilders map[string]func(context.Context, string, traktLiveScope) ([]traktLiveEntry, error)
	traktLiveRunning  map[string]bool
	similarMu         sync.Mutex
	similar           map[int64]similarCacheEntry
	similarWork       map[int64]bool

	scopedMu      sync.Mutex
	scopedPending map[string]map[string]bool
	scopedRunning map[string]bool

	backfillMu      sync.Mutex
	backfillPending map[string]bool
	backfillRunning bool
}

type responseCache struct {
	mu      sync.Mutex
	entries map[string]cachedResponse
}

type cachedResponse struct {
	body      []byte
	expiresAt time.Time
}

type authUserContextKey struct{}

type loginAttempt struct {
	Failures     int
	FirstFailure time.Time
	BlockedUntil time.Time
}

func New(opts Options) *App {
	ctx, cancel := context.WithCancel(context.Background())
	app := &App{
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
		cache:       responseCache{entries: map[string]cachedResponse{}},
		history:     map[int64]historyCacheEntry{},
		similar:     map[int64]similarCacheEntry{},
		similarWork: map[int64]bool{},

		scopedPending: map[string]map[string]bool{},
		scopedRunning: map[string]bool{},
	}
	app.cleanHLSScratch()
	app.cleanThumbCache()
	go app.reapIdleHLSSessions()
	go app.recommendationPrebuildWorker()
	go app.traktCollectionWorker()
	go app.traktLiveWorker()
	return app
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
	mux.HandleFunc("GET /api/home", a.home)
	mux.HandleFunc("POST /api/scan", a.scan)
	mux.HandleFunc("POST /api/scan/path", a.scanPath)
	mux.HandleFunc("GET /api/scan", a.scanStatus)
	mux.HandleFunc("GET /api/items", a.items)
	mux.HandleFunc("GET /api/search", a.search)
	mux.HandleFunc("GET /api/genres", a.genres)
	mux.HandleFunc("GET /api/alphabet", a.alphabet)
	mux.HandleFunc("GET /api/decades", a.decades)
	mux.HandleFunc("GET /api/actors", a.actorDetail)
	mux.HandleFunc("GET /api/actors/image", a.actorImage)
	mux.HandleFunc("GET /api/tv/shows", a.tvShows)
	mux.HandleFunc("GET /api/tv/shows/actors", a.tvShowActors)
	mux.HandleFunc("GET /api/tv/seasons", a.tvSeasons)
	mux.HandleFunc("GET /api/tv/seasons/actors", a.tvSeasonActors)
	mux.HandleFunc("GET /api/tv/episodes", a.tvEpisodes)
	mux.HandleFunc("GET /api/items/{id}", a.item)
	mux.HandleFunc("GET /api/items/{id}/ratings", a.itemRatings)
	mux.HandleFunc("GET /api/items/{id}/similar", a.itemSimilar)
	mux.HandleFunc("POST /api/auth/login", a.login)
	mux.HandleFunc("POST /api/auth/logout", a.logout)
	mux.HandleFunc("GET /api/auth/me", a.me)
	mux.HandleFunc("POST /api/auth/qr/start", a.authQRStart)
	mux.HandleFunc("GET /api/auth/qr/poll", a.authQRPoll)
	mux.HandleFunc("POST /api/auth/qr/complete", a.authQRComplete)
	mux.HandleFunc("POST /api/auth/qr/claim", a.authQRClaim)
	mux.HandleFunc("GET /api/users", a.users)
	mux.HandleFunc("POST /api/users", a.createUser)
	mux.HandleFunc("PUT /api/users/{id}", a.updateUser)
	mux.HandleFunc("GET /api/users/{id}/avatar", a.userAvatar)
	mux.HandleFunc("POST /api/users/{id}/avatar", a.uploadUserAvatar)
	mux.HandleFunc("DELETE /api/users/{id}/avatar", a.deleteUserAvatar)
	mux.HandleFunc("GET /api/progress", a.progressList)
	mux.HandleFunc("GET /api/history", a.watchHistory)
	mux.HandleFunc("GET /api/progress/tv", a.progressShows)
	mux.HandleFunc("PUT /api/progress/tv", a.progressShowSave)
	mux.HandleFunc("DELETE /api/progress/tv", a.progressShowDelete)
	mux.HandleFunc("PUT /api/progress/tv/season", a.progressSeasonSave)
	mux.HandleFunc("DELETE /api/progress/tv/season", a.progressSeasonDelete)
	mux.HandleFunc("GET /api/items/{id}/progress", a.progressGet)
	mux.HandleFunc("PUT /api/items/{id}/progress", a.progressSave)
	mux.HandleFunc("DELETE /api/items/{id}/progress", a.progressDelete)
	mux.HandleFunc("GET /api/watchlist", a.watchlistGet)
	mux.HandleFunc("GET /api/ratings/user", a.userRatingsGet)
	mux.HandleFunc("PUT /api/items/{id}/rating", a.itemRatingSave)
	mux.HandleFunc("DELETE /api/items/{id}/rating", a.itemRatingDelete)
	mux.HandleFunc("PUT /api/ratings/tv", a.showRatingSave)
	mux.HandleFunc("DELETE /api/ratings/tv", a.showRatingDelete)
	mux.HandleFunc("PUT /api/items/{id}/watchlist", a.watchlistItemSave)
	mux.HandleFunc("DELETE /api/items/{id}/watchlist", a.watchlistItemDelete)
	mux.HandleFunc("PUT /api/watchlist/tv", a.watchlistShowSave)
	mux.HandleFunc("DELETE /api/watchlist/tv", a.watchlistShowDelete)
	mux.HandleFunc("GET /api/home/catalog", a.homeSectionCatalogGet)
	mux.HandleFunc("GET /api/home/facets", a.homeFacets)
	mux.HandleFunc("GET /api/home/layout", a.homeLayoutGet)
	mux.HandleFunc("PUT /api/home/layout", a.homeLayoutSave)
	mux.HandleFunc("DELETE /api/home/layout", a.homeLayoutDelete)
	mux.HandleFunc("GET /api/recommendations/exclusions", a.recommendationExclusionsGet)
	mux.HandleFunc("PUT /api/items/{id}/recommendation-exclusion", a.recommendationItemSave)
	mux.HandleFunc("DELETE /api/items/{id}/recommendation-exclusion", a.recommendationItemDelete)
	mux.HandleFunc("PUT /api/recommendations/exclusions/tv", a.recommendationShowSave)
	mux.HandleFunc("DELETE /api/recommendations/exclusions/tv", a.recommendationShowDelete)
	mux.HandleFunc("POST /api/trakt/import-recommendation-exclusions", a.traktImportRecommendationExclusions)
	mux.HandleFunc("GET /api/trakt/status", a.traktStatus)
	mux.HandleFunc("GET /api/trakt/live/watchlist", a.traktLiveWatchlist)
	mux.HandleFunc("GET /api/trakt/live/history", a.traktLiveHistory)
	mux.HandleFunc("GET /api/trakt/live/recommendations", a.traktLiveRecommendations)
	mux.HandleFunc("GET /api/trakt/live/upcoming", a.traktLiveUpcoming)
	mux.HandleFunc("POST /api/trakt/live/watchlist", a.traktLiveWatchlistAdd)
	mux.HandleFunc("DELETE /api/trakt/live/watchlist", a.traktLiveWatchlistRemove)
	mux.HandleFunc("POST /api/trakt/live/hide", a.traktLiveHide)
	mux.HandleFunc("POST /api/trakt/device", a.traktDeviceCode)
	mux.HandleFunc("POST /api/trakt/device/token", a.traktDeviceToken)
	mux.HandleFunc("POST /api/trakt/sync-collection", a.traktSyncCollectionEndpoint)
	mux.HandleFunc("POST /api/trakt/import-watched", a.traktImportWatched)
	mux.HandleFunc("POST /api/trakt/import-watchlist", a.traktImportWatchlist)
	mux.HandleFunc("POST /api/trakt/import-ratings", a.traktImportRatings)
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
	mux.HandleFunc("POST /api/client/log", a.clientLog)
	mux.HandleFunc("GET /api/items/{id}/streams", a.streams)
	mux.HandleFunc("POST /api/playback/plan", a.playbackPlan)
	mux.HandleFunc("POST /api/playback/failure", a.playbackFailure)
	mux.HandleFunc("GET /api/items/{id}/sidecars", a.itemSidecars)
	mux.HandleFunc("GET /api/items/{id}/trailer", a.itemTrailer)
	mux.HandleFunc("GET /api/tv/theme", a.showTheme)
	mux.HandleFunc("GET /api/items/{id}/stream", a.stream)
	mux.HandleFunc("GET /api/items/{id}/download", a.itemDownload)
	mux.HandleFunc("GET /api/items/{id}/subtitles/{subtitle}", a.subtitle)
	mux.HandleFunc("GET /api/items/{id}/transcode", a.transcode)
	mux.HandleFunc("GET /api/items/{id}/hls/{session}/index.m3u8", a.hlsPlaylist)
	mux.HandleFunc("GET /api/items/{id}/hls/{session}/{segment}", a.hlsSegment)
	mux.HandleFunc("DELETE /api/hls/{session}", a.hlsStop)
	mux.HandleFunc("GET /api/items/{id}/image/{kind}", a.image)
	mux.Handle("GET /web", popcornWebFiles("/web"))
	mux.Handle("GET /web/", popcornWebFiles("/web"))
	mux.Handle("GET /popcorn", popcornWebFiles("/popcorn"))
	mux.Handle("GET /popcorn/", popcornWebFiles("/popcorn"))
	mux.Handle("/", popcornWebFiles(""))
	return logging(a.log, a.authGate(mux))
}

func (a *App) authGate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		needsAuth := strings.HasPrefix(r.URL.Path, "/api/")
		if !needsAuth || publicAPIRoute(r) {
			next.ServeHTTP(w, r)
			return
		}
		user, ok := a.requireUser(w, r)
		if !ok {
			return
		}
		r = r.WithContext(context.WithValue(r.Context(), authUserContextKey{}, user))
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
	case r.Method == http.MethodPost && r.URL.Path == "/api/auth/qr/claim":
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
	if _, ok := a.requireAdmin(w, r); !ok {
		return
	}
	release, ok := media.TryStartScan()
	if !ok {
		http.Error(w, "scan already running", http.StatusConflict)
		return
	}
	a.invalidateResponseCache()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), a.cfg.ScanTimeout)
		defer cancel()
		scanner := media.NewScanner(a.cfg, a.store, a.log)
		scanner.OnItemsAdded = a.ItemsAdded
		if err := scanner.ScanWithLease(ctx, release); err != nil {
			a.log.Error("library update failed", "error", err)
		}
		a.invalidateResponseCache()
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
	a.writeCachedJSON(w, r, cacheKey(r, "items", user.ID), 20*time.Second, func() (any, error) {
		return a.store.ListItemsForUser(r.Context(), r.URL.Query().Get("libraryId"), r.URL.Query().Get("q"), r.URL.Query().Get("genre"), r.URL.Query().Get("decades"), r.URL.Query().Get("sort"), r.URL.Query().Get("seen"), user.ID, minRating, limit, offset)
	})
}

func (a *App) genres(w http.ResponseWriter, r *http.Request) {
	a.writeCachedJSON(w, r, cacheKey(r, "genres"), 1*time.Minute, func() (any, error) {
		return a.store.ListGenres(r.Context(), r.URL.Query().Get("libraryId"))
	})
}

func (a *App) alphabet(w http.ResponseWriter, r *http.Request) {
	a.writeCachedJSON(w, r, cacheKey(r, "alphabet"), 1*time.Minute, func() (any, error) {
		return a.store.AlphabetIndex(r.Context(), media.AlphabetOptions{
			LibraryID: r.URL.Query().Get("libraryId"),
			Kind:      r.URL.Query().Get("kind"),
			Genre:     r.URL.Query().Get("genre"),
			Decades:   r.URL.Query().Get("decades"),
		})
	})
}

func (a *App) decades(w http.ResponseWriter, r *http.Request) {
	a.writeCachedJSON(w, r, cacheKey(r, "decades"), 1*time.Minute, func() (any, error) {
		return a.store.ListDecades(r.Context(), r.URL.Query().Get("libraryId"), r.URL.Query().Get("kind"))
	})
}

func (a *App) search(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	a.writeCachedJSON(w, r, cacheKey(r, "search"), 20*time.Second, func() (any, error) {
		items, err := a.store.SearchItems(r.Context(), media.SearchOptions{
			Query:        r.URL.Query().Get("q"),
			LibraryID:    r.URL.Query().Get("libraryId"),
			Kind:         r.URL.Query().Get("kind"),
			Genre:        r.URL.Query().Get("genre"),
			Sort:         r.URL.Query().Get("sort"),
			SearchFields: r.URL.Query().Get("fields"),
			MinRating:    queryFloat(r, "minRating"),
			Limit:        limit,
			Offset:       offset,
		})
		if err != nil {
			return nil, err
		}
		return map[string]any{
			"query":  r.URL.Query().Get("q"),
			"items":  items,
			"limit":  limit,
			"offset": offset,
		}, nil
	})
}

func (a *App) tvShows(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	a.writeCachedJSON(w, r, cacheKey(r, "tvShows", user.ID), 20*time.Second, func() (any, error) {
		return a.store.ListShowsForUserWithFields(r.Context(), r.URL.Query().Get("libraryId"), r.URL.Query().Get("q"), r.URL.Query().Get("genre"), r.URL.Query().Get("decades"), r.URL.Query().Get("sort"), r.URL.Query().Get("seen"), user.ID, queryFloat(r, "minRating"), r.URL.Query().Get("fields"), limit, offset)
	})
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
	a.writeCachedJSON(w, r, cacheKey(r, "actor", actor.Name), 1*time.Minute, func() (any, error) {
		info := a.actorInfo(r.Context(), actor)
		movies, err := a.store.ListItemsByActor(r.Context(), actor.Name, "", "movie", "recent", 60, 0)
		if err != nil {
			return nil, err
		}
		shows, err := a.store.ListShowsByActor(r.Context(), actor.Name, "", "recent", 60, 0)
		if err != nil {
			return nil, err
		}
		apiActor := a.actorForResponse(actor)
		profileURL := a.actorProfileURLFromCredits(r.Context(), actor.Name, movies, shows)
		if profileURL != "" {
			apiActor.Thumb = profileURL
		} else if strings.TrimSpace(apiActor.Thumb) != "" {
			profileURL = strings.TrimSpace(apiActor.Thumb)
		} else {
			profileURL = tmdbProfileURL(info.ProfilePath)
		}
		return map[string]any{
			"actor":      apiActor,
			"info":       info,
			"profileUrl": profileURL,
			"movies":     movies,
			"shows":      shows,
		}, nil
	})
}

func (a *App) actorImage(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if name == "" {
		http.Error(w, "name is required", http.StatusBadRequest)
		return
	}
	path := a.localActorThumbFromRequest(r.Context(), r, name)
	if path == "" {
		actor, err := a.store.ActorByName(r.Context(), name)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				http.NotFound(w, r)
				return
			}
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		path = localActorThumbPath(actor.Thumb)
	}
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
	a.writeCachedJSON(w, r, cacheKey(r, "tvSeasons"), 30*time.Second, func() (any, error) {
		return a.store.ListSeasons(r.Context(), libraryID, showTitle)
	})
}

func (a *App) tvShowActors(w http.ResponseWriter, r *http.Request) {
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	if libraryID == "" || showTitle == "" {
		http.Error(w, "libraryId and showTitle are required", http.StatusBadRequest)
		return
	}
	a.writeCachedJSON(w, r, cacheKey(r, "tvShowActors"), 1*time.Minute, func() (any, error) {
		actors, err := a.store.ListShowActors(r.Context(), libraryID, showTitle)
		if err != nil {
			return nil, err
		}
		return a.actorsForShowResponse(r.Context(), libraryID, showTitle, actors), nil
	})
}

func (a *App) tvSeasonActors(w http.ResponseWriter, r *http.Request) {
	libraryID := r.URL.Query().Get("libraryId")
	showTitle := r.URL.Query().Get("showTitle")
	season, err := strconv.Atoi(r.URL.Query().Get("season"))
	if libraryID == "" || showTitle == "" || err != nil {
		http.Error(w, "libraryId, showTitle, and season are required", http.StatusBadRequest)
		return
	}
	a.writeCachedJSON(w, r, cacheKey(r, "tvSeasonActors"), 1*time.Minute, func() (any, error) {
		actors, err := a.store.ListSeasonActors(r.Context(), libraryID, showTitle, season)
		if err != nil {
			return nil, err
		}
		return a.actorsForShowResponse(r.Context(), libraryID, showTitle, actors), nil
	})
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
	a.writeCachedJSON(w, r, cacheKey(r, "tvEpisodes"), 30*time.Second, func() (any, error) {
		return a.store.ListEpisodes(r.Context(), libraryID, showTitle, season)
	})
}

func (a *App) item(w http.ResponseWriter, r *http.Request) {
	item, ok := a.lookupItem(w, r)
	if !ok {
		return
	}
	a.writeCachedJSON(w, r, cacheKey(r, "item", item.ID), 30*time.Second, func() (any, error) {
		item.Actors = a.enrichedItemActors(r.Context(), item)
		item.Actors = a.actorsForItemResponse(item, item.Actors)
		return item, nil
	})
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
	path := media.ResolveExistingPath(item.Path)
	if path == "" {
		return legacyItemStreams(item), nil
	}
	probe := media.ProbeMedia(ctx, a.cfg.FFprobePath, path)
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
	path := media.ResolveExistingPath(item.Path)
	if path == "" {
		http.Error(w, "media unavailable", http.StatusNotFound)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "media unavailable", http.StatusNotFound)
		return
	}
	defer f.Close()
	modTime := time.Unix(item.MTimeUnix, 0)
	if info, err := f.Stat(); err == nil {
		modTime = info.ModTime()
	}
	http.ServeContent(w, r, filepath.Base(path), modTime, f)
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
	path = media.ResolveExistingPath(path)
	if path == "" {
		http.NotFound(w, r)
		return
	}
	if width := normalizeThumbWidth(r.URL.Query().Get("w")); width > 0 {
		if thumb := a.thumbnail(r.Context(), path, width); thumb != "" {
			path = thumb
		}
	}
	// Clients version these URLs with a ?v= param derived from the artwork
	// mtime, so a changed poster changes the URL and immutable is safe.
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
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

func (a *App) actorsForItemResponse(item media.Item, actors []media.Actor) []media.Actor {
	out := make([]media.Actor, len(actors))
	for i, actor := range actors {
		if thumb := a.localActorThumbForItem(item, actor.Name); thumb != "" {
			actor.Thumb = a.actorImageURL(actor.Name, thumb, map[string]string{
				"itemId": strconv.FormatInt(item.ID, 10),
			})
		} else {
			actor = a.actorForResponse(actor)
		}
		out[i] = actor
	}
	return out
}

func (a *App) actorsForShowResponse(ctx context.Context, libraryID, showTitle string, actors []media.Actor) []media.Actor {
	sample, err := a.store.ShowSampleItem(ctx, libraryID, showTitle)
	if err != nil {
		return a.actorsForResponse(actors)
	}
	out := make([]media.Actor, len(actors))
	for i, actor := range actors {
		if thumb := a.localActorThumbForShow(sample, actor.Name); thumb != "" {
			actor.Thumb = a.actorImageURL(actor.Name, thumb, map[string]string{
				"libraryId": libraryID,
				"showTitle": showTitle,
			})
		} else {
			actor = a.actorForResponse(actor)
		}
		out[i] = actor
	}
	return out
}

func (a *App) actorForResponse(actor media.Actor) media.Actor {
	if localActorThumbPath(actor.Thumb) == "" {
		return actor
	}
	actor.Thumb = a.actorImageURL(actor.Name, actor.Thumb, nil)
	return actor
}

func (a *App) actorImageURL(name, thumb string, extra map[string]string) string {
	values := url.Values{}
	values.Set("name", name)
	for key, value := range extra {
		if strings.TrimSpace(value) != "" {
			values.Set(key, value)
		}
	}
	if info, err := os.Stat(thumb); err == nil {
		values.Set("v", strconv.FormatInt(info.ModTime().Unix(), 10))
	}
	return "/api/actors/image?" + values.Encode()
}

func (a *App) actorProfileURLFromCredits(ctx context.Context, name string, movies []media.Item, shows []media.ShowSummary) string {
	for _, item := range movies {
		if thumb := a.localActorThumbForItem(item, name); thumb != "" {
			return a.actorImageURL(name, thumb, map[string]string{
				"itemId": strconv.FormatInt(item.ID, 10),
			})
		}
	}
	for _, show := range shows {
		sample, err := a.store.ShowSampleItem(ctx, show.LibraryID, show.Title)
		if err != nil {
			continue
		}
		if thumb := a.localActorThumbForShow(sample, name); thumb != "" {
			return a.actorImageURL(name, thumb, map[string]string{
				"libraryId": show.LibraryID,
				"showTitle": show.Title,
			})
		}
	}
	return ""
}

func (a *App) localActorThumbFromRequest(ctx context.Context, r *http.Request, name string) string {
	if itemID, err := strconv.ParseInt(r.URL.Query().Get("itemId"), 10, 64); err == nil && itemID > 0 {
		if item, err := a.store.GetItem(ctx, itemID); err == nil {
			return a.localActorThumbForItem(item, name)
		}
	}
	libraryID := strings.TrimSpace(r.URL.Query().Get("libraryId"))
	showTitle := strings.TrimSpace(r.URL.Query().Get("showTitle"))
	if libraryID != "" && showTitle != "" {
		if item, err := a.store.ShowSampleItem(ctx, libraryID, showTitle); err == nil {
			return a.localActorThumbForShow(item, name)
		}
	}
	return ""
}

func (a *App) localActorThumbForItem(item media.Item, name string) string {
	lib := config.Library{ID: item.LibraryID, Type: "movies", Path: a.libraryRoot(item.LibraryID)}
	if item.Kind == "episode" {
		lib.Type = "tv"
	}
	return media.FindActorThumb(name, media.ActorDirsForItem(lib, item.Path)...)
}

func (a *App) localActorThumbForShow(item media.Item, name string) string {
	root := a.libraryRoot(item.LibraryID)
	return media.FindActorThumb(name, media.ActorDirsForShow(root, item.Path)...)
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

func (a *App) writeCachedJSON(w http.ResponseWriter, r *http.Request, key string, ttl time.Duration, build func() (any, error)) {
	cacheKey := strconv.FormatUint(a.cacheGen.Load(), 10) + ":" + key
	if body, ok := a.cachedResponse(cacheKey); ok {
		writeJSONBytes(w, http.StatusOK, body)
		return
	}
	value, err := build()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	body, err := json.Marshal(value)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	body = append(body, '\n')
	a.storeCachedResponse(cacheKey, body, ttl)
	writeJSONBytes(w, http.StatusOK, body)
}

func writeJSONBytes(w http.ResponseWriter, status int, body []byte) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func (a *App) cachedResponse(key string) ([]byte, bool) {
	now := time.Now()
	a.cache.mu.Lock()
	defer a.cache.mu.Unlock()
	entry, ok := a.cache.entries[key]
	if !ok {
		return nil, false
	}
	if now.After(entry.expiresAt) {
		delete(a.cache.entries, key)
		return nil, false
	}
	return append([]byte(nil), entry.body...), true
}

func (a *App) storeCachedResponse(key string, body []byte, ttl time.Duration) {
	if ttl <= 0 {
		return
	}
	a.cache.mu.Lock()
	defer a.cache.mu.Unlock()
	if len(a.cache.entries) >= 256 {
		a.cache.entries = map[string]cachedResponse{}
	}
	a.cache.entries[key] = cachedResponse{
		body:      append([]byte(nil), body...),
		expiresAt: time.Now().Add(ttl),
	}
}

func (a *App) invalidateResponseCache() {
	a.cacheGen.Add(1)
	a.cache.mu.Lock()
	a.cache.entries = map[string]cachedResponse{}
	a.cache.mu.Unlock()
}

func cacheKey(r *http.Request, parts ...any) string {
	values := make([]string, 0, len(parts)+3)
	values = append(values, r.Method, r.URL.Path, r.URL.RawQuery)
	for _, part := range parts {
		values = append(values, fmt.Sprint(part))
	}
	return strings.Join(values, "\x1f")
}

func logging(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Debug("request", "method", r.Method, "path", r.URL.Path, "elapsed", time.Since(start))
	})
}

// htmlAssetRef matches local css/js references in served HTML so they can be
// stamped with the build version for cache busting.
var htmlAssetRef = regexp.MustCompile(`(href|src)="(/[^"?]+\.(?:css|js))"`)

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
		if strings.HasSuffix(path, ".html") {
			w.Header().Set("Cache-Control", "no-store")
			// Stamp asset URLs with the build version AND build time so a new
			// deploy invalidates the browser cache without renaming files —
			// version alone stays identical between two builds of the same
			// dirty tree, which would leave stale css/js cached for a day.
			if data, err := web.Files.ReadFile(path); err == nil {
				ver := url.QueryEscape(version.Version + "-" + version.BuildTime)
				rewritten := htmlAssetRef.ReplaceAll(data, []byte(`${1}="${2}?v=`+ver+`"`))
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				_, _ = w.Write(rewritten)
				return
			}
		} else {
			w.Header().Set("Cache-Control", "public, max-age=86400")
		}
		http.ServeFileFS(w, r, web.Files, path)
	})
}
