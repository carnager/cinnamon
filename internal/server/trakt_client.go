package server

import (
	"bytes"
	"context"
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

func (a *App) traktExchangeDeviceCode(ctx context.Context, code string) (traktToken, int, error) {
	if code == "" {
		return traktToken{}, http.StatusBadRequest, fmt.Errorf("deviceCode is required")
	}
	resp, err := a.traktRequest(ctx, "", http.MethodPost, "/oauth/device/token", map[string]any{
		"code":          code,
		"client_id":     a.cfg.TraktClientID,
		"client_secret": a.cfg.TraktClientSecret,
	})
	if err != nil {
		return traktToken{}, http.StatusBadGateway, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		status := resp.StatusCode
		if status == http.StatusBadRequest {
			status = http.StatusAccepted
		}
		return traktToken{}, status, errors.New(strings.TrimSpace(string(body)))
	}
	var token traktToken
	if err := json.Unmarshal(body, &token); err != nil {
		return traktToken{}, http.StatusBadGateway, err
	}
	if token.AccessToken == "" || token.RefreshToken == "" {
		return traktToken{}, http.StatusBadGateway, fmt.Errorf("trakt token response was incomplete")
	}
	if token.ExpiresIn <= 0 {
		token.ExpiresIn = 90 * 24 * 60 * 60
	}
	return token, http.StatusOK, nil
}

func (a *App) traktAccountForRequest(ctx context.Context, userID int64) (media.TraktAccount, error) {
	account, err := a.store.TraktAccount(ctx, userID)
	if err != nil {
		return media.TraktAccount{}, err
	}
	expires, err := time.Parse(time.RFC3339, account.ExpiresAt)
	if err != nil || time.Until(expires) > 5*time.Minute {
		return account, nil
	}
	token, err := a.traktRefreshToken(ctx, account.RefreshToken)
	if err != nil {
		return account, err
	}
	account.AccessToken = token.AccessToken
	account.RefreshToken = token.RefreshToken
	account.ExpiresAt = time.Now().Add(time.Duration(token.ExpiresIn) * time.Second).UTC().Format(time.RFC3339)
	if err := a.store.SaveTraktAccount(ctx, account); err != nil {
		return account, err
	}
	return account, nil
}

func (a *App) traktRefreshToken(ctx context.Context, refreshToken string) (traktToken, error) {
	resp, err := a.traktRequest(ctx, "", http.MethodPost, "/oauth/token", map[string]any{
		"refresh_token": refreshToken,
		"client_id":     a.cfg.TraktClientID,
		"client_secret": a.cfg.TraktClientSecret,
		"redirect_uri":  "urn:ietf:wg:oauth:2.0:oob",
		"grant_type":    "refresh_token",
	})
	if err != nil {
		return traktToken{}, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return traktToken{}, fmt.Errorf("trakt refresh failed: %s", strings.TrimSpace(string(body)))
	}
	var token traktToken
	if err := json.Unmarshal(body, &token); err != nil {
		return traktToken{}, err
	}
	if token.ExpiresIn <= 0 {
		token.ExpiresIn = 90 * 24 * 60 * 60
	}
	return token, nil
}

type traktMovieSource struct {
	Items            []traktWatchedMovie
	SyncWatched      int
	UserWatched      int
	History          int
	AllHistory       int
	AllHistoryMovies int
	SyncWatchedDebug traktPageDebug
	UserWatchedDebug traktPageDebug
	HistoryDebug     traktPageDebug
	AllHistoryDebug  traktPageDebug
}

type traktShowSource struct {
	Items            []traktWatchedShow
	SyncWatched      int
	UserWatched      int
	SyncWatchedDebug traktPageDebug
	UserWatchedDebug traktPageDebug
}

func (a *App) traktWatchedMovies(ctx context.Context, bearer string) (traktMovieSource, error) {
	var syncWatched []traktWatchedMovie
	syncDebug, err := a.traktPaged(ctx, bearer, "/sync/watched/movies", &syncWatched)
	if err != nil {
		return traktMovieSource{}, err
	}
	var userWatched []traktWatchedMovie
	userDebug, err := a.traktPaged(ctx, bearer, "/users/me/watched/movies", &userWatched)
	if err != nil {
		return traktMovieSource{}, err
	}
	var history []traktWatchedMovie
	historyDebug, err := a.traktPaged(ctx, bearer, "/sync/history/movies", &history)
	if err != nil {
		return traktMovieSource{}, err
	}
	var allHistory []traktHistoryItem
	allHistoryDebug, err := a.traktPaged(ctx, bearer, "/sync/history", &allHistory)
	if err != nil {
		return traktMovieSource{}, err
	}
	allHistoryMovies := traktMoviesFromHistory(allHistory)
	return traktMovieSource{
		Items:            mergeTraktMovies(syncWatched, userWatched, history, allHistoryMovies),
		SyncWatched:      len(syncWatched),
		UserWatched:      len(userWatched),
		History:          len(uniqueTraktMovies(history)),
		AllHistory:       len(allHistory),
		AllHistoryMovies: len(uniqueTraktMovies(allHistoryMovies)),
		SyncWatchedDebug: syncDebug,
		UserWatchedDebug: userDebug,
		HistoryDebug:     historyDebug,
		AllHistoryDebug:  allHistoryDebug,
	}, nil
}

func (a *App) traktWatchedShows(ctx context.Context, bearer string) (traktShowSource, error) {
	var syncWatched []traktWatchedShow
	syncDebug, err := a.traktPaged(ctx, bearer, "/sync/watched/shows", &syncWatched)
	if err != nil {
		return traktShowSource{}, err
	}
	var userWatched []traktWatchedShow
	userDebug, err := a.traktPaged(ctx, bearer, "/users/me/watched/shows", &userWatched)
	if err != nil {
		return traktShowSource{}, err
	}
	return traktShowSource{
		Items:            mergeTraktShows(syncWatched, userWatched),
		SyncWatched:      len(syncWatched),
		UserWatched:      len(userWatched),
		SyncWatchedDebug: syncDebug,
		UserWatchedDebug: userDebug,
	}, nil
}

func (a *App) traktWatchlist(ctx context.Context, bearer string) (traktWatchlistSource, error) {
	var movies []traktWatchlistItem
	moviesDebug, err := a.traktPaged(ctx, bearer, "/sync/watchlist/movies", &movies)
	if err != nil {
		return traktWatchlistSource{}, err
	}
	var shows []traktWatchlistItem
	showsDebug, err := a.traktPaged(ctx, bearer, "/sync/watchlist/shows", &shows)
	if err != nil {
		return traktWatchlistSource{}, err
	}
	var episodes []traktWatchlistItem
	episodesDebug, err := a.traktPaged(ctx, bearer, "/sync/watchlist/episodes", &episodes)
	if err != nil {
		return traktWatchlistSource{}, err
	}
	return traktWatchlistSource{
		Movies:        movies,
		Shows:         shows,
		Episodes:      episodes,
		MoviesDebug:   moviesDebug,
		ShowsDebug:    showsDebug,
		EpisodesDebug: episodesDebug,
	}, nil
}

func (a *App) traktAllHistoryDebug(ctx context.Context, bearer string) (traktPageDebug, error) {
	var history []json.RawMessage
	return a.traktPaged(ctx, bearer, "/sync/history", &history)
}

func (a *App) traktUserStats(ctx context.Context, bearer string) (traktUserStats, error) {
	resp, err := a.traktRequest(ctx, bearer, http.MethodGet, "/users/me/stats", nil)
	if err != nil {
		return traktUserStats{}, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return traktUserStats{}, fmt.Errorf("trakt stats failed: %s", strings.TrimSpace(string(body)))
	}
	var stats traktUserStats
	if err := json.Unmarshal(body, &stats); err != nil {
		return traktUserStats{}, err
	}
	return stats, nil
}

func (a *App) traktPaged(ctx context.Context, bearer, path string, dest any) (traktPageDebug, error) {
	page := 1
	limit := 100
	raw := []json.RawMessage{}
	seenPages := map[string]struct{}{}
	debug := traktPageDebug{Limit: limit}
	for {
		resp, err := a.traktRequest(ctx, bearer, http.MethodGet, fmt.Sprintf("%s?page=%d&limit=%d", path, page, limit), nil)
		if err != nil {
			return debug, err
		}
		debug.PagesFetched++
		if page == 1 {
			debug.HeaderItemCount = parseTraktPaginationHeader(resp.Header.Get("X-Pagination-Item-Count"))
			debug.HeaderPageCount = parseTraktPaginationHeader(resp.Header.Get("X-Pagination-Page-Count"))
		}
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode > 299 {
			return debug, fmt.Errorf("trakt watched import failed: %s", strings.TrimSpace(string(body)))
		}
		var pageItems []json.RawMessage
		if err := json.Unmarshal(body, &pageItems); err != nil {
			return debug, err
		}
		if len(pageItems) == 0 {
			break
		}
		pageKey := string(body)
		if _, ok := seenPages[pageKey]; ok {
			break
		}
		seenPages[pageKey] = struct{}{}
		raw = append(raw, pageItems...)
		if len(pageItems) < limit {
			break
		}
		page++
		if page > 1000 {
			return debug, fmt.Errorf("trakt pagination exceeded 1000 pages for %s", path)
		}
	}
	debug.Items = len(raw)
	b, err := json.Marshal(raw)
	if err != nil {
		return debug, err
	}
	return debug, json.Unmarshal(b, dest)
}

func parseTraktPaginationHeader(value string) int {
	n, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || n < 0 {
		return 0
	}
	return n
}

func mergeTraktMovies(groups ...[]traktWatchedMovie) []traktWatchedMovie {
	seen := map[string]traktWatchedMovie{}
	for _, group := range groups {
		for _, movie := range group {
			key := traktMovieKey(movie)
			if key == "" {
				continue
			}
			seen[key] = movie
		}
	}
	out := make([]traktWatchedMovie, 0, len(seen))
	for _, movie := range seen {
		out = append(out, movie)
	}
	return out
}

func uniqueTraktMovies(movies []traktWatchedMovie) []traktWatchedMovie {
	return mergeTraktMovies(movies)
}

func traktMovieKey(movie traktWatchedMovie) string {
	if movie.Movie.IDs.Trakt > 0 {
		return fmt.Sprintf("trakt:%d", movie.Movie.IDs.Trakt)
	}
	if movie.Movie.IDs.IMDb != "" {
		return "imdb:" + strings.ToLower(movie.Movie.IDs.IMDb)
	}
	if movie.Movie.IDs.TMDb > 0 {
		return fmt.Sprintf("tmdb:%d", movie.Movie.IDs.TMDb)
	}
	return fmt.Sprintf("title:%s:%d", normalizeMatch(movie.Movie.Title), movie.Movie.Year)
}

func mergeTraktShows(groups ...[]traktWatchedShow) []traktWatchedShow {
	seen := map[string]traktWatchedShow{}
	for _, group := range groups {
		for _, show := range group {
			key := traktShowKey(show)
			if key == "" {
				continue
			}
			if existing, ok := seen[key]; ok {
				show = mergeTraktShow(existing, show)
			}
			seen[key] = show
		}
	}
	out := make([]traktWatchedShow, 0, len(seen))
	for _, show := range seen {
		out = append(out, show)
	}
	return out
}

func traktShowKey(show traktWatchedShow) string {
	if show.Show.IDs.Trakt > 0 {
		return fmt.Sprintf("trakt:%d", show.Show.IDs.Trakt)
	}
	if show.Show.IDs.TVDb > 0 {
		return fmt.Sprintf("tvdb:%d", show.Show.IDs.TVDb)
	}
	if show.Show.IDs.TMDb > 0 {
		return fmt.Sprintf("tmdb:%d", show.Show.IDs.TMDb)
	}
	return fmt.Sprintf("title:%s:%d", normalizeMatch(show.Show.Title), show.Show.Year)
}

func mergeTraktShow(a, b traktWatchedShow) traktWatchedShow {
	episodes := map[string]struct{}{}
	for _, season := range a.Seasons {
		for _, episode := range season.Episodes {
			episodes[fmt.Sprintf("%d:%d", season.Number, episode.Number)] = struct{}{}
		}
	}
	for _, season := range b.Seasons {
		for _, episode := range season.Episodes {
			key := fmt.Sprintf("%d:%d", season.Number, episode.Number)
			if _, ok := episodes[key]; ok {
				continue
			}
			a.Seasons = append(a.Seasons, season)
			episodes[key] = struct{}{}
		}
	}
	return a
}

func (a *App) traktRequest(ctx context.Context, bearer, method, path string, body any) (*http.Response, error) {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, strings.TrimRight(a.cfg.TraktAPIURL, "/")+path, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Popcorn/0.1")
	req.Header.Set("trakt-api-version", "2")
	req.Header.Set("trakt-api-key", a.cfg.TraktClientID)
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	return http.DefaultClient.Do(req)
}

func (a *App) traktConfigured() bool {
	return strings.TrimSpace(a.cfg.TraktClientID) != "" && strings.TrimSpace(a.cfg.TraktClientSecret) != ""
}
