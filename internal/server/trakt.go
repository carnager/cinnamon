package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode"

	"popcorn/internal/media"
)

func (a *App) traktStatus(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	account, err := a.store.TraktAccount(r.Context(), user.ID)
	connected := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"configured": a.traktConfigured(),
		"connected":  connected,
		"expiresAt":  account.ExpiresAt,
	})
}

func (a *App) traktDeviceCode(w http.ResponseWriter, r *http.Request) {
	if _, ok := a.requireUser(w, r); !ok {
		return
	}
	if !a.traktConfigured() {
		http.Error(w, "trakt is not configured", http.StatusServiceUnavailable)
		return
	}
	resp, err := a.traktRequest(r.Context(), "", http.MethodPost, "/oauth/device/code", map[string]any{
		"client_id": a.cfg.TraktClientID,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		http.Error(w, string(body), resp.StatusCode)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(body)
}

func (a *App) traktDeviceToken(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	if !a.traktConfigured() {
		http.Error(w, "trakt is not configured", http.StatusServiceUnavailable)
		return
	}
	var in struct {
		DeviceCode string `json:"deviceCode"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	token, status, err := a.traktExchangeDeviceCode(r.Context(), strings.TrimSpace(in.DeviceCode))
	if err != nil {
		if status == http.StatusAccepted {
			writeJSON(w, http.StatusAccepted, map[string]any{"connected": false, "pending": true, "message": err.Error()})
			return
		}
		http.Error(w, err.Error(), status)
		return
	}
	if err := a.store.SaveTraktAccount(r.Context(), media.TraktAccount{
		UserID:       user.ID,
		AccessToken:  token.AccessToken,
		RefreshToken: token.RefreshToken,
		ExpiresAt:    time.Now().Add(time.Duration(token.ExpiresIn) * time.Second).UTC().Format(time.RFC3339),
	}); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"connected": true})
}

func (a *App) traktDisconnect(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	if err := a.store.DeleteTraktAccount(r.Context(), user.ID); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) traktImportWatched(w http.ResponseWriter, r *http.Request) {
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
	items, err := a.store.AllItems(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	index := newTraktImportIndex(items)
	movieSource, err := a.traktWatchedMovies(r.Context(), account.AccessToken)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	showSource, err := a.traktWatchedShows(r.Context(), account.AccessToken)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	allHistoryDebug, err := a.traktAllHistoryDebug(r.Context(), account.AccessToken)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	traktStats, err := a.traktUserStats(r.Context(), account.AccessToken)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	summary := traktImportSummary{
		MoviesSeen:   len(movieSource.Items),
		ShowsSeen:    len(showSource.Items),
		EpisodesSeen: countTraktEpisodes(showSource.Items),
		Debug: traktImportDebug{
			LocalMovies:       index.localMovies,
			LocalEpisodes:     index.localEpisodes,
			MovieIDs:          index.movieIDs,
			SyncWatchedMovies: movieSource.SyncWatched,
			UserWatchedMovies: movieSource.UserWatched,
			HistoryMovies:     movieSource.History,
			SyncWatchedShows:  showSource.SyncWatched,
			UserWatchedShows:  showSource.UserWatched,
			AllHistory:        allHistoryDebug.Items,
			TraktStats:        &traktStats,
			TraktSources: map[string]traktPageDebug{
				"allHistory":        allHistoryDebug,
				"syncWatchedMovies": movieSource.SyncWatchedDebug,
				"userWatchedMovies": movieSource.UserWatchedDebug,
				"historyMovies":     movieSource.HistoryDebug,
				"syncWatchedShows":  showSource.SyncWatchedDebug,
				"userWatchedShows":  showSource.UserWatchedDebug,
			},
		},
	}
	seen := map[int64]struct{}{}
	for _, movie := range movieSource.Items {
		item, method := index.matchMovie(movie.Movie.Title, movie.Movie.Year, movie.Movie.IDs)
		if item == nil {
			summary.MoviesUnmatched++
			summary.Debug.UnmatchedMovies = appendSample(summary.Debug.UnmatchedMovies, traktMovieSample(movie))
			continue
		}
		if _, ok := seen[item.ID]; ok {
			continue
		}
		if err := markItemWatched(r.Context(), a.store, user.ID, *item); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		seen[item.ID] = struct{}{}
		summary.MoviesMatched++
		summary.ItemsMarked++
		summary.Debug.MatchedMovies = append(summary.Debug.MatchedMovies, fmt.Sprintf("%s (%d) -> %s [%s]", movie.Movie.Title, movie.Movie.Year, item.Title, method))
	}
	for _, show := range showSource.Items {
		for _, season := range show.Seasons {
			for _, episode := range season.Episodes {
				item, method := index.matchEpisode(show.Show.Title, show.Show.Year, show.Show.IDs, traktIDsPayload{}, season.Number, episode.Number)
				if item == nil {
					summary.EpisodesUnmatched++
					summary.Debug.UnmatchedEpisodes = appendSample(summary.Debug.UnmatchedEpisodes, fmt.Sprintf("%s S%02dE%02d", show.Show.Title, season.Number, episode.Number))
					continue
				}
				if _, ok := seen[item.ID]; ok {
					continue
				}
				if err := markItemWatched(r.Context(), a.store, user.ID, *item); err != nil {
					http.Error(w, err.Error(), http.StatusInternalServerError)
					return
				}
				seen[item.ID] = struct{}{}
				summary.EpisodesMatched++
				summary.ItemsMarked++
				summary.Debug.MatchedEpisodes = appendSample(summary.Debug.MatchedEpisodes, fmt.Sprintf("%s S%02dE%02d -> %s [%s]", show.Show.Title, season.Number, episode.Number, item.Title, method))
			}
		}
	}
	writeJSON(w, http.StatusOK, summary)
}

func (a *App) traktImportExport(w http.ResponseWriter, r *http.Request) {
	user, ok := a.requireUser(w, r)
	if !ok {
		return
	}
	var in struct {
		Path string `json:"path"`
	}
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	exportPath, err := expandLocalPath(in.Path)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	export, err := readTraktExport(exportPath)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	items, err := a.store.AllItems(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	index := newTraktImportIndex(items)
	summary := traktImportSummary{
		MoviesSeen:   len(export.Movies),
		ShowsSeen:    export.ShowsSeen,
		EpisodesSeen: len(export.Episodes),
		Debug: traktImportDebug{
			LocalMovies:   index.localMovies,
			LocalEpisodes: index.localEpisodes,
			MovieIDs:      index.movieIDs,
			EpisodeIDs:    index.episodeIDs,
			AllHistory:    export.AllHistory,
			TraktSources: map[string]traktPageDebug{
				"exportHistory": {Items: export.AllHistory},
				"exportMovies":  {Items: export.MovieRows},
				"exportEpisodes": {
					Items: export.EpisodeRows,
				},
			},
		},
	}
	seen := map[int64]struct{}{}
	for _, movie := range export.Movies {
		item, method := index.matchMovie(movie.Movie.Title, movie.Movie.Year, movie.Movie.IDs)
		if item == nil {
			summary.MoviesUnmatched++
			summary.Debug.UnmatchedMovies = appendSample(summary.Debug.UnmatchedMovies, traktMovieSample(movie))
			continue
		}
		if _, ok := seen[item.ID]; ok {
			continue
		}
		if err := markItemWatched(r.Context(), a.store, user.ID, *item); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		seen[item.ID] = struct{}{}
		summary.MoviesMatched++
		summary.ItemsMarked++
		summary.Debug.MatchedMovies = append(summary.Debug.MatchedMovies, fmt.Sprintf("%s (%d) -> %s [%s]", movie.Movie.Title, movie.Movie.Year, item.Title, method))
	}
	for _, episode := range export.Episodes {
		item, method := index.matchEpisode(episode.Show.Title, episode.Show.Year, episode.Show.IDs, episode.Episode.IDs, episode.Episode.Season, episode.Episode.Number)
		label := fmt.Sprintf("%s S%02dE%02d", episode.Show.Title, episode.Episode.Season, episode.Episode.Number)
		if item == nil {
			summary.EpisodesUnmatched++
			summary.Debug.UnmatchedEpisodes = appendSample(summary.Debug.UnmatchedEpisodes, label)
			continue
		}
		if _, ok := seen[item.ID]; ok {
			continue
		}
		if err := markItemWatched(r.Context(), a.store, user.ID, *item); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		seen[item.ID] = struct{}{}
		summary.EpisodesMatched++
		summary.ItemsMarked++
		summary.Debug.MatchedEpisodes = appendSample(summary.Debug.MatchedEpisodes, fmt.Sprintf("%s -> %s [%s]", label, item.Title, method))
	}
	writeJSON(w, http.StatusOK, summary)
}

func (a *App) traktImportWatchlist(w http.ResponseWriter, r *http.Request) {
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
	items, err := a.store.AllItems(r.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	shows, err := a.store.ListShows(r.Context(), "", "", "", "", 1000, 0)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	itemIndex := newTraktImportIndex(items)
	showIndex := newTraktShowIndex(shows)
	source, err := a.traktWatchlist(r.Context(), account.AccessToken)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	summary := traktWatchlistImportSummary{
		EntriesSeen:  len(source.Movies) + len(source.Shows) + len(source.Episodes),
		MoviesSeen:   len(source.Movies),
		ShowsSeen:    len(source.Shows),
		EpisodesSeen: len(source.Episodes),
		TraktSources: map[string]traktPageDebug{
			"movies":   source.MoviesDebug,
			"shows":    source.ShowsDebug,
			"episodes": source.EpisodesDebug,
		},
	}
	seenItems := map[int64]struct{}{}
	seenShows := map[string]struct{}{}
	for _, movie := range source.Movies {
		item, method := itemIndex.matchMovie(movie.Movie.Title, movie.Movie.Year, movie.Movie.IDs)
		if item == nil {
			summary.MoviesUnmatched++
			summary.Unmatched = appendSample(summary.Unmatched, fmt.Sprintf("movie: %s (%d)", movie.Movie.Title, movie.Movie.Year))
			continue
		}
		if _, ok := seenItems[item.ID]; ok {
			continue
		}
		if err := a.store.SaveItemWatchlist(r.Context(), user.ID, *item); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		seenItems[item.ID] = struct{}{}
		summary.MoviesMatched++
		summary.ItemsMarked++
		summary.Matched = appendSample(summary.Matched, fmt.Sprintf("%s (%d) -> %s [%s]", movie.Movie.Title, movie.Movie.Year, item.Title, method))
	}
	for _, episode := range source.Episodes {
		item, method := itemIndex.matchEpisode(episode.Show.Title, episode.Show.Year, episode.Show.IDs, episode.Episode.IDs, episode.Episode.Season, episode.Episode.Number)
		if item == nil {
			summary.EpisodesUnmatched++
			summary.Unmatched = appendSample(summary.Unmatched, fmt.Sprintf("episode: %s S%02dE%02d", episode.Show.Title, episode.Episode.Season, episode.Episode.Number))
			continue
		}
		if _, ok := seenItems[item.ID]; ok {
			continue
		}
		if err := a.store.SaveItemWatchlist(r.Context(), user.ID, *item); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		seenItems[item.ID] = struct{}{}
		summary.EpisodesMatched++
		summary.ItemsMarked++
		summary.Matched = appendSample(summary.Matched, fmt.Sprintf("%s S%02dE%02d -> %s [%s]", episode.Show.Title, episode.Episode.Season, episode.Episode.Number, item.Title, method))
	}
	for _, traktShow := range source.Shows {
		show, method := showIndex.matchShow(traktShow.Show.Title, traktShow.Show.Year, traktShow.Show.IDs)
		if show == nil {
			summary.ShowsUnmatched++
			summary.Unmatched = appendSample(summary.Unmatched, fmt.Sprintf("show: %s (%d)", traktShow.Show.Title, traktShow.Show.Year))
			continue
		}
		key := strings.ToLower(show.LibraryID + "\n" + show.Title)
		if _, ok := seenShows[key]; ok {
			continue
		}
		if err := a.store.SaveShowWatchlist(r.Context(), user.ID, show.LibraryID, show.Title); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		seenShows[key] = struct{}{}
		summary.ShowsMatched++
		summary.ShowsMarked++
		summary.Matched = appendSample(summary.Matched, fmt.Sprintf("%s (%d) -> %s [%s]", traktShow.Show.Title, traktShow.Show.Year, show.Title, method))
	}
	writeJSON(w, http.StatusOK, summary)
}

func markItemWatched(ctx context.Context, store *media.Store, userID int64, item media.Item) error {
	duration := item.DurationMS
	position := duration
	if duration <= 0 {
		position = 0
	}
	_, err := store.SaveProgress(ctx, userID, item.ID, position, duration, true)
	return err
}

func countTraktEpisodes(shows []traktWatchedShow) int {
	total := 0
	for _, show := range shows {
		for _, season := range show.Seasons {
			total += len(season.Episodes)
		}
	}
	return total
}

func appendSample(samples []string, value string) []string {
	if len(samples) >= 30 {
		return samples
	}
	return append(samples, value)
}

func traktMovieSample(movie traktWatchedMovie) string {
	ids := []string{}
	if movie.Movie.IDs.IMDb != "" {
		ids = append(ids, "imdb="+movie.Movie.IDs.IMDb)
	}
	if movie.Movie.IDs.TMDb > 0 {
		ids = append(ids, fmt.Sprintf("tmdb=%d", movie.Movie.IDs.TMDb))
	}
	return fmt.Sprintf("%s (%d) %s", movie.Movie.Title, movie.Movie.Year, strings.Join(ids, " "))
}

func titleKeys(title, originalTitle string, year int) []string {
	keys := []string{}
	for _, value := range []string{title, originalTitle} {
		normalized := normalizeMatch(value)
		if normalized == "" {
			continue
		}
		if year > 0 {
			keys = append(keys, fmt.Sprintf("%s:%d", normalized, year))
		}
		keys = append(keys, normalized)
	}
	return keys
}

func episodeKey(showTitle string, season, episode int) string {
	show := normalizeMatch(showTitle)
	if show == "" || season < 0 || episode <= 0 {
		return ""
	}
	return fmt.Sprintf("%s:s%d:e%d", show, season, episode)
}

func episodeKeyWithYear(showTitle string, year, season, episode int) string {
	show := normalizeMatch(showTitle)
	if show == "" || year <= 0 || season < 0 || episode <= 0 {
		return ""
	}
	return fmt.Sprintf("%s:%d:s%d:e%d", show, year, season, episode)
}

func normalizeMatch(v string) string {
	v = strings.ToLower(strings.TrimSpace(v))
	var b strings.Builder
	lastSpace := false
	for _, r := range v {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			lastSpace = false
			continue
		}
		if !lastSpace {
			b.WriteByte(' ')
			lastSpace = true
		}
	}
	return strings.Join(strings.Fields(b.String()), " ")
}
