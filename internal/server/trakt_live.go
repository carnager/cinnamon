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
	TVDbID    int    `json:"tvdbId,omitempty"`
	ListedAt  string `json:"listedAt,omitempty"`
	WatchedAt string `json:"watchedAt,omitempty"`
	AiredAt   string `json:"airedAt,omitempty"`
	Plays     int    `json:"plays,omitempty"`
	// InLibrary and Item are what no third-party client can offer: this one is
	// on the shelf, press play.
	InLibrary bool        `json:"inLibrary"`
	Item      *media.Item `json:"item,omitempty"`
}

type traktLiveList struct {
	Entries   []traktLiveEntry `json:"entries"`
	FetchedAt string           `json:"fetchedAt"`
	Source    string           `json:"source"`
}

func (a *App) traktLiveWatchlist(w http.ResponseWriter, r *http.Request) {
	a.serveTraktLive(w, r, "watchlist", func(ctx context.Context, bearer string, index traktImportIndex) ([]traktLiveEntry, error) {
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
			matchTraktMovie(&entry, index)
			entries = append(entries, entry)
		}
		for _, row := range source.Shows {
			entry := traktLiveEntry{
				Kind: "show", Title: row.Show.Title, Year: row.Show.Year, ListedAt: row.ListedAt,
			}
			applyTraktIDs(&entry, row.Show.IDs)
			matchTraktShow(&entry, index)
			entries = append(entries, entry)
		}
		for _, row := range source.Episodes {
			entry := traktLiveEntry{
				Kind: "episode", Title: row.Episode.Title, ShowTitle: row.Show.Title,
				Season: row.Episode.Season, Episode: row.Episode.Number, ListedAt: row.ListedAt,
			}
			applyTraktIDs(&entry, row.Episode.IDs)
			matchTraktEpisode(&entry, index)
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
	a.serveTraktLive(w, r, "history", func(ctx context.Context, bearer string, index traktImportIndex) ([]traktLiveEntry, error) {
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
				matchTraktEpisode(&entry, index)
			default:
				entry.Kind = "movie"
				entry.Title = row.Movie.Title
				entry.Year = row.Movie.Year
				applyTraktIDs(&entry, row.Movie.IDs)
				matchTraktMovie(&entry, index)
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
	a.serveTraktLive(w, r, "recommendations", func(ctx context.Context, bearer string, index traktImportIndex) ([]traktLiveEntry, error) {
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
				reason = matchTraktMovie(&entry, index)
			} else {
				reason = matchTraktShow(&entry, index)
			}
			if matchedByID(reason) {
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
	a.serveTraktLive(w, r, "upcoming", func(ctx context.Context, bearer string, index traktImportIndex) ([]traktLiveEntry, error) {
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
				Kind:      "episode",
				Title:     row.Episode.Title,
				ShowTitle: row.Show.Title,
				Year:      row.Show.Year,
				Season:    row.Episode.Season,
				Episode:   row.Episode.Number,
				AiredAt:   row.FirstAired,
			}
			applyTraktIDs(&entry, row.Episode.IDs)
			matchTraktEpisode(&entry, index)
			entries = append(entries, entry)
		}
		return entries, nil
	})
}

func (a *App) serveTraktLive(w http.ResponseWriter, r *http.Request, name string, build func(context.Context, string, traktImportIndex) ([]traktLiveEntry, error)) {
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
		entries, err := build(r.Context(), account.AccessToken, newTraktImportIndex(items))
		if err != nil {
			return nil, err
		}
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

// A show is in the library if any episode of it is; the first one found is
// enough to open the show.
func matchTraktShow(entry *traktLiveEntry, index traktImportIndex) string {
	item, reason := index.matchEpisode(entry.Title, entry.Year, traktIDsPayload{}, traktIDsPayload{}, 1, 1)
	if item != nil {
		entry.InLibrary = true
		entry.Item = item
	}
	return reason
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
