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
	if _, byShow := body["show"]; byShow {
		body["show"] = a.traktShowEntry(ctx, media.ShowSummary{LibraryID: item.LibraryID, Title: item.ShowTitle, Year: item.Year})
	}
	if body == nil {
		a.log.Debug("trakt scrobble skipped, item cannot be identified", "item", item.ID)
		return
	}
	resp, err := a.traktRequest(ctx, account.AccessToken, http.MethodPost, "/scrobble/"+action, body)
	if err != nil {
		a.log.Warn("trakt scrobble request failed", "item", item.ID, "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		level := slog.LevelWarn
		if resp.StatusCode == http.StatusConflict {
			// Trakt refuses a second scrobble of the same item within its
			// window; that is not a lost watch.
			level = slog.LevelDebug
		}
		a.log.Log(ctx, level, "trakt scrobble failed", "user", userID, "item", item.ID, "title", item.Title, "action", action, "status", resp.StatusCode, "body", string(respBody))
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
	var extra map[string]any
	if !remove {
		extra = map[string]any{"watched_at": time.Now().UTC().Format(time.RFC3339)}
	}
	body := a.traktItemsBody(ctx, items, extra, "history sync")
	if len(body) == 0 {
		return
	}
	path := "/sync/history"
	action := "add"
	if remove {
		path = "/sync/history/remove"
		action = "remove"
	}
	resp, err := a.traktRequest(ctx, account.AccessToken, http.MethodPost, path, body)
	if err != nil {
		a.log.Warn("trakt history sync request failed", "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Warn("trakt history sync failed", "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.warnTraktNotFound("history sync", action, respBody)
	a.log.Debug("trakt history sync sent", "action", action, "items", len(items))
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
	body := a.traktItemsBody(ctx, items, nil, "watchlist sync")
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
		entry := a.traktShowEntry(ctx, show)
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
		a.log.Warn("trakt watchlist sync request failed", "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Warn("trakt watchlist sync failed", "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.warnTraktNotFound("watchlist sync", action, respBody)
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
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	entries := make([]map[string]any, 0, len(shows))
	for _, show := range shows {
		entry := a.traktShowEntry(ctx, show)
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
		a.log.Warn("trakt recommendation exclusion sync failed", "action", action, "error", err)
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 16*1024))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		a.log.Warn("trakt recommendation exclusion sync failed", "action", action, "status", resp.StatusCode, "body", string(respBody))
		return
	}
	a.warnTraktNotFound("recommendation exclusion sync", action, respBody)
	a.log.Debug("trakt recommendation exclusion synced", "action", action)
}

func traktScrobbleBody(item media.Item, progress float64) map[string]any {
	body := map[string]any{"progress": progress}
	if item.Kind == "episode" {
		// Episode IDs identify it outright. The title fallback only works when
		// the local show title matches Trakt's, which a translated title
		// ("Die Krankenschwester" for "The Nurse") does not.
		if ids := traktIDs(item); len(ids) > 0 {
			body["episode"] = map[string]any{"ids": ids}
			return body
		}
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

// traktShowEntry identifies a show for a Trakt sync body. By IDs from its
// tvshow.nfo when it has them: a title only matches when the local title is
// Trakt's, which a translated title ("Haus des Geldes" for "Money Heist")
// is not, and Trakt answers such a miss with a 2xx and a not_found list.
func (a *App) traktShowEntry(ctx context.Context, show media.ShowSummary) map[string]any {
	ids := map[string]any{}
	if path, err := a.store.ShowNFOPath(ctx, show.LibraryID, show.Title); err == nil && path != "" {
		imdbID, tmdbID, tvdbID := media.ReadNFOExternalIDs(path)
		if imdbID != "" {
			ids["imdb"] = imdbID
		}
		if n, err := strconv.Atoi(tmdbID); err == nil && n > 0 {
			ids["tmdb"] = n
		}
		if n, err := strconv.Atoi(tvdbID); err == nil && n > 0 {
			ids["tvdb"] = n
		}
	}
	if len(ids) > 0 {
		return map[string]any{"ids": ids}
	}
	entry := map[string]any{"title": show.Title}
	if show.Year > 0 {
		entry["year"] = show.Year
	}
	return entry
}

// warnTraktNotFound logs the entries Trakt could not identify. Trakt answers
// a sync write with 2xx even when it matched nothing, listing the misses
// under not_found, so without this a lost watch, rating or watchlist entry
// leaves no trace at the default log level.
func (a *App) warnTraktNotFound(kind, action string, body []byte) {
	var parsed struct {
		NotFound map[string][]json.RawMessage `json:"not_found"`
	}
	if json.Unmarshal(body, &parsed) != nil {
		return
	}
	attrs := []any{"action", action}
	missing := 0
	for group, entries := range parsed.NotFound {
		if len(entries) == 0 {
			continue
		}
		missing += len(entries)
		sample, _ := json.Marshal(entries[:min(len(entries), 3)])
		attrs = append(attrs, group, string(sample))
	}
	if missing > 0 {
		a.log.Warn("trakt "+kind+": entries not found on Trakt", append(attrs, "count", missing)...)
	}
}

// traktItemsBody builds a /sync/{history,watchlist,ratings} body for movies
// and episodes, copying extra (watched_at, rating) onto every entry.
//
// An episode with IDs of its own goes in "episodes". One without them can
// only be named by its show plus season and number, which the sync API
// accepts solely nested as shows -> seasons -> episodes; the show is named by
// its tvshow.nfo IDs where it has them, since its local title may be a
// translation Trakt does not know.
func (a *App) traktItemsBody(ctx context.Context, items []media.Item, extra map[string]any, kind string) map[string]any {
	movies := []map[string]any{}
	episodes := []map[string]any{}
	type showGroup struct {
		entry   map[string]any
		seasons map[int][]map[string]any
		order   []int
	}
	var shows []*showGroup
	byShow := map[string]*showGroup{}
	with := func(entry map[string]any) map[string]any {
		for k, v := range extra {
			entry[k] = v
		}
		return entry
	}
	for _, item := range items {
		entry := traktHistoryEntry(item, false, "")
		if entry == nil {
			a.log.Debug("trakt "+kind+" skipped, item cannot be identified", "item", item.ID)
			continue
		}
		switch item.Kind {
		case "movie":
			movies = append(movies, with(entry))
		case "episode":
			if _, hasIDs := entry["ids"]; hasIDs {
				episodes = append(episodes, with(entry))
				continue
			}
			key := item.LibraryID + "\x00" + item.ShowTitle
			group := byShow[key]
			if group == nil {
				group = &showGroup{
					entry:   a.traktShowEntry(ctx, media.ShowSummary{LibraryID: item.LibraryID, Title: item.ShowTitle}),
					seasons: map[int][]map[string]any{},
				}
				byShow[key] = group
				shows = append(shows, group)
			}
			if _, seen := group.seasons[item.SeasonNumber]; !seen {
				group.order = append(group.order, item.SeasonNumber)
			}
			group.seasons[item.SeasonNumber] = append(group.seasons[item.SeasonNumber], with(map[string]any{"number": item.EpisodeNumber}))
		}
	}
	body := map[string]any{}
	if len(movies) > 0 {
		body["movies"] = movies
	}
	if len(episodes) > 0 {
		body["episodes"] = episodes
	}
	if len(shows) > 0 {
		out := make([]map[string]any, 0, len(shows))
		for _, group := range shows {
			seasons := make([]map[string]any, 0, len(group.order))
			for _, number := range group.order {
				seasons = append(seasons, map[string]any{"number": number, "episodes": group.seasons[number]})
			}
			entry := group.entry
			entry["seasons"] = seasons
			out = append(out, entry)
		}
		body["shows"] = out
	}
	return body
}
