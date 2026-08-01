package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"popcorn/internal/media"
)

// What Trakt says, now — not a copy of it.
//
// Every Trakt client that keeps its own mirror eventually shows a watchlist
// that is empty because a sync failed months ago, or history that stops at the
// last successful refresh. These endpoints read through to Trakt on each call,
// cache the answer for a minute, and report a failure as a failure. An empty
// list here means Trakt returned an empty list.

const traktLiveCacheTTL = 60 * time.Second

// traktLiveEntry is one row as Trakt has it, plus what popcorn can add: whether
// the title is in the library, and if so how to play it.
type traktLiveEntry struct {
	Kind      string `json:"kind"`
	Title     string `json:"title"`
	Year      int    `json:"year"`
	ShowTitle string `json:"showTitle,omitempty"`
	Season    int    `json:"season,omitempty"`
	Episode   int    `json:"episode,omitempty"`
	IMDbID    string `json:"imdbId,omitempty"`
	TMDbID    int    `json:"tmdbId,omitempty"`
	// An episode's own id says nothing to an artwork lookup; the show's does.
	ShowTMDbID int    `json:"showTmdbId,omitempty"`
	TVDbID     int    `json:"tvdbId,omitempty"`
	ListedAt   string `json:"listedAt,omitempty"`
	WatchedAt  string `json:"watchedAt,omitempty"`
	AiredAt    string `json:"airedAt,omitempty"`
	Plays      int    `json:"plays,omitempty"`
	// InLibrary and Item are what no third-party client can offer: this one is
	// on the shelf, press play.
	InLibrary bool        `json:"inLibrary"`
	Item      *media.Item `json:"item,omitempty"`
	// Filled from TMDb for titles the library does not have, so a row can show
	// a cover and say what it is about.
	PosterURL string  `json:"posterUrl,omitempty"`
	Overview  string  `json:"overview,omitempty"`
	Rating    float64 `json:"rating,omitempty"`
	Runtime   int     `json:"runtime,omitempty"`
	Genres    string  `json:"genres,omitempty"`
}

// traktLiveScope is what a builder needs to say whether a row is on the shelf:
// the import matchers for movies and episodes, and every show title the library
// holds, because a show has no external id of its own here.
type traktLiveScope struct {
	index traktImportIndex
	shows map[string]bool
}

// A library title often carries a local subtitle the source does not have —
// "Fringe" against "Fringe - Grenzfälle des FBI" — so a prefix up to a
// separator counts as the same show. Bare prefixes do not, or "Dark" would
// swallow "Dark Matter".
func (s traktLiveScope) ownsShow(title string) bool {
	wanted := strings.ToLower(strings.TrimSpace(title))
	if wanted == "" {
		return false
	}
	if s.shows[wanted] {
		return true
	}
	for known := range s.shows {
		if len(known) <= len(wanted) || !strings.HasPrefix(known, wanted) {
			continue
		}
		switch known[len(wanted)] {
		case ' ', '-', ':', '(', ',':
			return true
		}
	}
	return false
}

type traktLiveList struct {
	Entries   []traktLiveEntry `json:"entries"`
	FetchedAt string           `json:"fetchedAt"`
	Source    string           `json:"source"`
}

func (a *App) traktLiveWatchlist(w http.ResponseWriter, r *http.Request) {
	a.serveTraktLive(w, r, "watchlist", func(ctx context.Context, bearer string, scope traktLiveScope) ([]traktLiveEntry, error) {
		source, err := a.traktWatchlist(ctx, bearer)
		if err != nil {
			return nil, err
		}
		entries := make([]traktLiveEntry, 0, len(source.Movies)+len(source.Shows)+len(source.Episodes))
		for _, row := range source.Movies {
			entry := traktLiveEntry{
				Kind: "movie", Title: row.Movie.Title, Year: row.Movie.Year, ListedAt: row.ListedAt,
			}
			applyTraktIDs(&entry, row.Movie.IDs)
			matchTraktMovie(&entry, scope.index)
			entries = append(entries, entry)
		}
		for _, row := range source.Shows {
			entry := traktLiveEntry{
				Kind: "show", Title: row.Show.Title, Year: row.Show.Year, ListedAt: row.ListedAt,
			}
			applyTraktIDs(&entry, row.Show.IDs)
			matchTraktShow(&entry, scope)
			entries = append(entries, entry)
		}
		for _, row := range source.Episodes {
			entry := traktLiveEntry{
				Kind: "episode", Title: row.Episode.Title, ShowTitle: row.Show.Title,
				Season: row.Episode.Season, Episode: row.Episode.Number, ListedAt: row.ListedAt,
			}
			applyTraktIDs(&entry, row.Episode.IDs)
			matchTraktEpisode(&entry, scope.index)
			entries = append(entries, entry)
		}
		return entries, nil
	})
}

func (a *App) traktLiveHistory(w http.ResponseWriter, r *http.Request) {
	limit := 100
	if value, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && value > 0 && value <= 500 {
		limit = value
	}
	a.serveTraktLive(w, r, "history", func(ctx context.Context, bearer string, scope traktLiveScope) ([]traktLiveEntry, error) {
		var rows []struct {
			WatchedAt string `json:"watched_at"`
			Type      string `json:"type"`
			Movie     struct {
				Title string          `json:"title"`
				Year  int             `json:"year"`
				IDs   traktIDsPayload `json:"ids"`
			} `json:"movie"`
			Show struct {
				Title string          `json:"title"`
				Year  int             `json:"year"`
				IDs   traktIDsPayload `json:"ids"`
			} `json:"show"`
			Episode struct {
				Title  string          `json:"title"`
				Season int             `json:"season"`
				Number int             `json:"number"`
				IDs    traktIDsPayload `json:"ids"`
			} `json:"episode"`
		}
		if err := a.traktGetInto(ctx, bearer, fmt.Sprintf("/sync/history?limit=%d", limit), &rows); err != nil {
			return nil, err
		}
		entries := make([]traktLiveEntry, 0, len(rows))
		for _, row := range rows {
			entry := traktLiveEntry{WatchedAt: row.WatchedAt}
			switch row.Type {
			case "episode":
				entry.Kind = "episode"
				entry.Title = row.Episode.Title
				entry.ShowTitle = row.Show.Title
				entry.Season = row.Episode.Season
				entry.Episode = row.Episode.Number
				applyTraktIDs(&entry, row.Episode.IDs)
				matchTraktEpisode(&entry, scope.index)
			default:
				entry.Kind = "movie"
				entry.Title = row.Movie.Title
				entry.Year = row.Movie.Year
				applyTraktIDs(&entry, row.Movie.IDs)
				matchTraktMovie(&entry, scope.index)
			}
			entries = append(entries, entry)
		}
		return entries, nil
	})
}

// traktLiveRecommendations is what the collection sync bought: Trakt's own
// suggestions with everything on the shelf already filtered out.
func (a *App) traktLiveRecommendations(w http.ResponseWriter, r *http.Request) {
	kind := "movies"
	if strings.EqualFold(r.URL.Query().Get("kind"), "shows") {
		kind = "shows"
	}
	a.serveTraktLive(w, r, "recommendations", func(ctx context.Context, bearer string, scope traktLiveScope) ([]traktLiveEntry, error) {
		var rows []struct {
			Title string          `json:"title"`
			Year  int             `json:"year"`
			IDs   traktIDsPayload `json:"ids"`
		}
		path := fmt.Sprintf("/recommendations/%s?ignore_collected=true&ignore_watchlisted=true&limit=40", kind)
		if err := a.traktGetInto(ctx, bearer, path, &rows); err != nil {
			return nil, err
		}
		// ignore_collected is asked for, but Trakt builds its recommendation
		// set periodically and still offered sixteen titles it had recorded as
		// collected minutes earlier. A shelf of "things you do not have" has to
		// be true, so anything matching the library by an external id is
		// dropped here. Title-only matches are kept but flagged: those are
		// guesses, and dropping a real recommendation over a title collision is
		// the worse mistake.
		entries := make([]traktLiveEntry, 0, len(rows))
		for _, row := range rows {
			entry := traktLiveEntry{Kind: strings.TrimSuffix(kind, "s"), Title: row.Title, Year: row.Year}
			applyTraktIDs(&entry, row.IDs)
			var reason string
			if entry.Kind == "movie" {
				reason = matchTraktMovie(&entry, scope.index)
			} else {
				reason = matchTraktShow(&entry, scope)
			}
			// A movie is dropped on an id match; a show on a title match,
			// because that is the only handle a show has here.
			if matchedByID(reason) || reason == "show-title" {
				continue
			}
			entries = append(entries, entry)
		}
		return entries, nil
	})
}

// traktLiveUpcoming is the payoff from the collection sync: Trakt's calendar of
// what is next for the shows you actually have, rather than everything airing.
// An episode already on the shelf says so, which turns the list into "what has
// landed and what is still coming".
func (a *App) traktLiveUpcoming(w http.ResponseWriter, r *http.Request) {
	days := 14
	if value, err := strconv.Atoi(r.URL.Query().Get("days")); err == nil && value > 0 && value <= 33 {
		days = value
	}
	start := time.Now().UTC().AddDate(0, 0, -1).Format("2006-01-02")
	a.serveTraktLive(w, r, "upcoming", func(ctx context.Context, bearer string, scope traktLiveScope) ([]traktLiveEntry, error) {
		var rows []struct {
			FirstAired string `json:"first_aired"`
			Episode    struct {
				Title  string          `json:"title"`
				Season int             `json:"season"`
				Number int             `json:"number"`
				IDs    traktIDsPayload `json:"ids"`
			} `json:"episode"`
			Show struct {
				Title string          `json:"title"`
				Year  int             `json:"year"`
				IDs   traktIDsPayload `json:"ids"`
			} `json:"show"`
		}
		path := fmt.Sprintf("/calendars/my/shows/%s/%d", start, days)
		if err := a.traktGetInto(ctx, bearer, path, &rows); err != nil {
			return nil, err
		}
		entries := make([]traktLiveEntry, 0, len(rows))
		for _, row := range rows {
			entry := traktLiveEntry{
				Kind:       "episode",
				Title:      row.Episode.Title,
				ShowTitle:  row.Show.Title,
				Year:       row.Show.Year,
				Season:     row.Episode.Season,
				Episode:    row.Episode.Number,
				AiredAt:    row.FirstAired,
				ShowTMDbID: row.Show.IDs.TMDb,
			}
			applyTraktIDs(&entry, row.Episode.IDs)
			// Trakt's calendar covers everything you watch; this list is for
			// the shows actually on the shelf.
			if !scope.ownsShow(row.Show.Title) {
				continue
			}
			matchTraktEpisode(&entry, scope.index)
			entries = append(entries, entry)
		}
		return entries, nil
	})
}

func (a *App) serveTraktLive(w http.ResponseWriter, r *http.Request, name string, build func(context.Context, string, traktLiveScope) ([]traktLiveEntry, error)) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	account, err := a.traktAccountForRequest(r.Context(), user.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "trakt account is not linked", http.StatusConflict)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	// A short cache keeps a tab responsive without letting it go stale, and a
	// failed fetch is never cached — the next pull tries again.
	a.writeCachedJSON(w, r, cacheKey(r, "trakt-live", name, user.ID), traktLiveCacheTTL, func() (any, error) {
		items, err := a.store.AllItems(r.Context())
		if err != nil {
			return nil, err
		}
		shows, err := a.store.ShowTitleIndex(r.Context())
		if err != nil {
			return nil, err
		}
		entries, err := build(r.Context(), account.AccessToken, traktLiveScope{index: newTraktImportIndex(items), shows: shows})
		if err != nil {
			return nil, err
		}
		a.fillTMDbDetails(r.Context(), entries)
		return traktLiveList{
			Entries:   entries,
			FetchedAt: time.Now().UTC().Format(time.RFC3339),
			Source:    "trakt",
		}, nil
	})
}

func (a *App) traktGetInto(ctx context.Context, bearer, path string, dest any) error {
	resp, err := a.traktRequest(ctx, bearer, http.MethodGet, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 32*1024*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		message := strings.TrimSpace(string(body))
		if message == "" {
			message = http.StatusText(resp.StatusCode)
		}
		return fmt.Errorf("trakt %s failed (%d): %s", path, resp.StatusCode, message)
	}
	return json.Unmarshal(body, dest)
}

func applyTraktIDs(entry *traktLiveEntry, ids traktIDsPayload) {
	entry.IMDbID = ids.IMDb
	entry.TMDbID = ids.TMDb
	entry.TVDbID = ids.TVDb
}

// The same matchers the Trakt import uses, so "in your library" means the same
// thing on every surface.
func matchTraktMovie(entry *traktLiveEntry, index traktImportIndex) string {
	item, reason := index.matchMovie(entry.Title, entry.Year, traktIDsPayload{IMDb: entry.IMDbID, TMDb: entry.TMDbID, TVDb: entry.TVDbID})
	if item != nil {
		entry.InLibrary = true
		entry.Item = item
	}
	return reason
}

// An id match is a fact; a title match is a guess.
func matchedByID(reason string) bool {
	switch reason {
	case "imdb", "tmdb", "episode-imdb", "episode-tmdb", "episode-tvdb":
		return true
	}
	return false
}

// A show is in the library if the library holds episodes under that title.
// Matching an episode by number missed anything whose first episode is not
// S01E01 — which is why owned shows kept being recommended.
func matchTraktShow(entry *traktLiveEntry, scope traktLiveScope) string {
	if !scope.ownsShow(entry.Title) {
		return ""
	}
	entry.InLibrary = true
	if item, _ := scope.index.matchEpisode(entry.Title, entry.Year, traktIDsPayload{}, traktIDsPayload{}, 1, 1); item != nil {
		entry.Item = item
	}
	return "show-title"
}

func matchTraktEpisode(entry *traktLiveEntry, index traktImportIndex) string {
	ids := traktIDsPayload{IMDb: entry.IMDbID, TMDb: entry.TMDbID, TVDb: entry.TVDbID}
	item, reason := index.matchEpisode(entry.ShowTitle, entry.Year, traktIDsPayload{}, ids, entry.Season, entry.Episode)
	if item != nil {
		entry.InLibrary = true
		entry.Item = item
	}
	return reason
}

// ── Acting on something you do not have ──
//
// The library watchlist is a row in popcorn's own table; a title that is not in
// the library has no row to point at, so these write straight to Trakt. The
// phone shows one button either way.

type traktLiveActionRequest struct {
	Kind   string `json:"kind"`
	IMDbID string `json:"imdbId"`
	TMDbID int    `json:"tmdbId"`
	Title  string `json:"title"`
	Year   int    `json:"year"`
}

func (a *App) traktLiveWatchlistAdd(w http.ResponseWriter, r *http.Request) {
	a.traktLiveWatchlistWrite(w, r, false)
}

func (a *App) traktLiveWatchlistRemove(w http.ResponseWriter, r *http.Request) {
	a.traktLiveWatchlistWrite(w, r, true)
}

func (a *App) traktLiveWatchlistWrite(w http.ResponseWriter, r *http.Request, remove bool) {
	account, in, ok := a.traktLiveAction(w, r)
	if !ok {
		return
	}
	entry := map[string]any{}
	if ids := traktLiveIDs(in); len(ids) > 0 {
		entry["ids"] = ids
	} else if strings.TrimSpace(in.Title) != "" {
		entry["title"] = in.Title
		if in.Year > 0 {
			entry["year"] = in.Year
		}
	} else {
		http.Error(w, "need an id or a title", http.StatusBadRequest)
		return
	}
	body := map[string]any{traktLiveCollectionKey(in.Kind): []map[string]any{entry}}
	path := "/sync/watchlist"
	if remove {
		path = "/sync/watchlist/remove"
	}
	if err := a.traktCollectionWrite(r.Context(), account.AccessToken, path, body); err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	a.invalidateResponseCache()
	w.WriteHeader(http.StatusNoContent)
}

// Hiding tells Trakt to stop suggesting it, which is the same intent as "not
// interested" on a library title.
func (a *App) traktLiveHide(w http.ResponseWriter, r *http.Request) {
	account, in, ok := a.traktLiveAction(w, r)
	if !ok {
		return
	}
	ids := traktLiveIDs(in)
	if len(ids) == 0 {
		http.Error(w, "need an id", http.StatusBadRequest)
		return
	}
	body := map[string]any{traktLiveCollectionKey(in.Kind): []map[string]any{{"ids": ids}}}
	if err := a.traktCollectionWrite(r.Context(), account.AccessToken, "/users/hidden/recommendations", body); err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	a.invalidateResponseCache()
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) traktLiveAction(w http.ResponseWriter, r *http.Request) (media.TraktAccount, traktLiveActionRequest, bool) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return media.TraktAccount{}, traktLiveActionRequest{}, false
	}
	account, err := a.traktAccountForRequest(r.Context(), user.ID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.Error(w, "trakt account is not linked", http.StatusConflict)
		} else {
			http.Error(w, err.Error(), http.StatusInternalServerError)
		}
		return media.TraktAccount{}, traktLiveActionRequest{}, false
	}
	var in traktLiveActionRequest
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return media.TraktAccount{}, traktLiveActionRequest{}, false
	}
	return account, in, true
}

func traktLiveIDs(in traktLiveActionRequest) map[string]any {
	ids := map[string]any{}
	if strings.TrimSpace(in.IMDbID) != "" {
		ids["imdb"] = strings.TrimSpace(in.IMDbID)
	}
	if in.TMDbID > 0 {
		ids["tmdb"] = in.TMDbID
	}
	return ids
}

func traktLiveCollectionKey(kind string) string {
	if strings.EqualFold(kind, "show") || strings.EqualFold(kind, "shows") {
		return "shows"
	}
	return "movies"
}

// ── Covers and synopses for what the library does not have ──
//
// A library title has artwork on disk; a suggestion has nothing but ids. TMDb
// fills that in, cached in tmdb_titles so a second look costs no requests, and
// fetched a few at a time so a list of forty does not open forty connections.

const (
	tmdbPosterBase   = "https://image.tmdb.org/t/p/w342"
	tmdbDetailWorker = 6
)

func (a *App) fillTMDbDetails(ctx context.Context, entries []traktLiveEntry) {
	if !a.tmdbConfigured() {
		return
	}
	wanted := map[string][]int{}
	for _, entry := range entries {
		id := artworkTMDbID(entry)
		if entry.InLibrary || id <= 0 {
			continue
		}
		wanted[tmdbKindFor(entry.Kind)] = append(wanted[tmdbKindFor(entry.Kind)], id)
	}
	if len(wanted) == 0 {
		return
	}

	known := map[string]media.TMDbTitle{}
	for kind, ids := range wanted {
		cached, err := a.store.TMDbTitles(ctx, kind, ids)
		if err != nil {
			continue
		}
		for id, title := range cached {
			known[tmdbCacheKey(kind, id)] = title
		}
	}

	missing := []struct {
		kind string
		id   int
	}{}
	seen := map[string]bool{}
	for kind, ids := range wanted {
		for _, id := range ids {
			key := tmdbCacheKey(kind, id)
			if seen[key] || known[key].TMDbID > 0 {
				continue
			}
			seen[key] = true
			missing = append(missing, struct {
				kind string
				id   int
			}{kind, id})
		}
	}

	if len(missing) > 0 {
		var mu sync.Mutex
		var wg sync.WaitGroup
		work := make(chan struct {
			kind string
			id   int
		})
		for worker := 0; worker < tmdbDetailWorker; worker++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for job := range work {
					title, err := a.fetchTMDbTitle(ctx, job.kind, job.id)
					if err != nil {
						continue
					}
					_ = a.store.SaveTMDbTitle(ctx, title)
					mu.Lock()
					known[tmdbCacheKey(job.kind, job.id)] = title
					mu.Unlock()
				}
			}()
		}
		for _, job := range missing {
			select {
			case work <- job:
			case <-ctx.Done():
			}
		}
		close(work)
		wg.Wait()
	}

	for i := range entries {
		entry := &entries[i]
		id := artworkTMDbID(*entry)
		if entry.InLibrary || id <= 0 {
			continue
		}
		title, ok := known[tmdbCacheKey(tmdbKindFor(entry.Kind), id)]
		if !ok {
			continue
		}
		if title.PosterPath != "" {
			entry.PosterURL = tmdbPosterBase + title.PosterPath
		}
		entry.Overview = title.Overview
		entry.Rating = title.Rating
		entry.Runtime = title.Runtime
		entry.Genres = title.Genres
	}
}

func (a *App) fetchTMDbTitle(ctx context.Context, kind string, id int) (media.TMDbTitle, error) {
	path := "/3/movie/" + strconv.Itoa(id)
	if kind == "show" {
		path = "/3/tv/" + strconv.Itoa(id)
	}
	var res struct {
		Title        string  `json:"title"`
		Name         string  `json:"name"`
		Overview     string  `json:"overview"`
		PosterPath   string  `json:"poster_path"`
		BackdropPath string  `json:"backdrop_path"`
		VoteAverage  float64 `json:"vote_average"`
		Runtime      int     `json:"runtime"`
		EpisodeTimes []int   `json:"episode_run_time"`
		Genres       []struct {
			Name string `json:"name"`
		} `json:"genres"`
	}
	if err := a.tmdbGet(ctx, path, nil, &res); err != nil {
		return media.TMDbTitle{}, err
	}
	runtime := res.Runtime
	if runtime == 0 && len(res.EpisodeTimes) > 0 {
		runtime = res.EpisodeTimes[0]
	}
	names := make([]string, 0, len(res.Genres))
	for _, genre := range res.Genres {
		names = append(names, genre.Name)
	}
	return media.TMDbTitle{
		Kind:         kind,
		TMDbID:       id,
		Title:        firstNonEmpty(res.Title, res.Name),
		Overview:     res.Overview,
		PosterPath:   res.PosterPath,
		BackdropPath: res.BackdropPath,
		Rating:       res.VoteAverage,
		Runtime:      runtime,
		Genres:       strings.Join(names, ", "),
	}, nil
}

// An episode borrows its show's artwork and synopsis: the poster of a single
// episode is not what a row wants to show, and often does not exist.
func artworkTMDbID(entry traktLiveEntry) int {
	if entry.Kind == "episode" {
		return entry.ShowTMDbID
	}
	return entry.TMDbID
}

func tmdbKindFor(kind string) string {
	if kind == "movie" {
		return "movie"
	}
	return "show"
}

func tmdbCacheKey(kind string, id int) string {
	return kind + ":" + strconv.Itoa(id)
}
