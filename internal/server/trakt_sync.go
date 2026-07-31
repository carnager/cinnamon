package server

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"popcorn/internal/media"
)

func (a *App) scrobblePlayback(userID int64, item media.Item, progress media.PlaybackProgress, state string) {
	if !a.traktConfigured() || progress.DurationMS <= 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	account, err := a.traktAccountForRequest(ctx, userID)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			a.log.Debug("trakt account unavailable", "user", userID, "error", err)
		}
		return
	}
	action := "start"
	switch strings.ToLower(strings.TrimSpace(state)) {
	case "paused", "pause":
		action = "pause"
	case "stopped", "stop":
		action = "pause"
	}
	if progress.Completed {
		action = "stop"
	}
	percent := (float64(progress.PositionMS) / float64(progress.DurationMS)) * 100
	if percent < 0 {
		percent = 0
	}
	if percent > 100 {
		percent = 100
	}
	body := traktScrobbleBody(item, percent)
	if body == nil {
		a.log.Debug("trakt scrobble skipped, item cannot be identified", "item", item.ID)
		return
	}
	resp, err := a.traktRequest(ctx, account.AccessToken, http.MethodPost, "/scrobble/"+action, body)
	if err != nil {
		a.log.Debug("trakt scrobble request failed", "item", item.ID, "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Debug("trakt scrobble failed", "item", item.ID, "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.log.Debug("trakt scrobble sent", "item", item.ID, "action", action, "progress", fmt.Sprintf("%.1f", percent))
}

func (a *App) traktSyncHistoryItems(userID int64, items []media.Item, remove bool) {
	if !a.traktConfigured() || len(items) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	account, err := a.traktAccountForRequest(ctx, userID)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			a.log.Debug("trakt history sync account unavailable", "user", userID, "error", err)
		}
		return
	}
	movies := []map[string]any{}
	episodes := []map[string]any{}
	now := time.Now().UTC().Format(time.RFC3339)
	for _, item := range items {
		entry := traktHistoryEntry(item, !remove, now)
		if entry == nil {
			a.log.Debug("trakt history sync skipped, item cannot be identified", "item", item.ID)
			continue
		}
		switch item.Kind {
		case "movie":
			movies = append(movies, entry)
		case "episode":
			episodes = append(episodes, entry)
		}
	}
	if len(movies) == 0 && len(episodes) == 0 {
		return
	}
	body := map[string]any{}
	if len(movies) > 0 {
		body["movies"] = movies
	}
	if len(episodes) > 0 {
		body["episodes"] = episodes
	}
	path := "/sync/history"
	action := "add"
	if remove {
		path = "/sync/history/remove"
		action = "remove"
	}
	resp, err := a.traktRequest(ctx, account.AccessToken, http.MethodPost, path, body)
	if err != nil {
		a.log.Debug("trakt history sync request failed", "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Debug("trakt history sync failed", "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.log.Debug("trakt history sync sent", "action", action, "movies", len(movies), "episodes", len(episodes))
}

func (a *App) traktSyncWatchlistItems(userID int64, items []media.Item, remove bool) {
	if !a.traktConfigured() || len(items) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	account, err := a.traktAccountForRequest(ctx, userID)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			a.log.Debug("trakt watchlist sync account unavailable", "user", userID, "error", err)
		}
		return
	}
	movies := []map[string]any{}
	episodes := []map[string]any{}
	for _, item := range items {
		entry := traktHistoryEntry(item, false, "")
		if entry == nil {
			a.log.Debug("trakt watchlist sync skipped, item cannot be identified", "item", item.ID)
			continue
		}
		switch item.Kind {
		case "movie":
			movies = append(movies, entry)
		case "episode":
			episodes = append(episodes, entry)
		}
	}
	body := map[string]any{}
	if len(movies) > 0 {
		body["movies"] = movies
	}
	if len(episodes) > 0 {
		body["episodes"] = episodes
	}
	a.traktSyncWatchlistBody(ctx, account.AccessToken, body, remove)
}

func (a *App) traktSyncWatchlistShows(userID int64, shows []media.ShowSummary, remove bool) {
	if !a.traktConfigured() || len(shows) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	account, err := a.traktAccountForRequest(ctx, userID)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			a.log.Debug("trakt watchlist sync account unavailable", "user", userID, "error", err)
		}
		return
	}
	entries := []map[string]any{}
	for _, show := range shows {
		entry := map[string]any{"title": show.Title}
		if show.Year > 0 {
			entry["year"] = show.Year
		}
		entries = append(entries, entry)
	}
	a.traktSyncWatchlistBody(ctx, account.AccessToken, map[string]any{"shows": entries}, remove)
}

func (a *App) traktSyncWatchlistBody(ctx context.Context, bearer string, body map[string]any, remove bool) {
	if len(body) == 0 {
		return
	}
	path := "/sync/watchlist"
	action := "add"
	if remove {
		path = "/sync/watchlist/remove"
		action = "remove"
	}
	resp, err := a.traktRequest(ctx, bearer, http.MethodPost, path, body)
	if err != nil {
		a.log.Debug("trakt watchlist sync request failed", "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Debug("trakt watchlist sync failed", "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.log.Debug("trakt watchlist sync sent", "action", action)
}

func (a *App) traktSyncRecommendationExclusionItems(userID int64, items []media.Item, remove bool) {
	movies := []map[string]any{}
	for _, item := range items {
		if item.Kind != "movie" {
			continue // Trakt recommendation hiding supports movies and shows, not episodes.
		}
		if entry := traktHistoryEntry(item, false, ""); entry != nil {
			movies = append(movies, entry)
		}
	}
	if len(movies) > 0 {
		a.traktSyncRecommendationExclusions(userID, map[string]any{"movies": movies}, remove)
	}
}

func (a *App) traktSyncAllRecommendationExclusions(userID int64) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	entries, err := a.store.ListRecommendationExclusions(ctx, userID)
	if err != nil {
		a.log.Debug("trakt recommendation exclusion backup load failed", "user", userID, "error", err)
		return
	}
	items := []media.Item{}
	shows := []media.ShowSummary{}
	for _, entry := range entries {
		if entry.Item != nil {
			items = append(items, *entry.Item)
		}
		if entry.Show != nil {
			shows = append(shows, *entry.Show)
		}
	}
	a.traktSyncRecommendationExclusionItems(userID, items, false)
	a.traktSyncRecommendationExclusionShows(userID, shows, false)
}

func (a *App) traktSyncRecommendationExclusionShows(userID int64, shows []media.ShowSummary, remove bool) {
	entries := make([]map[string]any, 0, len(shows))
	for _, show := range shows {
		entry := map[string]any{"title": show.Title}
		if show.Year > 0 {
			entry["year"] = show.Year
		}
		entries = append(entries, entry)
	}
	if len(entries) > 0 {
		a.traktSyncRecommendationExclusions(userID, map[string]any{"shows": entries}, remove)
	}
}

func (a *App) traktSyncRecommendationExclusions(userID int64, body map[string]any, remove bool) {
	if !a.traktConfigured() {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	account, err := a.traktAccountForRequest(ctx, userID)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			a.log.Debug("trakt recommendation exclusion account unavailable", "user", userID, "error", err)
		}
		return
	}
	path := "/users/hidden/recommendations"
	action := "hide"
	if remove {
		path += "/remove"
		action = "restore"
	}
	resp, err := a.traktRequest(ctx, account.AccessToken, http.MethodPost, path, body)
	if err != nil {
		a.log.Debug("trakt recommendation exclusion sync failed", "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Debug("trakt recommendation exclusion sync failed", "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.log.Debug("trakt recommendation exclusion synced", "action", action)
}

func traktScrobbleBody(item media.Item, progress float64) map[string]any {
	body := map[string]any{"progress": progress}
	if item.Kind == "episode" {
		show := map[string]any{"title": firstNonEmpty(item.ShowTitle, item.Title)}
		if item.Year > 0 {
			show["year"] = item.Year
		}
		body["show"] = show
		body["episode"] = map[string]any{
			"season": item.SeasonNumber,
			"number": item.EpisodeNumber,
		}
		return body
	}
	movie := map[string]any{"title": item.Title}
	if item.Year > 0 {
		movie["year"] = item.Year
	}
	if ids := traktIDs(item); len(ids) > 0 {
		movie["ids"] = ids
	}
	body["movie"] = movie
	return body
}

func traktHistoryEntry(item media.Item, includeWatchedAt bool, watchedAt string) map[string]any {
	entry := map[string]any{}
	if ids := traktIDs(item); len(ids) > 0 {
		entry["ids"] = ids
	} else if item.Kind == "movie" {
		entry["title"] = item.Title
		if item.Year > 0 {
			entry["year"] = item.Year
		}
	} else {
		if item.ShowTitle == "" || item.SeasonNumber <= 0 || item.EpisodeNumber <= 0 {
			return nil
		}
		entry["season"] = item.SeasonNumber
		entry["number"] = item.EpisodeNumber
		entry["show"] = map[string]any{"title": item.ShowTitle}
	}
	if includeWatchedAt {
		entry["watched_at"] = watchedAt
	}
	return entry
}

func traktIDs(item media.Item) map[string]any {
	ids := map[string]any{}
	imdbID, tmdbID, tvdbID := item.IMDbID, item.TMDbID, item.TVDbID
	if (imdbID == "" || tmdbID == "" || tvdbID == "") && item.NFOPath != "" {
		nfoIMDb, nfoTMDb, nfoTVDb := media.ReadNFOExternalIDs(item.NFOPath)
		if imdbID == "" {
			imdbID = nfoIMDb
		}
		if tmdbID == "" {
			tmdbID = nfoTMDb
		}
		if tvdbID == "" {
			tvdbID = nfoTVDb
		}
	}
	if imdbID != "" {
		ids["imdb"] = imdbID
	}
	if tmdbID != "" {
		if n, err := strconv.Atoi(tmdbID); err == nil {
			ids["tmdb"] = n
		}
	}
	if tvdbID != "" {
		if n, err := strconv.Atoi(tvdbID); err == nil {
			ids["tvdb"] = n
		}
	}
	return ids
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
